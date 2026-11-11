package com.shilapi.xcertplay

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale

/**
 * Where the FAT32 volume starts, read from the disk's first 512-byte sector.
 *
 * A stick with a partition table puts a master boot record at sector 0 and the FAT32 boot sector at
 * the partition's start sector. A superfloppy puts the boot sector itself at sector 0. libaums reads
 * sector 0 as a boot sector, so it finds neither: this decides which sector to hand it instead.
 *
 * [mountLba] is the sector the FAT32 volume starts at; null means there is no FAT32 volume to mount,
 * and [error] says why. [logLine] is the one line the save path writes for this decision.
 */
internal data class Fat32VolumeDecision(
    val mountLba: Long?,
    val partitionType: Int,
    val sectorCount: Long,
    val error: String?,
    val logLine: String,
)

internal object Fat32VolumeLayout {
    /** Partition types that carry a FAT filesystem. 0x0B/0x0C are FAT32; the rest are FAT12/16. */
    private val fatTypes = setOf(0x01, 0x04, 0x06, 0x0B, 0x0C, 0x0E, 0x1B, 0x1C)
    private const val exFatOrNtfsType = 0x07
    private const val partitionTableOffset = 446
    private const val partitionEntrySize = 16
    private const val partitionEntryCount = 4

    fun decide(sector: ByteArray): Fat32VolumeDecision {
        val signature = signature(sector)
        if (signature != "55aa") {
            return Fat32VolumeDecision(
                mountLba = null,
                partitionType = 0,
                sectorCount = 0L,
                error = "no filesystem",
                logLine = "usb sector0 sig=$signature parts=none error=no filesystem",
            )
        }
        val entries = (0 until partitionEntryCount).map { readEntry(sector, it) }
        val parts = entries.joinToString(",") { entry ->
            String.format(Locale.US, "%02x@%d", entry.type, entry.lba)
        }
        val prefix = "usb sector0 sig=$signature parts=$parts"

        entries.firstOrNull { it.type in fatTypes && it.lba > 0 }?.let { fat ->
            return Fat32VolumeDecision(
                mountLba = fat.lba,
                partitionType = fat.type,
                sectorCount = fat.count,
                error = null,
                logLine = "$prefix mount=${fat.lba}",
            )
        }
        if (entries.any { it.type == exFatOrNtfsType }) {
            return Fat32VolumeDecision(null, 0, 0L, "exfat-or-ntfs", "$prefix error=exfat-or-ntfs")
        }
        if (entries.all { it.type == 0 } && looksLikeFat32BootSector(sector)) {
            return Fat32VolumeDecision(0L, 0, 0L, null, "$prefix mount=0")
        }
        return Fat32VolumeDecision(null, 0, 0L, "no filesystem", "$prefix error=no filesystem")
    }

    private data class PartitionEntry(val type: Int, val lba: Long, val count: Long)

    private fun readEntry(sector: ByteArray, index: Int): PartitionEntry {
        val at = partitionTableOffset + index * partitionEntrySize
        return PartitionEntry(
            type = sector[at + 4].toInt() and 0xFF,
            lba = le32(sector, at + 8),
            count = le32(sector, at + 12),
        )
    }

    private fun signature(sector: ByteArray): String = String.format(
        Locale.US,
        "%02x%02x",
        sector[510].toInt() and 0xFF,
        sector[511].toInt() and 0xFF,
    )

    /** Same parameters [RobustFat32FileSystemCreator] accepts, read from a boot sector at offset 0. */
    private fun looksLikeFat32BootSector(sector: ByteArray): Boolean {
        val buffer = ByteBuffer.wrap(sector).order(ByteOrder.LITTLE_ENDIAN)
        val bytesPerSector = buffer.getShort(11).toInt() and 0xFFFF
        val sectorsPerCluster = buffer.get(13).toInt() and 0xFF
        val reservedSectors = buffer.getShort(14).toInt() and 0xFFFF
        val fatCount = buffer.get(16).toInt() and 0xFF
        val totalSectors32 = buffer.getInt(32).toLong() and 0xFFFFFFFFL
        val sectorsPerFat32 = buffer.getInt(36).toLong() and 0xFFFFFFFFL
        val rootDirStartCluster = buffer.getInt(44).toLong() and 0xFFFFFFFFL
        return bytesPerSector in listOf(512, 1024, 2048, 4096) &&
            sectorsPerCluster in listOf(1, 2, 4, 8, 16, 32, 64, 128) &&
            reservedSectors != 0 &&
            fatCount != 0 &&
            totalSectors32 != 0L &&
            sectorsPerFat32 != 0L &&
            rootDirStartCluster != 0L
    }

    private fun le32(sector: ByteArray, at: Int): Long =
        ByteBuffer.wrap(sector).order(ByteOrder.LITTLE_ENDIAN).getInt(at).toLong() and 0xFFFFFFFFL
}
