# Video Synchronizer

<p align="center">
  <img src="src/main/resources/video_synchronizer.png" alt="Video Synchronizer logo" width="240">
</p>

[简体中文](README_zh_CN.md)

A Minecraft Forge 1.20.1 mod for synchronized online video playback on continuous
multi-block screens. The server owns the playback session and clock; each client uses
FFmpeg to decode video and audio locally. Its goal is to minimize server overhead while
preserving a smooth and synchronized playback experience for every player.

## Features

- Server-authoritative pause, resume, seek, reconnect, and late-join synchronization.
- Continuous screens on walls, floors, and ceilings, up to 256 × 256 blocks.
- HTTP(S) MP4, HLS, and split DASH video/audio support through FFmpeg.
- Commands and an in-world Video Manager GUI for playback control.
- Hardware-decoding fallback, bounded frame buffers, configurable scaling, RGB24/RGBA
  output, and multi-lane raw-frame transfer.

## Requirements

- Minecraft Forge 1.20.1
- Java 17
- Gradle 8.x for building; ForgeGradle is incompatible with Gradle 9
- FFmpeg and ffprobe on `PATH` when using the `no-ffmpeg` build

Release JARs are provided for Linux and Windows on AMD64 and ARM64. Each platform build
contains the matching BtbN FFmpeg LGPL shared distribution, extracted on first launch to
`<Minecraft game directory>/video_synchronizer/ffmpeg/`. A platform-independent
`no-ffmpeg` JAR uses `ffmpeg` and `ffprobe` from `PATH` instead.

## Quick start

Look at a loaded wall, floor, or ceiling within 32 blocks and create a screen:

```text
/video create cinema 4 3
/video start demo https://example.com/video.mp4
```

Adjacent screen blocks with the same facing form one display. Right-click a screen to
bind its ID; sneak-right-click to place another screen against it. The Video Manager can
control a loaded screen in another location or dimension and faces the player when placed.
Screen and manager blocks have no recipe or loot and are available through the creative
tab, `/give`, or `/video create`.

Status commands are available to every player. Other controls require permission level 2:

```text
/video create <screen_id> <width> <height>
/video bind <screen_id>
/video unbind
/video start <video_id> <http_or_https_url>
/video pause
/video resume
/video seek <milliseconds>
/video weight <player> <0.01-100>
/video status
/video status bossbar
/video stop
```

`/video status` prints the current session and local playback report.
`/video status bossbar` toggles a per-player synchronization status bar. The Video
Manager accepts seek positions in `HH:MM:SS` format.

## Playback notes

- New sessions and explicit seeks start after a verified frame is ready on at least 80%
  of online clients. Reconnecting players receive the active session and screen layout.
- A joining player's progress is admitted only after two reports closely match the
  authoritative clock, and normal playback reports cannot move that clock backward.
- If established video or audio output stalls, both decoders restart together from the
  current synchronized position so one stream cannot continue drifting away from the other.
  Fatal HTTP responses stop the session and show the status code.
- Reaching the known media duration automatically stops the server session and closes every
  client's FFmpeg processes.
- Request headers and Cookies can be configured for authenticated sources. They are sent
  to every client and may appear in local process arguments, so do not use credentials
  that connected players must not receive. Sensitive media values are omitted from
  routine logs.
- Scaling defaults to at most 1920 × 1080 and 60 FPS. With scaling enabled, sources are
  limited to 4096 pixels per axis and 4096 × 2160 total pixels. Disabling scaling outputs
  the probed source size directly and can greatly increase bandwidth and memory usage.
- Hardware decoding falls back to software. Audio is 48 kHz stereo PCM and follows the
  Minecraft master and records volume controls. Frame queues remain bounded and prefer
  the newest useful data.
- RGB24 is the default raw output; RGBA is available for compatibility. Large frames can
  use Auto, 1, 2, 4, 8, or 16 local transfer lanes, with stdout used for the 1-lane mode.
- Screens preserve orientation, continuous UVs, aspect ratio, and letterboxing across
  multi-block layouts.

## Configuration

JVM properties:

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

The Video Manager scaling and lane settings override client defaults for that session.
Setting `videoPipeLanes=1` selects the stdout compatibility path; values above 16 are
capped. In an unpublished single-player world, the Esc menu pauses both the decoder and
server clock. Multiplayer playback remains controlled by the server.

## Development

The repository contains Gradle wrapper properties but no `gradlew` launcher scripts.
Use a compatible Gradle 8.x executable:

```text
gradle compileJava
gradle build
gradle runClient
```

The default build embeds Windows AMD64 FFmpeg. Select a release variant with:

```text
gradle build -PembeddedFfmpegPlatform=linux-aarch64
gradle build -PembeddedFfmpegPlatform=linux-x86_64
gradle build -PembeddedFfmpegPlatform=windows-aarch64
gradle build -PembeddedFfmpegPlatform=windows-x86_64
gradle build -PembeddedFfmpegPlatform=none
```

Embedded archives use pinned SHA-256 values and include the LGPL v3 license and
third-party notice. Pushing a `v*` tag runs the GitHub Actions release workflow, builds
all five variants, writes `SHA256SUMS`, and creates a GitHub Release. For runtime checks,
inspect `run/logs/latest.log` and `run/logs/debug.log`.
