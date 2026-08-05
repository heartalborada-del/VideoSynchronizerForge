## Downloads / 下载

Choose exactly one JAR for each client. All variants contain the same mod code and use
the same network protocol.

每个客户端只需选择一个 JAR。所有版本包含相同的模组代码，并使用相同的网络协议。

| File suffix / 文件后缀 | System / 系统 | Architecture / 架构 | FFmpeg |
| --- | --- | --- | --- |
| `linux-amd64` | Linux | AMD64 / x86_64 | Embedded LGPL shared build / 内嵌 LGPL shared 版本 |
| `linux-arm64` | Linux | ARM64 / AArch64 | Embedded LGPL shared build / 内嵌 LGPL shared 版本 |
| `windows-amd64` | Windows | AMD64 / x86_64 | Embedded LGPL shared build / 内嵌 LGPL shared 版本 |
| `windows-arm64` | Windows | ARM64 / AArch64 | Embedded LGPL shared build / 内嵌 LGPL shared 版本 |
| `no-ffmpeg` | Any supported system / 任意受支持系统 | Any / 任意 | Requires FFmpeg on `PATH` / 需要在 `PATH` 中提供 FFmpeg |

The server can use any variant because media decoding is client-side. For a mixed-OS
player group, distribute the matching JAR to each client, or use `no-ffmpeg` everywhere
and install FFmpeg separately.

服务端可使用任意版本，因为媒体解码在客户端完成。如果玩家使用不同操作系统，请向每个
客户端分发对应平台的 JAR；也可以统一使用 `no-ffmpeg`，并单独安装 FFmpeg。

## Requirements / 环境要求

- Minecraft Forge 1.20.1
- Java 17
- FFmpeg and ffprobe on `PATH` when using `no-ffmpeg`
- 使用 `no-ffmpeg` 时，需要在 `PATH` 中提供 FFmpeg 和 ffprobe

## FFmpeg licensing / FFmpeg 许可

The four platform builds contain an unmodified BtbN FFmpeg **LGPL shared** distribution.
Each JAR includes the corresponding LGPL v3 license and third-party source notice. The
`no-ffmpeg` JAR does not distribute FFmpeg. Video Synchronizer itself is MIT licensed.

四个平台版本均包含未经修改的 BtbN FFmpeg **LGPL shared** 分发包。每个 JAR 都附带对应
的 LGPL v3 许可证和第三方来源说明；`no-ffmpeg` 不分发 FFmpeg。Video Synchronizer
本身使用 MIT 许可证。

- [Bundled BtbN FFmpeg release / 内嵌的 BtbN FFmpeg 版本](https://github.com/BtbN/FFmpeg-Builds/releases/tag/autobuild-2026-08-04-21-26)
- [Pinned BtbN build scripts / 固定版本的 BtbN 构建脚本](https://github.com/BtbN/FFmpeg-Builds/tree/f596dc82d2710c555b74d0584e52e03fc0fd039d)
- [Bundled FFmpeg source revision / 内嵌 FFmpeg 对应源码](https://github.com/FFmpeg/FFmpeg/tree/1fdbca85aa)

Use `SHA256SUMS` to verify downloaded files.

请使用 `SHA256SUMS` 校验下载文件。
