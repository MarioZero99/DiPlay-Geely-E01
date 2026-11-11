package com.shilapi.xcertplay

import me.jahnen.libaums.core.driver.BlockDeviceDriver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.nio.ByteBuffer

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
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

    @Test fun reportBytesRoundTripThroughTheFat32Partition() {
        val disk = MemoryBlockDevice()
        TinyFat32.install(disk, partitionLba = 64)
        val boot = ByteArray(512)
        disk.read(0L, java.nio.ByteBuffer.wrap(boot))
        val fs = UsbMassStorageManager.mountAtDecision(disk, Fat32VolumeLayout.decide(boot))
        org.junit.Assert.assertNotNull(fs)
        val payload = "diplay-report".toByteArray()
        org.junit.Assert.assertTrue(DiagnosticExportStore.writeReport(fs!!, "note.txt", payload))
        val folder = fs.rootDirectory.search("DiPlay")
        val file = folder!!.search("note.txt")!!
        val back = ByteArray(payload.size)
        me.jahnen.libaums.core.fs.UsbFileInputStream(file).use { it.read(back) }
        org.junit.Assert.assertArrayEquals(payload, back)
    }

    private fun signatureSector(): ByteArray = ByteArray(512).also {
        it[510] = 0x55
        it[511] = 0xAA.toByte()
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

/**
 * A 256-block disk held in memory. Each block is 512 bytes, which is also what the FAT32 image below
 * assumes. Reads and writes cover as many blocks as the caller's buffer holds, the same contract
 * `ScsiBlockDevice` implements.
 */
private class MemoryBlockDevice : BlockDeviceDriver {
    private val data = mutableMapOf<Long, ByteArray>()

    override val blockSize: Int = 512
    override val blocks: Long = 256L
    override fun init() {}

    override fun read(offset: Long, dest: ByteBuffer) {
        var block = offset
        while (dest.remaining() >= 512) {
            dest.put(data[block] ?: ByteArray(512))
            block++
        }
    }

    override fun write(offset: Long, src: ByteBuffer) {
        var block = offset
        while (src.remaining() >= 512) {
            val bytes = ByteArray(512)
            src.get(bytes)
            data[block] = bytes
            block++
        }
    }
}

/**
 * One FAT32 partition starting at [partitionLba], small enough to read in a test.
 * Volume sector 0 is the boot sector; reserved sectors 1 and 2 hold FSInfo and nothing;
 * the FATs at sectors 32 and 33 are one sector each; cluster 2 is the root directory.
 */
private object TinyFat32 {
    fun install(disk: MemoryBlockDevice, partitionLba: Long) {
        val mbr = ByteArray(512)
        putPartition(mbr, 0, type = 0x0C, lba = partitionLba, count = 128)
        mbr[510] = 0x55
        mbr[511] = 0xAA.toByte()
        disk.write(0L, ByteBuffer.wrap(mbr))

        val boot = ByteArray(512)
        boot[11] = 0x00
        boot[12] = 0x02
        boot[13] = 1
        boot[14] = 32
        boot[16] = 2
        writeLe32(boot, 32, 128)
        writeLe32(boot, 36, 1)
        writeLe32(boot, 44, 2)
        boot[48] = 1
        "FAT32   ".forEachIndexed { i, c -> boot[82 + i] = c.code.toByte() }
        boot[510] = 0x55
        boot[511] = 0xAA.toByte()
        disk.write(partitionLba, ByteBuffer.wrap(boot))

        val fsInfo = ByteArray(512)
        writeLe32(fsInfo, 0, 0x41615252L)
        writeLe32(fsInfo, 484, 0x61417272L)
        writeLe32(fsInfo, 492, 3L)
        fsInfo[508] = 0x00
        fsInfo[509] = 0x00
        fsInfo[510] = 0x55
        fsInfo[511] = 0xAA.toByte()
        disk.write(partitionLba + 1, ByteBuffer.wrap(fsInfo))

        val fat = ByteArray(512)
        writeLe32(fat, 0, 0x0FFFFFF8L)
        writeLe32(fat, 4, 0x0FFFFFFFL)
        writeLe32(fat, 8, 0x0FFFFFFFL)
        disk.write(partitionLba + 32, ByteBuffer.wrap(fat))
        disk.write(partitionLba + 33, ByteBuffer.wrap(fat.copyOf()))

        disk.write(partitionLba + 34, ByteBuffer.wrap(ByteArray(512)))
    }
}
