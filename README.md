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

- Server-authoritative pause, resume, seek, reconnect, and late-join synchronization for
  on-demand media; live streams play independently at each client's current live edge.
- Continuous screens on walls, floors, and ceilings, up to 1024 x 1024 blocks.
- Independent screens can run simultaneous playback sessions through their Video Managers.
- HTTP(S) MP4, HLS, and split DASH video/audio support through FFmpeg.
- Screen audio supports positional fading, fixed volume within a configurable 1-1024 block
  range, or full-volume server-wide broadcast.
- Commands and an in-world Video Manager GUI for playback control.
- Batch text import for HTTP request headers and Cookies in the Video Manager.
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

Alternatively, use the Screen Selection Tool on two corners of the same surface, then enter
the screen ID in the binding screen. Sneak-use the tool to clear an unfinished selection.

Adjacent screen blocks with the same facing form one continuous display. Right-click a
screen to bind it to a Video Manager. Playback controls require permission level 2;
status commands are available to every player. Starting playback from different Video
Managers keeps those screen sessions independent.

```text
/video create <screen_id> <width> <height>
/video bind <screen_id>
/video unbind
/video start <video_id> <http_or_https_url>
/video pause
/video resume
/video seek <milliseconds>
/video sync
/video weight <player> <0.01-100>
/video status
/video status bossbar
/video stop
```

## Notes

- New sessions and seeks wait for most online clients to become ready. Reconnecting and
  late-joining players automatically synchronize to the active session.
- Clients validate both `ffmpeg` and `ffprobe` at startup. Clients that fail validation
  cannot play video and are excluded from preload thresholds and clock consensus.
- If audio or video stalls, both streams recover together. On-demand media restarts from
  its synchronized position; live media reconnects at the current live edge.
- `/video sync` is a client-only command that stops local video and audio, requests the
  current server-authoritative positions, and restarts local playback from those positions.
  For live media, it reconnects at the current live edge instead.
- Audio is positioned at the center of its screen. Each Video Manager stores its own cutoff
  range, which defaults to 48 blocks, and can instead use fixed-range or global audio.
- In positional fading mode, clients outside the configured range do not start FFmpeg and
  release existing decoder, audio, frame, and texture resources. Re-entering the range loads
  the session at the current server-authoritative position.
- `/video status` lists every session and marks positional sessions unloaded for the local
  player when out of range. Its Boss Bar follows only sessions the player should load.
- URLs must expose media streams that FFmpeg can read directly. If a video URL has no video
  stream, or a configured audio URL has no audio stream, playback stops and players see a
  bold red notice above the hotbar.
- `/video stop` stops all active sessions; a Video Manager stops only its own screen.
- `/video pause`, `resume`, and `seek` control the command-started session; use each
  Video Manager to control independent screen sessions.
- Playback stops automatically at the known media duration. Live streams do not participate
  in playback-time consensus or drift seeking, disable seek controls, and continue until
  stopped manually.
- Media URLs, request headers, and Cookies are sent to clients for local decoding. Do not
  use credentials that connected players must not receive.
