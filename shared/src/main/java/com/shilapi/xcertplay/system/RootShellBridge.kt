package com.shilapi.xcertplay.system

import android.os.IBinder
import android.os.Parcel
import com.shilapi.xcertplay.DiagLog
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.ReentrantLock

/**
 * Executes commands as uid 0 (root) through the head unit's `ExtraUtilsService`.
 *
 * The service is a vendor Binder exposed by `com.neusoft.alfus.os.IExtraUtilsService`.
 * The command line has to fit in 220 bytes, including the exit-code wrapper.
 * If unavailable (e.g. on generic phones, AVDs, or unit test runners), operations return false.
 * A call that is still inside the service when [ROOT_COMMAND_TIMEOUT_MILLIS] elapses returns false.
 * Concurrent commands queue safely behind a reentrant lock up to the timeout rather than being dropped.
 */
object RootShellBridge {
    private const val TAG = "RootShellBridge"
    private const val SERVICE_NAME = "ExtraUtilsService"
    private const val DESCRIPTOR = "com.neusoft.alfus.os.IExtraUtilsService"
    private const val TRANSACTION_GET_MESSAGE = 6
    private const val EVENT_SHELL = 8
    private const val ACTION_EXEC = 0
    private const val RC_MARKER = "__RC:"
    private const val MAX_COMMAND_BYTES = 220
    const val ROOT_COMMAND_TIMEOUT_MILLIS = 1_500L

    private val executionLock = ReentrantLock()
    private val inFlight = AtomicBoolean(false)
    private val cachedWifiApTransactionCode = AtomicReference<Int?>(null)
    @Volatile
    private var codeResolved = false

    fun isAvailable(): Boolean = findExtraUtilsService() != null

    internal fun wrap(command: String): String =
        "{ $command; } 2>&1; rc=\$?; echo $RC_MARKER\$rc"

    internal fun extractOutput(output: String): Pair<Int?, String> {
        val marker = output.lastIndexOf(RC_MARKER)
        if (marker < 0) return null to output.trim().take(200)
        val rc = output.substring(marker + RC_MARKER.length).trim().toIntOrNull()
        val body = output.substring(0, marker).trim()
        return rc to body.take(200)
    }

    internal fun classifyOutput(output: String): Boolean =
        extractOutput(output).first == 0

    /**
     * Resolves the Binder transaction code for [android.net.wifi.IWifiManager.setWifiApEnabled]
     * dynamically via reflection on [android.net.wifi.IWifiManager.Stub].
     * Returns null if not running in an Android framework environment with the stub class present.
     */
    fun getWifiApTransactionCode(): Int? {
        if (codeResolved) return cachedWifiApTransactionCode.get()
        val code = try {
            val stubClass = Class.forName("android.net.wifi.IWifiManager\$Stub")
            val field = stubClass.getDeclaredField("TRANSACTION_setWifiApEnabled")
            field.isAccessible = true
            field.getInt(null)
        } catch (_: Throwable) {
            null
        }
        cachedWifiApTransactionCode.set(code)
        codeResolved = true
        return code
    }

    /** For unit test injection of transaction code. */
    internal fun setWifiApTransactionCodeForTesting(code: Int?) {
        cachedWifiApTransactionCode.set(code)
        codeResolved = true
    }

    /**
     * Builds a lightweight `/system/bin/service call wifi <CODE>` command to switch the AP
     * directly at Binder level without starting an expensive Java runtime (`app_process`).
     *
     * In Android 5.1, `/system/bin/service call` automatically writes the service's interface token.
     * Arguments follow directly: i32 0 (null WifiConfiguration) i32 (1|0 enable).
     */
    fun hotspotServiceCallCommand(code: Int, enable: Boolean): String =
        "/system/bin/service call wifi $code i32 0 i32 ${if (enable) 1 else 0}"

    /**
     * Builds a standard AOSP `/system/bin/svc wifi enable|disable` command.
     */
    fun wifiServiceCommand(enable: Boolean): String =
        "/system/bin/svc wifi ${if (enable) "enable" else "disable"}"

    fun execute(command: String, timeoutMillis: Long = ROOT_COMMAND_TIMEOUT_MILLIS): Boolean {
        if (command.contains('\n') || command.contains('\r')) return false
        val wrapped = wrap(command)
        if (wrapped.toByteArray(Charsets.UTF_8).size > MAX_COMMAND_BYTES) {
            DiagLog.w(TAG, "Root command exceeds $MAX_COMMAND_BYTES bytes: $command")
            return false
        }
        val waitBudget = timeoutMillis.coerceAtLeast(100L)
        val acquired = try {
            executionLock.tryLock(waitBudget, TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            false
        }
        if (!acquired) {
            DiagLog.w(TAG, "Root command lock acquisition timeout for: $command")
            return false
        }
        return try {
            if (inFlight.get()) {
                DiagLog.w(TAG, "Previous root command still in flight in vendor service, dropping: $command")
                return false
            }
            inFlight.set(true)
            val done = AtomicReference<Boolean?>(null)
            val worker = Thread({
                try {
                    done.set(executeBlocking(command, wrapped))
                } finally {
                    inFlight.set(false)
                }
            }, "root-shell")
            worker.isDaemon = true
            worker.start()
            worker.join(timeoutMillis.coerceAtLeast(0L))
            done.get() ?: run {
                DiagLog.w(TAG, "Root command exceeded ${timeoutMillis}ms: $command")
                false
            }
        } finally {
            executionLock.unlock()
        }
    }

    private fun executeBlocking(originalCommand: String, wrapped: String): Boolean {
        val binder = findExtraUtilsService() ?: return false
        return synchronized("ExtraUtilsService_Global_Lock".intern()) {
            try {
                val output = transact(binder, wrapped)
                val (rc, body) = extractOutput(output)
                if (rc == 0) {
                    if (originalCommand.contains("service call")) {
                        DiagLog.i(TAG, "service call reply: $body")
                    }
                    true
                } else {
                    DiagLog.w(TAG, "Root command failed (rc=$rc, cmd='$originalCommand'): $body")
                    false
                }
            } catch (error: Throwable) {
                DiagLog.w(TAG, "Root transaction failed: ${error.message}")
                false
            }
        }
    }

    private fun findExtraUtilsService(): IBinder? = try {
        val serviceManager = Class.forName("android.os.ServiceManager")
        val getService = serviceManager.getMethod("getService", String::class.java)
        getService.invoke(null, SERVICE_NAME) as? IBinder
    } catch (_: Throwable) {
        null
    }

    private fun transact(binder: IBinder, command: String): String {
        val request = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            request.writeInterfaceToken(DESCRIPTOR)
            request.writeInt(EVENT_SHELL)
            request.writeInt(ACTION_EXEC)
            request.writeByteArray(command.toByteArray(Charsets.UTF_8))
            if (!binder.transact(TRANSACTION_GET_MESSAGE, request, reply, 0)) {
                ""
            } else {
                reply.readException()
                val bytes = reply.createByteArray() ?: ByteArray(0)
                String(bytes, Charsets.UTF_8)
            }
        } finally {
            reply.recycle()
            request.recycle()
        }
    }
}
