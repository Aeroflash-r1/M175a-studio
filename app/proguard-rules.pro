# M175a Print — R8 keep rules.
# Manifest components are kept automatically; these are explicit backstops so
# the system PrintService binding and the bridge foreground service can never
# be renamed/stripped, and USB permission broadcast actions keep working.

-keep public class com.m175astudio.MainActivity
-keep public class com.m175astudio.print.M175PrintService
-keep public class com.m175astudio.net.PhoneBridgeService

# UsbPrinterConnection is reached via the bridge + service through shared
# references only — keep its public surface (open/close/bulk helpers).
-keep public class com.m175astudio.usb.UsbPrinterConnection {
    public *;
}

# kotlinx-coroutines-android ships its own consumer rules; nothing extra needed.
