# DocEditor Best Practices

This document outlines the coding standards, design patterns, and best practices followed in the DocEditor Android application.

## a) Coding Style Guidelines

### Language-Specific Conventions
- Follow [Google's Kotlin Style Guide](https://developer.android.com/kotlin/style-guide)
- Use 4-space indentation (not tabs)
- Limit line length to 100 characters
- Import ordering: Android, androidx, other libraries, local project

### Naming Conventions
- Classes: PascalCase (e.g., `DocumentViewModel`)
- Functions and variables: camelCase (e.g., `loadDocument()`)
- Constants: UPPER_SNAKE_CASE (e.g., `MENU_TOGGLE_MARKDOWN_MODE`)
- File names: match class name (e.g., `DocumentEditorFragment.kt`)

### File Organization
- One class per file (except for small helper functions)
- Keep UI-related code in fragments/activities
- Separate business logic into ViewModels and repositories
- Place extensions and utility functions in appropriate packages

### Formatting
- Use trailing commas in function calls with multiple parameters
- Prefer single quotes for strings without interpolation
- Use string templates for strings with variables
- Apply consistent spacing around operators and after commas

## b) Design Patterns

### Creational Patterns

#### Factory Pattern ✅
**Example:** `DocumentViewModel.DocType` enum with `determineDocType()` method
```kotlin
private fun determineDocType(uri: Uri): DocType {
    // Returns appropriate DocType based on URI inspection
}
```
**Counterexample:** Hardcoding document type checks throughout the codebase
**Rationale:** Centralizes document type detection logic, making it easier to add new formats

### Structural Patterns

#### Adapter Pattern ✅
**Example:** `Markwon` library usage for Markdown rendering
```kotlin
markwon?.setMarkdown(markdownView, contentText)
```
**Counterexample:** Direct manipulation of TextView with HTML/Markdown strings
**Rationale:** Separates Markdown rendering logic from UI components

#### Facade Pattern ✅
**Example:** `RecentFilesRepository` simplifies DataStore interactions
```kotlin
suspend fun addRecentFile(uri: String) { /* complex logic hidden */ }
```
**Counterexample:** Direct DataStore access scattered throughout the codebase
**Rationale:** Provides a clean interface for recent file management

### Behavioral Patterns

#### Observer Pattern ✅
**Example:** LiveData usage in ViewModel-Fragment communication
```kotlin
viewModel.content.observe(viewLifecycleOwner) { contentText -> /* update UI */ }
```
**Counterexample:** Manual UI updates or tight coupling between components
**Rationale:** Enables automatic UI updates when data changes

#### Strategy Pattern ✅
**Example:** Different UI updates based on document type in `updateDocumentTypeUi()`
```kotlin
when (type) {
    DocumentViewModel.DocType.TEXT -> { /* show edit text */ }
    DocumentViewModel.DocType.MARKDOWN -> { /* show editor/preview */ }
    // ...
}
```
**Counterexample:** Large conditional blocks duplicated across multiple methods
**Rationale:** Encapsulates type-specific UI logic in one place

## c) Error Handling Patterns

### Try/Catch Boundaries
- Catch exceptions at repository level where recovery is possible
- Allow exceptions to propagate to ViewModel for UI-level error display
- Use `runCatching` for non-critical operations where failure should be silent

### Result Types
- Use `LiveData<String>` for error messages in ViewModel
- Repository methods return `Unit` or throw exceptions for handling in ViewModel
- Consider adopting Kotlin's `Result` type for explicit success/failure handling

### Exception Hierarchies
- Currently uses generic `Exception`; consider creating domain-specific exceptions
- Base exception: `DocumentEditorException`
- Specific exceptions: `UnsupportedFormatException`, `StorageException`

### Cleanup Patterns
- Use `use {}` for AutoCloseable resources (streams, file descriptors)
- Implement `onCleared()` in ViewModel for resource cleanup
- Apply proper lifecycle awareness with `viewLifecycleOwner`

## d) Logging and Debugging Patterns

### Log Levels
- **ERROR:** Critical failures that prevent core functionality
- **WARN:** Recoverable issues or unexpected states
- **INFO:** Important lifecycle events and user actions
- **DEBUG:** Detailed diagnostic information for development
- **VERBOSE:** Fine-grained tracing (rarely used)

### Structured Logging
- Include tag, message, and relevant context (IDs, states)
- Use placeholder syntax for performance: `Log.d(TAG, "Loading uri: %s", uri)`
- Avoid string concatenation in production builds

### Correlation ID
- Not currently implemented; consider adding for tracking user sessions
- Could be passed through ViewModel calls to trace operations

## e) Testing Patterns

### Arrange-Act-Assert (AAA)
```kotlin
@Test
fun `saveDocument writes content to file`() {
    // Arrange: Set up mock repository and test data
    val testUri = "test://file.txt"
    val testContent = "Hello, World!"
    
    // Act: Call method under test
    viewModel.saveDocument(testUri, testContent)
    
    // Assert: Verify expected outcome
    verify(repository).saveDocument(testUri, testContent)
}
```

### Test Fixtures
- Use `@Before` to set up common test state
- Leverage `Mockito` for mocking dependencies
- Create test-specific implementations for complex dependencies

### Mocking vs Real Dependencies
- Mock external services (analytics, ads, file I/O)
- Use real implementations for pure functions and simple data transformations
- Consider using `androidx.test.core.app.ApplicationProvider` for Context

### Coverage Thresholds
- Target: 80% line coverage, 70% branch coverage
- Critical paths (file loading/saving) should exceed 90%
- UI layer tested via Espresso for critical user flows

## f) Performance Patterns

### Caching Strategies
- Recent files cached in DataStore (persistent)
- Consider in-memory caching for frequently accessed document content
- Implement LRU cache for bitmap resources in PDF preview

### Lazy Loading
- PDF pages loaded on demand, not all at once
- Markdown rendering triggered only when switching to preview mode
- ViewModel initialization deferred until first access

### Batch Operations
- Recent file updates batched to minimize DataStore writes
- Consider batching analytics events

### Connection Pooling
- Not applicable (no network connections in current implementation)
- For future network features: use OkHttp connection pooling

### Asynchronous Decisions
- Use coroutines with appropriate dispatchers (Main for UI, IO for disk/network)
- Long-running operations moved off-main thread
- UI updates always posted to Main dispatcher

## g) Security Patterns

### Input Validation
- Validate URI permissions before accessing files
- Check file sizes before loading to prevent OOM
- Sanitize file names for display (prevent path traversal in UI)

### Output Sanitization
- Markwon library handles Markdown sanitization
- TextView content is automatically Android-safe
- Consider additional validation for user-generated content

### Authentication/Authorization
- Not applicable (local-only app)
- For future cloud features: implement proper auth with least privilege

### Secret Management
- No secrets currently in codebase
- Future API keys should be in `local.properties` or build config
- Never commit secrets to version control

### Rate Limiting
- Not applicable (no network calls)
- For future implementation: use token bucket or leaky bucket algorithms

## h) Code Organization Patterns

### Modularization
- Separation of concerns: UI (Fragment/Activity), State (ViewModel), Data (Repository)
- Each class has a single, well-defined responsibility
- Dependencies flow downward: UI → ViewModel → Repository

### Dependency Injection
- Manual DI via constructors (ViewModel, Repository)
- Consider Hilt/Dagger for larger applications
- Avoid service locator pattern

### Configuration
- Externalize strings to `resources/values/strings.xml`
- Consider using `BuildConfig` for feature flags
- Use DataStore for user preferences

### Interfaces/Abstraction
- Define interfaces for repositories to enable testing
- Consider abstract base classes for shared ViewModel functionality
- Use composition over inheritance where appropriate

## i) Documentation Standards

### Docstring Format
- Use KDoc (Kotlin documentation) format
- Include @param, @return, @throws tags as appropriate
- Document complex invariants and assumptions

Example:
```kotlin
/**
 * Loads a document from the given URI and determines its type.
 *
 * @param uriString The content:// or file:// URI of the document to load
 * @throws IOException If there's an error accessing the document
 * @throws IllegalArgumentException If the URI format is invalid
 */
fun loadDocument(uriString: String) { /* ... */ }
```

### Module Documentation
- Each file should begin with a brief description of its purpose
- Complex algorithms should include explanatory comments
- Public APIs should have comprehensive documentation
