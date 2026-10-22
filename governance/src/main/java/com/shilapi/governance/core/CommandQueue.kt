package com.shilapi.governance.core

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Ensures root governance commands are serialized and executed one by one,
 * preventing race conditions or concurrent Binder transactions.
 */
class CommandQueue {
    private val mutex = Mutex()

    suspend fun <T> execute(action: suspend () -> T): T {
        return mutex.withLock {
            action()
        }
    }
}
