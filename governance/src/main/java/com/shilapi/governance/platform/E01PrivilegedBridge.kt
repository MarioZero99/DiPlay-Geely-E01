package com.shilapi.governance.platform

import android.os.IBinder
import android.os.Parcel

/**
 * A privileged shell bridge communicating with the head unit's native `ExtraUtilsService`.
 *
 * The service is an OEM Binder service exposed by `com.neusoft.alfus.os.IExtraUtilsService`.
 * Its shell event runs the command with uid 0 and returns its stdout/stderr.
 *
 * The reply travels as one UTF-8 byte array within a 220-byte command budget.
 */
object E01PrivilegedBridge {
    const val SERVICE_NAME = "ExtraUtilsService"
    const val DESCRIPTOR = "com.neusoft.alfus.os.IExtraUtilsService"
    const val TRANSACTION_GET_MESSAGE = 6
    const val EVENT_SHELL = 8
    const val ACTION_EXEC = 0
    const val RC_MARKER = "__GEEKPLAY_ROOT_RC:"
    const val MAX_COMMAND_BYTES = 220

    sealed class Result {
        data class Success(val output: String) : Result()
        data object ServiceUnavailable : Result()
        data class TransactionFailed(val kind: String) : Result()
        data class CommandFailed(val exitCode: Int, val output: String) : Result()
        data class MalformedReply(val output: String) : Result()
    }

    /**
     * Wraps [command] so the shell reports its exit status even when the command failed.
     */
    internal fun wrap(command: String): String =
        if (command.trimEnd().endsWith('&')) {
            "$command rc=\$?; echo $RC_MARKER\$rc"
        } else {
            "{ $command; } 2>&1; rc=\$?; echo $RC_MARKER\$rc"
        }

    /**
     * Splits a service reply into its output and exit code.
     */
    internal fun classify(output: String): Result {
        val marker = output.lastIndexOf(RC_MARKER)
        if (marker < 0) return Result.MalformedReply(output.trim())
        val body = output.substring(0, marker).trim()
        val rc = output.substring(marker + RC_MARKER.length).trim().toIntOrNull()
            ?: return Result.MalformedReply(body)
        return if (rc == 0) Result.Success(body) else Result.CommandFailed(rc, body)
    }

    /**
     * Checks if ExtraUtilsService is available in the current environment.
     */
    fun isAvailable(): Boolean = findExtraUtilsService() != null

    /**
     * Executes a single-line command with uid 0.
     */
    fun execute(command: String, binder: IBinder? = findExtraUtilsService()): Result {
        require(!command.contains('\n') && !command.contains('\r') && !command.contains('\u0000')) {
            "Privileged command must be one line"
        }
        val wrapped = wrap(command)
        require(wrapped.toByteArray(Charsets.UTF_8).size <= MAX_COMMAND_BYTES) {
            "Privileged command exceeds $MAX_COMMAND_BYTES bytes"
        }
        if (binder == null) return Result.ServiceUnavailable
        val reply = try {
            transact(binder, wrapped)
        } catch (error: Throwable) {
            return Result.TransactionFailed(error.javaClass.simpleName)
        }
        return classify(reply)
    }

    internal fun findExtraUtilsService(): IBinder? = try {
        val serviceManager = Class.forName("android.os.ServiceManager")
        val getService = serviceManager.getMethod("getService", String::class.java)
        getService.invoke(null, SERVICE_NAME) as? IBinder
    } catch (_: Throwable) {
        null
    }

    private fun transact(binder: IBinder, command: String): String {
        val request = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            request.writeInterfaceToken(DESCRIPTOR)
            request.writeInt(EVENT_SHELL)
            request.writeInt(ACTION_EXEC)
            request.writeByteArray(command.toByteArray(Charsets.UTF_8))
            if (!binder.transact(TRANSACTION_GET_MESSAGE, request, reply, 0)) {
                throw IllegalStateException("Binder transaction rejected")
            }
            reply.readException()
            return String(reply.createByteArray() ?: ByteArray(0), Charsets.UTF_8)
        } finally {
            reply.recycle()
            request.recycle()
        }
    }
}
