package com.shilapi.xcertplay.network

import android.annotation.SuppressLint
import android.os.Build
import android.system.Os
import java.io.FileDescriptor
import java.net.Socket

/** Detect a vanished peer, without treating a legitimately idle CarPlay screen as a failure. */
internal object TcpLiveness {
    // Linux UAPI <linux/tcp.h>. These are numbers rather than OsConstants fields for a reason: the SDK
    // omits three of them outright, and a field this release may not carry would fail as a missing
    // static at the moment the option is set — inside a session, where a missing option must not be an
    // error. The numbers are the same on every release; the fields are not.
    private const val IPPROTO_TCP = 6
    private const val TCP_KEEPIDLE = 4
    private const val TCP_KEEPINTVL = 5
    private const val TCP_KEEPCNT = 6
    private const val TCP_USER_TIMEOUT = 18

    private const val KEEPALIVE_IDLE_SECONDS = 10
    private const val KEEPALIVE_INTERVAL_SECONDS = 3
    private const val KEEPALIVE_PROBES = 3

    /** A write to a peer that stopped acknowledging is abandoned after this, rather than never. */
    private const val USER_TIMEOUT_MILLIS = 20_000

    /**
     * Makes a dead peer visible to this connection, instead of to the driver.
     *
     * The platform default is a keepalive idle time of two hours: a phone that left the network leaves
     * every socket ESTABLISHED for that long, nothing reads or writes an error, and the head unit keeps
     * the last frame on screen with the app's own page still hidden behind it. Every option below is
     * what turns that into a failure the session can act on.
     *
     * The numbers are the whole point and they are not negotiable with the platform, so a refusal is
     * reported rather than raised: this runs inside a session, and a connection without the tuning is
     * exactly what it was before this existed.
     */
    @SuppressLint("NewApi")
    fun configure(socket: Socket, diagnostic: (String) -> Unit) {
        try {
            socket.keepAlive = true
        } catch (failure: Throwable) {
            diagnostic("TCP peer health tuning unavailable: socket keepalive failed: ${failure.javaClass.simpleName}")
            return
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
            diagnostic("TCP peer health fine sockopt tuning requires API 21+")
            return
        }
        val descriptor = socketDescriptor(socket)
        if (descriptor == null) {
            diagnostic("TCP peer health tuning unavailable: no socket descriptor on this release")
            return
        }
        var keepaliveConfigured = false
        try {
            Os.setsockoptInt(descriptor, IPPROTO_TCP, TCP_KEEPIDLE, KEEPALIVE_IDLE_SECONDS)
            Os.setsockoptInt(descriptor, IPPROTO_TCP, TCP_KEEPINTVL, KEEPALIVE_INTERVAL_SECONDS)
            Os.setsockoptInt(descriptor, IPPROTO_TCP, TCP_KEEPCNT, KEEPALIVE_PROBES)
            keepaliveConfigured = true
        } catch (failure: Throwable) {
            diagnostic("TCP keepalive sockopt tuning unavailable: ${failure.javaClass.simpleName}")
        }
        var userTimeoutConfigured = false
        try {
            Os.setsockoptInt(descriptor, IPPROTO_TCP, TCP_USER_TIMEOUT, USER_TIMEOUT_MILLIS)
            userTimeoutConfigured = true
        } catch (failure: Throwable) {
            diagnostic("TCP user timeout sockopt unavailable: ${failure.javaClass.simpleName}")
        }
        if (keepaliveConfigured || userTimeoutConfigured) {
            diagnostic(
                "TCP peer health enabled idle=${KEEPALIVE_IDLE_SECONDS}s " +
                    "interval=${KEEPALIVE_INTERVAL_SECONDS}s count=$KEEPALIVE_PROBES " +
                    "deadline=${USER_TIMEOUT_MILLIS}ms; no video-idle timeout",
            )
        }
    }

    /**
     * The socket's own descriptor, borrowed rather than taken.
     *
     * `ParcelFileDescriptor.fromSocket` is what this would otherwise use, and it cannot be used here:
     * below API 29 it wraps the socket's own descriptor instead of a duplicate, so closing it — or
     * letting it be finalized — closes the connection it was read from. The hidden accessor hands the
     * same descriptor over and owns nothing. It is reachable while the hidden-API policy is not
     * enforced, which is every release this fork runs on; where it is not, the caller says so and the
     * session goes on without the tuning.
     */
    private fun socketDescriptor(socket: Socket): FileDescriptor? = runCatching {
        val accessor = Socket::class.java.getDeclaredMethod("getFileDescriptor$")
        if (!accessor.isAccessible) accessor.isAccessible = true
        accessor.invoke(socket) as? FileDescriptor
    }.getOrNull()
}
