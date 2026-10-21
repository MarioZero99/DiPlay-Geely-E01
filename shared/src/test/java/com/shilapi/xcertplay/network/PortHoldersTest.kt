package com.shilapi.xcertplay.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The rows below are the kernel's own format, so the columns are the ones that will really be read.
 * A port match that read the wrong column would report "the port is free" on the car — the one answer
 * that sends the next run after the wrong cause.
 */
class PortHoldersTest {
    private val header =
        "  sl  local_address rem_address   st tx_queue rx_queue tr tm->when retrnsmt   uid  timeout inode ref pointer drops"

    @Test fun aSocketOnThePortIsNamed() {
        val row = "   0: 00000000:14E9 00000000:0000 07 00000000:00000000 00:00000000 00000000  1000        0 12345 2 0000000000000000 0"

        assertEquals("uid=1000 st=07 inode=12345", PortHolders.holderOf(row, 5353))
    }

    @Test fun aSocketOnAnotherPortIsNotThePort() {
        val row = "   3: 00000000:1B58 00000000:0000 0A 00000000:00000000 00:00000000 00000000     0        0 99999 2 0000000000000000 0"

        assertNull(PortHolders.holderOf(row, 5353))
    }

    @Test fun theHeaderAndTruncatedRowsAreIgnored() {
        assertNull(PortHolders.holderOf(header, 5353))
        assertNull(PortHolders.holderOf("   0: 00000000:14E9", 5353))
    }

    @Test fun anIPv6RowCarriesItsUidToo() {
        val row = "  12: 00000000000000000000000000000000:14E9 00000000000000000000000000000000:0000" +
            " 07 00000000:00000000 00:00000000 00000000  10123        0 54321 2 0000000000000000 0"

        assertEquals("uid=10123 st=07 inode=54321", PortHolders.holderOf(row, 5353))
    }

    /** A uid the platform can name is the whole point: it turns a held port into a package. */
    @Test fun aResolvableUidIsNamedInTheRow() {
        val row = "   0: 00000000:14E9 00000000:0000 07 00000000:00000000 00:00000000 00000000  10123        0 54321 2 0000000000000000 0"

        assertEquals(
            "uid=10123 pkg=com.example.receiver st=07 inode=54321",
            PortHolders.holderOf(row, 5353) { uid -> if (uid == 10123) "com.example.receiver" else null },
        )
    }

    /** A system uid has no package; the row must still be reported, without an empty name. */
    @Test fun ASystemUidKeepsItsRowWithoutAName() {
        val row = "   0: 00000000:14E9 00000000:0000 07 00000000:00000000 00:00000000 00000000      0        0 54321 2 0000000000000000 0"

        assertEquals("uid=0 st=07 inode=54321", PortHolders.holderOf(row, 5353) { null })
    }

    @Test fun detectsPortHoldingFromTableContent() {
        val row = "   0: 00000000:14E9 00000000:0000 07 00000000:00000000 00:00000000 00000000  1020        0 108314 2 0000000000000000 0"
        org.junit.Assert.assertTrue(PortHolders.isRowHoldingPort(row, 5353))
        org.junit.Assert.assertFalse(PortHolders.isRowHoldingPort(row, 7000))
    }
}
