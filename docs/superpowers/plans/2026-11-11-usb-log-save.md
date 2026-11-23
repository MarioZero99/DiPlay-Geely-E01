# U 盘保存诊断日志 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 点保存之后，报告字节写进 FAT32 U 盘的 `/DiPlay/<文件名>`，并且能从这个文件系统里原样读回来。

**Architecture:** libaums 报分区为空时，先做一次 Bulk-Only 复位并清掉两个批量端点的停顿，再读 LUN 0 的第 0 扇区。`Fat32VolumeLayout.decide` 从主引导记录里取出 FAT32 分区的起始扇区。`Partition` 把这个偏移交给现有的 `RobustFat32FileSystemCreator`。`DiagnosticExportStore.writeReport` 在该文件系统里创建 `/DiPlay/` 并写入。单元测试用一张内存里的 FAT32 盘走同一条写入，再读回字节。

**Tech Stack:** Kotlin，libaums `0.10.0`，Android 5.1 / API 22，Robolectric，`:common:testDebugUnitTest`。

**Spec:** 本文「约定」一节。它取代 `docs/superpowers/plans/2026-11-11-usb-mbr-fat32-mount.md`。`docs/superpowers/plans/2026-11-05-usb-fat32-direct-save.md` 的列表和权限规则继续有效。

## Global Constraints

- 车机是 Android 5.1 / API 22。不要新增 USB 库，不要把 libaums 升版本。
- 写入目标是 FAT32。继续用 `RobustFat32FileSystemCreator`。不要加 exFAT 或 NTFS 实现，不要 `mount`，不要 `RootShellBridge`，不要解绑内核 `usb-storage`。
- 金士顿若是出厂 exFAT（分区类型 `0x07`），这条路径写不进去。日志写 `error=exfat-or-ntfs`。把盘格式化成 FAT32 后再保存。不要在代码里格式化用户的盘。
- 不要改 libaums 的 `setupDevice`。Get Max LUN 返回 `0xFF` 时它的 LUN 循环是空的；本计划在那之后自己读 LUN 0。
- `folders()` 不准调用 `openFileSystem`。
- 不准写 `/storage/usbotg`、`/mnt/media_rw/usbotg`、`sdcard1`、`sdcard2`。
- 一次保存只打开一次设备。写完调用 `UsbMassStorageManager.close()`。
- 提交时只 `git add` 本任务点名的路径。
- 块设备 `read(long, ByteBuffer)` / `write(long, ByteBuffer)` 的 long 是块号。第 0 扇区是 `read(0L, buffer)`。`Partition` 继承 `ByteBlockDevice`，调用方传入的是字节偏移，它会除以块大小再加上分区起始块号。
- 日志行：
  - `usb recover bulk-reset`
  - `usb sector0 sig=55aa parts=0c@2048,00@0,00@0,00@0 mount=2048`
  - `usb partition mounted lba=2048`
  - `usb save written=true bytes=1234 path=USB:/DiPlay/DiPlay-20261110-175101-524.txt`
- `sig` 是第 510、511 字节，四位小写十六进制。不是 `55aa` 时 `parts=none`。是 `55aa` 时 `parts=` 固定四项：两位小写类型、`@`、十进制起始扇区。
- `mount=` 与 `error=` 互斥。`usb open` 的 `error=` 只允许 `none`、`no filesystem`、`exfat-or-ntfs`，或 `异常类名: 消息`。

## 约定

2026-11-10 17:51，`0.5.0-geely`，金士顿 `0951:1666`，权限已有，`device.init()` 没抛错，`partitions=0`，超软盘兜底没有文件系统，`usb save written=false`。`/proc/mounts` 只有 tmpfs `/storage/usbotg`。

原因有两段，两段都要修，否则文件不会出现在盘上：

1. Get Max LUN 的返回值 `0xFF` 会让 libaums 一个 LUN 都不读。控制传输停顿之后，批量端点可能处于停顿，随后的 SCSI 初始化抛错，旧代码用 `getOrNull()` 吞掉。先发 Bulk-Only Mass Storage Reset（`0x21` / `0xFF` / 接口号），再对 IN、OUT 端点 `clearFeatureHalt`，然后初始化 LUN 0。
2. 普通 FAT32 的第 0 扇区是主引导记录，不是引导扇区。旧兜底固定用起始扇区 0，RobustFat32 对不上引导参数。按分区项里的起始扇区挂载。FAT 类型是 `0x01`、`0x04`、`0x06`、`0x0B`、`0x0C`、`0x0E`、`0x1B`、`0x1C`，起始扇区必须大于 0。四个类型都是 0 且引导参数像 FAT32 时，挂载扇区 0。只有 `0x07` 时 `error=exfat-or-ntfs`。`0x05`、`0x0F`、`0xEE` 不解析，结果是 `error=no filesystem`。

引导参数与 `RobustFat32FileSystemCreator` 相同：每扇区 512、1024、2048 或 4096 字节；每簇 1、2、4、8、16、32、64 或 128 扇区；保留扇区、FAT 份数、32 位总扇区、32 位每 FAT 扇区、根目录起始簇都不为 0。

写入成功的定义：`writeReport` 返回 true，并且 `UsbFileInputStream` 读回的字节与写入的字节相同。`saveToUsb` 必须调用这个函数，不能再写一套创建目录和写流的代码。

## 文件

- 创建 `common/src/main/java/com/shilapi/xcertplay/Fat32VolumeLayout.kt`
- 创建 `common/src/test/java/com/shilapi/xcertplay/Fat32VolumeLayoutTest.kt`
- 修改 `common/src/main/java/com/shilapi/xcertplay/UsbMassStorageManager.kt`
- 修改 `common/src/main/java/com/shilapi/xcertplay/DiagnosticExportStore.kt`
- 不改 `DiPlayActivity.kt`、`DiagnosticVolumeProbe.kt`

---

### Task 1: 从第 0 扇区得到 FAT32 起始扇区

**Files:**
- Create: `common/src/main/java/com/shilapi/xcertplay/Fat32VolumeLayout.kt`
- Test: `common/src/test/java/com/shilapi/xcertplay/Fat32VolumeLayoutTest.kt`

**Interfaces:**
- Consumes: 无
- Produces: `Fat32VolumeDecision(mountLba: Long?, partitionType: Int, sectorCount: Long, error: String?, logLine: String)` 和 `Fat32VolumeLayout.decide(sector: ByteArray)`。`mountLba != null` 时 `error == null`。

- [ ] **Step 1: 写会失败的测试**

`decide` 不调用 Android API，这个类先不要 Robolectric。

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
```

- [ ] **Step 2: 跑测试，确认失败**

Run: `./gradlew :common:testDebugUnitTest --tests com.shilapi.xcertplay.Fat32VolumeLayoutTest`

Expected: 编译失败，找不到 `Fat32VolumeLayout`。

- [ ] **Step 3: 实现 `decide`**

按「约定」的判定写 `Fat32VolumeLayout.kt`。签名不是 `55aa` 时不要读分区项。第一个类型属于 FAT 且起始扇区大于 0 的项胜出。没有这样的项但存在 `0x07` 时返回 `exfat-or-ntfs`。四个类型都是 0 且 `looksLikeFat32BootSector` 为真时 `mountLba = 0`。其余 `error=no filesystem`。日志行格式见 Global Constraints。小端 32 位用 `ByteBuffer.order(LITTLE_ENDIAN).getInt`。

- [ ] **Step 4: 跑测试，确认通过**

Run: `./gradlew :common:testDebugUnitTest --tests com.shilapi.xcertplay.Fat32VolumeLayoutTest`

Expected: 4 个测试通过。

- [ ] **Step 5: 提交**

```bash
git add common/src/main/java/com/shilapi/xcertplay/Fat32VolumeLayout.kt \
  common/src/test/java/com/shilapi/xcertplay/Fat32VolumeLayoutTest.kt
git commit -m "fix(export): choose the FAT32 partition start sector"
```

---

### Task 2: 把报告写入这张 FAT32 并读回来

**Files:**
- Modify: `common/src/main/java/com/shilapi/xcertplay/UsbMassStorageManager.kt`
- Modify: `common/src/main/java/com/shilapi/xcertplay/DiagnosticExportStore.kt`
- Test: `common/src/test/java/com/shilapi/xcertplay/Fat32VolumeLayoutTest.kt`

**Interfaces:**
- Consumes: `Fat32VolumeLayout.decide`、`RobustFat32FileSystemCreator`、libaums `Partition`、`UsbFileOutputStream`、`UsbFileInputStream`
- Produces:
  - `UsbMassStorageManager.mountAtDecision(device: BlockDeviceDriver, decision: Fat32VolumeDecision): FileSystem?`
  - `DiagnosticExportStore.writeReport(fs: FileSystem, fileName: String, bytes: ByteArray): Boolean`
  - `saveToUsb` 在 `openFileSystem` 返回文件系统之后只通过 `writeReport` 写盘

- [ ] **Step 1: 写会失败的测试**

在 `Fat32VolumeLayoutTest` 增加下面的测试，并给类加上 `@RunWith(RobolectricTestRunner::class)` 与 `@Config(sdk = [28])`。`fat32Reader` 保持 null，必须走真正的 RobustFat32。

```kotlin
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
    val file = folder!!.search("note.txt")
    val back = ByteArray(payload.size)
    me.jahnen.libaums.core.fs.UsbFileInputStream(file).use { it.read(back) }
    org.junit.Assert.assertArrayEquals(payload, back)
}
```

`MemoryBlockDevice` 用 `MutableMap<Long, ByteArray>` 保存块号到 512 字节。`read` 把该块写入 `dest`。`write` 从 `src.remaining()` 拷贝进该块。`getBlockSize()` 返回 512，`getBlocks()` 返回 256，`init()` 为空。

`TinyFat32.install` 造这张盘：

- 块 0 是主引导记录：签名 `55 AA`，第一个分区类型 `0x0C`，起始块 64，扇区数 128。
- 块 64 是 FAT32 引导扇区：每扇区 512，每簇 1，保留扇区 32，2 份 FAT，每份 1 扇区，总扇区 128，根目录簇 2，FSInfo 在卷内扇区 1，偏移 82 起是 `FAT32   `，签名 `55 AA`。
- 块 65 是 FSInfo：偏移 0 为 `52 52 61 41`，偏移 484 为 `72 72 41 61`，偏移 492 的小端整数是 3，偏移 508 起是 `00 00 55 AA`。
- 块 96 是第一份 FAT：项 0 为 `F8 FF FF 0F`，项 1 和项 2 为 `FF FF FF 0F`。块 97 是同样的第二份 FAT。
- 块 98（根目录簇）全 0。

卷内扇区 0 在磁盘块 64。保留扇区 32 之后，第一份 FAT 在块 96，数据区从块 98 开始，簇 2 就是块 98。

- [ ] **Step 2: 跑测试，确认失败**

Run: `./gradlew :common:testDebugUnitTest --tests com.shilapi.xcertplay.Fat32VolumeLayoutTest.reportBytesRoundTripThroughTheFat32Partition`

Expected: 编译失败，找不到 `mountAtDecision` 或 `writeReport`。

- [ ] **Step 3: 实现挂载和写入**

`mountAtDecision`：`mountLba == null` 时返回 null。否则构造 `PartitionTableEntry(partitionType, lba, sectorCount)`。`lba == 0` 时把原设备交给 `RobustFat32FileSystemCreator.read`，否则交给 `Partition(device, entry)`。

`writeReport`：

```kotlin
internal fun writeReport(fs: me.jahnen.libaums.core.fs.FileSystem, fileName: String, bytes: ByteArray): Boolean {
    val root = fs.rootDirectory
    val folder = root.search("DiPlay") ?: root.createDirectory("DiPlay")
    if (!folder.isDirectory) return false
    val existing = folder.search(fileName)
    existing?.delete()
    val file = folder.createFile(fileName)
    me.jahnen.libaums.core.fs.UsbFileOutputStream(file).use { out ->
        out.write(bytes)
        out.flush()
    }
    return file.length == bytes.size.toLong()
}
```

`saveToUsb` 里原来的 `root.search` / `createDirectory` / `createFile` / `UsbFileOutputStream` 整段换成 `written = writeReport(fs, fileName, bytes)`。成功和失败日志、`close()`、私有副本保持原样。

- [ ] **Step 4: 跑测试，确认通过**

Run: `./gradlew :common:testDebugUnitTest --tests com.shilapi.xcertplay.Fat32VolumeLayoutTest --tests com.shilapi.xcertplay.DiagnosticExportStoreTest`

Expected: 通过。`reportBytesRoundTripThroughTheFat32Partition` 读回的字节等于 `diplay-report`。

- [ ] **Step 5: 提交**

```bash
git add common/src/main/java/com/shilapi/xcertplay/UsbMassStorageManager.kt \
  common/src/main/java/com/shilapi/xcertplay/DiagnosticExportStore.kt \
  common/src/test/java/com/shilapi/xcertplay/Fat32VolumeLayoutTest.kt
git commit -m "fix(export): write the report into the FAT32 partition"
```

---

### Task 3: 空分区时复位端点再挂载

**Files:**
- Modify: `common/src/main/java/com/shilapi/xcertplay/UsbMassStorageManager.kt`

**Interfaces:**
- Consumes: `mountAtDecision`、`Fat32VolumeLayout.decide`、`UsbCommunication.clearFeatureHalt`、`UsbCommunication.controlTransfer`
- Produces: `openFileSystem` 在 `device.partitions` 为空时打出 `usb recover bulk-reset` 和 `decision.logLine`。挂上之后 `lastOpenFacts` 是 `partitionCount=1`、`fat32=true`、`error=null`。读盘抛错时 `error` 是 `异常类名: 消息`。

- [ ] **Step 1: 替换空分区分支**

删掉 `PartitionTableEntry(0, 0L, 0L)` 和包住它的 `getOrNull()`。换成：

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
            runCatching {
                comm.controlTransfer(0x21, 0xFF, 0, comm.usbInterface.id, ByteArray(0), 0)
            }
            runCatching { comm.clearFeatureHalt(comm.outEndpoint) }
            runCatching { comm.clearFeatureHalt(comm.inEndpoint) }
            DiagLog.i("UsbMassStorage", "usb recover bulk-reset")
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
                val entry = me.jahnen.libaums.core.partition.PartitionTableEntry(
                    decision.partitionType, lba, decision.sectorCount,
                )
                val partition = me.jahnen.libaums.core.partition.Partition(raw, entry)
                val fsField = me.jahnen.libaums.core.partition.Partition::class.java
                    .getDeclaredField("fileSystem")
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

`lastOpenFacts.error` 在没有文件系统时用 `openError ?: "no filesystem"`，不要把 `exfat-or-ntfs` 或异常文本覆盖掉。

- [ ] **Step 2: 跑已有测试**

Run: `./gradlew :common:testDebugUnitTest --tests com.shilapi.xcertplay.Fat32VolumeLayoutTest --tests com.shilapi.xcertplay.DiagnosticExportStoreTest --tests com.shilapi.xcertplay.DiagnosticExportUiTest`

Expected: 通过。列表测试的打开次数仍是 0。

- [ ] **Step 3: 提交**

```bash
git add common/src/main/java/com/shilapi/xcertplay/UsbMassStorageManager.kt
git commit -m "fix(export): reset the USB endpoints before reading the stick"
```

## 车上验收

装上这个包，插这支 FAT32 金士顿，点一次保存。会话日志里要有 `usb recover bulk-reset`、`usb partition mounted lba=`、`usb save written=true`。拔出 U 盘，`/DiPlay/` 下的文件大小等于日志里的 `bytes=`。

若日志是 `error=exfat-or-ntfs`，先把盘格式化成 FAT32，不要再改解析。若是 `异常类名:` 或 `sig=0000`，扇区没有读出来，停在这一步。

## Self-review

- Task 2 用真正的 RobustFat32 和 `UsbFileOutputStream` 写入，并用 `UsbFileInputStream` 读回。这是保存成功的定义。
- Task 3 补上昨天日志里被吞掉的那一步：复位端点后再读第 0 扇区，并按分区起始扇区挂载。
- `saveToUsb` 不再保留第二套写文件代码。
- exFAT、GPT、root 挂载不在本计划里。FAT32 盘走 Task 2 已验证的写入。
