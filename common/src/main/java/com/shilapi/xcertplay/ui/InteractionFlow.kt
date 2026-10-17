package com.shilapi.xcertplay.ui

class InteractionFlow(private val isSessionIdle: () -> Boolean) {
    /** 唯一真相源：完全对齐 WirelessPageState.session == SessionPhase.Idle */
    fun canTriggerConnect(): Boolean = isSessionIdle()
}
