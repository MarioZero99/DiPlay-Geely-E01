package com.shilapi.xcertplay

import android.content.Context
import java.io.File

/**
 * 诊断日志导出的挂载卷探测。
 *
 * 第一屏严格只来自 StorageManager.getVolumeList()，筛选已挂载、可移动、非主存储的系统卷。
 * 标题使用系统 getDescription(context)（系统默认「USB 存储」），路径使用 getPath()。
 * 仅当路径为 /mnt/media_rw/<name> 且同名 /storage/<name> 的 listFiles() 至少返回一项时才做平级替换。
 * 空目录不替换。另外按名字查找 usbotg-sda1、usbotg-sda2、udisk1、udisk2。
 * 找不到能 list() 的分区时，不把 sdcard1、sdcard2 和 usbotg 父目录当成盘。
 */
internal object DiagnosticVolumeProbe {

    internal var volumeListProvider: ((Context) -> Array<*>?)? = null
    internal var volumePathsProvider: ((Context) -> Array<String>?)? = null
    internal var volumeStateProvider: ((String) -> String?)? = null
    internal var procMountsProvider: (() -> String?)? = null

    internal fun unescapeMountPoint(raw: String): String {
        if (!raw.contains('\\')) return raw
        val regex = Regex("\\\\([0-7]{3})")
        return regex.replace(raw) { match ->
            val octal = match.groupValues[1].toInt(8)
            octal.toChar().toString()
        }
    }

    internal fun parseMountPoints(mountsContent: String): List<File> {
        val candidates = mutableListOf<File>()
        val systemPrefixes = listOf(
            "/system", "/data", "/dev", "/proc", "/sys", "/mnt/secure", "/mnt/asec", "/mnt/obb",
            "/mnt/shell", "/mnt/runtime", "/cache", "/config",
        )
        val targetFs = setOf("vfat", "exfat", "texfat", "ntfs", "fuse", "fuseblk", "ext4", "ext3", "ext2", "f2fs")
        for (line in mountsContent.lineSequence()) {
            val parts = line.trim().split(Regex("\\s+"))
            if (parts.size < 3) continue
            val dev = parts[0]
            val mountPoint = unescapeMountPoint(parts[1])
            val fsType = parts[2].lowercase()
            if (fsType !in targetFs) continue
            if (mountPoint == "/" || mountPoint == "/mnt" || mountPoint == "/mnt/media_rw" || mountPoint.startsWith("/storage/emulated") || systemPrefixes.any { mountPoint.startsWith(it) }) continue
            val isLikelyExternal = dev.startsWith("/dev/block/vold/") ||
                dev.startsWith("/dev/block/sd") ||
                mountPoint.startsWith("/storage/") ||
                mountPoint.startsWith("/mnt/media_rw/")
            if (!isLikelyExternal) continue
            val file = File(mountPoint)
            if (!isPrimaryStorage(file)) {
                candidates.add(file)
            }
        }
        return candidates
    }

    /**
     * 只在同名 /storage 目录能列出至少一项时才换过去。
     * 空目录留在原路径。MTK 的 sdcard1、sdcard2、usbotg 空壳只是目录存在，里面没有盘上的文件。
     */
    internal fun resolveVolumePath(
        dir: File,
        storageRoot: File = File("/storage"),
        mountedPaths: Set<String> = emptySet(),
    ): File {
        val path = dir.absolutePath
        val prefix = "/mnt/media_rw/"
        if (!path.startsWith(prefix)) return dir
        val leaf = path.removePrefix(prefix).trim('/')
        if (leaf.isEmpty() || leaf.contains('/')) return dir
        val twin = File(storageRoot, leaf)
        val listed = runCatching { twin.listFiles() }.getOrNull()
        val twinMounted = mountedPaths.isEmpty() || mountedPaths.contains(twin.absolutePath)
        if (listed != null && listed.isNotEmpty() && twinMounted) {
            DiagLog.i("DiagnosticExport", "usb $path -> ${twin.absolutePath}")
            return twin
        }
        DiagLog.i("DiagnosticExport", "usb $path kept (twin ${twin.absolutePath} not a mounted listing)")
        return dir
    }

    /**
     * 第一屏抓取系统已挂载的可移动卷：
     * 1. 优先调用 StorageManager.getVolumeList() 反射解析 StorageVolume。
     * 2. 若 getVolumeList 不存在或返回空，回退到 MTK Android 5.1 标准的 StorageManager.getVolumePaths()。
     */
    fun candidates(
        context: Context,
        storageRoot: File = File("/storage"),
        mediaRw: File = File("/mnt/media_rw"),
    ): List<File> {
        val found = linkedMapOf<String, File>()
        val mountsContent = mountText()
        logMountEvidence(mountsContent)

        // 1. 尝试标准 AOSP StorageManager.getVolumeList()
        val volumes = volumeListProvider?.invoke(context) ?: runCatching {
            val sm = context.getSystemService(Context.STORAGE_SERVICE) ?: return@runCatching null
            sm.javaClass.getMethod("getVolumeList").invoke(sm) as? Array<*>
        }.getOrNull()

        if (volumes != null && volumes.isNotEmpty()) {
            for (vol in volumes) {
                if (vol == null) continue
                runCatching {
                    val isRemovable = runCatching {
                        vol.javaClass.getMethod("isRemovable").invoke(vol) as? Boolean
                    }.getOrNull() ?: false
                    if (!isRemovable) return@runCatching

                    val path = vol.javaClass.getMethod("getPath").invoke(vol) as? String ?: return@runCatching
                    if (path.isBlank()) return@runCatching

                    val state = runCatching {
                        vol.javaClass.getMethod("getState").invoke(vol) as? String
                    }.getOrNull() ?: runCatching {
                        val sm = context.getSystemService(Context.STORAGE_SERVICE)
                        sm?.javaClass?.getMethod("getVolumeState", String::class.java)?.invoke(sm, path) as? String
                    }.getOrNull()
                    if (state != "mounted") return@runCatching

                    val isPrimary = runCatching {
                        vol.javaClass.getMethod("isPrimary").invoke(vol) as? Boolean
                    }.getOrNull()
                    val candidateFile = File(path)
                    if (isPrimary == true || isPrimaryStorage(candidateFile)) return@runCatching

                    val effectiveFile = resolveVolumePath(candidateFile, storageRoot)
                    found[effectiveFile.absolutePath] = effectiveFile
                    DiagLog.i("DiagnosticVolumeProbe", "Candidate from getVolumeList: ${effectiveFile.absolutePath} (raw=$path, state=$state)")
                }.onFailure { t ->
                    DiagLog.w("DiagnosticVolumeProbe", "Error inspecting volume in getVolumeList", t)
                }
            }
        }

        // 2. MTK 5.1 厂商接口回退：StorageManager.getVolumePaths()
        if (found.isEmpty()) {
            val paths = volumePathsProvider?.invoke(context) ?: runCatching {
                val sm = context.getSystemService(Context.STORAGE_SERVICE) ?: return@runCatching null
                val method = runCatching { sm.javaClass.getMethod("getVolumePaths") }.getOrNull()
                method?.invoke(sm) as? Array<*>
            }.getOrNull()

            if (paths != null && paths.isNotEmpty()) {
                val sm = context.getSystemService(Context.STORAGE_SERVICE)
                val getVolumeStateMethod = runCatching {
                    sm?.javaClass?.getMethod("getVolumeState", String::class.java)
                }.getOrNull()

                for (item in paths) {
                    val path = item as? String ?: continue
                    if (path.isBlank()) continue
                    val candidateFile = File(path)
                    if (isPrimaryStorage(candidateFile)) continue

                    val state = volumeStateProvider?.invoke(path) ?: runCatching {
                        getVolumeStateMethod?.invoke(sm, path) as? String
                    }.getOrNull()
                    if (state != null && state != "mounted") continue

                    val effectiveFile = resolveVolumePath(candidateFile, storageRoot)
                    found[effectiveFile.absolutePath] = effectiveFile
                    DiagLog.i("DiagnosticVolumeProbe", "Candidate from getVolumePaths: ${effectiveFile.absolutePath} (raw=$path, state=$state)")
                }
            }
        }

        // 3. /proc/mounts 挂载点旁证（当系统服务未登记卷时兜底识别物理挂载）
        if (found.isEmpty() && mountsContent.isNotEmpty()) {
                val mountCandidates = parseMountPoints(mountsContent)
                val mounted = mountCandidates.map { it.absolutePath }.toSet()
                for (file in mountCandidates) {
                    if (file.absolutePath == "/mnt/media_rw" || file.absolutePath == "/storage") continue
                    val effectiveFile = resolveVolumePath(file, storageRoot, mounted)
                    found[effectiveFile.absolutePath] = effectiveFile
                    DiagLog.i("DiagnosticVolumeProbe", "Candidate from /proc/mounts: ${effectiveFile.absolutePath} (raw=${file.absolutePath})")
                }
        }

        for (partition in usbPartitionCandidates(storageRoot, mediaRw)) {
            found.putIfAbsent(partition.absolutePath, partition)
            DiagLog.i("DiagnosticVolumeProbe", "Candidate from usbotg partition: ${partition.absolutePath}")
        }

        val kept = found.values.mapNotNull { keepListableVolume(it) }
        if (kept.isEmpty()) {
            DiagLog.i("DiagnosticVolumeProbe", "No listable USB partition. sdcard1, sdcard2 and the usbotg parent are not the stick.")
        } else {
            DiagLog.i("DiagnosticVolumeProbe", "kept ${kept.joinToString { it.absolutePath }}")
        }
        return kept.distinctBy { it.absolutePath }
    }

    /**
     * CX 和东软认的 U 盘是 `usbotg-sda1` / `usbotg-sda2`，在 `usbotg` 下面一层。
     * `sdcard1`、`sdcard2` 和单独的 `usbotg` 目录不是这支盘。
     * 目录要能 list()，空数组可以，返回 null 的不收。
     */
    internal fun usbPartitionCandidates(
        storageRoot: File = File("/storage"),
        mediaRw: File = File("/mnt/media_rw"),
    ): List<File> {
        val names = listOf("usbotg-sda1", "usbotg-sda2", "udisk1", "udisk2")
        val parents = listOf(
            File(storageRoot, "usbotg"),
            File(mediaRw, "usbotg"),
            storageRoot,
            mediaRw,
        )
        val found = linkedMapOf<String, File>()
        for (parent in parents) {
            for (name in names) {
                val dir = File(parent, name)
                if (!dir.isDirectory) continue
                val namesListed = runCatching { dir.list() }.getOrNull()
                DiagLog.i(
                    "DiagnosticVolumeProbe",
                    "partition ${dir.absolutePath} list=${namesListed?.size ?: "null"} sample=${namesListed?.take(3)?.joinToString(",") ?: "null"}",
                )
                if (namesListed == null) continue
                found[dir.absolutePath] = dir
            }
        }
        return found.values.toList()
    }

    /** 打开保存列表时记一次挂载，后面不用再上车看 /proc/mounts。 */
    private fun logMountEvidence(text: String) {
        val interesting = text.lineSequence()
            .map { it.trim() }
            .filter { line ->
                val lower = line.lowercase()
                lower.contains("usbotg") || lower.contains("vfat") || lower.contains("exfat") ||
                    lower.contains("udisk") || lower.contains("sdcard")
            }
            .take(12)
            .toList()
        if (interesting.isEmpty()) {
            DiagLog.i("DiagnosticVolumeProbe", "mounts: no usbotg/vfat/exfat/udisk/sdcard line")
        } else {
            interesting.forEach { line -> DiagLog.i("DiagnosticVolumeProbe", "mount $line") }
        }
    }

    private fun mountText(): String {
        procMountsProvider?.let { return it().orEmpty() }
        val fileText = runCatching { File("/proc/mounts").takeIf { it.canRead() }?.readText() }.getOrNull()
        if (!fileText.isNullOrBlank()) return fileText
        if (com.shilapi.xcertplay.system.RootShellBridge.isAvailable()) {
            return com.shilapi.xcertplay.system.RootShellBridge.executeWithOutput("cat /proc/mounts").second
        }
        return ""
    }

    internal fun keepListableVolume(dir: File): File? {
        val leaf = dir.name.lowercase()
        if (leaf == "usbotg" || leaf == "udisk") {
            val children = runCatching { dir.listFiles() }.getOrNull() ?: return null
            val partition = children.firstOrNull { child ->
                child.isDirectory && child.name.lowercase().let { name ->
                    name.contains("sda") || name == "udisk1" || name == "udisk2"
                }
            }
            if (partition != null && runCatching { partition.listFiles() }.getOrNull() != null) {
                return partition
            }
            if (children.isNotEmpty()) return dir
            return null
        }
        if (leaf == "sdcard1" || leaf == "sdcard2") {
            val listed = runCatching { dir.listFiles() }.getOrNull()
            if (listed == null || listed.isEmpty()) {
                DiagLog.i("DiagnosticVolumeProbe", "dropped ${dir.absolutePath} list=${listed?.size ?: "null"}")
                return null
            }
            return dir
        }
        if (runCatching { dir.listFiles() }.getOrNull() == null) {
            DiagLog.i("DiagnosticVolumeProbe", "dropped ${dir.absolutePath} list=null")
            return null
        }
        return dir
    }

    internal fun isPrimaryStorage(candidate: File): Boolean {
        val primary = runCatching { android.os.Environment.getExternalStorageDirectory() }.getOrNull()
            ?: return false
        if (candidate.absolutePath == primary.absolutePath) return true
        return runCatching { candidate.canonicalPath == primary.canonicalPath }.getOrDefault(false)
    }
}
