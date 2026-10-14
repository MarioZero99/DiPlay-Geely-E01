# DiPlay — 吉利星瑞 E01

[English](README.md)

给**吉利星瑞 2021 款**车机用的 CarPlay。车机是亿咖通 E01，联发科 MT6735，GKUI 19，系统 **Android 5.1（API 22）**。装在车机上，不用越狱，也不用转接盒。

现在是 `0.4.1-geely`（versionCode 41），包名 `com.shihab.diplay`。

这东西没经过 Apple 认证，跟吉利、亿咖通也没关系。CarPlay 这个名字和它的图标都是 Apple 的。

我改的时候对着的那台车，软件版本是 **5068**（出厂那批是 5066），内部版本 `SWFS11G0706H5181.00600`。你在车机 **设置 → 关于智能平台 → 版本信息** 里对一下。号对不上也能装。下面说的风险，落到你车上可能不一样，自己掂量。

## 装之前，这段先看完

无线借的是车机自己那套蓝牙。为了把 CarPlay 的数据通道接上，DiPlay 会动车机正在跑的那个蓝牙进程。

- 它不写 `/system`。系统分区不动。**重启车机，原来的进程自己回来**，你不用做别的。
- 应用里没有「撤销」。想马上还回去，重启车机。
- **只要打开 DiPlay，它就会去动**，这次用不用无线都一样。会留到下次重启。

车机自己的电话、蓝牙音乐，跟换过的进程搭不搭得上，**这台上还没试过**。出了问题，一般是重启车机。这是非官方改动，不在保修里，也可能绊到以后的官方升级。

不想担这个，就用有线。有线什么都不动。

完整的风险和免责在 [v0.4.1 说明](docs/release-notes/v0.4.1-geely.md)。看完再决定装不装。

## 这台上确认过的

方向盘**上一首 / 下一首**能切歌。车机把按键重新发成普通的媒体键，DiPlay 接住了。我在车上按过，日志是 `DiPlay-MediaKeys: media key -> CarPlay 4/5 sent=true`。

星瑞方向盘没有播放/暂停。那个位置是车机自己的**飞屏键**，只走到厂商自己的界面，哪个应用都接不住。

有线 USB 在这台上出过画面。iPhone 17 Pro Max 要的电流比这个口给得起的多，会话会断了再连。加一个带供电的 Hub 就能绕开。

无线和后来改的设置页，有的上过车，有的还没有。更新日志写的是代码里改了什么。没标实车的，得到车上才算数。

## 怎么装

车机得允许安装 APK。

从上游那份 DiPlay 换过来，签名不一样，盖不上去。先卸再装。卸的时候，分辨率、音频缓冲、方向盘按键角色、存过的 Wi-Fi 会一起没了。

这条线自己往后更新，可以直接盖上去，这些设置还在。

```sh
adb uninstall com.shihab.diplay
adb install -r DiPlay-Legacy-Geely-V041.apk
```

没有 ADB，就把 APK 拷进 U 盘，用车机文件管理器装，记得允许「未知来源」。

## 连上之后

无线用**车载热点**。名字和密码车机里已经有了，你没另外填的话，它自己去读。5 GHz 能用就用。手机开着蓝牙和 Wi-Fi，跟车机配过对，再点 **连接手机**。

有线点 **通过 USB 连接**。用数据线，插在能传数据的口上。只充电的那根，插上也没反应。

CarPlay 画面里**三指向下划**，能打开设置。这一版设置回到一整页，屏幕宽，两个并排。分辨率、帧率、图标大小，连着的时候要点「应用并重连」。只选中，还是旧的。

卡住了，到设置里 **保存诊断报告**。发出去的包不带上传钥匙，日志不会自己送走。往公开的 issue 里贴之前，先把密码和配对记录擦掉。

## 这台车机很紧

空闲内存经常只剩 **26–33 MB**。拿一大块内存去换更顺的声音，这车上不划算，所以没做。GKUI 不给分屏，DiPlay 一直占满整块屏。

编译还守着 **Android 4.4（API 19）** 的底线，老车机也能编。天天对着的是这台 5.1。别的车没坐过。

## 从哪来的

这支是 [programmerguohuajing/DiPlay-Legacy-Android](https://github.com/programmerguohuajing/DiPlay-Legacy-Android) 的吉利线，再往上是 [shihabal3amri/DiPlay](https://github.com/shihabal3amri/DiPlay)。底子是上游 **v0.2.7**。比亚迪的仪表、HUD、电池，这台车没有，已经拿掉了。

协议基于 [xcertplay](https://github.com/shilapi/xcertplay)，GPL-3.0。主页和设置的样子改编自 [DiAuto](https://github.com/shihabal3amri/DiAuto)，AGPL-3.0，许可证在 `docs/licenses`。你要是再改了拿去发，这些话留着。

仓库里是一份干净的公开源码。配件密钥和 Android 签名密钥不在里面。发出去的 APK 带着一份实验性的配件身份，是从公开的 Carlinkit 固件里取出来的，不是为 DiPlay 申领的 MFi 身份。私钥能被提出来。以后的 iOS 还认不认、适不适合到处发，都还没定。自己在本地编译，默认不带这份身份。细节看[构建说明](docs/BUILD.md)和[致谢](docs/THIRD_PARTY_NOTICES.md)。

## 还想看

- [这一版说明](docs/release-notes/v0.4.1-geely.md)
- [更新日志](CHANGELOG.md)
- [从源码构建](docs/BUILD.md)
- [安装与连接](docs/INSTALL.md)
- [连接设置](docs/CONNECTION_SETUP.md)
- [兼容性](docs/COMPATIBILITY.md)
- [隐私与诊断报告](docs/PRIVACY.md)
- [验证](docs/VALIDATION.md)
- [致谢与许可证](docs/THIRD_PARTY_NOTICES.md)
