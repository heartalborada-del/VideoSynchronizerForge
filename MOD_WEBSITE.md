# Video Synchronizer

<p align="center">
  <img src="https://raw.githubusercontent.com/heartalborada-del/VideoSynchronizerForge/main/src/main/resources/video_synchronizer.png" alt="Video Synchronizer" width="240">
</p>

> Synchronized online video playback on continuous multi-block screens for Minecraft
> Forge 1.20.1.

Video Synchronizer lets a Minecraft server control one shared playback session while
every player decodes the media locally with FFmpeg. This keeps video traffic and decoding
work away from the server while preserving synchronized playback across all clients.

## Highlights

- Server-authoritative play, pause, seek, stop, reconnect, and late-join synchronization
- Continuous video walls, floors, and ceilings up to 256 × 256 blocks
- HTTP(S) MP4, HLS, and split DASH video/audio sources
- In-world Video Manager GUI and `/video` commands
- Automatic resolution and frame-rate limiting for high-resolution sources
- Hardware decoding with automatic software fallback
- Bounded frame buffering with RGB24/RGBA and multi-lane frame transfer
- Coordinated audio/video recovery if either stream stalls
- Automatic session cleanup when playback reaches the end
- English and Simplified Chinese interface

## Requirements

- Minecraft 1.20.1
- Forge
- Java 17

Modrinth lists five separate versions. Choose the suffix matching the client:

- `linux-amd64` or `linux-arm64`
- `windows-amd64` or `windows-arm64`
- `no-ffmpeg` when `ffmpeg` and `ffprobe` are already available on `PATH`

The platform builds include an LGPL shared FFmpeg distribution. Video decoding is
client-side, so the dedicated server may use any release variant.

## Quick start

Create a screen while looking at a loaded wall, floor, or ceiling within 32 blocks:

```text
/video create cinema 4 3
/video start demo https://example.com/video.mp4
```

Adjacent screen blocks with the same orientation form one continuous image. Right-click
a screen to bind its ID to a Video Manager. Playback controls require permission level 2;
`/video status` and `/video status bossbar` are available to every player.

## Notes

- All players must install the mod and use the same mod version.
- Media URLs and authentication options are sent to connected clients because decoding
  happens locally. Do not use credentials that players must not receive.
- Live streams without a known duration cannot use automatic end-of-playback stopping.
- The embedded FFmpeg packages use LGPL shared builds and include the corresponding
  license and source notices.

## Links and license

- [Source code](https://github.com/heartalborada-del/VideoSynchronizerForge)
- [Releases](https://github.com/heartalborada-del/VideoSynchronizerForge/releases)
- [Issue tracker](https://github.com/heartalborada-del/VideoSynchronizerForge/issues)

Video Synchronizer is MIT licensed. Bundled FFmpeg distributions remain under LGPL v3.

---

# Video Synchronizer 中文介绍

> 为 Minecraft Forge 1.20.1 提供多方块连续屏幕与在线音视频同步播放。

Video Synchronizer 由服务端维护统一的播放会话和权威时钟，各客户端使用 FFmpeg 在本地
完成媒体解码。视频流量和解码开销不会集中在服务端，同时所有玩家仍能看到同步的播放内容。

## 主要功能

- 服务端统一控制播放、暂停、跳转、停止、重连和中途加入同步
- 支持墙面、地面和天花板连续屏幕，最大 256 × 256 方块
- 支持 HTTP(S) MP4、HLS，以及 DASH 音视频分流地址
- 提供游戏内视频管理器 GUI 和 `/video` 命令
- 自动限制高分辨率视频的输出尺寸和帧率
- 支持硬件解码，并在失败时自动回退到软件解码
- 有界帧缓冲、RGB24/RGBA 输出与多通道帧传输
- 音频或视频任一路卡住时，两路会从同一同步位置恢复
- 播放到媒体结尾后自动停止并清理客户端进程
- 提供英文和简体中文界面

## 环境要求

- Minecraft 1.20.1
- Forge
- Java 17

Modrinth 会列出五个独立版本，请根据客户端选择对应后缀：

- `linux-amd64` 或 `linux-arm64`
- `windows-amd64` 或 `windows-arm64`
- 已在 `PATH` 中提供 `ffmpeg` 和 `ffprobe` 时选择 `no-ffmpeg`

平台版本内嵌 LGPL shared FFmpeg。媒体解码只在客户端进行，因此独立服务端可以使用
任意发布版本。

## 快速开始

看向 32 格内已加载的墙面、地面或天花板，然后执行：

```text
/video create cinema 4 3
/video start demo https://example.com/video.mp4
```

朝向相同的相邻屏幕方块会显示一张连续画面。右键屏幕可将其 ID 绑定到视频管理器。
播放控制需要 2 级权限；所有玩家都可使用 `/video status` 和
`/video status bossbar` 查看同步状态。

## 使用须知

- 所有玩家都需要安装本模组，并使用相同的模组版本。
- 媒体由客户端本地解码，因此媒体地址和鉴权选项会发送给在线客户端。请勿填写不应向
  玩家公开的凭据。
- 没有已知时长的直播流无法在播放结尾自动停止。
- 内嵌 FFmpeg 使用 LGPL shared 构建，并附带相应许可证与源码说明。

## 链接与许可证

- [源代码](https://github.com/heartalborada-del/VideoSynchronizerForge)
- [版本下载](https://github.com/heartalborada-del/VideoSynchronizerForge/releases)
- [问题反馈](https://github.com/heartalborada-del/VideoSynchronizerForge/issues)

Video Synchronizer 使用 MIT 许可证；内嵌 FFmpeg 分发包继续遵循 LGPL v3。
