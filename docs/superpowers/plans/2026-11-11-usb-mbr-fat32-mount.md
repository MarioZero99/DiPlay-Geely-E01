# U 盘按主引导记录挂载 FAT32 Implementation Plan

> 已被 `2026-11-11-usb-log-save.md` 取代。不要按这份执行。这份只决定挂载扇区，没有把报告写进文件系统，也没有在读盘前清掉端点停顿。

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 系统没有挂载分区时，诊断报告仍通过 libaums 写到 FAT32 U 盘的 `/DiPlay/`。金士顿这种「第 0 扇区是主引导记录、FAT32 在后面分区里」的盘要能挂上。

**Architecture:** `device.partitions` 为空时，仍打开 LUN 0，读第 0 扇区。`Fat32VolumeLayout.decide` 只根据这 512 字节决定挂载扇区，或给出 `no filesystem` / `exfat-or-ntfs`。挂载扇区大于 0 时用 libaums 的 `Partition` 做偏移，再交给现有的 `RobustFat32FileSystemCreator`。不改列表、权限和写入路径。

**Tech Stack:** Kotlin，libaums `0.10.0`，Android 5.1 / API 22，Robolectric，`:common:testDebugUnitTest`。

**Spec:** 本文「约定」一节。它只补 `docs/superpowers/plans/2026-11-05-usb-fat32-direct-save.md` 里「分区表为空就按超软盘读第 0 扇区」这一条。那份计划的列表和写入规则继续有效。

## Global Constraints

- 车机是 Android 5.1 / API 22。不要新增 USB 库，不要把 libaums 升版本。
- 只认 FAT32。继续用 `RobustFat32FileSystemCreator`。不要加 exFAT、NTFS、GPT 解析，不要 `mount`，不要 `RootShellBridge`，不要解绑 `usb-storage`。
- 不要改 libaums 的 `setupDevice`。Get Max LUN 返回 `0xFF` 时它的 LUN 循环是空的；本计划用已经存在的 LUN 0 兜底覆盖这种情况。
- `DiagnosticExportStore.folders()` 和保存列表的绘制都不准调用 `UsbMassStorageManager.openFileSystem`。
- 不准把文件写到 `/storage/usbotg`、`/mnt/media_rw/usbotg`、`sdcard1`、`sdcard2`。
- 一次保存只打开一次设备。写入结束（成功或失败）后仍调用 `UsbMassStorageManager.close()`。
- 工作区里还有未提交的其他改动。提交时只 `git add` 本任务点名的路径，不要 `git add -A`。
- 扩展分区类型 `0x05`、`0x0F` 不跟随。保护性 GPT 类型 `0xEE` 不解析。它们和别的非 FAT 类型一样记进 `parts=`，结果是 `error=no filesystem`。只有类型 `0x07` 且没有 FAT 分区时，`error=exfat-or-ntfs`。
- 日志行格式固定：
  - `usb sector0 sig=55aa parts=0c@2048,00@0,00@0,00@0 mount=2048`
  - `usb sector0 sig=55aa parts=07@2048,00@0,00@0,00@0 error=exfat-or-ntfs`
  - `usb sector0 sig=0000 parts=none error=no filesystem`
  - `usb partition lba=2048 fat32=false`
  - `usb open vid=0951 pid=1666 perm=true partitions=1 fat32=true error=none`
- `sig` 是第 510、511 字节的四位小写十六进制。签名不是 `55aa` 时 `parts=none`，不要把引导代码当成分区项。签名是 `55aa` 时 `parts=` 固定四个项，类型两位小写十六进制，`@` 后面是十进制起始扇区，逗号分隔。
- `mount=` 和 `error=` 互斥。能挂载时不写 `error=`。不能挂载时不写 `mount=`。
- `usb open` 的 `error=` 只有这几类：`none`、`no filesystem`、`exfat-or-ntfs`，或 `异常类名: 消息`。LUN 0 初始化或读第 0 扇区抛错时用最后一类，不要再写成 `no filesystem`。

## 约定

2026-11-10 17:51，包 `0.5.0-geely`，报告 `dist/supabase-logs/DiPlay-20261110-175256-186.txt`。金士顿 DataTraveler `0951:1666` 在 `/dev/bus/usb/001/005`，权限已有。`device.init()` 没有抛错，`partitions=0`。兜底把第 0 扇区当成超软盘上的 FAT32，没有打出 RobustFat32 成功或异常，三次都是 `fat32=false error=no filesystem`，`usb save written=false`。`/proc/mounts` 只有 tmpfs `/storage/usbotg`。

libaums 0.10 找到分区表之后，文件系统不是它认定的严格 FAT32，就会把该分区丢掉，`partitions` 仍是空列表，且不向会话日志抛异常。现在的兜底固定构造 `PartitionTableEntry(0, 0L, 0L)`。普通 FAT32 U 盘的第 0 扇区是主引导记录，末尾也是 `55 AA`，但不是引导扇区，RobustFat32 对不上引导参数就返回空。

判定只看第 0 扇区：

1. 末尾不是 `55 AA`：`error=no filesystem`。
2. 四个分区项里，按槽位顺序，第一个类型属于 FAT 且起始扇区大于 0 的项：挂载这个起始扇区。FAT 类型是 `0x01`、`0x04`、`0x06`、`0x0B`、`0x0C`、`0x0E`、`0x1B`、`0x1C`。起始扇区为 0 的项不能用，那会再读到主引导记录。
3. 没有可用 FAT 项，但有类型 `0x07`：`error=exfat-or-ntfs`。不要尝试挂载。
4. 四个类型都是 0，并且引导参数看起来像 FAT32：这是超软盘，挂载扇区 0。引导参数与 `RobustFat32FileSystemCreator` 里失败返回前的判断相同：每扇区字节数是 512、1024、2048 或 4096；每簇扇区数是 1、2、4、8、16、32、64 或 128；保留扇区、FAT 份数、32 位总扇区、32 位每 FAT 扇区、根目录起始簇都不为 0。
5. 其余情况：`error=no filesystem`。

挂载扇区大于 0 时，把 libaums `Partition` 交给 `RobustFat32FileSystemCreator`。`Partition` 继承 `ByteBlockDevice`，读偏移 `0` 会加上分区的逻辑块地址。块设备的 `read(long, ByteBuffer)` 第一个参数是块号，不是字节偏移。第 0 扇区就是 `read(0L, buffer)`。

`openFileSystemProvider` 继续短路真实打开。已有的列表测试和界面测试不得因此去读 USB。

## 文件

- 创建 `common/src/main/java/com/shilapi/xcertplay/Fat32VolumeLayout.kt`
- 创建 `common/src/test/java/com/shilapi/xcertplay/Fat32VolumeLayoutTest.kt`
- 修改 `common/src/main/java/com/shilapi/xcertplay/UsbMassStorageManager.kt`
- 不改 `DiagnosticExportStore.kt`、`DiPlayActivity.kt`、`DiagnosticVolumeProbe.kt`

---

### Task 1: 第 0 扇区决定挂载位置

**Files:**
- Create: `common/src/main/java/com/shilapi/xcertplay/Fat32VolumeLayout.kt`
- Test: `common/src/test/java/com/shilapi/xcertplay/Fat32VolumeLayoutTest.kt`

**Interfaces:**
- Consumes: 无
- Produces:
  - `internal data class Fat32VolumeDecision(val mountLba: Long?, val partitionType: Int, val sectorCount: Long, val error: String?, val logLine: String)`
  - `internal object Fat32VolumeLayout { fun decide(sector: ByteArray): Fat32VolumeDecision }`
  - `mountLba != null` 时 `error == null`，`logLine` 含 `mount=`。否则 `mountLba == null`，`error` 是 `no filesystem` 或 `exfat-or-ntfs`，`logLine` 含 `error=`。

- [ ] **Step 1: 写会失败的测试**

`Fat32VolumeLayoutTest` 不需要 Robolectric，`decide` 不调用 `android.util.Log`。

```kotlin
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

    @Test fun firstFatSlotWinsWhenALaterSlotAlsoExists() {
        val sector = signatureSector()
        putPartition(sector, 0, type = 0x0E, lba = 63, count = 10)
        putPartition(sector, 1, type = 0x0C, lba = 2048, count = 20)

        val decision = Fat32VolumeLayout.decide(sector)

        assertEquals(63L, decision.mountLba)
        assertEquals("usb sector0 sig=55aa parts=0e@63,0c@2048,00@0,00@0 mount=63", decision.logLine)
    }

    @Test fun zeroStartSectorIsNotAFatTarget() {
        val sector = signatureSector()
        putPartition(sector, 0, type = 0x0C, lba = 0, count = 100)
        putPartition(sector, 1, type = 0x0B, lba = 128, count = 50)

        val decision = Fat32VolumeLayout.decide(sector)

        assertEquals(128L, decision.mountLba)
        assertEquals(0x0B, decision.partitionType)
    }

    @Test fun type07WithoutFatIsExfatOrNtfs() {
        val sector = signatureSector()
        putPartition(sector, 0, type = 0x07, lba = 2048, count = 100)

        val decision = Fat32VolumeLayout.decide(sector)

        assertNull(decision.mountLba)
        assertEquals("exfat-or-ntfs", decision.error)
        assertEquals("usb sector0 sig=55aa parts=07@2048,00@0,00@0,00@0 error=exfat-or-ntfs", decision.logLine)
    }

    @Test fun fatSlotBeatsType07() {
        val sector = signatureSector()
        putPartition(sector, 0, type = 0x07, lba = 2048, count = 100)
        putPartition(sector, 1, type = 0x0C, lba = 4096, count = 100)

        val decision = Fat32VolumeLayout.decide(sector)

        assertEquals(4096L, decision.mountLba)
        assertEquals(0x0C, decision.partitionType)
    }

    @Test fun protectiveGptIsNotFat32() {
        val sector = signatureSector()
        putPartition(sector, 0, type = 0xEE, lba = 1, count = 100)

        val decision = Fat32VolumeLayout.decide(sector)

        assertNull(decision.mountLba)
        assertEquals("no filesystem", decision.error)
        assertEquals("usb sector0 sig=55aa parts=ee@1,00@0,00@0,00@0 error=no filesystem", decision.logLine)
    }

    @Test fun missingSignatureDoesNotParsePartitions() {
        val sector = ByteArray(512)
        sector[446 + 4] = 0x0C
        val decision = Fat32VolumeLayout.decide(sector)

        assertNull(decision.mountLba)
        assertEquals("no filesystem", decision.error)
        assertEquals("usb sector0 sig=0000 parts=none error=no filesystem", decision.logLine)
    }

    @Test fun superfloppyWithFat32BiosParameterBlockMountsSectorZero() {
        val sector = signatureSector()
        writeFat32BiosParameterBlock(sector)

        val decision = Fat32VolumeLayout.decide(sector)

        assertEquals(0L, decision.mountLba)
        assertEquals(0, decision.partitionType)
        assertNull(decision.error)
        assertEquals("usb sector0 sig=55aa parts=00@0,00@0,00@0,00@0 mount=0", decision.logLine)
    }

    @Test fun signatureWithoutPartitionOrBiosParameterBlockIsNoFilesystem() {
        val decision = Fat32VolumeLayout.decide(signatureSector())

        assertNull(decision.mountLba)
        assertEquals("no filesystem", decision.error)
        assertEquals("usb sector0 sig=55aa parts=00@0,00@0,00@0,00@0 error=no filesystem", decision.logLine)
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
        sector[13] = 8
        sector[14] = 32
        sector[15] = 0
        sector[16] = 2
        writeLe32(sector, 32, 2048)
        writeLe32(sector, 36, 16)
        writeLe32(sector, 44, 2)
    }
}
```

- [ ] **Step 2: 跑测试，确认失败**

在 `DiPlay-Legacy-Android-Geely` 目录：

Run: `./gradlew :common:testDebugUnitTest --tests com.shilapi.xcertplay.Fat32VolumeLayoutTest`

Expected: 编译失败，找不到 `Fat32VolumeLayout`。

- [ ] **Step 3: 写最小实现**

`Fat32VolumeLayout.kt`：

```kotlin
package com.shilapi.xcertplay

import java.nio.ByteBuffer
import java.nio.ByteOrder

internal data class Fat32VolumeDecision(
    val mountLba: Long?,
    val partitionType: Int,
    val sectorCount: Long,
    val error: String?,
    val logLine: String,
)

internal object Fat32VolumeLayout {
    private val fatTypes = setOf(0x01, 0x04, 0x06, 0x0B, 0x0C, 0x0E, 0x1B, 0x1C)

    fun decide(sector: ByteArray): Fat32VolumeDecision {
        val sig = if (sector.size >= 512) {
            "%02x%02x".format(sector[510].toInt() and 0xFF, sector[511].toInt() and 0xFF)
        } else {
            "0000"
        }
        if (sig != "55aa") {
            return rejected(sig, "none", "no filesystem")
        }
        val slots = (0 until 4).map { index ->
            val at = 446 + index * 16
            Slot(
                type = sector[at + 4].toInt() and 0xFF,
                lba = le32(sector, at + 8),
                count = le32(sector, at + 12),
            )
        }
        val parts = slots.joinToString(",") { "%02x@%d".format(it.type, it.lba) }
        val fat = slots.firstOrNull { it.type in fatTypes && it.lba > 0L }
        if (fat != null) {
            return Fat32VolumeDecision(
                mountLba = fat.lba,
                partitionType = fat.type,
                sectorCount = fat.count,
                error = null,
                logLine = "usb sector0 sig=55aa parts=$parts mount=${fat.lba}",
            )
        }
        if (slots.any { it.type == 0x07 }) {
            return rejected("55aa", parts, "exfat-or-ntfs")
        }
        if (slots.all { it.type == 0 } && looksLikeFat32BootSector(sector)) {
            return Fat32VolumeDecision(
                mountLba = 0L,
                partitionType = 0,
                sectorCount = 0L,
                error = null,
                logLine = "usb sector0 sig=55aa parts=$parts mount=0",
            )
        }
        return rejected("55aa", parts, "no filesystem")
    }

    private fun rejected(sig: String, parts: String, error: String) = Fat32VolumeDecision(
        mountLba = null,
        partitionType = 0,
        sectorCount = 0L,
        error = error,
        logLine = "usb sector0 sig=$sig parts=$parts error=$error",
    )

    private fun le32(sector: ByteArray, at: Int): Long {
        val buffer = ByteBuffer.wrap(sector, at, 4).order(ByteOrder.LITTLE_ENDIAN)
        return buffer.int.toLong() and 0xFFFFFFFFL
    }

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
            reservedSectors != 0 && fatCount != 0 &&
            totalSectors32 != 0L && sectorsPerFat32 != 0L && rootDirStartCluster != 0L
    }

    private data class Slot(val type: Int, val lba: Long, val count: Long)
}
```

- [ ] **Step 4: 跑测试，确认通过**

Run: `./gradlew :common:testDebugUnitTest --tests com.shilapi.xcertplay.Fat32VolumeLayoutTest`

Expected: 9 个测试通过。

- [ ] **Step 5: 提交**

```bash
git add common/src/main/java/com/shilapi/xcertplay/Fat32VolumeLayout.kt \
  common/src/test/java/com/shilapi/xcertplay/Fat32VolumeLayoutTest.kt
git commit -m "fix(export): choose FAT32 mount sector from the MBR"
```

---

### Task 2: 空分区表改挂主引导记录里的 FAT32

**Files:**
- Modify: `common/src/main/java/com/shilapi/xcertplay/UsbMassStorageManager.kt`
- Test: `common/src/test/java/com/shilapi/xcertplay/Fat32VolumeLayoutTest.kt`

**Interfaces:**
- Consumes: `Fat32VolumeDecision`、`Fat32VolumeLayout.decide`、`RobustFat32FileSystemCreator.read`、libaums `Partition`、`PartitionTableEntry`、`BlockDeviceDriver`
- Produces:
  - `internal var fat32Reader: (PartitionTableEntry, BlockDeviceDriver) -> FileSystem?`
  - `internal fun mountAtDecision(device: BlockDeviceDriver, decision: Fat32VolumeDecision): FileSystem?`
  - `openFileSystem` 在 `device.partitions` 为空时读 LUN 0 的第 0 扇区，打 `decision.logLine`，再用 `mountAtDecision`。成功时 `lastOpenFacts` 为 `partitions=1`、`fat32=true`、`error=null`。失败时 `error` 来自判定或异常，不再固定写成 `no filesystem`。

- [ ] **Step 1: 写会失败的测试**

在 `Fat32VolumeLayoutTest` 末尾增加。这个测试要打到 `DiagLog`，所以类上加上 `@RunWith(RobolectricTestRunner::class)` 和 `@Config(sdk = [28])`。`decide` 的测试保持不动。

```kotlin
@Test fun mountAtDecisionReadsThePartitionBootSector() {
    val disk = MemoryBlockDevice()
    val sector = ByteArray(512).also {
        it[510] = 0x55
        it[511] = 0xAA.toByte()
        it[446 + 4] = 0x0C
        it[446 + 8] = 64
    }
    disk.sectors[0L] = sector
    UsbMassStorageManager.fat32Reader = { _, view ->
        val buffer = java.nio.ByteBuffer.allocate(512)
        view.read(0L, buffer)
        stubFileSystem()
    }

    val fs = UsbMassStorageManager.mountAtDecision(disk, Fat32VolumeLayout.decide(sector))

    org.junit.Assert.assertNotNull(fs)
    assertEquals(64L, disk.lastRead)
}

private class MemoryBlockDevice : me.jahnen.libaums.core.driver.BlockDeviceDriver {
    val sectors = mutableMapOf<Long, ByteArray>()
    var lastRead: Long = -1
    override fun getBlockSize() = 512
    override fun getBlocks() = 1024L
    override fun init() {}
    override fun read(offset: Long, dest: java.nio.ByteBuffer) {
        lastRead = offset
        dest.put(sectors[offset] ?: ByteArray(dest.remaining()))
    }
    override fun write(offset: Long, src: java.nio.ByteBuffer) = error("read only")
}

private fun stubFileSystem() = object : me.jahnen.libaums.core.fs.FileSystem {
    override fun getRootDirectory(): me.jahnen.libaums.core.fs.UsbFile = error("unused")
    override fun getVolumeLabel() = ""
    override fun getCapacity() = 0L
    override fun getOccupiedSpace() = 0L
    override fun getFreeSpace() = 0L
    override fun getChunkSize() = 512
    override fun getType() = 2
}
```

`@After fun tearDown()` 里把 `UsbMassStorageManager.fat32Reader` 设回 `null`。生产代码把 `null` 看成调用 `RobustFat32FileSystemCreator.read`。

- [ ] **Step 2: 跑测试，确认失败**

Run: `./gradlew :common:testDebugUnitTest --tests com.shilapi.xcertplay.Fat32VolumeLayoutTest.mountAtDecisionReadsThePartitionBootSector`

Expected: 编译失败，找不到 `mountAtDecision`。

- [ ] **Step 3: 接上打开路径**

在 `UsbMassStorageManager` 里增加：

```kotlin
internal var fat32Reader: ((PartitionTableEntry, BlockDeviceDriver) -> FileSystem?)? = null

internal fun mountAtDecision(device: BlockDeviceDriver, decision: Fat32VolumeDecision): FileSystem? {
    val lba = decision.mountLba ?: return null
    val entry = PartitionTableEntry(decision.partitionType, lba, decision.sectorCount)
    val view: BlockDeviceDriver = if (lba == 0L) device else Partition(device, entry)
    val reader = fat32Reader ?: { tableEntry, blockDevice ->
        RobustFat32FileSystemCreator.read(tableEntry, blockDevice)
    }
    return reader(entry, view)
}
```

把 `openFileSystem` 里 `partitions.isEmpty()` 的整段换成：

```kotlin
var openError: String? = null
if (partitions.isEmpty()) {
    DiagLog.w("UsbMassStorage", "device.partitions is empty, reading LUN 0 sector 0")
    try {
        val commField = UsbMassStorageDevice::class.java.getDeclaredField("usbCommunication")
        commField.isAccessible = true
        val comm = commField.get(device) as? me.jahnen.libaums.core.usb.UsbCommunication
        if (comm == null) {
            openError = "no filesystem"
        } else {
            val raw = me.jahnen.libaums.core.driver.BlockDeviceDriverFactory
                .createBlockDevice(comm, 0.toByte())
            raw.init()
            val sector = ByteArray(512)
            raw.read(0L, java.nio.ByteBuffer.wrap(sector))
            val decision = Fat32VolumeLayout.decide(sector)
            DiagLog.i("UsbMassStorage", decision.logLine)
            val fs = mountAtDecision(raw, decision)
            if (fs != null) {
                val lba = decision.mountLba ?: 0L
                val entry = PartitionTableEntry(decision.partitionType, lba, decision.sectorCount)
                val partition = Partition(raw, entry)
                val fsField = Partition::class.java.getDeclaredField("fileSystem")
                fsField.isAccessible = true
                fsField.set(partition, fs)
                partitions = listOf(partition)
                device.partitions = partitions
                DiagLog.i("UsbMassStorage", "usb partition mounted lba=$lba")
            } else if (decision.mountLba != null) {
                DiagLog.w("UsbMassStorage", "usb partition lba=${decision.mountLba} fat32=false")
                openError = "no filesystem"
            } else {
                openError = decision.error
            }
        }
    } catch (e: Exception) {
        DiagLog.w("UsbMassStorage", "LUN 0 read failed", e)
        openError = "${e.javaClass.simpleName}: ${e.message}"
    }
}
```

后面的 `lastOpenFacts` 用这个错误，不要在判定已经给出 `exfat-or-ntfs` 或读盘抛错之后再覆盖成 `no filesystem`：

```kotlin
lastOpenFacts = UsbOpenFacts(
    partitionCount = partitions.size,
    fat32 = primaryFs != null,
    error = if (primaryFs != null) null else openError ?: "no filesystem",
)
```

删掉原来的 `runCatching { ... }.getOrNull()` 和 `PartitionTableEntry(0, 0L, 0L)` 超软盘专用分支。超软盘由 `decide` 返回 `mountLba = 0`。

- [ ] **Step 4: 跑测试，确认通过**

Run: `./gradlew :common:testDebugUnitTest --tests com.shilapi.xcertplay.Fat32VolumeLayoutTest --tests com.shilapi.xcertplay.DiagnosticExportStoreTest --tests com.shilapi.xcertplay.DiagnosticExportUiTest`

Expected: 这三个类通过。`foldersListsLibaumsDeviceWithoutOpeningWhenNothingIsMounted` 仍然是 0 次打开。

- [ ] **Step 5: 提交**

```bash
git add common/src/main/java/com/shilapi/xcertplay/UsbMassStorageManager.kt \
  common/src/test/java/com/shilapi/xcertplay/Fat32VolumeLayoutTest.kt
git commit -m "fix(export): mount the FAT32 partition when libaums returns none"
```

## 车上验收

单元测试覆盖不了 SCSI。装上这个包之后插同一支金士顿，点一次 U 盘保存。会话日志里要同时有：

- `usb sector0 sig=55aa parts=` 里出现一个 FAT 类型和大于 0 的起始扇区
- `usb partition mounted lba=`
- `usb open vid=0951 pid=1666 perm=true partitions=1 fat32=true error=none`
- `usb save written=true`

若是 `error=exfat-or-ntfs`，这支盘不是 FAT32，格式化成 FAT32 再保存。若是 `异常类名:` 或 `sig=0000`，扇区没有读出来，不要再改分区解析。

## Self-review

- 约定里的五条判定都有 Task 1 的测试：FAT 起始扇区、槽位顺序、起始扇区 0、类型 `0x07`、FAT 优先于 `0x07`、`0xEE`、无签名、超软盘、只有签名。
- Task 2 把判定接到 LUN 0，并检查偏移后的 `read(0)` 落在分区起始块。
- 没有新增库，没有 exFAT 实现，没有 GPT 解析，没有 root。
- `error=exfat-or-ntfs` 和读盘异常不会被后面的 `no filesystem` 盖掉。
