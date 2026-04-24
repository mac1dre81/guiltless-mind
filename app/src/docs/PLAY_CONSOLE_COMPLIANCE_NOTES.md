# Play Console Compliance Notes

Use this document when filling out Play Console metadata, Data safety, and release notes for DocEditor.

## What the current app actually does
- Opens user-selected documents through Android's Storage Access Framework
- Persists recent-file URI strings and last-opened timestamps locally in DataStore
- Edits PDF files by adding freehand and text annotations, then exporting an edited PDF copy
- Edits text and Markdown files
- Uses Google Play Billing for Premium and Pro purchases
- Uses camera/document scanner functionality for the Pro scanner flow
- Runs OCR locally using ML Kit text recognition
- Initializes the AdMob SDK for release monetization support
- Disables Android app-data backup in the manifest (`android:allowBackup="false"`)
- Explicitly blocks cleartext traffic in the manifest (`android:usesCleartextTraffic="false"`)

## Important store-listing accuracy checks
Before submitting, make sure your Play listing does **not** claim features the shipped build does not currently provide.

### Safe claims for the current codebase
- PDF editing (annotation/export workflow)
- Text editing
- Markdown editing and preview
- Document scanning for Pro users
- OCR text extraction for Pro users
- Offline/local document processing

### Claims to verify before publishing
- Visible banner ads in the free tier
- Ad removal behavior in Premium
- Any statement that PDFs support arbitrary content rewriting instead of annotation-based editing/export
- Any statement that files sync to a backend or cloud service

## Release configuration handling
- Keep signing keys and release-only IDs out of version control.
- Provide `DOCEDITOR_ADMOB_APP_ID` from a local untracked Gradle property or CI secret.
- Treat Play listing copy, privacy policy, README, and release notes as a single source-of-truth set that must stay aligned.

## Data safety form topics to review
This is not legal advice, but these are the release-sensitive areas you should review carefully in Play Console:

- **Billing / purchases**: Play Billing is integrated for one-time and subscription products
- **Ads / advertising ID**: AdMob SDK support is integrated; use the conservative disclosure that matches your shipped release
- **Camera**: required for scanner flow
- **Files and docs**: user-selected documents are accessed via SAF and kept local to the device by the app
- **OCR / scanner**: ML Kit OCR is performed on-device; scanned content stays on device under app control
- **Diagnostics**: local crash diagnostics are written to app-local storage

## Product IDs currently referenced in code
- `premium_upgrade`
- `pro_monthly`
- `pro_yearly`

## Final pre-submission questions
- Does the shipped build actually render ads anywhere?
- Does Premium remove those ads in that exact build?
- Does the listing describe PDF editing accurately as annotation-based editing/export?
- Do privacy policy, README, and Play listing all agree on scanner/OCR being Pro-only?
- Are support email and privacy-policy URL production-ready?

