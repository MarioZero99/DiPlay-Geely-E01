package com.shilapi.xcertplay

import android.content.Context
import java.io.File

/**
 * 诊断日志导出的挂载卷探测。
 *
 * 第一屏严格只来自 StorageManager.getVolumeList()，筛选已挂载、可移动、非主存储的系统卷。
 * 标题使用系统 getDescription(context)（系统默认「USB 存储」），路径使用 getPath()。
 * 仅当路径为 /mnt/media_rw/<name> 且同名 /storage/<name> 的 listFiles() 至少返回一项时才做平级替换；
 * 空目录不替换，绝不改写为 /storage/usbotg。
 * 彻底剔除广播路径注入、/proc/mounts 扫描以及厂商目录探测。
 */
internal object DiagnosticVolumeProbe {

    internal var volumeListProvider: ((Context) -> Array<*>?)? = null
    internal var volumePathsProvider: ((Context) -> Array<String>?)? = null
    internal var volumeStateProvider: ((String) -> String?)? = null

    /**
     * 路径只允许一种替换：
     * getPath() 是 /mnt/media_rw/某个名字，名字里面没有斜线，
     * 而且 /storage/同一个名字 的 listFiles() 至少返回一项。这时用 /storage 那条。
     * 空目录不算。否则留 getPath()。禁止改写成 /storage/usbotg。
     */
    internal fun resolveVolumePath(dir: File, storageRoot: File = File("/storage")): File {
        val path = dir.absolutePath
        val prefix = "/mnt/media_rw/"
        if (!path.startsWith(prefix)) return dir
        val leaf = path.removePrefix(prefix).trim('/')
        if (leaf.isEmpty() || leaf.contains('/')) return dir
        val twin = File(storageRoot, leaf)
        val listed = runCatching { twin.listFiles() }.getOrNull()
        if (listed != null && listed.isNotEmpty()) {
            DiagLog.i("DiagnosticExport", "usb $path -> ${twin.absolutePath} (has ${listed.size} files)")
            return twin
        }
        DiagLog.i("DiagnosticExport", "usb $path kept (twin ${twin.absolutePath} not found or empty)")
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
    ): List<File> {
        val found = linkedMapOf<String, File>()

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

        return found.values.toList()
    }

    internal fun isPrimaryStorage(candidate: File): Boolean {
        val primary = runCatching { android.os.Environment.getExternalStorageDirectory() }.getOrNull()
            ?: return false
        if (candidate.absolutePath == primary.absolutePath) return true
        return runCatching { candidate.canonicalPath == primary.canonicalPath }.getOrDefault(false)
    }
}
