# Scanner Protocol

This document details the reverse-engineered scanner communication protocol for the HP LaserJet 100 color MFP M175a, and documents the Phase 5 Scanner Protocol Library.

## Protocol Stack
Scanner communication utilizes the following stack over Interface 0:
- **SOAP 1.2:** Messaging envelope for RPC calls and fault management.
- **XML:** Serialization format for message payloads and device structures.
- **DIME:** Binary packaging layout for transport of multi-part messages (e.g. SOAP XML + binary JPEGs).
- **WSD / WSCN:** Services and interfaces matching standard HP device profiles.

---

## Implemented Components

The HP M175a Scanner Protocol Library features a highly modular architecture split into pure functional areas:

### 1. XML Support (`XmlParser`, `XmlSerializer`)
Provides a lightweight, zero-dependency, strict XML parser and pretty-printing serializer:
- Strict tag matching validation (mismatched tags raise `XmlException`).
- Standard character entity encoding and decoding (`&amp;`, `&lt;`, `&gt;`, `&quot;`, `&apos;`).
- Native handling of nested structures, attributes, and namespace mappings.

### 2. SOAP Support (`SoapParser`, `SoapSerializer`)
Handles packaging and parsing of standard SOAP 1.2 messages:
- **SoapEnvelope:** Standard envelope with customizable namespaces.
- **SoapHeader:** Parsing and writing of WSA Addressing headers (`Action`, `MessageID`, `RelatesTo`, `To`).
- **SoapBody:** Extracting payload structures or translating `SoapFault` detail blocks (Codes, Reasons, Details).

### 3. DIME Support (`DimeParser`, `DimeSerializer`)
Translates multi-part record payloads between raw big-endian byte-arrays and lists of structured `DimePart` objects:
- Standardized byte alignment logic ensuring strict 4-byte padding offsets for all headers and payload elements.
- Automatic MB (Message Begin) and ME (Message End) state flagging for packet boundaries.
- Support for type-scheme mappings (such as MIME / application-soap-xml and image-jpeg).

### 4. WSCN Operations and Models (`WscnMessage`, `WscnMessageTranslator`)
Contains typed Kotlin models representing every core reverse-engineered scanner message:
- `GetScannerElements` / `ScannerElements`: Fetches and parses scanner status, hardware model, serial, ADF sensor state, supported color spaces, and DPI resolutions (300 and 600 DPI).
- `CreateScanJob` / `CreateScanJobResponse`: Sets scanning options (Resolution, ColorMode, Format, InputSource) and returns the job ID.
- `RetrieveImage` / `RetrieveImageResponse`: Retrieves scan attachments and binary JPEG payloads.
- `GetJobInfo` / `JobSummaryType`: Status polling structure containing state, reason, and completed page counts.
- `DestroyScanJob` / `DestroyScanJobResponse`: Standard job destruction command and success results.

### 5. Repository and Validation Layer (`ScannerProtocolRepository`, `ScannerProtocolValidator`)
Orchestrates protocol building, response parsing, and state validation:
- Strictly isolated from transport mechanisms, claims, endpoint operations, or workflows.
- Validates XML syntax, SOAP structures, WSCN data constraints, and DIME attachment headers (including JPEG byte SOI headers).

---

## Testing and Verification
The entire protocol suite can be tested without physical hardware using JVM tests:
- Tests verify error resilience on mismatched XML tags.
- Verifies exact serialization and parsing matching for all WSCN models.
- Verifies correct boundary flag operations and padding alignments on binary DIME packets.

Run tests:
```bash
gradle :app:testDebugUnitTest
```

## Related Documentation
- [USB Protocol](USB_PROTOCOL.md)
- [Architecture](ARCHITECTURE.md)

