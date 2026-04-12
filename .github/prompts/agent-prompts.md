# Agent Prompts

When generating or refactoring code for DocEditor:
1. Try to adopt a ViewModel-based architecture with coroutines.
2. Check `gradle.properties` or `libs.versions.toml` before adding dependencies.
3. Keep offline capabilities and privacy restrictions in mind.
4. If editing themes, modify `Theme.kt`, `Color.kt` rather than local components.
5. Provide minimal viable edits using replace_string_in_file when applicable.

