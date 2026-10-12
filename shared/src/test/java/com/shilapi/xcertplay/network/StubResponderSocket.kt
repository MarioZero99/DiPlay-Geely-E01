package com.shilapi.xcertplay.network

import javax.jmdns.ServiceInfo
import javax.jmdns.ServiceListener

/**
 * Stands in for the process's mDNS socket so a test can see who borrowed it and what they did with
 * it, without binding 5353 or needing a real jmDNS instance.
 *
 * Deliberately dumb: it records publishes and retracts in the order they arrive. Which of them the
 * responder should have allowed is [MdnsResponder]'s rule, and a test that reproduced the rule here
 * would only be checking itself.
 */
internal class StubResponderSocket : MdnsResponderSocket {
    var closed = false
        private set
    val published = mutableListOf<ServiceInfo>()
    val retracted = mutableListOf<ServiceInfo>()
    var listeners = 0
        private set

    override fun addServiceListener(type: String, listener: ServiceListener) {
        listeners += 1
    }

    override fun removeServiceListener(type: String, listener: ServiceListener) {
        listeners -= 1
    }

    override fun publish(info: ServiceInfo) {
        published.add(info)
    }

    override fun retract(info: ServiceInfo) {
        retracted.add(info)
    }

    override fun close() {
        closed = true
    }
}
