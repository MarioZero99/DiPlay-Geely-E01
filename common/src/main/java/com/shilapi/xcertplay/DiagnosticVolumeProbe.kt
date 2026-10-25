package com.shilapi.xcertplay

import android.content.Context
import android.os.Build
import java.io.File

/**
 * Mount points a diagnostic report might be saved to.
 *
 * A stick on this head unit is mounted at `/mnt/media_rw/usbotg-sda1`. An ordinary app lists and
 * writes the same stick at `/storage/usbotg-sda1` when that directory exists. This collects those
 * directories and shows the `/storage` one. It does not create `DiPlay` and it does not write a
 * probe file: looking is not saving.
 */
internal object DiagnosticVolumeProbe {
    private val vendorMountRoots = listOf(
        "/storage/usbotg",
        "/storage/udisk",
        "/storage/sdcard1",
        "/storage/sdcard2",
        "/mnt/usb_storage",
        "/mnt/udisk",
        "/mnt/usb",
        "/mnt/media_rw",
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
            // Exact parent only. /mnt/media_rw has no filesystem of its own;
            // /mnt/media_rw/usbotg-sda1 is the stick and must be kept.
            if (mountPoint == "/" || mountPoint == "/mnt" || mountPoint == "/mnt/media_rw" || mountPoint.startsWith("/storage/emulated") || systemPrefixes.any { mountPoint.startsWith(it) }) continue
            if (parts.size >= 4) {
                val options = parts[3].split(",")
                if (options.contains("ro")) continue
            }
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

    /**
     * Volume roots only. `getExternalFilesDirs` hands back `Android/data/<pkg>/files`; the root is
     * what a person can recognise, so the package directory is never returned.
     */
    fun candidates(
        context: Context,
        extraMountPaths: Collection<String> = emptySet(),
        procMountsFile: File? = defaultProcMounts(),
        storageRoot: File = File("/storage"),
    ): List<File> {
        val found = linkedMapOf<String, File>()
        var mountsContent = ""

        runCatching {
            val sm = context.getSystemService(Context.STORAGE_SERVICE) ?: return@runCatching
            val volumes = sm.javaClass.getMethod("getVolumeList").invoke(sm) as? Array<*> ?: return@runCatching
            for (vol in volumes) {
                if (vol == null) continue
                val path = vol.javaClass.getMethod("getPath").invoke(vol) as? String ?: continue
                val file = File(path)
                val state = runCatching {
                    vol.javaClass.getMethod("getState").invoke(vol) as? String
                }.getOrNull() ?: runCatching {
                    sm.javaClass.getMethod("getVolumeState", String::class.java).invoke(sm, path) as? String
                }.getOrNull()
                if (state != null && state != "mounted") continue
                if (file.isDirectory || com.shilapi.xcertplay.system.RootShellBridge.isAvailable()) {
                    remember(found, file)
                }
            }
        }

        // Drops system mounts and the empty parent /mnt/media_rw. A child such as
        // /mnt/media_rw/usbotg-sda1 is the stick and is kept.
        runCatching {
            if (procMountsFile != null && procMountsFile.exists() && procMountsFile.canRead()) {
                mountsContent = procMountsFile.readText()
                for (mount in parseMountPoints(mountsContent)) remember(found, mount)
            }
        }

        runCatching {
            val dirs = context.getExternalFilesDirs(null) ?: emptyArray()
            for (index in 1 until dirs.size) {
                val dir = dirs[index] ?: continue
                val root = extractVolumeRoot(dir) ?: continue
                remember(found, root)
            }
        }

        for (path in extraMountPaths) {
            val file = File(path)
            if (!file.isDirectory) continue
            remember(found, file)
        }

        // Vendor containers are a last resort. An empty parent such as /mnt/media_rw is not a stick;
        // a child that is mounted, or that already has files, is.
        if (found.isEmpty()) {
            for (rootPath in vendorMountRoots) {
                val root = File(rootPath)
                if (!root.isDirectory) continue
                val children = runCatching { root.listFiles() }.getOrNull().orEmpty().filter { it.isDirectory }
                for (target in listOf(root) + children) {
                    val listed = runCatching { target.list() }.getOrNull()
                    if (mountsContent.isEmpty() || isMountedFileSystem(target, mountsContent) || listed?.isNotEmpty() == true) {
                        remember(found, target)
                    }
                }
            }
        }
        return found.values.map { visibleRoot(it, storageRoot) }.distinctBy { it.absolutePath }
    }

    /**
     * The raw mount is `/mnt/media_rw/<name>`. The directory an app can list and write is
     * `/storage/<name>` when it exists. A name with another slash is not the volume root.
     */
    internal fun visibleRoot(dir: File, storageRoot: File = File("/storage")): File {
        val marker = "/mnt/media_rw/"
        val path = dir.absolutePath
        val at = path.indexOf(marker)
        if (at < 0) return dir
        val leaf = path.substring(at + marker.length).trim('/')
        if (leaf.isEmpty() || leaf.contains('/')) return dir
        val twin = File(storageRoot, leaf)
        return if (twin.isDirectory) {
            DiagLog.i("DiagnosticExport", "usb $path -> ${twin.absolutePath}")
            twin
        } else {
            DiagLog.i("DiagnosticExport", "usb $path kept, ${twin.absolutePath} is not a directory")
            dir
        }
    }

    private fun remember(found: MutableMap<String, File>, file: File) {
        val path = file.absolutePath
        if (path == "/" || path == "/mnt" || path == "/mnt/media_rw") return
        if (path.contains("/Android/data/")) return
        if (isPrimaryStorage(file)) return
        found[path] = file
    }

    private fun isPrimaryStorage(candidate: File): Boolean {
        val primary = runCatching { android.os.Environment.getExternalStorageDirectory() }.getOrNull()
            ?: return false
        if (candidate.absolutePath == primary.absolutePath) return true
        return runCatching { candidate.canonicalPath == primary.canonicalPath }.getOrDefault(false)
    }

    private fun extractVolumeRoot(packageDir: File): File? {
        val path = packageDir.absolutePath
        val marker = "/Android/data"
        val idx = path.indexOf(marker)
        return if (idx > 0) File(path.substring(0, idx)) else null
    }
}
