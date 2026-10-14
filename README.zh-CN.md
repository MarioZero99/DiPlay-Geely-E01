# DiPlay — 吉利星瑞 E01

[English](README.md)

给**吉利星瑞 2021 款**车机用的 CarPlay。车机是亿咖通 E01，联发科 MT6735，GKUI 19，系统 **Android 5.1（API 22）**。装在车机上，不用越狱，也不用转接盒。

当前版本 `0.4.1-geely`（versionCode 41），包名 `com.shihab.diplay`。

没有 Apple 认证，和吉利、亿咖通也没有关系。CarPlay 的名字和图标属于 Apple。

参考车的软件版本是 **5068**（出厂批次是 5066），内部版本 `SWFS11G0706H5181.00600`。在车机 **设置 → 关于智能平台 → 版本信息** 里可以核对。版本号不同也能装。换一台车，下面的风险可能不一样，装之前先看清楚。

## 装之前先看完

无线用的是车机自带的蓝牙。要把 CarPlay 的数据通道接上，DiPlay 会替换车机正在运行的蓝牙进程。

- 不改 `/system`。**重启车机后，原来的进程会自己恢复。**
- 应用里没有还原。要马上恢复，重启车机。
- **打开 DiPlay 就会替换**，这次用不用无线都一样，一直留到下次重启。

车机原来的电话和蓝牙音乐，在进程换过之后是否还正常，**这台车还没有试过**。出了问题，先重启车机。这是非官方改动，不在保修范围内，也可能影响以后的官方升级。

不想接受这件事，就用有线。有线不会动蓝牙。

风险和免责的全文在 [v0.4.1 说明](docs/release-notes/v0.4.1-geely.md)。看完再决定要不要装。

## 这台车上试过的

方向盘**上一首 / 下一首**可以切歌。车机把这两个键转成普通的媒体键，DiPlay 能收到。实车日志：`DiPlay-MediaKeys: media key -> CarPlay 4/5 sent=true`。

方向盘上没有播放/暂停。那个位置是**飞屏键**，只进车机自己的界面，应用收不到。

有线 USB 在这台上出过画面。iPhone 17 Pro Max 需要的电流超过这个 USB 口的供电，连接会断开再重连。中间加一个带供电的 Hub 可以避开。

无线和后来改过的设置页，一部分上过车，一部分还没有。更新日志写的是代码改了什么。没有标明实车验证的，要到车上才知道。

## 怎么装

车机要允许安装 APK。

从上游的 DiPlay 换到这个版本，签名不同，不能覆盖安装。需要先卸载。卸载后，分辨率、音频缓冲、方向盘按键和保存的 Wi-Fi 都会清掉。

以后还用这个分支的新版本，可以直接覆盖，这些设置会留下。

```sh
adb uninstall com.shihab.diplay
adb install -r DiPlay-Legacy-Geely-V041.apk
```

没有 ADB 时，把 APK 拷到 U 盘，用车机的文件管理器安装，并允许「未知来源」。

## 连上之后

无线走**车载热点**。名称和密码车机里已经有了。没有另外填写时，DiPlay 会直接读取。能用 5 GHz 就用 5 GHz。手机打开蓝牙和 Wi-Fi，与车机配对后，点 **连接手机**。

有线点 **通过 USB 连接**。要用数据线，插在能传数据的口上。只充电的线没有反应。

在 CarPlay 画面里**三指向下滑动**，可以打开设置。这一版把设置放回同一页，宽屏上并排两个。分辨率、帧率和图标大小，连接中要点「应用并重连」才会生效。只是选中，用的还是原来的值。

卡住时，到设置里 **保存诊断报告**。公开发布的安装包没有上传密钥，日志不会自动发出。贴到公开的 issue 之前，先去掉密码和配对记录。

## 这台车机内存很少

空闲内存经常只剩 **26–33 MB**。用大块内存换更顺的声音，在这台上不划算，所以没有做。GKUI 没有分屏，DiPlay 一直是全屏。

编译仍保留 **Android 4.4（API 19）** 的底线，更老的车机也能编出来。实际对着的是这台 5.1。其他车型没有验证过。

## 从哪来

这一支是 [programmerguohuajing/DiPlay-Legacy-Android](https://github.com/programmerguohuajing/DiPlay-Legacy-Android) 的吉利版本，再往上是 [shihabal3amri/DiPlay](https://github.com/shihabal3amri/DiPlay)。底子是上游 **v0.2.7**。比亚迪的仪表、HUD 和电池在这台上没有对应硬件，相关代码已经去掉。

协议基于 [xcertplay](https://github.com/shilapi/xcertplay)，GPL-3.0。主页和设置界面改编自 [DiAuto](https://github.com/shihabal3amri/DiAuto)，AGPL-3.0，许可证在 `docs/licenses`。再分发修改版时，请把这些声明留下。

仓库里是一份干净的公开源码。配件密钥和 Android 签名密钥不在里面。发布的 APK 带有一份实验性配件身份，取自公开的 Carlinkit 固件，不是为 DiPlay 申请的 MFi 身份。私钥可以从包里提取。以后的 iOS 是否还接受、是否适合公开分发，目前还没有定论。本地编译默认不带这份身份。细节见[构建说明](docs/BUILD.md)和[致谢](docs/THIRD_PARTY_NOTICES.md)。

## 更多说明

- [这一版说明](docs/release-notes/v0.4.1-geely.md)
- [更新日志](CHANGELOG.md)
- [从源码构建](docs/BUILD.md)
- [安装与连接](docs/INSTALL.md)
- [连接设置](docs/CONNECTION_SETUP.md)
- [兼容性](docs/COMPATIBILITY.md)
- [隐私与诊断报告](docs/PRIVACY.md)
- [验证](docs/VALIDATION.md)
- [致谢与许可证](docs/THIRD_PARTY_NOTICES.md)
