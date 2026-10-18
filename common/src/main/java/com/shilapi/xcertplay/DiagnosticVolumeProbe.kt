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
        "/mnt/usb_storage",
        "/mnt/udisk",
        "/mnt/usb",
        "/mnt/media_rw",
    )

    fun probe(context: Context, extraMountPaths: Collection<String> = emptySet()): ProbeSummary {
        val candidates = linkedMapOf<String, File>()

        // Source 1: the system's own contract. The second entry onwards is another volume; the
        // first is this app's own external directory, which is always writable and proves nothing.
        runCatching {
            val dirs = context.getExternalFilesDirs(null) ?: emptyArray()
            for (i in 1 until dirs.size) {
                val dir = dirs[i] ?: continue
                candidates[extractVolumeName(dir)] = dir
            }
        }

        // Source 2: the path ACTION_MEDIA_MOUNTED carried, recorded when the stick was plugged in.
        // The same action is broadcast for the head unit's own storage, and a report written to
        // /storage/emulated/0/DiPlay is not "saved to a USB drive" — that would be the exact false
        // claim this whole path exists to stop, so the device's own volume is refused here.
        for (path in extraMountPaths) {
            val file = File(path)
            if (!file.isDirectory || isPrimaryStorage(file)) continue
            candidates[file.name] = file
        }

        // Source 3: vendor roots, last and only as a fallback — these names are guesses, the two
        // sources above are not.
        for (rootPath in vendorMountRoots) {
            val root = File(rootPath)
            if (!root.isDirectory) continue
            root.listFiles()?.filter { it.isDirectory }?.forEach { sub ->
                if (sub.name !in candidates) candidates[sub.name] = sub
            }
        }

        val results = candidates.map { (name, target) -> probeVolume(context, name, target) }
        return ProbeSummary(results, formatSummary(results))
    }

    private fun isPrimaryStorage(candidate: File): Boolean {
        val primary = runCatching { android.os.Environment.getExternalStorageDirectory() }.getOrNull()
            ?: return false
        return candidate.absolutePath == primary.absolutePath
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
            if (current is android.system.ErrnoException) {
                val name = runCatching { android.system.OsConstants.errnoName(current.errno) }.getOrNull()
                return name ?: "errno_${current.errno}"
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
