# Budget

Android app that scans a card payment receipt with the camera, finds the **amount**, the **shop** and the **date**,
stores the ticket locally, and can upload the photo and the data to an S3 bucket.

Everything runs on the device: the text recognition is Google ML Kit with a bundled model (no network, no AI
service). The only network call is the upload, when you press **Upload**.

## How it works

1. **Scan ticket** takes a photo. The text is read three times (full, half and quarter size) and the results are
   combined.
2. A dialog shows the photo with the guessed **date**, **shop** and **amount**. Correct them if needed and press
   **Save**. The photo is shown so the amount can be checked against the paper (the OCR sometimes confuses a bold
   `0` with an `8`).
3. Tickets are stored in a local SQLite database. The photo stays in the app private storage.
4. **Upload** sends every ticket without a ✓ (photo `ticket_<id>.jpg` and data `ticket_<id>.json`) to the bucket,
   under the configured prefix, then marks it ✓. Long-press a ticket to delete it from the list.

The parser (`Parser.kt`) is plain Kotlin:

- **Amount**: amounts followed by `EUR` or `€` (the most frequent wins, the largest on a tie); date and time lines are
  ignored. A single decimal (`14.8`) is read as `14.80`.
- **Shop**: a previously saved shop that matches a line of the ticket (tolerating about 20% of OCR typos, and cheap
  swaps such as I/T, 0/O, 8/B, 5/S, M/H); otherwise the first plausible line in capitals.
- **Date**: `dd/mm/yyyy` with an optional time. Dates in the future are rejected; when the OCR drops a digit of the
  month (`19/1/2026`), the candidate closest to today wins.

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

## Upload to S3

The app uses a plain SigV4 signed `PUT` (no AWS SDK). Use a dedicated IAM user that can only write under the
prefix: `iam-policy.json` is the policy (`s3:PutObject` on `<prefix>/*`, nothing else).

```
aws iam create-user --user-name budget-uploader
aws iam put-user-policy --user-name budget-uploader --policy-name budget-put-only --policy-document file://iam-policy.json
aws iam create-access-key --user-name budget-uploader
```

Put the returned `AccessKeyId` and `SecretAccessKey` in `aws.properties` (`key_id` is 20 characters, `secret` is 40).
Set `region`, `bucket` and `prefix` there too.

### The key is not in the apk

The apk contains no secret. The key is stored in the app private storage on each device, and gets there in one of
two ways:

- **USB**: `make push-key` copies `key_id` and `secret` from `aws.properties` to the app external files folder with
  `adb push`. The app imports the file when it comes to the foreground, then deletes it. It works for debug and
  release builds, and the secret never shows on screen.
- **By hand**: the **Settings** button takes the key ID and the secret (paste into the password field).

Rotate the key: create a new one with `aws iam create-access-key`, update `aws.properties`, run `make push-key`, then
`aws iam delete-access-key` for the old one.

## Release signing

`keystore.properties` and the keystore are created once (`keytool -genkeypair ...`), then `make release` signs the
apk. **Back up `budget-release.jks` and `keystore.properties`**: without them an installed release app cannot be
updated, only uninstalled (which erases its local tickets). A copy is kept in `gdrive:apk/budget/`.

A device that has the debug-signed app must be uninstalled (`adb uninstall com.perso.budget`) before the release
apk can be installed, because the signatures differ.

## Debugging

- `make log` prints the text read by the OCR for each scan.
- Re-run the OCR on a photo already stored on the device, without the camera:
  `adb shell am start -n com.perso.budget/.MainActivity --es rescan ticket_<id>.jpg`
  (the dialog opens; close the app instead of pressing Discard, which deletes the photo).
