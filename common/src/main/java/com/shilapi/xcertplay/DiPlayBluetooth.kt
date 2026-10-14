package com.shilapi.xcertplay

import android.bluetooth.BluetoothManager
import android.content.Context
import android.provider.Settings

/**
 * The head unit's own Bluetooth address, and which of its services said so.
 *
 * The source travels with the value because only one of the two answers is about this head unit's radio
 * at all, and downstream -- the AirPlay `bluetoothIDs`, the iAP2 Bluetooth transport's MAC -- the two
 * are the same shape. A report that carries the value alone cannot say which radio the accessory
 * declared itself to be.
 */
internal object DiPlayBluetooth {
    /** The two services that may know, in the order they are asked. */
    internal const val SOURCE_ADAPTER = "adapter"
    internal const val SOURCE_SETTINGS = "settings"

    /** An address, and which service answered with it. */
    internal data class LocalAddress(val value: String, val source: String)

    @android.annotation.SuppressLint("MissingPermission")
    fun localAddress(context: Context): LocalAddress? = firstUsableLocalAddress(
        listOf(
            SOURCE_ADAPTER to runCatching {
                (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter?.address
            }.getOrNull(),
            SOURCE_SETTINGS to runCatching {
                Settings.Secure.getString(context.contentResolver, "bluetooth_address")
            }.getOrNull(),
        ),
    )

    /** The first source that answers with something shaped like an address of this head unit's own. */
    internal fun firstUsableLocalAddress(sources: List<Pair<String, String?>>): LocalAddress? =
        sources.firstNotNullOfOrNull { (source, value) ->
            value?.takeIf(::isUsableLocalBluetoothAddress)?.let { LocalAddress(it, source) }
        }

    /**
     * Whether a value from the head unit's own services is the radio's address at all.
     *
     * Both rejected shapes are placeholders the platform hands out when it has nothing to say, and both
     * look like addresses to everything downstream.
     */
    internal fun isUsableLocalBluetoothAddress(value: String): Boolean =
        Regex("(?i)([0-9a-f]{2}:){5}[0-9a-f]{2}").matches(value) &&
            !value.startsWith("02:00:00:00:00:") &&
            value != "00:00:00:00:00:00"
}
