# laurent-budget

Android app that scans a card payment receipt with the camera, finds the **amount**, the **shop** and the **date**,
stores the ticket locally, and keeps several devices (phone, tablet) in sync through an S3 bucket.

The screens, dialogs and messages are in French. Everything runs on the device: the text recognition is Google ML Kit with a bundled model (no network, no AI
service). The only network calls are the sync, when you press **Synchro** and once when the app opens.

## How it works

1. **Scanner un ticket** takes a photo. The text is read three times (full, half and quarter size) and the results are
   combined.
2. A dialog shows the photo with the guessed **date**, **shop** and **amount**. Correct them if needed and press
   **Enregistrer**. The photo is shown so the amount can be checked against the paper (the OCR sometimes confuses a bold
   `0` with an `8`).
3. Tickets are stored in a local SQLite database. The photo stays in the app private storage.
4. Tap a ticket to correct its date, shop or amount (the shop field proposes the known shops), to add a free
   description, or to delete it; a long press deletes it directly. The description is shown after the amount in the
   list and travels with the ticket when syncing.
5. No ticket to scan? **Parler** asks for the amount, then for the shop, in French (for example "quatorze euros
   quatre-vingts", then "chez Carbone"). The same dialog opens prefilled, with what was heard; the ticket has no photo.
   The recognition is Android's speech service: it uses the offline French pack when it is installed (Google speech
   settings), otherwise it sends the audio to Google. `Spoken.kt` turns the French words or digits into an amount
   (`14 euros 80`, `14,80`, `douze cinquante`, `quatre-vingt-dix-neuf euros`) and the shop into a known shop when it
   matches.
6. **Synchro** pushes what changed on this device and pulls what the other devices pushed (see below). A ✓ means the
   ticket is in sync. Edits and deletes reach the other devices at their next sync; saving an edit also starts a sync.

The parser (`Parser.kt`) is plain Kotlin:

- **Amount**: amounts followed by `EUR` or `€` (the most frequent wins, the largest on a tie); date and time lines are
  ignored. A single decimal (`14.8`) is read as `14.80`.
- **Shop**: a previously saved shop that matches a line of the ticket (tolerating about 20% of OCR typos, and cheap
  swaps such as I/T, 0/O, 8/B, 5/S, M/H); otherwise the first plausible line in capitals.
- **Date**: `dd/mm/yyyy` or French month names (`12 novembre 2026`, `3 janv. 2026`), with an optional time. Dates in
  the future are rejected; when the OCR drops a digit of the month (`19/1/2026`), the candidate closest to today wins.

## Requirements

- JDK 17 or newer (a plain JRE is enough, `jlink` is not needed)
- Android SDK with platform 34 and build-tools 34.0.0, path in `local.properties` (`sdk.dir=...`, git-ignored)
- `adb` and a device with USB debugging
- `rclone` with a `gdrive:` remote (only for `make gdrive`)
- the AWS CLI (only to create the upload user)

## Make targets

`make help` lists them.

| Target | What it does |
|---|---|
| `make build` | debug apk (`app/build/outputs/apk/debug/laurent-budget-debug.apk`) |
| `make release` | signed release apk, `laurent-budget-release.apk` (needs `keystore.properties`) |
| `make deploy` | build and install on the connected device, grant the camera permission |
| `make run` | deploy and launch |
| `make test` | unit tests (parser, SigV4 signing, sync decisions) |
| `make push-key` | send the AWS key to the device over USB (see below) |
| `make gdrive` | upload the apk to `gdrive:apk/budget/laurent-budget.apk` |
| `make log` | show the OCR text logged by the app |
| `make clean` | clean the build |

When `keystore.properties` exists, `deploy`, `run` and `gdrive` use the **release** apk, otherwise the debug one.
With several devices connected, add `SERIAL=<serial>` (see `adb devices`).

## Configuration files (all git-ignored)

| File | Content |
|---|---|
| `local.properties` | `sdk.dir` |
| `aws.properties` | `key_id`, `secret` (used only by `make push-key`) and `region`, `bucket`, `prefix` (built into the app). Template: `aws.properties.example` |
| `keystore.properties` | release signing: `storeFile`, `storePassword`, `keyAlias`, `keyPassword` |
| `budget-release.jks` | the release keystore |

## Sync through S3

Each ticket is two objects under the configured prefix: `<uid>.jpg` (the photo) and `<uid>.json` (date, shop, amount,
`description`, `deleted`, `updatedAt`). The `uid` is the photo file name, `ticket_<epochMs>_<4 hex of the install>`, so two devices
never collide. A **Synchro** (button, and once when the app opens) does:

1. **push**: every ticket changed here (new, or deleted) is uploaded; a delete is a `.json` with `deleted: true`, so
   no S3 delete permission is needed
2. **pull**: list the prefix, download the `.json` files that are unknown here or whose ETag changed, and the photos of
   new tickets; the most recent `updatedAt` wins

The app talks to S3 with plain SigV4 signed requests (`PUT`, `GET`, list; no AWS SDK). Use a dedicated IAM user:
`iam-policy.json` allows `s3:PutObject` and `s3:GetObject` on `<prefix>/*` and `s3:ListBucket` on the bucket for the
prefix only. It cannot delete anything and cannot touch anything outside the prefix.

```
aws iam create-user --user-name budget-uploader
aws iam put-user-policy --user-name budget-uploader --policy-name budget-sync --policy-document file://iam-policy.json
aws iam create-access-key --user-name budget-uploader
```

Put the returned `AccessKeyId` and `SecretAccessKey` in `aws.properties` (`key_id` is 20 characters, `secret` is 40).
Set `region`, `bucket` and `prefix` there too. Because the key can read the tickets, treat a lost device like a lost
key: revoke it with `aws iam delete-access-key`.

A new device (or a reinstall) gets everything back with its first sync.

### The key is not in the apk

The apk contains no secret. The key is stored in the app private storage on each device, and gets there in one of
two ways:

- **USB**: `make push-key` copies `key_id` and `secret` from `aws.properties` to the app external files folder with
  `adb push`. The app imports the file when it comes to the foreground, then deletes it. It works for debug and
  release builds, and the secret never shows on screen.
- **By hand**: the **Réglages** button takes the key ID and the secret (paste into the password field).

Rotate the key: create a new one with `aws iam create-access-key`, update `aws.properties`, run `make push-key`, then
`aws iam delete-access-key` for the old one.

## Release signing

`keystore.properties` and the keystore are created once (`keytool -genkeypair ...`), then `make release` signs the
apk. **Back up `budget-release.jks` and `keystore.properties`**: without them an installed release app cannot be
updated, only uninstalled (which erases its local tickets). A copy is kept in `gdrive:apk/budget/`.

A device that has the debug-signed app must be uninstalled (`adb uninstall com.perso.budget`) before the release
apk can be installed, because the signatures differ. Sync the tickets first (✓ on every ticket): the first sync after
the reinstall brings them all back.

## Debugging

- `make log` prints the text read by the OCR for each scan.
- Re-run the OCR on a photo already stored on the device, without the camera:
  `adb shell am start -n com.perso.budget/.MainActivity --es rescan ticket_<id>.jpg`
  (the dialog opens; close the app instead of pressing Discard, which deletes the photo).
