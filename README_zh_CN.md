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
- 支持硬件解码回退和高分辨率视频自动缩放。

## 环境要求

- Minecraft Forge 1.20.1
- Java 17
- 使用 `no-ffmpeg` 版本时，需要在 `PATH` 中提供 FFmpeg 和 ffprobe

请根据客户端系统选择 Linux 或 Windows 的 AMD64、ARM64 版本，这些版本已内嵌 FFmpeg。
平台无关的 `no-ffmpeg` 版本使用 `PATH` 中已有的 FFmpeg。Modrinth 将五种选择发布为
独立版本，请根据版本号中的平台后缀下载。

## 快速开始

看向 32 格内已加载的墙面、地面或天花板并创建屏幕：

```text
/video create cinema 4 3
/video start demo https://example.com/video.mp4
```

朝向相同的相邻屏幕方块会组成一块连续画面。右键屏幕可将其绑定到视频管理器。播放控制
需要 2 级权限，所有玩家都可以使用状态命令。

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

## 使用须知

- 新会话和跳转会等待大多数在线客户端准备完成；重连和中途加入的玩家会自动同步。
- 音频或视频卡住时，两路会从同一个同步位置重新开始。
- 已知媒体时长播放完毕后会自动停止；直播流需要手动停止。
- 媒体地址、请求头和 Cookie 会发送到客户端用于本地解码，请勿使用不应向在线玩家公开
  的凭据。
