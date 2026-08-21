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
- Craftable Screen Panels, Video Managers, and a reusable Screen Selection Tool support
  normal survival play without generating free screen blocks.
- Screen owners can grant playback or editing access through the Video Manager. Editable
  loaded screens can be selected from a distance-sorted list.
- Each player must allow a screen before its media session and request credentials are sent
  to that client.
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

Craft the survival components:

- Six Screen Panels: glass panes, black concrete, and redstone.
- Video Manager: a Screen Panel, comparators, iron ingots, and redstone.
- Screen Selection Tool: sticks, iron ingots, and a redstone torch.

Place a rectangular set of Screen Panels with the same orientation. Use the Screen Selection
Tool on two corners, then enter a screen ID. The tool only groups existing panels and never
creates blocks; sneak-use it to clear an unfinished selection. The creator becomes the owner.
Place a Video Manager, select an editable loaded screen from the distance-sorted list, save a
direct HTTP(S) media URL, and start playback.

The manager's Access screen grants other players Playback or Edit permission. Playback can
control saved media; Edit can also change URLs and settings. Access modes are Private (owner
only), Trusted (owner and grants), Public Control (everyone can control), and Public View
(everyone can inspect status but cannot control without a grant). New screens are Private.
Operators can manage every screen. Each logical screen stores one owner and permission set;
its panels only reference that logical screen.

Right-click a screen or its Video Manager to allow playback on the local client. Until the
player agrees, the server excludes that player from preload and clock consensus and does not
send the screen's media session. Sneak-right-click either block to review or revoke consent.
Screen owners consent automatically when the screen is created. Playback control permission
does not grant viewing consent on another player's behalf. The consent screen identifies the
screen owner and the player who initiated the current playback request.

```text
/video start <screen_id> <http_or_https_url>
/video pause <screen_id>
/video resume <screen_id>
/video seek <screen_id> <milliseconds>
/video stop <screen_id>
/video trust <screen_id> <player> editor|controller
/video untrust <screen_id> <player>
/video access <screen_id> private|trusted|public_control|public_view
/video transfer <screen_id> <player>
/video info <screen_id>
/video sync
/video status
/video status bossbar
```

`transfer`, global `/video stop`, player weights, global binding, and commands without a
screen ID remain administrator actions. `/video create <screen_id> <width> <height>` is an
administrator construction command; normal survival creation always consumes placed panels.

Servers can override the default player permissions through Forge permission nodes:
`video_synchronizer.create`, `video_synchronizer.bind`,
`video_synchronizer.edit_source`, `video_synchronizer.control`,
`video_synchronizer.remove`, and `video_synchronizer.admin`.

## Notes

- New sessions and seeks wait for most online clients to become ready. Reconnecting and
  late-joining players automatically synchronize to the active session.
- Clients validate both `ffmpeg` and `ffprobe` at startup. Clients that fail validation
  cannot play video and are excluded from preload thresholds and clock consensus.
- Clients first use a bounded metadata probe to avoid excessive startup buffering for long
  media, then automatically retry with full analysis when the quick result is incomplete.
- If audio or video stalls, both streams recover together. On-demand media restarts from
  its synchronized position; live media reconnects at the current live edge.
- During small forward synchronization corrections, current audio continues until video
  reaches the target, then audio restarts from the synchronized position.
- If FFmpeg receives HTTP 403, it retries from the session's original URL up to five times
  before reporting the HTTP error.
- `/video sync` is a client-only command that stops local video and audio, requests the
  current server-authoritative positions, and restarts local playback from those positions.
  For live media, it reconnects at the current live edge instead.
- Audio is positioned at the center of its screen. Each Video Manager stores its own cutoff
  range, which defaults to 48 blocks, and can instead use fixed-range audio. Global audio
  broadcasts at full volume in every dimension and can only be configured or started by an
  operator.
- In positional fading mode, clients outside the configured range do not start FFmpeg and
  release existing decoder, audio, frame, and texture resources. Re-entering the range loads
  the session at the current server-authoritative position.
- `/video status` lists sessions the player may view and marks positional sessions unloaded
  when out of range. Its Boss Bar follows only sessions the player should load.
- URLs must expose media streams that FFmpeg can read directly. If a video URL has no video
  stream, or a configured audio URL has no audio stream, playback stops and players see a
  bold red notice above the hotbar.
- Screen-specific control commands require `screen_id`; the no-ID pause, resume, seek, and
  stop forms are reserved for administrators and command-started sessions.
- Playback stops automatically at the known media duration. Live streams do not participate
  in playback-time consensus or drift seeking, disable seek controls, and continue until
  stopped manually.
- Media URLs, request headers, and Cookies are sent to clients for local decoding. Do not
  use credentials that connected players must not receive.
- By default, private, loopback, link-local, and local-network URL hosts are rejected, and
  non-admin players cannot receive or edit saved custom headers or Cookies. Server
  configuration controls the host allowlist, per-player screen/panel/session limits, and
  creation/playback cooldowns.
