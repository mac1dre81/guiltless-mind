# DocEditor

DocEditor is an offline-first document app for Android, built with Jetpack Compose and Material 3. It supports PDF editing, text/Markdown editing, and document scanning with OCR powered by Google ML Kit.

## Features

* **Offline-First:** All documents are stored locally and accessible without an internet connection.
* **Format Support:** Edit PDF, text, and Markdown documents on-device.
* **Persistent Access:** Recent files are remembered across app restarts via `takePersistableUriPermission`.
* **Document Scanner:** High-quality document scanning using Google ML Kit.
* **OCR Text Extraction:** Seamlessly extract editable text from scanned documents.
* **Dark Mode:** Full support for Android system dark mode and dynamic colors.
* **PDF Editing:** Annotate PDF pages with freehand drawing and text notes, then export an edited PDF copy.
* **Monetization Tiers:**
  * **Free:** Core local document viewing and editing. AdMob SDK support is integrated for release monetization; verify visible ad behavior in the shipped build before making store claims.
  * **Premium:** One-time purchase ($3.99) unlocks paid document features and is intended to remove ads in ad-enabled releases.
  * **Pro:** Subscription ($2.99/month) unlocks the ML Kit-based Document Scanner and OCR capabilities.

## Architecture & Tech Stack

* **UI:** Jetpack Compose, Material 3
* **Language:** Kotlin
* **Architecture:** MVVM with structured concurrency (Coroutines), `ViewModel`, `LiveData`, and Flow-backed DataStore preferences
* **Local Storage:** DataStore for preferences and the Storage Access Framework for persistent document access
* **Machine Learning:** Google ML Kit (Document Scanner, Text Recognition)
* **Monetization:** Google Play Billing Library
* **Build System:** Gradle Version Catalogs (TOML)

## Requirements

* Android Studio Koala or newer
* Minimum SDK: Android 7.0 (API level 24)
* Target SDK: Android 15 (API level 36)

## Building the App

1. Clone the repository.
2. Open the project in Android Studio.
3. Sync the project with Gradle files.
4. Run the app on an emulator or physical device using the `app` run configuration.
   * Or via command line: `./gradlew.bat assembleDebug`

CI is also configured to run `assembleDebug`, `lintDebug`, and `testDebugUnitTest` on pushes and pull requests via `.github/workflows/android.yml`.

After running `assembleDebug`, the generated debug APK will be located at:
`app/build/outputs/apk/debug/app-debug.apk`

### Release Build

To build the release App Bundle (AAB):

1. Ensure `keystore.properties` is present in the `app` directory with valid signing credentials (do not commit this file to version control).
   * Start from `app/keystore.properties.example`.
2. Set a real AdMob App ID through the `DOCEDITOR_ADMOB_APP_ID` Gradle property or CI environment variable.
   * Recommended: keep it in your user-level `~/.gradle/gradle.properties` or CI secret store, not in the repo.
3. Review lint output; release builds are configured to fail when lint errors remain.
4. Run the Gradle task: `./gradlew.bat bundleRelease`
5. The generated AAB will be located in `app/build/outputs/bundle/release/`.

## Release Docs

Release-specific documentation is stored under `app/src/docs/`:

* [`RELEASE_READINESS_CHECKLIST.md`](app/src/docs/RELEASE_READINESS_CHECKLIST.md)
* [`SIGNED_RELEASE_AAB_GUIDE.md`](app/src/docs/SIGNED_RELEASE_AAB_GUIDE.md)
* [`MANUAL_QA_TEST_MATRIX.md`](app/src/docs/MANUAL_QA_TEST_MATRIX.md)
* [`PLAY_CONSOLE_COMPLIANCE_NOTES.md`](app/src/docs/PLAY_CONSOLE_COMPLIANCE_NOTES.md)
* [`ANDROID_APP_REVIEW.md`](app/src/docs/ANDROID_APP_REVIEW.md)

## Privacy Policy

For information on how user data is handled, please review our [Privacy Policy](PRIVACY_POLICY.md).

## License

Copyright © 2026 DocEditor. All rights reserved.
