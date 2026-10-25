package com.shilapi.xcertplay

import android.content.Context
import android.os.Build
import java.io.File
import java.io.FileOutputStream

/**
 * Where a USB stick on this head unit could be, asked of three sources instead of guessed.
 *
 * E01 mounts a stick under a vendor path and the driver's app can only write the package's own
 * directory on it, so "found a volume" and "can write it" are different answers: the probe tries
 * both the root and `<vol>/Android/data/<pkg>/files` and keeps the kernel's reason when one fails,
 * because a head unit with no adb has nothing else to show anyone.
 */
internal data class VolumeProbeResult(
    val name: String,
    val rootDir: File?,
    val rootWritable: Boolean,
    val rootErrno: String? = null,
    val packageDir: File?,
    val packageWritable: Boolean,
    val packageErrno: String? = null,
)

internal data class ProbeSummary(
    val volumes: List<VolumeProbeResult>,
    val summaryLine: String,
)

internal object DiagnosticVolumeProbe {
    private val vendorMountRoots = listOf(
        "/storage/usbotg",
        "/storage/udisk",
        "/storage/sdcard1",
        "/storage/sdcard2",
        "/mnt/usb_storage",
        "/mnt/udisk",
        "/mnt/usb",
    )

    internal fun unescapeMountPoint(raw: String): String {
        if (!raw.contains('\\')) return raw
        val regex = Regex("\\\\([0-7]{3})")
        return regex.replace(raw) { match ->
            val octal = match.groupValues[1].toInt(8)
            octal.toChar().toString()
        }
    }

    internal fun defaultProcMounts(): File? =
        if (Build.FINGERPRINT == "robolectric") null else File("/proc/mounts")

    internal fun parseMountPoints(mountsContent: String): List<File> {
        val candidates = mutableListOf<File>()
        val systemPrefixes = listOf(
            "/system", "/data", "/dev", "/proc", "/sys", "/mnt/secure", "/mnt/asec", "/mnt/obb",
            "/mnt/shell", "/mnt/runtime", "/mnt/media_rw", "/cache", "/config",
        )
        val targetFs = setOf("vfat", "exfat", "texfat", "ntfs", "fuse", "fuseblk", "ext4", "ext3", "ext2", "f2fs")
        for (line in mountsContent.lineSequence()) {
            val parts = line.trim().split(Regex("\\s+"))
            if (parts.size < 3) continue
            val dev = parts[0]
            val mountPoint = unescapeMountPoint(parts[1])
            val fsType = parts[2].lowercase()
            if (fsType !in targetFs) continue
            if (mountPoint == "/" || mountPoint == "/mnt" || mountPoint.startsWith("/storage/emulated") || systemPrefixes.any { mountPoint.startsWith(it) }) continue
            if (parts.size >= 4) {
                val options = parts[3].split(",")
                if (options.contains("ro")) continue
            }
            // An external volume either comes from Android vold, or is mounted under /storage/ or /mnt/
            val isLikelyExternal = dev.startsWith("/dev/block/vold/") ||
                dev.startsWith("/dev/block/sd") ||
                mountPoint.startsWith("/storage/") ||
                mountPoint.startsWith("/mnt/")
            if (!isLikelyExternal) continue
            val file = File(mountPoint)
            if (!isPrimaryStorage(file)) {
                if (file.isDirectory || (dev.startsWith("/dev/block/") && com.shilapi.xcertplay.system.RootShellBridge.isAvailable())) {
                    candidates.add(file)
                }
            }
        }
        return candidates
    }

    internal fun isMountedFileSystem(dir: File, mountsContent: String): Boolean {
        val targetPath = dir.absolutePath.trimEnd('/')
        for (line in mountsContent.lineSequence()) {
            val parts = line.trim().split(Regex("\\s+"))
            if (parts.size < 2) continue
            val dev = parts[0]
            val mountPoint = unescapeMountPoint(parts[1]).trimEnd('/')
            if (mountPoint == targetPath) {
                val isExternal = dev.startsWith("/dev/block/vold/") ||
                    dev.startsWith("/dev/block/sd") ||
                    dev.startsWith("/dev/fuse")
                return isExternal
            }
        }
        return false
    }

    fun probe(
        context: Context,
        extraMountPaths: Collection<String> = emptySet(),
        procMountsFile: File? = defaultProcMounts(),
    ): ProbeSummary {
        val candidates = linkedMapOf<String, File>()
        var mountsContent = ""

        // Source 1: StorageManager (via reflection, same as CX File Explorer / Android framework)
        // StorageManager.getVolumeList() returns StorageVolume objects representing mounted volumes.
        // It provides the canonical presentation path (e.g. /storage/usbotg) and marks removable/primary state.
        runCatching {
            val sm = context.getSystemService(Context.STORAGE_SERVICE)
            if (sm != null) {
                val method = sm.javaClass.getMethod("getVolumeList")
                val volumes = method.invoke(sm) as? Array<*>
                if (volumes != null) {
                    for (vol in volumes) {
                        if (vol == null) continue
                        val getPathMethod = vol.javaClass.getMethod("getPath")
                        val path = getPathMethod.invoke(vol) as? String ?: continue
                        val file = File(path)
                        if (isPrimaryStorage(file)) continue

                        // Check state if available
                        val state = runCatching {
                            vol.javaClass.getMethod("getState").invoke(vol) as? String
                        }.getOrNull() ?: runCatching {
                            sm.javaClass.getMethod("getVolumeState", String::class.java).invoke(sm, path) as? String
                        }.getOrNull()
                        if (state != null && state != "mounted") continue

                        if (file.isDirectory || com.shilapi.xcertplay.system.RootShellBridge.isAvailable()) {
                            candidates[file.absolutePath] = file
                        }
                    }
                }
            }
        }

        // Source 2: /proc/mounts, the kernel's own mount table.
        // This is a direct source naming currently mounted filesystems, filtering out system & raw mirrors (/mnt/media_rw).
        runCatching {
            if (procMountsFile != null && procMountsFile.exists() && procMountsFile.canRead()) {
                mountsContent = procMountsFile.readText()
                for (mount in parseMountPoints(mountsContent)) {
                    candidates[mount.absolutePath] = mount
                }
            }
        }

        // Source 3: the system's own contract getExternalFilesDirs.
        // The second entry onwards is another volume; the first is this app's own external directory.
        runCatching {
            val dirs = context.getExternalFilesDirs(null) ?: emptyArray()
            for (i in 1 until dirs.size) {
                val dir = dirs[i] ?: continue
                val root = extractVolumeRoot(dir) ?: dir
                if (!isPrimaryStorage(root)) {
                    candidates[root.absolutePath] = dir
                }
            }
        }

        // Source 4: the path ACTION_MEDIA_MOUNTED carried, recorded when the stick was plugged in.
        for (path in extraMountPaths) {
            val file = File(path)
            if (!file.isDirectory || isPrimaryStorage(file)) continue
            candidates[file.absolutePath] = file
        }

        // Source 5: static vendor roots, fallback when nothing yielded so far.
        // On Android, USB sticks are mounted under container roots (e.g. /storage/usbotg) or as subdirectories.
        if (candidates.isEmpty()) {
            for (rootPath in vendorMountRoots) {
                val root = File(rootPath)
                if (!root.isDirectory || isPrimaryStorage(root)) continue
                val targets = listOf(root) + root.listFiles().orEmpty().filter { it.isDirectory }
                for (target in targets) {
                    if (!isPrimaryStorage(target) && (mountsContent.isEmpty() || isMountedFileSystem(target, mountsContent) || target.list()?.isNotEmpty() == true)) {
                        candidates[target.absolutePath] = target
                    }
                }
            }
        }

        val results = candidates.map { (_, target) -> probeVolume(context, extractVolumeName(target), target) }
        return ProbeSummary(results, formatSummary(results))
    }

    private fun isPrimaryStorage(candidate: File): Boolean {
        val primary = runCatching { android.os.Environment.getExternalStorageDirectory() }.getOrNull()
            ?: return false
        if (candidate.absolutePath == primary.absolutePath) return true
        return runCatching { candidate.canonicalPath == primary.canonicalPath }.getOrDefault(false)
    }

    private fun extractVolumeName(packageDir: File): String {
        val path = packageDir.absolutePath
        val marker = "/Android/data"
        val idx = path.indexOf(marker)
        return if (idx > 0) File(path.substring(0, idx)).name else packageDir.name
    }

    private fun extractVolumeRoot(packageDir: File): File? {
        val path = packageDir.absolutePath
        val marker = "/Android/data"
        val idx = path.indexOf(marker)
        return if (idx > 0) File(path.substring(0, idx)) else null
    }

    private fun probeVolume(context: Context, name: String, target: File): VolumeProbeResult {
        val (rootDir, pkgDir) = if (target.absolutePath.contains("/Android/data/")) {
            extractVolumeRoot(target) to target
        } else {
            target to File(target, "Android/data/${context.packageName}/files")
        }

        val (rootWritable, rootErrno) = testWritable(rootDir?.let { File(it, "DiPlay") })
        val (pkgWritable, pkgErrno) = testWritable(pkgDir?.let { File(it, "DiPlay") })

        return VolumeProbeResult(
            name = name,
            rootDir = rootDir,
            rootWritable = rootWritable,
            rootErrno = rootErrno,
            packageDir = pkgDir,
            packageWritable = pkgWritable,
            packageErrno = pkgErrno,
        )
    }

    private fun testWritable(dir: File?): Pair<Boolean, String?> {
        if (dir == null) return false to "ENOENT"
        // A file of the same name blocks every mkdir the app will ever make here. Report ENOTDIR
        // rather than letting the later FileOutputStream failure speak for it.
        if (dir.exists() && !dir.isDirectory) return false to "ENOTDIR"
        if (!dir.isDirectory && !dir.mkdirs()) {
            return false to (kernelErrno(dir) ?: fallbackErrno(dir))
        }
        return try {
            val probe = File(dir, ".probe_${System.nanoTime()}")
            FileOutputStream(probe).use { stream ->
                stream.write(42)
                stream.fd.sync()
            }
            val written = probe.length() > 0L
            probe.delete()
            if (written) true to null else false to "EIO"
        } catch (error: Exception) {
            false to (errnoOf(error) ?: error.javaClass.simpleName)
        }
    }

    /**
     * The reason the directory could not be made, asked of the kernel: `mkdir` is the call that
     * just failed, so its errno is the answer rather than a guess made from `canWrite()`.
     */
    private fun kernelErrno(target: File): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) return null
        val failure = runCatching {
            android.system.Os.mkdir(target.absolutePath, 0x1C0 /* 0700 */)
        }.exceptionOrNull() ?: return null
        return errnoOf(failure)
    }

    private fun fallbackErrno(target: File): String = when {
        !target.exists() -> "ENOENT"
        !target.canWrite() -> "EACCES"
        else -> "unwritable"
    }

    private fun errnoOf(error: Throwable): String? {
        var current: Throwable? = error
        while (current != null) {
            // android.system arrived with Android 5, and this app still installs down to 4.4: on
            // those units there is no kernel errno to read, only the exception's own message.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                val kernel = runCatching { (current as? android.system.ErrnoException)?.errno }.getOrNull()
                if (kernel != null) {
                    val name = runCatching { android.system.OsConstants.errnoName(kernel) }.getOrNull()
                    return name ?: "errno_$kernel"
                }
            }
            val match = current.message?.let {
                Regex("""\b(EACCES|EROFS|EPERM|ENOENT|ENOTDIR|ENOSPC|EIO)\b""").find(it)
            }
            if (match != null) return match.value
            current = current.cause
        }
        return null
    }

    fun formatSummary(volumes: List<VolumeProbeResult>): String {
        if (volumes.isEmpty()) return "未发现外置存储卷 (No external volume)"
        val details = volumes.joinToString(" · ") { v ->
            when {
                v.rootWritable -> "${v.name} 可写(根目录)"
                v.packageWritable -> "${v.name} 可写(包目录)${v.rootErrno?.let { " · 根目录 $it" } ?: ""}"
                else -> "${v.name} 不可写(${v.packageErrno ?: v.rootErrno ?: "unknown"})"
            }
        }
        return "发现 ${volumes.size} 个卷 · $details"
    }
}
