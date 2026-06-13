# Manual QA Test Matrix

Use this matrix on at least one physical device before release. Prefer one Android 13+ phone and one lower API phone if available.

| Area | Scenario | Steps | Expected Result |
|---|---|---|---|
| App launch | Fresh install | Install debug or release build, open app | Splash screen appears, app reaches document list without crash |
| App launch | Reopen after force close | Force stop app, relaunch | App opens normally, recent files persist |
| Recent files | Empty state | Launch app with no recent files | Empty state message is visible and no crash occurs |
| Recent files | TalkBack row summary | Enable TalkBack, focus a recent file row | Row announces file name, subtitle, and removal hint clearly |
| Recent files | Add text file | Open `.txt` via picker | File opens in editor and appears in recent files |
| Recent files | Add markdown file | Open `.md` via picker | File opens in markdown editor and appears in recent files |
| Recent files | Add PDF | Open `.pdf` via picker | File opens in PDF viewer and appears in recent files |
| Recent files | Missing file cleanup | Move/delete a previously opened file outside the app, reopen app | Broken item is removed and user sees a friendly message |
| Editor | Edit TXT | Modify text and save | Content persists after reopen |
| Editor | Large text setting | Increase font size in Settings, reopen editor | Editor text, markdown preview, and search field all scale up clearly |
| Editor | Markdown preview | Open markdown file, toggle preview/edit, save | Preview renders and save works |
| Editor | Search | Search text inside TXT/Markdown | Match is selected and navigation works |
| PDF | Basic render | Open small PDF | First page renders without crash |
| PDF | Large PDF | Open a large PDF | App remains responsive and errors gracefully if unsupported |
| PDF | Draw annotation | Open a PDF, draw on a page, save | Edited PDF copy is generated and visible in recent files |
| PDF | Text annotation | Open a PDF, place a text note, save | Text annotation appears in the exported PDF copy |
| PDF | Multi-page edit | Open a multi-page PDF, navigate pages, annotate more than one page, save | Edited copy contains annotations on the correct pages |
| PDF | TalkBack tool state | Enable TalkBack, move focus across PDF tool buttons | Each tool announces its label and whether it is currently selected |
| PDF | TalkBack page/zoom state | Open a PDF and adjust zoom | Page indicator and zoom control expose the updated state clearly |
| Settings | Dark mode | Toggle dark mode in settings | Theme updates and persists after restart |
| Settings | Navigate up label | Open Settings with TalkBack enabled | Back navigation announces a clear “navigate up” label |
| Billing | Free tier | Launch app without purchases | Free state shown and monetization messaging is accurate for the shipped build |
| Ads | Visible ad surface | Test the release build on the documents screen | If ads are enabled for the shipped release, they render only where intended; if not, the store listing does not promise visible ads |
| Billing | Premium purchase flow | Trigger Premium purchase | Billing sheet opens and paid unlock state updates after purchase |
| Billing | Pro purchase flow | Trigger scanner paywall purchase | Billing sheet opens and Pro-gated flows unlock after purchase |
| Billing | Restore purchases | Reinstall app or switch account state | Entitlements restore correctly |
| Scanner | Cancel scan | Start scanner and cancel | App returns safely without crash |
| Scanner | Multi-page scan | Scan multiple pages | Scan completes and OCR screen opens |
| Scanner | Loading announcement | Start scanner with TalkBack enabled | Loading screen announces scanner preparation state clearly |
| OCR | Success path | Complete scan with readable text | OCR text appears in editable field |
| OCR | Blank/poor image | Scan blank page or low-text image | App shows empty/low-result state without crash |
| OCR | Retry | Force OCR failure if possible, tap Retry | Retry runs and UI remains usable |
| OCR | TalkBack status changes | Use TalkBack during loading and error states | OCR loading and retry states are announced without trapping focus |
| OCR | Save as TXT | Save OCR result as `.txt` | File is created and appears in recent files |
| OCR | Save as MD | Save OCR result as `.md` | File is created and appears in recent files |
| Clipboard | Copy extracted text | Tap Copy on OCR result screen | Clipboard is updated and feedback message appears |
| Permissions | Camera denied | Deny camera permission and start scanner | User gets a clear failure path without crash |
| Permissions | SAF persisted URI | Open file, restart app, reopen recent item | File still opens if permission remains valid |
| Rotation | OCR screen rotation | Rotate device during OCR | App remains stable and no fatal state loss occurs |
| Offline | No network | Turn on airplane mode, use editor and OCR | Core local features still work; ads/billing degrade gracefully |
| Release build | Signed AAB smoke test | Install Play-distributed or locally built release | App launches, billing initializes, and release-only config is correct |

## Device notes to capture during QA
- Device model
- Android version
- Build type (`debug` or `release`)
- Whether Google Play Services is up to date
- Network state (`online`, `offline`, `metered`)
- Whether TalkBack or Accessibility Scanner was used
- Font scale / display size used during testing
- Any crash breadcrumb or recovery message shown

