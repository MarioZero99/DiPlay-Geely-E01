# 修复计划：仪表盘进度与元数据、U盘挂载真实性与生命周期、NATP探针真实性与启动防重

## 1. 仪表盘进度与元数据修复 (CarPlayMediaKeys)
- **进度保存与恢复**：
  - 维护 `lastKnownPosition` 与 `lastPositionUpdateWallTime`。
  - 当 `onPlaybackChanged(playing)` 触发时，不要盲目写入 `PLAYBACK_POSITION_UNKNOWN` (-1)，保留并计算真实播放进度，避免仪表进度条归零或消失。
- **元数据字段去重重构**：
  - `duration` 和 `id` 晚到必须允许重新广播！`isMetaChanged` 必须检测：
    - `track != lastBroadcastTrack`
    - `artist != lastBroadcastArtist`
    - `album != lastBroadcastAlbum`
    - `(duration > 0 && duration != lastBroadcastDuration)`（处理 iAP2 歌名先到、时长后到的情况）
    - `(id != 0L && id != lastBroadcastId)`（处理封面编号晚到的情况）
  - 生成 `id`：没有封面编号时不硬编码 `1`，而是基于 `(track + artist).hashCode()` 生成稳定唯一 ID，封面编号到达时平滑更新。
- **暂停与断开状态**：
  - 暂停广播 (`playstatechanged`) 中的 `position` 使用 `lastKnownPosition`，不再显示 0。
  - 断开时发送 `playstate = 1` (`STATE_STOPPED`)，彻底清除老车机仪表和小部件的残留歌曲。
- **保留用户开关**：
  - 恢复 `AirPlayPersistence.loadClusterMediaMetadataEnabled(context)` 读取（默认 `true`），并在【显示与音频】中保留该开关，给用户和异常车机留下退路。

## 2. U盘挂载真实性与生命周期修复 (DiagnosticVolumeProbe & RootShellBridge)
- **块设备枚举**：
  - 不再依赖普通应用无权限的 `File("/dev/block").list()`。改为读取所有 Android 均可读的 `/proc/partitions` 获取物理块设备名，或者在 root 中执行探测。
  - 严格过滤只挂载分区（如 `sda1`, `sdb1`，正则 `sd[a-z][0-9]+`），绝对禁止强挂 `sda` 整盘裸节点破坏磁盘头。
- **挂载验证与清理**：
  - 挂载前先清理可能残留的 `/mnt/udisk`（`umount -l /mnt/udisk 2>/dev/null`）。
  - 挂载命令控制在预算内（单设备挂载，尝试 `vfat/ntfs/exfat`），单次命令 <= 180 字节。
  - 挂载完成后，必须**回读 `/proc/mounts`** 校验 `/mnt/udisk` 是否真挂载成功，而不是仅看退出码。
  - 导出完成后执行 `umount /mnt/udisk` 或在下一次挂载前做脏挂载清理。
- **挂载超时提升**：
  - 为 U 盘挂载提供 4000ms 超时，避免 MTK 慢速 I/O 导致误判超时。

## 3. NATP 探针真实性与冷启动防重修复 (governance)
- **探针命令兼容 Android 5.1**：
  - 不再使用 Lollipop toolbox 中不存在的 `pidof`。
  - 采用 Android 原生 `init` 状态属性 `getprop init.svc.run_natp`（返回 `running`, `stopped`, `stopping` 等）结合 `ps | grep -F "natp"`。
  - 严格区分 `127`（命令不存在/探测失败）与真实退出码，绝不将查失败误报为“已死”。
- **异步停止延迟探针**：
  - `ctl.stop` 属性下发后，允许短暂停留或异步等待 init 响应，不再即时检测导致出现“停止成功但依然活着”。
- **退出路径去锁与免探针**：
  - `onAppExit` 仅负责下发恢复指令，严禁在主线程关页面退出时执行耗时探针，设置快速通道防止主线程卡死。
- **消除冷启动双重初始化**：
  - `VehicleGovernance.install` 增加原子状态标志 `installed.compareAndSet(false, true)`，确保无论何处调用只执行一次。
  - 移除 `DiPlayActivity.onCreate` 中的重复 `VehicleGovernance.install` 调用，仅在 `DiPlayApplication.onCreate` 中初始化一次。
  - 让 `E01PrivilegedBridge` 与 `RootShellBridge` 共用互斥锁，避免开机时两路 root 指令踩踏。
