# M175a Studio — HP LaserJet 100 MFP M175a control suite

A complete, self-developed printing & scanning stack for the **HP LaserJet 100
color MFP M175a** (USB VID `03F0` / PID `062A`), built from live wire captures
of the real device — every protocol byte verified, nothing guessed.

```
┌──────────────┐  USB OTG   ┌─────────────┐
│   Android    │───────────▶│  HP M175a   │   Print · Scan (flatbed+ADF) ·
│  (this app)  │            │             │   Toner/drum status · Manual
└──────────────┘            └─────────────┘   duplex · Booklet · 21 papers
       │
       │ optional (Wi-Fi / same LAN)
       ▼
┌──────────────────────────────┐
│  windows-bridge (Python)     │  eSCL + IPP bridge that lets ANY phone
│  runs on the PC, owns USB    │  or iOS device print & scan over Wi-Fi
└──────────────────────────────┘
```

## Repository layout

| Path | What it is |
|---|---|
| `app/` | **Android app** (Kotlin + Compose Material 3) — USB OTG print/scan/status, system PrintService, plus Wi-Fi bridge mode |
| `docs/` | **M175 Android DevKit** — 17 reverse-engineering reports: USB/PCL XL/LEDM protocols, verified packet captures analysis, scan-corruption root-cause chain, full audit |
| `windows-bridge/` | PC-side Python bridge (IPP/eSCL → Windows spooler/WIA) + USBPcap analysis tools |
| `reference/` | Raw captured OTG streams used by the reverse-engineering docs |

## Feature summary (app, v0.1)

- **Print**: PDFs & images over USB OTG (PCL XL), 300/600 dpi, color or greyscale,
  page ranges, copies, scale/margins/position, orientation, **21 paper sizes**
  with media names byte-verified against HP's own driver output
- **Fast B&W**: 1-bit RLE mono path (Windows-GDI parity, 5–10x smaller pages,
  no printer JPEG decode) + printer-side `@PJL COPIES` — multi-page greyscale
  prints at engine speed
- **Preview first**: thumbnail preview of the exact page plan before anything prints
- **Manual duplex** & **booklet imposition**: waits for the engine to really finish
  side 1 (BIDI status poll), then shows the flip prompt on the **printer display**
  (`@PJL RDYMSG`) and the phone at the same moment
- **Wi-Fi bridge client**: point the app at a PC bridge or an old-phone bridge —
  print/scan/toner over Wi-Fi with zero cable
- **Phone bridge host**: an old phone stays on OTG and becomes the Wi-Fi printer
  (foreground service + wake/Wi-Fi locks, FIFO job queue, multi-page eSCL, mDNS)
- **Scan**: flatbed + ADF, JPEG/PDF output, 200/300/600 dpi (1200 removed —
  15+ min/page, no visible gain), greyscale/color/B&W, chunked-transfer hardened
  against HP's misframed HTTP chunks
- **Status**: live toner levels, drum, lifetime page counters, alerts
- **System integration**: registered Android `PrintService` (print from any
  app), share-sheet intake, persisted defaults, job history, notifications

## Building the app

**Requirements**: JDK 17, Android SDK (platform 34, build-tools 34), Gradle
8.7 (wrapper included).

```bash
./gradlew assembleDebug          # APK at app/build/outputs/apk/debug/
./gradlew testDebugUnitTest      # 29 unit tests (incl. wire-byte regression)
```

Or open the folder in Android Studio / a GitHub Codespace — the included
devcontainer provisions JDK 17 + the Android SDK automatically.

> **Hardware note**: printing/scanning needs a real M175a on the cable.
> Emulators can only exercise the UI and the pure-JVM protocol layers.

## The bridges (either one makes the printer Wi-Fi)

**PC bridge** (printer tethered to a Windows PC):

```powershell
cd windows-bridge
install.bat          # creates venv, installs requirements.txt, registers autostart
python run.py        # serves IPP + eSCL + web UI on :8080
```

**Phone bridge** (no PC — an old Android phone stays on the OTG cable):
app → Setup → *Host bridge on this phone*. Other phones then use
Setup → *Wi-Fi bridge* with the shown address. Background service with
FIFO queueing, so office phones wait their turn.

See `docs/README.md` for the full reverse-engineering story, including the
verified USB endpoint map (`docs/09-OTG-PROTOCOL-VERIFIED.md`) and the
scan-over-OTG protocol (`docs/10-SCAN-OTG-VERIFIED.md`).
