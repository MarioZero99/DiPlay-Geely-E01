package com.shilapi.xcertplay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class Fat32VolumeLayoutTest {
    @Test fun mbrFat32PartitionMountsAtItsStartSector() {
        val sector = signatureSector()
        putPartition(sector, 0, type = 0x0C, lba = 2048, count = 1000)
        val decision = Fat32VolumeLayout.decide(sector)
        assertEquals(2048L, decision.mountLba)
        assertEquals(0x0C, decision.partitionType)
        assertEquals(1000L, decision.sectorCount)
        assertNull(decision.error)
        assertEquals("usb sector0 sig=55aa parts=0c@2048,00@0,00@0,00@0 mount=2048", decision.logLine)
    }

    @Test fun type07WithoutFatIsExfatOrNtfs() {
        val sector = signatureSector()
        putPartition(sector, 0, type = 0x07, lba = 2048, count = 100)
        val decision = Fat32VolumeLayout.decide(sector)
        assertNull(decision.mountLba)
        assertEquals("exfat-or-ntfs", decision.error)
        assertEquals("usb sector0 sig=55aa parts=07@2048,00@0,00@0,00@0 error=exfat-or-ntfs", decision.logLine)
    }

    @Test fun superfloppyMountsSectorZero() {
        val sector = signatureSector()
        writeFat32BiosParameterBlock(sector)
        val decision = Fat32VolumeLayout.decide(sector)
        assertEquals(0L, decision.mountLba)
        assertEquals("usb sector0 sig=55aa parts=00@0,00@0,00@0,00@0 mount=0", decision.logLine)
    }

    @Test fun missingSignatureDoesNotParsePartitions() {
        val decision = Fat32VolumeLayout.decide(ByteArray(512))
        assertEquals("usb sector0 sig=0000 parts=none error=no filesystem", decision.logLine)
    }

    private fun signatureSector(): ByteArray = ByteArray(512).also {
        it[510] = 0x55
        it[511] = 0xAA.toByte()
    }

    private fun putPartition(sector: ByteArray, index: Int, type: Int, lba: Long, count: Long) {
        val at = 446 + index * 16
        sector[at + 4] = type.toByte()
        writeLe32(sector, at + 8, lba)
        writeLe32(sector, at + 12, count)
    }

    private fun writeLe32(sector: ByteArray, at: Int, value: Long) {
        var rest = value
        for (i in 0..3) {
            sector[at + i] = (rest and 0xFF).toByte()
            rest = rest shr 8
        }
    }

    private fun writeFat32BiosParameterBlock(sector: ByteArray) {
        sector[11] = 0x00
        sector[12] = 0x02
        sector[13] = 1
        sector[14] = 32
        sector[16] = 2
        writeLe32(sector, 32, 128)
        writeLe32(sector, 36, 1)
        writeLe32(sector, 44, 2)
    }
}
