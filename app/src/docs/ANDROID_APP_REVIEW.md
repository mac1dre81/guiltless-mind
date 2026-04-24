# Android App Review and Optimization Plan

This review maps the current `DocEditor` codebase against modern Android best practices and records both the improvements implemented in this pass and the recommended follow-up work.

## What was improved in this pass

### 1. Configuration and secret handling
- Removed the tracked `DOCEDITOR_ADMOB_APP_ID` value from `gradle.properties`.
- Removed the machine-specific `org.gradle.java.home` override from `gradle.properties` so local builds and CI are more portable.
- Updated `app/build.gradle.kts` so release AdMob configuration can come from either a Gradle property or a CI environment variable.
- Explicitly set `android:usesCleartextTraffic="false"` in `app/src/main/AndroidManifest.xml`.

**Why this helps**
- Prevents accidental source control exposure of release-only values.
- Avoids build failures on machines that do not have Android Studio installed in the same path.
- Makes future CI/CD setup simpler and safer.
- Enforces HTTPS-only networking by default.

### 2. Editor settings actually affect the app
- Centralized editor preferences in `RecentFilesRepository` via a typed `EditorPreferences` model.
- Wired `font_size` and `auto_save` preferences from `SettingsActivity` into storage.
- Applied the editor font size in `DocumentEditorFragment`.
- Added debounced auto-save for text and Markdown documents.
- Ensured the debounced save is silent to avoid repetitive success toasts.
- Cancelled pending auto-save work and removed the text watcher in `onDestroyView()`.

**Why this helps**
- Turns existing settings into real, user-visible functionality.
- Improves accessibility through larger text support.
- Reduces accidental data loss for text-based documents.
- Prevents view lifecycle leaks and redundant save requests.

### 3. Continuous integration baseline
- Added `.github/workflows/android.yml` to run `assembleDebug`, `lintDebug`, and `testDebugUnitTest` on pull requests and pushes.

**Why this helps**
- Catches regressions earlier.
- Raises confidence for release branches.
- Establishes the base for future instrumentation, release, and deployment workflows.

### 4. Accessibility hardening
- Added stronger spoken labels for recent-file rows, PDF tool buttons, zoom state, and page indicator state.
- Replaced several transient toast-only messages in `DocumentListFragment` with `Snackbar` feedback so important changes are easier for accessibility services to announce.
- Marked high-value section titles as accessibility headings in code where supported.
- Improved scanner and OCR loading/error UI with clearer accessible text and polite live-region semantics.
- Added focused Android test coverage for editor accessibility metadata in `DocumentEditorFragmentAccessibilityTest.kt`.

**Why this helps**
- Makes the app easier to use with TalkBack and other assistive technologies.
- Improves discoverability of selection state and dynamic PDF editing controls.
- Gives users better feedback during scanner/OCR processing and recent-file maintenance.
- Reduces reliance on gesture-only or visually implied state changes.

### 5. Hilt migration baseline
- Enabled Hilt in the application and main host surfaces.
- Converted `RecentFilesRepository` to constructor injection from `@ApplicationContext`.
- Injected `RecentFilesRepository` into the main activity, document list, document editor, settings fragment, and OCR activity.
- Migrated `DocumentViewModel` to `@HiltViewModel` so the editor flow now resolves through Hilt.

**Why this helps**
- Removes repeated manual repository construction.
- Improves consistency and testability of app-scoped dependencies.
- Establishes a clean base for later DI expansion into billing, scanner, and other services.

## Current strengths
- Kotlin-first codebase
- MVVM-style `ViewModel` usage for editor state
- DataStore-backed settings persistence
- Material 3 and edge-to-edge support in key screens
- Play Billing, ML Kit scanner/OCR, and PDF editing foundations
- Existing unit tests, lint checks, and Android test coverage for PDF tooling

## Highest-priority follow-up recommendations

### Architecture
- Split `DocumentEditorFragment` into smaller feature-focused collaborators:
  - text/markdown editor controller
  - PDF editor controller
  - file save coordinator
- Continue the Hilt rollout to `PremiumManager`, scanner collaborators, and other service-like classes.
- Convert new state surfaces to `StateFlow` where practical for consistency with coroutines.
- Consider feature modularization once the editor and monetization flows stabilize.

### Security
- Move any remaining release secrets to CI secrets or local untracked files only.
- Review whether a real signing keystore or release app IDs were ever committed; if yes, rotate them.
- Use encrypted storage for sensitive user or billing-related local values if such data is added later.
- Add a network security config if domain-level restrictions or pinning become necessary.
- Run a static security audit before release with MobSF or QARK.

### Performance
- Add a Baseline Profile and Macrobenchmark module for startup and scrolling.
- Profile PDF rendering and export paths with Android Studio Profiler.
- Use structured pagination if recent files or document catalogs grow substantially.
- Continue moving heavy work off the main thread and audit bitmap memory pressure.

### UI / UX
- Keep expanding responsive behavior for tablets and foldables.
- Add richer motion only where it improves comprehension, not at the expense of performance.
- Consider replacing remaining legacy View-based editor surfaces incrementally with Compose where it simplifies state handling.

### Accessibility
- Run Accessibility Scanner on all major flows.
- Verify touch target sizes, TalkBack focus order, and content descriptions across document, settings, billing, scanner, and OCR screens.
- Add a non-gesture fallback for advanced PDF box editing flows in a future pass.
- Continue expanding explicit tests and QA checklist coverage for large text, dark theme, and dynamic announcements.

### App Store Optimization / Play readiness
- Keep README, privacy policy, Play listing, and in-app upsell copy aligned with shipped behavior.
- Only claim ads, ad removal, or PDF rewriting features if the shipped build truly supports them.
- Add App Links and analytics only if the product roadmap requires discoverability and attribution.

### Testing and delivery
- Add Hilt-ready unit tests or fake repositories when DI is introduced.
- Expand Android tests for the new text settings behavior.
- Add a release workflow with signed bundle generation once signing inputs are provided through CI.
- Use Firebase Test Lab or a device farm before production rollout.

## Verification checklist
- [x] Release-only AdMob ID removed from tracked Gradle properties
- [x] Machine-specific `org.gradle.java.home` removed from tracked Gradle properties
- [x] Manifest explicitly blocks cleartext traffic
- [x] Settings screen writes dark mode, font size, and auto-save preferences
- [x] Text/Markdown editor applies stored font size
- [x] Text/Markdown editor auto-saves after a debounce interval
- [x] Auto-save does not spam success toasts
- [x] CI workflow added for build, lint, and unit tests
- [x] Dedicated accessibility hardening pass completed for the highest-impact document, PDF, scanner, and OCR surfaces
- [x] Hilt baseline added for application, repository injection, and the editor ViewModel
- [ ] Add Baseline Profile / Macrobenchmark
- [ ] Add release CI and device-cloud validation

