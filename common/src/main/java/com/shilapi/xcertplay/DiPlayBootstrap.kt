package com.shilapi.xcertplay

import android.content.Context
import android.os.Build
import com.shilapi.xcertplay.airplay.AirPlayIdentity
import com.shilapi.xcertplay.mfi.LocalMfiAuthenticationClient
import com.shilapi.xcertplay.orchestration.MfiTarget
import com.shilapi.xcertplay.transport.WirelessBluetoothHop
import com.shilapi.xcertplay.transport.adapter.AdapterRoutes
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit

/** Installs the private beta's experimental identity. It has no remote fallback. */
object DiPlayBootstrap {
    @Volatile private var ready = false
    @Volatile private var prewarmFuture: Future<*>? = null
    private val prewarmExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "diplay-prewarm").apply { isDaemon = true }
    }

    val isReady: Boolean get() = ready

    fun prewarm(context: Context) {
        if (ready || prewarmFuture != null) return
        val appContext = context.applicationContext
        synchronized(this) {
            if (ready || prewarmFuture != null) return
            try {
                prewarmFuture = prewarmExecutor.submit {
                    runCatching {
                        AirPlayPersistence.loadIdentity(appContext)
                        ensure(appContext)
                    }
                }
            } catch (_: Exception) {}
        }
    }

    @Synchronized fun ensure(context: Context) {
        if (ready) return
        prewarmFuture?.let { future ->
            if (!future.isDone) {
                runCatching { future.get(3, TimeUnit.SECONDS) }
            }
        }
        if (ready) return
        val privateRoot = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) context.noBackupFilesDir else context.filesDir
        val target = File(privateRoot, LocalMfiAuthenticationClient.DIRECTORY)
        if (!target.exists()) {
            val staging = File(privateRoot, "offline-mfi-staging")
            staging.deleteRecursively()
            check(staging.mkdirs()) { "Could not prepare local authentication" }
            staging.setReadable(false, false); staging.setReadable(true, true)
            staging.setExecutable(false, false); staging.setExecutable(true, true)
            try {
                for (name in listOf("identity.pk8", "certificate.p7b")) {
                    val file = File(staging, name)
                    context.assets.open("offline-mfi/$name").use { input ->
                        file.outputStream().use { output -> input.copyTo(output) }
                    }
                    file.setReadable(false, false); file.setReadable(true, true)
                    file.setWritable(false, false); file.setWritable(true, true)
                }
                LocalMfiAuthenticationClient.load(staging)
                check(staging.renameTo(target)) { "Could not install local authentication" }
            } finally {
                staging.deleteRecursively()
            }
        }
        LocalMfiAuthenticationClient.load(target)
        AirPlayPersistence.saveMfiTarget(context, MfiTarget.LOCAL)
        AirPlayPersistence.saveDebugLogsEnabled(context, false)
        ready = true
    }

    /**
     * The app's entry point for the accessory's device id. The derivation itself lives on
     * [com.shilapi.xcertplay.airplay.AirPlayIdentity.deviceId], because the desk bench needs the same
     * value and cannot see this module -- and two copies of "who the accessory is" is how a declaration
     * starts disagreeing with itself.
     */
    fun deviceId(identity: AirPlayIdentity): String = identity.deviceId
}

internal object DiPlayPreferences {
    private fun prefs(context: Context) = context.getSharedPreferences("diplay", Context.MODE_PRIVATE)
    fun phoneAddress(context: Context): String? = prefs(context).getString("phone_address", null)
    fun phoneName(context: Context): String = prefs(context).getString("phone_name", null) ?: "Your iPhone"
    fun savePhone(context: Context, address: String, name: String) {
        prefs(context).edit().putString("phone_address", address).putString("phone_name", name).apply()
    }
    /**
     * The radio a wireless run uses. With no adapter stack in this build there is one radio left, so it is
     * the answer whether or not the driver ever picked it — the page no longer shows a choice to make.
     */
    fun bluetoothHop(context: Context): WirelessBluetoothHop? {
        if (!AdapterRoutes.available) return WirelessBluetoothHop.CAR
        return WirelessBluetoothHop.fromKey(prefs(context).getString("bluetooth_hop", null))
    }
    fun saveBluetoothHop(context: Context, hop: WirelessBluetoothHop) {
        prefs(context).edit().putString("bluetooth_hop", hop.key).apply()
    }
}
