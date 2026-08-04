# Video Synchronizer

Forge 1.20.1 server-authoritative video synchronization with a client-side FFmpeg
decoder and a placeable screen block.

## Server synchronization

The server owns a session id, video id, HTTP(S) media URL, detected duration,
playback state, and authoritative clock. Clients report position and detected duration
every 10 ticks. The server
removes reports from disconnected players immediately and expires reports after 100
ticks without an update. A reconnect receives a complete session snapshot. Playback
state is changed only by the server commands `/video pause` and `/video resume`.
The authoritative playback clock uses monotonic elapsed time, so video speed does not
change when the integrated or dedicated server runs above or below 20 ticks per second.
An already-running client also keeps its monotonic clock between routine state snapshots;
only playback-state changes, explicit seeks, or confirmed large drift replace its anchor.
While a session is active, every player sees a bold, color-coded local playback position
and duration above the hotbar. The overlay remains visible while paused and clears on stop.
When an explicit seek or confirmed drift changes the local position, the overlay shows
the bold, color-coded `previous -> server` transition for two seconds.

The server rejects invalid durations, positions outside the reported video duration,
reports more than 15 seconds from its current clock, and jumps larger than the
per-report limit. The authoritative duration is the median duration reported by valid
clients. Before averaging,
it removes samples more than `max(10 seconds, duration / 20)` from the population median
(capped at 120 seconds). The remaining samples are averaged with server-owned player
weights. A weight defaults to `1.0` and can only be changed by an operator.

## Commands

```text
/video start <video_id> <url>
/video create <screen_id> <width> <height>
/video bind <screen_id>
/video unbind
/video pause
/video resume
/video seek <milliseconds>
/video weight <player> <0.01-100>
/video status
/video stop
```

All commands require permission level 2. The URL must be an absolute HTTP or HTTPS URL.

To choose a display before playback, an operator can right-click any block in the
rectangular screen, enter its screen ID in the binding GUI, and select Bind. The same
screen can later be selected by ID from a command:

```text
/video bind lobby
/video start demo "https://example.com/video.mp4"
```

Only the bound screen group renders the video. `/video unbind` clears the current
screen texture and stops all screen rendering until a screen is bound again.
Sneak-right-click a screen when placing another screen against it; normal right-click
is reserved for the binding GUI.

Operators can also place a Video Manager block and right-click it to control a named
screen remotely. Enter the screen ID and an absolute HTTP(S) media URL, then select
Save Target or Start Playback. The same GUI shows the current position and duration,
and provides pause, resume, millisecond seek, and stop controls. The manager remembers
its screen ID and URL across world reloads. A target screen may be in another location
or dimension, but its chunk and dimension must be loaded so the server can resolve it.
Manager controls require permission level 2 and only affect the active session when it
is bound to that manager's saved screen. The Video Manager has no crafting recipe; it
is available from the Video Synchronizer creative tab or with `/give`. It has hardness
`-1`, has no block loot, and cannot be broken after placement. Its block and item use a
custom 16 by 16 pixel-art media-console texture.

Alternatively, look at a wall, floor, or ceiling within 32 blocks and create an oriented screen:

```text
/video create lobby 4 3
```

The server raycasts from the player's eyes, places the screen immediately outside the
hit surface, uses that face as the display direction, assigns the requested screen ID,
and binds the new screen group. On a wall, `width` expands horizontally and `height`
expands vertically. Looking at the top or bottom of a block creates a horizontal floor
or ceiling screen instead. Floor and ceiling screens use the player's horizontal look
direction as the top of the video image, so horizontal multi-block screens keep a stable
orientation. The complete target area must already be in loaded server chunks.

## Screen block

`video_synchronizer:screen` has no recipe or block loot. It is available only from the
Video Synchronizer creative tab, `/give`, or `/video create`. It has hardness `-1` and
cannot be broken after placement. Place screens with the same facing in a rectangular,
hole-free area (maximum 256 by 256 blocks). Adjacent blocks automatically form one
display. A one-pixel gray border appears only along edges without a connected screen;
the same gray trim wraps around the exposed 2/16-block side depth. Internal front and
side borders disappear automatically when another screen connects. All border geometry
is omitted from the active video render.

The server sends the rectangle origin, facing, screen-up direction, width, and height to
every client, including players who join during playback. The client intersects that
authoritative rectangle with Minecraft's 16 by 16 by 16 render sections. Each loaded
section submits at most one continuous video quad with UVs calculated from the complete
screen, preserving orientation, aspect fitting, and letterboxing across section seams.
Screen block entities cache their render-section bounding box once. The renderer removes
Minecraft's default 64-block block-entity cutoff, but rendering is still limited by the
client's chunk render distance because unloaded chunks have no screen data to draw.
Without active video rendering, every screen surface is solid black. The renderer
preserves the video aspect ratio and uses black letterboxing when necessary.

While a playback session is active, including while paused, players and explosions
cannot destroy the bound screen group or a Video Manager configured for that screen;
Forge entity-destruction events are canceled as well. This session protection is
server-authoritative and therefore also applies to players who join during playback.

## FFmpeg playback

The client adapter is `FfmpegPlaybackAdapter.INSTANCE`. FFmpeg reads the HTTP(S) URL
directly and decodes it while data arrives; playback no longer waits for a complete
download or writes a media cache. HTTP reads use a 15-second timeout, reuse persistent
connections for range requests, read ahead up to 1 MiB for nearby byte seeks, and
reconnect automatically after a temporary disconnect. Long jumps use input-side FFmpeg
seeking so a progressive MP4 is fetched from the required byte range when the server
supports HTTP Range. FFmpeg-supported streaming formats such as HLS can also be used.

The duration is detected automatically with `ffprobe`; it is not supplied to
`/video start`. Sources larger than 4K (more than 4096 pixels on either axis or more
than `4096x2160` total pixels) are rejected before decoding. Accepted sources retain
their frame rate up to 60 FPS and are scaled to a maximum of 1920x1080. Playback uses
generic `-hwaccel auto` and falls back to software decoding. CUDA GPU scaling is
available as an opt-in with `-Dvideo_synchronizer.ffmpegCudaScale=true`.
Sources already within the output size and frame-rate limits bypass FFmpeg's scale and
FPS filters; pixel-format conversion to RGBA is still required for texture upload.
The client playback clock remains frozen while FFmpeg starts. It begins at the media
position of the first frame actually uploaded for rendering, and synchronized audio
waits for that same point. Decoding then continues without automatic process restarts.
For an explicit hard seek or clock correction, the current audio and video streams keep
playing while replacement FFmpeg processes seek in the background. The replacement
video process retains its first complete RGBA frame and the replacement audio process
retains its first PCM block. Once both are ready, the client switches the two process
streams together, uploads the prepared video frame, and starts both tracks from the new
clock anchor. A newer distant correction cancels older preparation. If preparation takes
longer than five seconds, playback falls back to terminating the old processes and
performing a normal hard seek. Client progress observations are withheld during this
short preparation window so the still-visible old stream cannot pull the authoritative
server clock away from the requested seek target.
The decoded-frame buffer keeps only the newest available frame. When decoding outpaces
rendering, newer frames replace queued intermediate frames so the next render upload catches
up without limiting 1080p playback to a fixed refresh interval. At most one video frame is
uploaded per Minecraft render frame, including on multi-block displays. Texture uploads use two
reusable OpenGL pixel buffers so GPU transfer can overlap rendering; a direct upload
remains as a compatibility fallback.
When DEBUG logging is enabled, playback writes ten-second aggregate diagnostics for video
decode rate and pipe throughput, frame-buffer replacement, active render uploads and CPU
upload time, clock drift, seeks, FFmpeg process lifetime, and synchronized audio. Idle
render intervals are omitted, while state changes are logged immediately. Media URLs are
deliberately omitted from these diagnostics.
FFmpeg and ffprobe must be available on the client PATH, or the executable can be
configured with:

```text
-Dvideo_synchronizer.ffmpeg=C:\tools\ffmpeg\bin\ffmpeg.exe
```

Scaling can be disabled with `-Dvideo_synchronizer.scaleVideo=false`. Its output limit
can be changed with
`-Dvideo_synchronizer.maxVideoWidth=<pixels>` and
`-Dvideo_synchronizer.maxVideoHeight=<pixels>`. The local decoder clock freezes with
the Esc pause screen only in a local single-player world. On a dedicated or multiplayer
server, opening Esc does not pause media; only `/video pause` and `/video resume`
change playback state.

When the source contains an audio track, a second FFmpeg process streams it as 48 kHz
stereo PCM to the client's default Java Sound output. This works without a
platform-specific player on Windows, Linux, and macOS. Every client seeks from the
server-authoritative position and discards stale startup samples. During normal playback,
the client aligns the first PCM block with the shared clock, then keeps the Java Sound
buffer fed continuously and measures drift from the audio device clock. Normal drift
observations do not pause the output line or restart FFmpeg. Minecraft's master and
records volume settings are applied. A single-player Esc pause, `/video pause`, explicit seeking,
stopping, and replacing the session restart or control video and audio together. A
hardware or CUDA mode that fails is skipped for later seeks in the same session. If no
compatible audio output is available, video playback continues and the client logs a
warning.

Hardware probing can be disabled with `-Dvideo_synchronizer.ffmpegHardware=false`.
Remote metadata probing waits up to 60 seconds by default. Slow sources can use
`-Dvideo_synchronizer.probeTimeoutSeconds=<seconds>` to increase or reduce that limit.

The player bridge is `ClientVideoState.PlaybackAdapter`. The built-in client setup
registers the FFmpeg adapter automatically. A different decoder can be registered with
`ClientVideoState.setPlaybackAdapter(...)`.
