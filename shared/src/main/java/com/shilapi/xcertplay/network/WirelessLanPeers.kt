package com.shilapi.xcertplay.network

/**
 * What the kernel's neighbour table says about the other hosts on the network the car joined.
 *
 * `tcpAccepted` in the startup summary answers one question — whether anything reached this
 * receiver's listening port — and a zero there cannot be read any further than that. The iPhone may
 * never have associated with the network at all, or it may have associated and been unable to reach
 * the address this receiver announced; from the socket those two look identical, and they are fixed
 * in different places. The one fact that tells them apart is whether any device other than the router
 * ever appeared on the network, and the only thing on the head unit that knows is the kernel's own
 * neighbour table: `/proc/net/arp` lists the hosts this interface has resolved an address for, and a
 * host that never sent a packet never got an entry.
 *
 * IPv4 only, and that is a limit to read the field with rather than an implementation detail. The
 * kernel keeps its IPv6 neighbours in netlink and never in `/proc`, so a phone that only ever speaks
 * IPv6 to this receiver leaves no trace here at all. `lanPeers=0` therefore says "nothing was seen on
 * IPv4", which is not proof that nothing came, and must not be reported as one.
 *
 * The baseline is pinned rather than assumed, because the table is not this session's to own. It
 * belongs to the interface and outlives a session: entries survive a Wi-Fi reconnect, and the
 * router's own entry is there from the first moment the car sends anything off the network. [pin] is
 * called once, as the session attaches, and every later count is made against that set — which is what
 * makes `lanPeersNew` mean somebody arrived rather than somebody was already there.
 *
 * The count itself decides nothing: it goes into an exported report and nothing else waits on it.
 * What the same table does decide, through [arpPeersOn], is which of two different faults a session
 * that never got a TCP connection is actually in — see the one caller, which asks once, at the moment
 * the question already has to be answered, and only for an IPv4 session.
 */
internal class WirelessLanPeers(
    private val interfaceName: String,
    private val gateway: String?,
    private val read: (String, Int) -> String = ::readBoundedDiagnosticFile,
) {
    /**
     * What the table held when this session attached, or null when no baseline could be taken.
     *
     * Written once by [pin] on the thread that starts the session, read by the observer's own thread
     * on every sample, hence volatile. Null covers both "not pinned yet" and "pinned, and the table
     * could not be read then": neither leaves anything to compare against, and which of the two it
     * was is not something a reader of the exported field can act on.
     */
    @Volatile private var baseline: Set<String>? = null

    /** Pins what was already on the network, so only a later arrival can be called one. */
    fun pin() {
        baseline = try { current() } catch (_: Exception) { null }
    }

    /** One short exported field, never a line of its own. */
    fun snapshot(): String {
        val peers = try { current() } catch (error: Exception) {
            // The path is fixed and the addresses are the network's rather than ours, so the class
            // name is the whole of what may travel out — the same rule the receive counters keep.
            return "lanPeers=unavailable failureClass=${error.javaClass.simpleName}"
        }
        val pinned = baseline ?: return "lanPeers=${peers.size} lanPeersNew=unknown"
        return "lanPeers=${peers.size} lanPeersNew=${peers.count { it !in pinned }}"
    }

    private fun current(): Set<String> =
        parseArpPeers(read(NEIGHBOURS_PATH, MAX_PROC_BYTES), interfaceName, gateway)
}

// File-scoped rather than companion-scoped, because [arpPeersOn] reads the same table for the one
// caller that asks at failure time, and a second copy of the path is a second place to get it wrong.
private const val NEIGHBOURS_PATH = "/proc/net/arp"
private const val MAX_PROC_BYTES = 16 * 1024

/**
 * The hosts [interfaceName] has resolved an address for right now, or null when that cannot be known.
 *
 * Null is the answer in three different situations and they are deliberately one answer: no interface
 * name to ask about, a table that cannot be read on this firmware, and a table that could not be
 * parsed. Every caller has to have a behaviour for "the evidence is missing" anyway — the only thing
 * that would be gained by separating them is a caller that forgot one of the three.
 *
 * [gateway] is the router's address on a network somebody else owns, which is dropped for the reason
 * [parseArpPeers] gives. An access point this receiver made has no gateway at all, so every entry is
 * a device that came to it — which is exactly the question asked of it here.
 */
internal fun arpPeersOn(interfaceName: String?, gateway: String? = null): Set<String>? {
    val device = interfaceName?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    return try {
        parseArpPeers(readBoundedDiagnosticFile(NEIGHBOURS_PATH, MAX_PROC_BYTES), device, gateway)
    } catch (_: Exception) {
        null
    }
}

/**
 * The addresses in one `/proc/net/arp`, kept to the rows that describe a real host on our interface.
 *
 * The kernel's columns are, in order: address, hardware type, flags, hardware address, mask, device.
 * A row is kept only when the kernel says the entry is complete, it belongs to the interface this
 * session is on, and its hardware address is a real one — a row with no hardware address is a
 * resolution still in progress, and the table holds rows for other interfaces on a head unit with
 * more than one. The router's own address is dropped by the caller's [gateway] because its entry is
 * certain to be there as soon as anything leaves the network, so counting it would answer nothing.
 *
 * A malformed line is skipped, never thrown on: this reads a file the kernel writes, and a report
 * that fails to be produced because one row looked odd would be worse than the row being missed.
 */
internal fun parseArpPeers(text: String, device: String, gateway: String?): Set<String> =
    text.lineSequence().mapNotNull { row ->
        val fields = row.trim().split(FIELD_SEPARATOR)
        if (fields.size < MINIMUM_FIELDS) return@mapNotNull null
        if (fields[DEVICE_COLUMN] != device) return@mapNotNull null
        val flags = hexField(fields[FLAGS_COLUMN]) ?: return@mapNotNull null
        if (flags and COMPLETE_FLAG == 0) return@mapNotNull null
        val hardware = fields[HARDWARE_COLUMN]
        if (!HARDWARE_ADDRESS.matches(hardware)) return@mapNotNull null
        if (hardware == NO_HARDWARE_ADDRESS) return@mapNotNull null
        val address = fields[ADDRESS_COLUMN]
        if (address == gateway) return@mapNotNull null
        address
    }.toSet()

// The kernel prints its numbers with the `0x` the C format asked for; the digits are the part meant.
private fun hexField(value: String): Int? =
    (if (value.startsWith("0x", ignoreCase = true)) value.substring(2) else value).toIntOrNull(16)

private val FIELD_SEPARATOR = Regex("\\s+")
private val HARDWARE_ADDRESS = Regex("(?i)[0-9a-f]{2}(:[0-9a-f]{2}){5}")

private const val MINIMUM_FIELDS = 6
private const val ADDRESS_COLUMN = 0
private const val FLAGS_COLUMN = 2
private const val HARDWARE_COLUMN = 3
private const val DEVICE_COLUMN = 5

/** What the kernel's "complete" flag says: the hardware address in this row was answered for. */
private const val COMPLETE_FLAG = 0x2

/** What an unresolved neighbour looks like: a hardware address that is not one. */
private const val NO_HARDWARE_ADDRESS = "00:00:00:00:00:00"
