# USB Protocol

This document outlines the reverse-engineered USB Host Architecture for the HP LaserJet 100 color MFP M175a.

## USB Device Information
- **Vendor ID:** `0x03F0`
- **Product ID:** `0x062A`
- **Device:** HP LaserJet 100 color MFP M175a
- **Specification:** USB 2.0

## USB Interfaces
The device exposes three distinct USB interfaces.

### Interface 0
- **Type:** Vendor Specific (Scanner)
- **Endpoints:**
  - EP3 Bulk OUT
  - EP3 Bulk IN
  - EP4 Interrupt IN

### Interface 1
- **Type:** USB Printer Class
- **Endpoints:**
  - EP1 Bulk OUT
  - EP1 Bulk IN

### Interface 2
- **Type:** Vendor Specific
- **Endpoints:**
  - EP9 Bulk OUT
  - EP9 Bulk IN
  - EP10 Interrupt IN

The purpose of Interface 2 has not yet been fully reverse-engineered. It is currently identified only as a Vendor-specific Interface. Future protocol analysis may determine its exact functionality.

## Related Documentation
- [Scanner Protocol](SCANNER_PROTOCOL.md)
- [Printer Protocol](PRINTER_PROTOCOL.md)
