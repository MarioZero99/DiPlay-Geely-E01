# `natp` 能改写吗？

**能，但要分清改哪一层。** 结论按代价排序：**改 `/ivres` 配置 < 停 `run_natp` 服务 < 动 `/system/bin/natp` 二进制（不建议）**。

本文全部基于本机已有的官方整包 `~/Downloads/xr/SWFS11G0706H5181.00600.zip`（2022-07-06，`SWFS11G0706H5181.00600`）解包实测，不是推测。

---

## 一、`natp` 是怎么被拉起来的（`boot.img` → ramdisk → `init.rc:703-708`）

```
#run natp for vr
service run_natp /system/bin/natp -f /ivres/vr/ddfw/config/nl-3b/natp.cfg -s natp_config \
  -c Hostagent GDM MonitorService FileService DragonDriveCommonService \
     DragonDriveBiometryService DragonDriveAsrService SASService SHPSService \
  -k VerboseLevel=2
    class main
    user root
    group root
    disabled
```

四个关键点，决定了「能不能停、改了生不生效」：

1. **服务名是 `run_natp`**，不是 `natp`。
2. **带 `disabled`** —— init **不会**开机自动拉起它。它是 userspace 用 `ctl.start` 拉起来的，这也解释了为什么它开机 29.7 秒才出现（`平台事实.md:33`）。
3. **`-c` 在命令行显式列了通道表**。cfg 里那行 `SAM.ChannelNames`（`natp.cfg:19`）本来是 **注释**，就算取消注释也会被命令行的 `-c` 盖掉。**想减通道只能改 `init.rc`，而 `init.rc` 在 `boot.img` 里，boot 有 LK 签名校验 → 这条路不通。**
4. **`-k VerboseLevel=2` 也是命令行给的**，所以改 cfg 里的 `TRACE.*` / `MonitorService.LogMode` 未必生效，需实测。

---

## 二、第一层：改 `/ivres` 配置（唯一低风险层）

`/ivres` 是独立分区（800 MB），OTA 里是 `mount ext4 /dev/block/mmcblk0p8` + `delete_recursive("/ivres")` + 全量解包（`updater-script`）。**改坏了重刷同一版 OTA 就能恢复**——这是它比 `/system` 值钱的地方。

### 能改什么

**① 线程优先级（最值得改的一处）**
`natp.cfg:245-283` 里几乎所有线程都是 `THREAD_PRIO_NUMERICAL=+48,SCHED_FIFO`：ASR、AudioIn、AudioRecord、CommonService、Prompter、TTS_NAVI……这就是它「占一颗核 40%」且能插队抢 CPU 的原因（`有线与性能.md:33`）。把这些降成 `+0`（普通调度），语音延迟会变差，但对 DiPlay 解码线程的抢占会明显减轻。

**② 音频路由（决定「一直抓麦」）**
`ivres/vr/ddfw/json/audioconfig/nl-3b/audioconfig.json` 里，常驻抓麦的链路是：

```
SDS_mic ──► asd_p_bi_wuw(4ch) ──► WUW_SSE(eCarX_Music_Wake_Up.scd) ──► SDS_asr
   ▲ AudioInput / PAL_BUFFER_AUDIO            ▲ 唤醒词，常开
```

同一文件里还另有一组 `audioscenario_asr_to_file*` 场景（`to_file_src` → `toFile`），对应 `natp` 往 `/sdcard/upload/*.pcm` 写文件那条路（`平台事实.md:34`）。**要停「常驻抓麦」就动这两处**：摘掉唤醒这一路，或把 `SDS_mic` 的输入断开。断开后 `natp` 大概率进错误重试而不是安静退出——这一点必须上车看日志。

**③ 输入插件（最直接的「关麦」开关）**
`natp.cfg:219` `DragonDriveCommonService.AudioInputAdapterPlugins = /system/vendor/lib/libpal-buffer-audio-input-adapter-plugin.so`，正是 `平台事实.md:34` 里报 `PalBufferAudioInputAdapter invalid state STATE_STARTED` 的那个插件。把它置空 = 从配置层断掉麦克风输入。

**④ 日志 I/O**
`MonitorService.LogMode=3`、`LogFileName=/log_data/nuance_log/natp.log`、`LogFileSize=1048576`、`LogFileCount=5`、`DragonDriveCommonService.MonitoringEnable=1`。降到 0 能省一点磁盘抖动，但会被命令行 `-k VerboseLevel=2` 折扣，别指望太多。

### 完整性校验：不挡路

`ivres/vr/ddfw.md5` 有 267 条，**不含 `config/`，也不含 `json/`**（实测 `grep -c "config/"` = 0）。所以改 `natp.cfg` / `audioconfig.json` 不会破坏那份 md5 清单。但清单是谁、在什么时候校验（`natp` 自己？升级脚本？），未知。

### 一个没解的事实

**`/ivres` 的运行时挂载，在 ramdisk 的 `init.rc` / `fstab.mt6735` / `fstab.system.mt6735` 里都找不到**——只有 OTA 的 `updater-script` 挂它。说明系统起来后是别的组件挂的。所以动手前第一件事是上车确认：

```bash
adb shell mount | grep ivres     # 挂没挂、是不是 ro
```

只读就得 remount，而不是直接写。

---

## 三、第二层：停服务（推荐先试这一层）

```bash
adb shell getprop init.svc.run_natp      # 期望 running
adb shell setprop ctl.stop run_natp      # 需 root（mtk-su）
adb shell getprop init.svc.run_natp      # 期望 stopped
sleep 60; adb shell ps | grep natp       # 有没有被谁拉回来
```

**为什么这比 `kill` 好**：`run_natp` 带 `disabled` 且不是 `oneshot`。`kill` 掉进程，init 会按 `class main` 的规则把它重新拉起；而 `disabled` 的服务被 `ctl.stop` 之后，init **不会**自动重启它——除非 userspace 再调一次 `ctl.start`。

所以这条命令同时是一个**探针**：60 秒后如果它又活了，说明有系统组件（晓咖？popview？）在按需 `ctl.start`，那就得先找到那个调用方，而不是跟进程较劲。**重启车机即恢复原状**，没有持久副作用——这是目前唯一「可逆、无 flash 写入」的停法。

---

## 四、第三层：换二进制（能做，但不划算）

技术上可行：`fstab.system.mt6735` 里 `/system` 没有 `verify` 标志 → **dm-verity 未启用**，改了也能开机（`平台事实.md:95`）。但有四条硬约束叠在一起：

- `boot.img` 有 LK 层签名校验，**没有 TWRP**，`recovery` 也用不上（`平台事实.md:22/94`）
- `mtk-su` 连跑 6 次车机直接重启，必须一个 root 会话里干完（`平台事实.md:19`）
- SELinux 是开的，替换文件要保住 `system_file` 上下文
- `run_natp` 以 `user root / group root` 跑；二进制缺失或 exec 失败，init 会在开机阶段反复重试

**风险不在「死机」，在「没有 recovery 回滚」**：换坏了就是黑屏 + 只能整包 OTA 或 4S。而它换来的收益（省 263 MB）用第二层 `ctl.stop` 也能拿到，且可逆。

---

## 五、动作顺序建议

1. `mount | grep ivres` 确认可写性 → 只读则 remount
2. `setprop ctl.stop run_natp`，60 秒 + 按一次语音键，看是否被拉回
3. 若停得住：量 `MemFree`、`dumpsys meminfo`、DiPlay 30 帧的 `recoveries`，看收益
4. 确认「谁在拉它」之后，再决定是改 `audioconfig.json`（摘唤醒/断麦）还是改线程优先级
5. **不要**为了省内存去换 `/system/bin/natp`

---

## 六、还没验证的问题

- [ ] `/ivres` 运行时是 rw 还是 ro，谁挂的
- [ ] `ddfw.md5` 谁校验、什么时候校验
- [ ] `ctl.stop` 后 60 秒内是否被 userspace `ctl.start` 拉回；拉它的是谁
- [ ] 摘掉 `WUW_SSE` 唤醒这一路后，`natp` 是安静降级还是进错误重试
- [ ] `-k VerboseLevel=2` 是否覆盖 cfg 的 `TRACE.*`（决定降日志有没有用）
- [ ] 把 `SCHED_FIFO +48` 降到 `+0`，语音延迟和 DiPlay 卡顿各变多少
