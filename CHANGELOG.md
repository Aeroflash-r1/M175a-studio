# Changelog

## v0.1 — first release (re-cut)

- App name **M175a Print**, package `com.m175astudio` (was `com.ganesan.m175otg`)
- Release APK is **R8-minified** (`minify + shrinkResources`, debug-signed for sideload)
- Notification permission fixed: `POST_NOTIFICATIONS` is now declared, asked on
  first launch, and re-askable from Setup → Notifications (incl. app-Settings
  path when permanently denied)
- Setup tab now carries the full phone-bridge guide (host steps + client steps)

Android app (`com.m175astudio`, versionCode 1) + PC bridge + reverse-engineering DevKit.

**Printing (USB OTG, PCL XL)**

- PDFs & images, 300/600 dpi, color/greyscale, 21 byte-verified paper sizes
- Fast B&W 1-bit RLE mono path (Windows-GDI parity) + printer-side `@PJL COPIES`
- Thumbnail preview of the exact page plan before printing
- Manual duplex & booklet: engine-idle wait (BIDI poll), then flip prompt on the
  printer display (`@PJL RDYMSG`) and the phone together
- N-up, booklet imposition, page ranges, odd/even, reverse, skip-blank, copies
- System `PrintService`, share-sheet intake, job history, notifications

**Scanning**

- Flatbed + ADF over WS-Scan/WIA paths, JPEG/PDF output, 200/300/600 dpi
  (1200 dpi step removed: 15+ min/page, no visible gain on this engine)
- DIME/chunked-framing hardened against the printer's misframed transfers,
  integrity gate with auto-retry, auto-levels, lineart mode

**Wi-Fi (no cable)**

- Bridge client: print/scan/status through a PC bridge or a phone bridge
- Phone bridge host: old phone on OTG becomes the Wi-Fi printer —
  foreground service (wake + Wi-Fi locks), FIFO engine queue, lazy multi-page
  eSCL sessions, mDNS (`_ipp._tcp` / `_uscan._tcp`)
- PC bridge (`windows-bridge/`): IPP/eSCL → WinSpool/WIA on `:8080`,
  with `requirements.txt` and web UI

**Status & health**

- Live toner/drum levels, lifetime counters, low-toner alerts
- Cooperative cancel (USB + scan paths), printer rescue sequences

Verified: `./gradlew assembleDebug testDebugUnitTest` — 40 unit tests green,
including wire-byte regression tests against real captures.
