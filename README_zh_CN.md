# Video Synchronizer

<p align="center">
  <img src="src/main/resources/video_synchronizer.png" alt="Video Synchronizer Logo" width="240">
</p>

[English](README.md)

适用于 Minecraft Forge 1.20.1 的在线视频同步播放模组。服务端维护权威播放会话和时钟，
客户端使用 FFmpeg 在本地解码音视频，并将画面显示在连续的多方块屏幕上。本模组的目标
是在降低服务端开销的同时，为所有玩家提供流畅、稳定且同步的播放体验。

## 功能

- 服务端统一控制暂停、恢复、跳转、重连和中途加入玩家的播放时间。
- 支持墙面、地面与天花板屏幕，最大尺寸为 256 × 256 方块。
- 通过 FFmpeg 播放 HTTP(S) MP4、HLS 和 DASH 音视频分流。
- 同时提供 `/video` 命令和游戏内视频管理器 GUI。
- 支持硬件解码回退、有界帧缓冲、可配置缩放、RGB24/RGBA 和多通道帧传输。

## 环境要求

- Minecraft Forge 1.20.1
- Java 17
- 构建使用 Gradle 8.x；ForgeGradle 不兼容 Gradle 9
- 使用 `no-ffmpeg` 版本时，需要在 `PATH` 中提供 FFmpeg 和 ffprobe

Release 提供 Linux 与 Windows 的 AMD64、ARM64 版本。每个平台版本都内嵌对应的 BtbN
FFmpeg LGPL shared 分发包，首次启动时解压到
`<Minecraft 游戏目录>/video_synchronizer/ffmpeg/`。平台无关的 `no-ffmpeg` 版本改用
`PATH` 中的 `ffmpeg` 和 `ffprobe`。

## 快速开始

看向 32 格内已加载的墙面、地面或天花板并创建屏幕：

```text
/video create cinema 4 3
/video start demo https://example.com/video.mp4
```

朝向相同的相邻屏幕方块会组成一块连续画面。右键屏幕可绑定 ID；潜行右键可贴着已有
屏幕放置新屏幕。视频管理器放置后正面会朝向玩家，并能控制其他位置或维度中已加载的
屏幕。屏幕和管理器没有合成配方或掉落物，可通过创造模式物品栏、`/give` 或
`/video create` 获得。

所有玩家都可以使用状态命令，其他控制命令需要 2 级权限：

```text
/video create <屏幕ID> <宽度> <高度>
/video bind <屏幕ID>
/video unbind
/video start <视频ID> <HTTP或HTTPS地址>
/video pause
/video resume
/video seek <毫秒>
/video weight <玩家> <0.01-100>
/video status
/video status bossbar
/video stop
```

`/video status` 显示当前会话和本地播放报告；`/video status bossbar` 切换每位玩家独立的
同步状态栏。视频管理器使用 `时:分:秒` 格式设置跳转位置。

## 播放说明

- 新会话和显式跳转会等待至少 80% 的在线客户端准备好已验证画面后统一开始。玩家重连
  后会收到当前会话和屏幕布局。
- 新加入玩家只有在连续两次上报接近权威时钟后才会参与汇总，正常播放上报也不能让
  权威时钟倒退。
- 已开始播放的视频或音频一旦卡住，两路解码器会从当前同步位置一起重启，避免其中一路
  继续播放并造成音画时间线偏移。遇到致命 HTTP 响应时，会停止会话并显示状态码。
- 播放到已知媒体时长后，服务端会自动停止会话，并关闭所有客户端的 FFmpeg 进程。
- 鉴权媒体可配置请求头和 Cookie。这些内容会发送给所有客户端，也可能出现在本地进程
  参数中，请勿使用不应向在线玩家公开的凭据。常规日志不会记录敏感媒体信息。
- 默认缩放上限为 1920 × 1080、60 FPS。启用缩放时，输入视频单边不超过 4096 像素，
  总像素不超过 4096 × 2160。关闭缩放会直接输出源尺寸，可能显著增加带宽和内存占用。
- 硬件解码失败时自动回退到软件解码。音频为 48 kHz 双声道 PCM，并跟随 Minecraft
  主音量和唱片/唱片机音量。帧队列有界并优先保留最新有效数据。
- 默认原始输出为 RGB24，也可切换 RGBA。大帧可使用自动、1、2、4、8 或 16 条本地传输
  通道；选择 1 通道时使用 stdout 兼容路径。
- 多方块屏幕会保持正确朝向、连续 UV、宽高比和黑边。

## 配置

JVM 系统属性：

```text
-Dvideo_synchronizer.ffmpeg=C:\tools\ffmpeg\bin\ffmpeg.exe
-Dvideo_synchronizer.scaleVideo=false
-Dvideo_synchronizer.maxVideoWidth=1920
-Dvideo_synchronizer.maxVideoHeight=1080
-Dvideo_synchronizer.ffmpegHardware=false
-Dvideo_synchronizer.ffmpegCudaScale=true
-Dvideo_synchronizer.probeTimeoutSeconds=60
-Dvideo_synchronizer.videoPipeLanes=2
-Dvideo_synchronizer.videoPipeMinFrameBytes=4194304
-Dvideo_synchronizer.videoPipeSocketBufferBytes=4194304
-Dvideo_synchronizer.videoPipeAcceptTimeoutMs=10000
```

视频管理器中的缩放和通道设置会覆盖本次会话的客户端默认值。将 `videoPipeLanes` 设为
`1` 可使用 stdout 兼容路径，超过 16 的值会被限制。在未开放局域网的单人游戏中，
Esc 菜单会同时暂停解码器和服务端时钟；多人游戏仍由服务端控制播放。

## 开发

仓库包含 Gradle Wrapper 配置，但没有 `gradlew` 或 `gradlew.bat` 启动脚本。请使用
兼容的 Gradle 8.x：

```text
gradle compileJava
gradle build
gradle runClient
```

默认构建内嵌 Windows AMD64 FFmpeg。可通过以下参数选择发行版本：

```text
gradle build -PembeddedFfmpegPlatform=linux-aarch64
gradle build -PembeddedFfmpegPlatform=linux-x86_64
gradle build -PembeddedFfmpegPlatform=windows-aarch64
gradle build -PembeddedFfmpegPlatform=windows-x86_64
gradle build -PembeddedFfmpegPlatform=none
```

内嵌分发包使用固定 SHA-256 校验，并附带 LGPL v3 许可证和第三方来源说明。推送 `v*`
标签会触发 GitHub Actions，构建全部五个版本、生成 `SHA256SUMS` 并创建 GitHub Release。
运行测试后可检查 `run/logs/latest.log` 和 `run/logs/debug.log`。
