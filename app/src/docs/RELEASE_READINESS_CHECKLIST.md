# DocEditor Release-Readiness Checklist

Use this checklist before uploading a build to Google Play.

## 1. Code and build health
- [ ] `./gradlew.bat compileDebugKotlin` succeeds
- [ ] `./gradlew.bat assembleDebug` succeeds
- [ ] `./gradlew.bat lintDebug` reviewed and release-relevant issues resolved
- [ ] `./gradlew.bat bundleRelease` succeeds with production signing inputs
- [ ] Release build launches on at least one physical Android device
- [ ] No lint errors remain; release builds are allowed to fail on lint errors

## 2. Signing and release configuration
- [ ] `app/keystore.properties` exists locally and is **not** committed
- [ ] `storeFile`, `storePassword`, `keyAlias`, and `keyPassword` are all present
- [ ] `DOCEDITOR_ADMOB_APP_ID` is defined in Gradle properties for release builds
- [ ] `versionCode` is incremented in `app/build.gradle.kts`
- [ ] `versionName` is updated in `app/build.gradle.kts`

## 3. Privacy, permissions, and disclosures
- [ ] `PRIVACY_POLICY.md` matches the shipped behavior
- [ ] Google Play Data safety form matches ads, billing, camera, and local file handling
- [ ] Monetization tiers in the store listing match the code behavior
- [ ] Test AdMob IDs are not used in release
- [ ] Camera access is explained in store copy or onboarding if scanner is advertised
- [ ] Store listing does not promise visible banner ads unless the shipped build actually renders them
- [ ] Store listing describes PDF editing accurately for the shipped annotation/export flow
- [ ] Backup behavior is accurate: app data backup is intentionally disabled in the manifest

## 4. Monetization checks
- [ ] One-time Premium product ID matches Play Console: `premium_upgrade`
- [ ] Pro subscription IDs match Play Console: `pro_monthly`, `pro_yearly`
- [ ] Free tier shows ads only where expected
- [ ] Premium removes ads and unlocks paid features
- [ ] Pro unlocks scanner and OCR without app restart
- [ ] Purchase restore / account change behavior is verified
- [ ] If ads are not yet rendered in the shipped build, free-tier marketing copy does not call the app "ad-supported"

## 5. Core feature checks
- [ ] Open TXT document via Storage Access Framework
- [ ] Edit TXT and save back successfully
- [ ] Open Markdown file, edit, preview, and save
- [ ] Open PDF and confirm viewer renders
- [ ] Add PDF annotations (draw + text) and export an edited PDF copy successfully
- [ ] Recent files persist across app restart
- [ ] Missing or moved files are removed gracefully from recent files
- [ ] Scanner flow works end to end on a real device
- [ ] OCR result can save as `.txt`
- [ ] OCR result can save as `.md`

## 6. Release build quality
- [ ] `minifyEnabled` and `shrinkResources` remain enabled for release
- [ ] App icon, label, and splash screen branding are correct
- [ ] No placeholder support email, URLs, or sample IDs remain in release assets
- [ ] App diagnostics do not expose sensitive document contents
- [ ] Crash recovery message is understandable and non-technical
- [ ] Accessibility Scanner run completed on Documents, Editor, Scanner, and OCR flows
- [ ] TalkBack focus order and spoken labels verified for recent files, PDF tools, settings navigation, and OCR actions
- [ ] Large font / display scaling checked for text editor, markdown preview, and OCR result screen

## 7. Store submission assets
- [ ] App title and short description are finalized
- [ ] Full description matches actual app behavior
- [ ] Phone screenshots are up to date
- [ ] Feature graphic is ready
- [ ] Privacy policy URL is live
- [ ] Support email is monitored

## 8. Rollout plan
- [ ] Closed/internal test track uploaded first
- [ ] Smoke test installed from Play internal testing
- [ ] Rollout notes prepared for v1.0
- [ ] Rollback plan documented if billing or scanner flows fail in production

