# The device keeps the app signed with one key: deploy the release apk when there is a signing key, the debug one otherwise.
HAS_KEY := $(wildcard keystore.properties)
ifdef HAS_KEY
APK := app/build/outputs/apk/release/laurent-budget-release.apk
BUILD := release
else
APK := app/build/outputs/apk/debug/laurent-budget-debug.apk
BUILD := build
endif
PKG := com.perso.budget
# several devices connected: make deploy SERIAL=<adb serial> (see adb devices)
ADB := adb $(if $(SERIAL),-s $(SERIAL))

.DEFAULT_GOAL := help
.PHONY: help test build release deploy run push-key log gdrive clean

help: ## show this help
	@grep -hE '^[a-zA-Z0-9_-]+:.*?## .*$$' $(MAKEFILE_LIST) | sort | awk 'BEGIN {FS = ":.*?## "}; {printf "\033[36m%-22s\033[0m %s\n", $$1, $$2}'

test: ## run the unit tests (parser: amount, shop, date)
	./gradlew testDebugUnitTest -q

build: ## build the debug apk
	./gradlew assembleDebug -q

release: ## build the signed release apk (needs keystore.properties)
	@test -f keystore.properties || { echo "keystore.properties is missing: no signing key"; exit 1; }
	./gradlew assembleRelease -q
	@ls -l app/build/outputs/apk/release/laurent-budget-release.apk

deploy: $(BUILD) ## build and install the apk (release if keystore.properties exists) on the connected device (adb), grant camera
	$(ADB) wait-for-device
	$(ADB) install -r $(APK)
	$(ADB) shell pm grant $(PKG) android.permission.CAMERA

run: deploy ## deploy and launch the app
	$(ADB) shell monkey -p $(PKG) -c android.intent.category.LAUNCHER 1

GDRIVE_FILE := gdrive:apk/budget/laurent-budget.apk

gdrive: $(BUILD) ## build the apk (release if keystore.properties exists) and upload it as laurent-budget.apk to gdrive:apk/budget with rclone
	rclone copyto -v $(APK) $(GDRIVE_FILE)
	@rclone lsl $(GDRIVE_FILE)

KEYDIR := /sdcard/Android/data/$(PKG)/files

push-key: ## copy the AWS key from aws.properties to the connected device over USB (any build); the app imports then deletes it
	@test -f aws.properties || { echo "aws.properties is missing"; exit 1; }
	@id=$$(sed -n 's/^key_id=//p' aws.properties); secret=$$(sed -n 's/^secret=//p' aws.properties); \
	[ $${#id} -eq 20 ] && [ $${#secret} -eq 40 ] || { echo "aws.properties: key_id must be 20 and secret 40 characters"; exit 1; }; \
	tmp=$$(mktemp) && trap 'rm -f $$tmp' EXIT && chmod 600 $$tmp && \
	printf 'key_id=%s\nsecret=%s\n' "$$id" "$$secret" > $$tmp && \
	$(ADB) shell mkdir -p $(KEYDIR) && \
	$(ADB) push $$tmp $(KEYDIR)/aws-key.properties >/dev/null && \
	$(ADB) shell am force-stop $(PKG) && \
	$(ADB) shell monkey -p $(PKG) -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1 && \
	echo "key $$id sent: the app imports it on launch and deletes the file"

log: ## show the OCR text logged by the app
	$(ADB) logcat -d -s Budget

clean: ## clean the build
	./gradlew clean -q
