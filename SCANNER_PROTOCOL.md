# Scanner Protocol

This document details the reverse-engineered scanner communication protocol for the HP LaserJet 100 color MFP M175a.

## Protocol Stack
Scanner communication utilizes the following stack over Interface 0:
- SOAP
- HTTP over USB Bulk
- WSD
- WSCN
- DIME
- JPEG transport

## Reverse Engineered Operations
The following operations must be implemented:
- `GetScannerElements`
- `CreateScanJob`
- `RetrieveImage`
- `GetJobInfo`
- `JobSummaryType`
- `DestroyScanJob`

## Image Transport
Image data is transported inside DIME multipart messages.

### Resolution Processing
Both 300 DPI and 600 DPI scanning utilize the exact same protocol. Only the resolution values change.

## Related Documentation
- [USB Protocol](USB_PROTOCOL.md)
- [Architecture](ARCHITECTURE.md)
