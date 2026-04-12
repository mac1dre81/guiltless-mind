# DocEditor Agent Guide

## Project snapshot
- `DocEditor` is a single-module Android app using Jetpack Compose + Material 3.
- The launch path is `app/src/main/AndroidManifest.xml` -> `com.document.editor.MainActivity`.
- `MainActivity.kt` currently hosts a simple `Greeting()` screen inside `DocEditorTheme` and `Scaffold`.

## Key code paths
- `app/src/main/java/com/document/editor/MainActivity.kt`: app entry point, `enableEdgeToEdge()`, Compose setup, previewable UI.
- `app/src/main/java/com/document/editor/ui/theme/{Theme.kt,Color.kt,Type.kt}`: app theme wrapper, dynamic color on Android 12+, shared design tokens.
- `gradle/libs.versions.toml`: single source of truth for plugin/library versions; prefer aliases over hardcoded coordinates.
- `app/build.gradle.kts`: Compose is enabled via `buildFeatures.compose = true`; dependencies are managed through the version catalog.

## Build, test, and debug
- Use the Gradle wrapper from the repo root on Windows: `.
gradlew.bat`.
- Common checks: `.
gradlew.bat assembleDebug`, `.
gradlew.bat testDebugUnitTest`, `.
gradlew.bat connectedDebugAndroidTest`.
- Android Studio run config is already named `app` in `.idea/workspace.xml` and targets a local emulator.

## Conventions to preserve
- Keep Kotlin package names under `com.document.editor`.
- Keep Compose UI functions small and previewable; `GreetingPreview()` shows the current preview pattern.
- Keep theme changes in `ui/theme` rather than in feature screens.
- The project uses `kotlin.code.style=official` and Java/Kotlin source compatibility at 11.
- Keep manifest labels/icons/theme aligned with the launcher activity.

## Integration points and boundaries
- External deps are limited to AndroidX, Compose BOM, Material 3, JUnit, and Espresso; there is no backend/service layer yet.
- `compileSdk`/`targetSdk` are set to 36, with `minSdk = 24`; watch API-level assumptions in new code.
- Release builds keep `minifyEnabled = false` and use `proguard-rules.pro` if that changes later.

## AI guidance
- Existing IDE Copilot metadata points to `.github/instructions` and `.github/prompts` in `.idea/workspace.xml`; those folders are not present yet.
- If you add agent-specific notes, keep them short and colocated with real repo structure rather than duplicating code comments.
