# Android Conventions

- **UI Framework**: Jetpack Compose + Material 3.
- **Architecture**: Keep Compose UI functions small and previewable (use `Preview` annotations).
- **Theming**: Keep theme logic in `app/src/main/java/com/document/editor/ui/theme/`.
- **Package name**: Keep all Kotlin code under `com.document.editor`.
- **SDK**: Targets API 36, minSdk 24.
- **Dependencies**: Use `gradle/libs.versions.toml` to manage plugin/library versions rather than hardcoded coordinates.
- **Comments**: Keep agent notes short and colocated. Do not duplicate code comments.

