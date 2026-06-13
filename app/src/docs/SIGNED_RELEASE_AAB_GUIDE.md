# Signed Release AAB Guide

This project is configured to build debug variants with a sample AdMob App ID and to fail early for release builds if required production values are missing.

## Required local files and properties

### 1. Create `app/keystore.properties`
Start from `app/keystore.properties.example`, copy it to `app/keystore.properties`, and fill in your real values.

```properties
storeFile=release.keystore
storePassword=your-strong-password
keyAlias=doceditor
keyPassword=your-strong-password
```

Notes:
- `app/keystore.properties` must stay local and must not be committed.
- `.gitignore` already excludes `app/keystore.properties` and `app/*.keystore`.
- If your keystore is stored elsewhere, set `storeFile` to the correct relative or absolute path.

### 2. Set the production AdMob App ID
Release builds require a real AdMob App ID through the Gradle property `DOCEDITOR_ADMOB_APP_ID`.

#### Option A: user-level Gradle properties
Add this to your user Gradle properties file.

Windows path:
`C:\Users\<your-user>\.gradle\gradle.properties`

```properties
DOCEDITOR_ADMOB_APP_ID=ca-app-pub-xxxxxxxxxxxxxxxx~yyyyyyyyyy
```

#### Option B: session-only PowerShell environment variable
```powershell
$env:ORG_GRADLE_PROJECT_DOCEDITOR_ADMOB_APP_ID="ca-app-pub-xxxxxxxxxxxxxxxx~yyyyyyyyyy"
```

## Build commands

### Debug APK
```powershell
Set-Location "C:\Users\Dredio\AndroidStudioProjects\DocEditor"
.\gradlew.bat assembleDebug
```

Output:
`app\build\outputs\apk\debug\app-debug.apk`

### Signed release App Bundle (AAB)
```powershell
Set-Location "C:\Users\Dredio\AndroidStudioProjects\DocEditor"
.\gradlew.bat bundleRelease
```

Output:
`app\build\outputs\bundle\release\app-release.aab`

## What the build validates automatically
When the Gradle task graph includes a release task, the project now checks:
- release signing keys exist in `app/keystore.properties`
- `DOCEDITOR_ADMOB_APP_ID` is set

If either is missing, the build fails immediately with a clear error message instead of producing a broken release.

## Pre-flight checklist before running `bundleRelease`
- Increment `versionCode` in `app/build.gradle.kts`
- Update `versionName` in `app/build.gradle.kts`
- Confirm `PRIVACY_POLICY.md` matches shipped behavior
- Confirm the Play listing matches the shipped build (for example: PDF annotation/edit-export behavior, scanner/OCR gating, and whether visible ads actually appear)
- Confirm Play Console product IDs match the code:
  - `premium_upgrade`
  - `pro_monthly`
  - `pro_yearly`
- Confirm you are not using Google sample AdMob IDs in release
- Confirm your Data safety form is updated for Billing, AdMob SDK integration, camera/scanner, document access, and on-device OCR
- Run:
  - `./gradlew.bat compileDebugKotlin`
  - `./gradlew.bat assembleDebug`
  - `./gradlew.bat lintDebug`

## Compliance note about ads
The current codebase initializes the AdMob SDK and requires a real release App ID, but visible ad rendering should be verified in the shipped build before describing the app as ad-supported in Play listing copy.

## Recommended release flow
1. Build and test `assembleDebug`
2. Build `bundleRelease`
3. Upload the AAB to an internal or closed testing track first
4. Install the Play-delivered build on a physical device
5. Verify ads, billing, scanner, OCR, recent files, and settings before wider rollout

## Troubleshooting
### Error: release signing is not configured
Make sure all of these keys are present in `app/keystore.properties`:
- `storeFile`
- `storePassword`
- `keyAlias`
- `keyPassword`

### Error: `DOCEDITOR_ADMOB_APP_ID` is missing
Set the property in either:
- `C:\Users\<your-user>\.gradle\gradle.properties`, or
- the current PowerShell session with `ORG_GRADLE_PROJECT_DOCEDITOR_ADMOB_APP_ID`

### Want to inspect the generated outputs?
Common locations:
- Debug APK: `app/build/outputs/apk/debug/`
- Release bundle: `app/build/outputs/bundle/release/`

