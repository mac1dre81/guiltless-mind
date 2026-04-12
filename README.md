# DocEditor

DocEditor is an offline-first PDF and Markdown editor for Android, built with Jetpack Compose and Material 3. It features advanced document scanning and OCR capabilities powered by Google ML Kit.

## Features

* **Offline-First:** All documents are stored locally and accessible without an internet connection.
* **Format Support:** View and edit PDF and Markdown files.
* **Persistent Access:** Recent files are remembered across app restarts via `takePersistableUriPermission`.
* **Document Scanner:** High-quality document scanning using Google ML Kit.
* **OCR Text Extraction:** Seamlessly extract editable text from scanned documents.
* **Dark Mode:** Full support for Android system dark mode and dynamic colors.
* **Monetization Tiers:**
  * **Free:** Ad-supported tier.
  * **Premium:** One-time purchase ($3.99) unlocks unlimited PDF pages, increased recent files, and removes ads.
  * **Pro:** Subscription ($2.99/month) unlocks the ML Kit-based Document Scanner and OCR capabilities.

## Architecture & Tech Stack

* **UI:** Jetpack Compose, Material 3
* **Language:** Kotlin
* **Architecture:** MVVM (Model-View-ViewModel) with structured concurrency (Coroutines & StateFlow)
* **Local Storage:** DataStore for preferences, Scoped Storage for file access
* **Machine Learning:** Google ML Kit (Document Scanner, Text Recognition)
* **Monetization:** Google Play Billing Library
* **Build System:** Gradle Version Catalogs (TOML)

## Requirements

* Android Studio Koala or newer
* Minimum SDK: Android 7.0 (API level 24)
* Target SDK: Android 15 (API level 36)

## Building the App

1. Clone the repository.-
2. Open the project in Android Studio.
3. Sync the project with Gradle files.
4. Run the app on an emulator or physical device using the `app` run configuration.
   * Or via command line: `./gradlew.bat assembleDebug`

After running `assembleDebug`, the generated debug APK will be located at:
`app/build/outputs/apk/debug/app-debug.apk`

### Release Build

To build the release App Bundle (AAB):

1. Ensure `keystore.properties` is present in the `app` directory with valid signing credentials (do not commit this file to version control).
2. Run the Gradle task: `./gradlew.bat bundleRelease`
3. The generated AAB will be located in `app/build/outputs/bundle/release/`.

## Privacy Policy

For information on how user data is handled, please review our [Privacy Policy](PRIVACY_POLICY.md).

## License

Copyright © 2026 DocEditor. All rights reserved.
