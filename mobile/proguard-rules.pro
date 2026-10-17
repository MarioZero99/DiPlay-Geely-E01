# Rules the release package needs because of how this app reaches a class, not because R8 guessed wrong.
#
# Everything else is left to R8: the app calls its own code directly, and every class named in the
# manifest or in a layout is kept by the rules the Android plugin generates from those files.

# --- What a field report has to stay readable for ------------------------------------------------
#
# The package carries "Save diagnostic report" and an upload path, and what comes back is read by a
# person: a stack trace full of `a.b.c` names cannot be matched to this source. So this app's own
# classes keep their names, and line numbers are kept with them. It costs about 30 KB and buys back the
# only thing that makes a report from a car actionable. Third-party names are still rewritten, which is
# where the size is.
-keepnames class com.shilapi.xcertplay.**
-keepattributes SourceFile,LineNumberTable

# --- The car hop ---------------------------------------------------------------------------------
#
# CarBluetoothHops loads the hop by this exact string (CarBluetoothHops.kt, VENDOR_HOP_CLASS) and
# instantiates it through its no-argument constructor. R8 cannot see a class name in a string, so
# without this it would rename the hop and the lookup would throw ClassNotFoundException — caught,
# turned into null, and the package would run the AOSP stack. That failure is silent at build time and
# looks like "no GOC on this car" at run time, which is why the rule is spelled out rather than left to
# the default constructor-keeping rules.
#
# Nothing else about the hop is kept: its methods and fields are renamed with the rest, since every one
# of them is reached through the CarBluetoothHop interface.
-keep class com.shilapi.xcertplay.carhop.VendorCarHop { <init>(); }

# The adapter route is loaded the same way (AdapterRoutes.kt, ADAPTER_ROUTE_CLASS). No package this
# project hands out carries an adapter, so the class is normally absent and the lookup throws
# ClassNotFoundException, which is the expected answer. The rule is here so that a hand-run build with
# -PadapterDir behaves like the hop rather than silently falling back to the AOSP path.
-keep class com.shilapi.xcertplay.adapter.UsbAdapterRoute { <init>(); }

# --- jmDNS ---------------------------------------------------------------------------------------
#
# jmDNS looks classes up by name inside its own implementation (JmmDNS.Factory does
# Class.forName("javax.jmdns.impl.JmmDNSImpl")). Names are kept, the code itself is still shrunk and
# optimised — this is the narrow form for a library that reflects on itself.
-keepnames class javax.jmdns.**

# --- Libraries compiled for a JDK, on a device ---------------------------------------------------
#
# bouncycastle is the full JDK build: it references javax.naming, java.awt and the JCE provider
# machinery this app does not use. Those references are unreachable from any code path here, and R8
# reports them as missing classes rather than treating them as the optionals they are. Dontwarn says
# "known absent, do not stop the build"; it does not keep anything.
-dontwarn org.bouncycastle.**
-dontwarn javax.naming.**
-dontwarn java.awt.**
-dontwarn org.conscrypt.**
