# Printer Protocol

This document specifies the reverse-engineered printer communication protocol for the HP LaserJet 100 color MFP M175a.

## Protocol Stack
Printer communication uses the following specifications over Interface 1:
- USB Printer Class
- PJL (Printer Job Language)
- PCL XL (Printer Command Language XL)

## Reverse Engineered Print Flow
A successful print job follows this exact sequence:
1. PJL Job Start
2. PJL Metadata
3. `ENTER LANGUAGE=PCLXL`
4. PCL XL Binary Stream
5. PJL EOJ

## Exclusions
The printer operates exclusively via PJL and PCL XL. The following protocols are strictly unsupported and must not be included in the implementation:
- No ESC/POS
- No ZPL
- No thermal printer protocols
- No label printer protocols

## Related Documentation
- [USB Protocol](USB_PROTOCOL.md)
- [Architecture](ARCHITECTURE.md)
