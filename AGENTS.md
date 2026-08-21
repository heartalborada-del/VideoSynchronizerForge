# AGENTS.md

## Project

`VideoSynchronizer` is a Minecraft Forge 1.20.1 mod targeting Java 17. The server owns
the playback session and authoritative clock. Clients use FFmpeg CLI processes to decode
audio and RGB24/RGBA video, then upload frames to a dynamic texture shared by tiled screen
blocks.

Read `README.md` before changing player-visible behavior, commands, synchronization,
screen layout, or playback. Keep `README.md` and `README_zh_CN.md` equivalent and concise;
they are player-facing mod descriptions, not developer or CI manuals.

## Toolchain

- Do not compile, run tests, or launch Minecraft unless the user explicitly requests it.
- When authorized, use JDK 17 and Gradle 8.x. ForgeGradle is incompatible with Gradle 9.
- The repository has wrapper properties but no `gradlew` or `gradlew.bat`; use an installed
  compatible `gradle` executable.
- Playback tests require `ffmpeg` and `ffprobe` on `PATH`, unless the selected build embeds
  them.

Validation commands, from narrowest to broadest:

```text
gradle compileJava
gradle build
gradle runClient
```

Use `compileJava` for focused Java changes, `build` for resources, networking,
registration, mixins, or packaging, and `runClient` for rendering, FFmpeg, or end-to-end
synchronization. This guidance applies only after validation has been authorized.

## Code map

- `Main.java`: Forge entry point and registrations.
- `server/`: authoritative clock, reports, commands, screen creation, and registry.
- `network/`: packet records, codecs, handlers, and `SimpleChannel` registration.
- `client/ClientVideoState.java`: network-to-player state bridge.
- `client/player/`: embedded FFmpeg extraction, process lifecycle, audio output, and frame
  buffering.
- `client/render/`: render-thread texture upload and block-entity rendering.
- `client/gui/`: screen binding, playback controls, and media request editors.
- `block/`: screen orientation, layout, blocks, and block entities.
- `mixin/`: narrowly scoped render-pipeline accessors.
- `src/main/resources/assets/`: models, blockstates, textures, and translations.
- `.github/workflows/release.yml`: tagged multi-platform release publishing.

## General rules

- Follow the existing Java style: four-space indentation, same-line braces, descriptive
  names, and small single-purpose methods.
- Keep client-only Minecraft classes under client packages or guarded by `Dist.CLIENT` so
  a dedicated server never loads them.
- Update both `en_us.json` and `zh_cn.json` for every user-visible translation key.
- Treat media URLs, headers, Cookies, and signed query strings as sensitive. Never add
  routine logs containing their full values.
- Keep `MOD_WEBSITE.md` suitable for Modrinth or CurseForge; keep repository README files
  focused on installing and using the mod.

## Server and network

- Preserve the server-authoritative session and monotonic playing clock. Client reports
  are observations and must not directly replace server state or move a playing clock
  backward.
- Keep Minecraft state mutations on the logical main thread. Packet handlers use
  `consumerMainThread`; do not move these mutations to Netty threads.
- When packet fields or registration order change, update encode/decode together and
  increment `VideoNetwork.PROTOCOL`.
- Joining clients must complete clock-alignment warmup before their reports influence the
  weighted consensus.
- Natural playback completion must use the normal stop path so every client releases its
  decoder processes and texture resources.
- Dont upgrade the protocol version unless the server and all clients can handle the new packet layout. Never
  downgrade the protocol version unless the server and all clients can handle the old
  packet layout.
## FFmpeg and concurrency

- Never perform decoding, probing, blocking pipe I/O, or audio-device writes on the render
  thread.
- Drain FFmpeg stderr independently. Rawvideo stdout must contain frame bytes only; never
  merge stderr into stdout.
- Terminate the complete FFmpeg process tree on close, hard seek, coordinated recovery,
  session replacement, logout, and stop.
- Do not restart a decoder merely because a routine server snapshot arrived while its first
  frame is still pending. Restart only for an explicit hard seek, cancellation, or confirmed
  failure.
- If established audio or video stalls, recover both streams from the same synchronized
  position so one cannot continue on a different timeline.
- Keep frame queues bounded and prefer the newest useful frame. Reuse pooled arrays and
  large buffers rather than allocating one per frame.
- Probe source metadata separately from output dimensions. Downscale high-resolution input
  before emitting raw frames.
- Shared client/decoder state must consistently use synchronization, `volatile`, or atomic
  access according to the adapter's existing discipline.

## Rendering

- Create, upload, register, and release Minecraft textures on the render thread.
- Release pooled frame arrays in a `finally` block after every upload attempt.
- Preserve facing, tiled UV continuity, aspect-ratio fitting, and letterboxing.
- Validate wall, floor, and ceiling screens, including multi-block layouts.

## Releases

- `v*` tags build Linux and Windows AMD64/ARM64 JARs plus `no-ffmpeg` using Gradle 8.8.
- Embedded FFmpeg archives must remain pinned LGPL shared builds with verified SHA-256
  values, bundled license text, and source notices. Never switch to GPL or nonfree builds.
- Modrinth publishing uses the `MODRINTH_PROJECT_ID` repository variable and
  `MODRINTH_TOKEN` repository secret. Never put the token or other credentials in source,
  logs, workflow output, or release text.
- Publish the five build variants as separate suffixed Modrinth versions, each containing
  exactly one primary JAR. Supplemental files are for source or support material, never
  alternative operating-system or architecture builds.
- Run `actionlint .github/workflows/release.yml` after workflow edits when `actionlint` is
  available.
- Do not move or recreate a published tag unless the user explicitly requests it.

## Runtime checklist

When runtime validation is authorized, cover the relevant cases:

1. HTTP(S) MP4 reaches first-frame texture upload with synchronized audio.
2. HEVC 4K starts and is downscaled to the configured output limit.
3. Hardware decode failure falls back to software with a useful diagnostic.
4. Audio-only and video-only stalls trigger coordinated recovery without permanent drift.
5. Pause, resume, seek, stop, natural completion, replacement, logout, and reconnect leave
   no FFmpeg processes behind.
6. Wall, floor, and ceiling layouts render one continuous correctly oriented image.
7. A dedicated server starts without loading client-only classes.
8. Do not generate any test code without real client-side rendering, FFmpeg, or audio output.
9. Do not generate modification, diff, validation, or rollback artifacts.

Inspect `run/logs/latest.log` and `run/logs/debug.log` after runtime tests. Never commit
generated `run/`, `build/`, IDE, cache, or log files.

## Worktree safety

Assume unrelated user changes may exist. Do not reset, discard, stage, or rewrite work
outside the request. Prefer small patches, stage only intended files, and report what
changed.
