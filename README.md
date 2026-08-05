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
- Hardware decoding with software fallback and automatic scaling for large videos.

## Requirements

- Minecraft Forge 1.20.1
- Java 17
- FFmpeg and ffprobe on `PATH` when using the `no-ffmpeg` build

Choose the release JAR matching the client's system: Linux or Windows on AMD64 or ARM64.
These builds include FFmpeg. The platform-independent `no-ffmpeg` JAR uses the FFmpeg
installation already available on `PATH`. Modrinth publishes all five choices as separate
versions identified by their platform suffix.

## Quick start

Look at a loaded wall, floor, or ceiling within 32 blocks and create a screen:

```text
/video create cinema 4 3
/video start demo https://example.com/video.mp4
```

Adjacent screen blocks with the same facing form one continuous display. Right-click a
screen to bind it to a Video Manager. Playback controls require permission level 2;
status commands are available to every player.

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

## Notes

- New sessions and seeks wait for most online clients to become ready. Reconnecting and
  late-joining players automatically synchronize to the active session.
- If audio or video stalls, both streams restart from the same synchronized position.
- Playback stops automatically at the known media duration. Live streams continue until
  stopped manually.
- Media URLs, request headers, and Cookies are sent to clients for local decoding. Do not
  use credentials that connected players must not receive.
