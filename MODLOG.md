# MODLOG

## 2026-10-10: GTA V Enhanced + creative flight / elytra

**Machine:** GTA V Enhanced 1.0.1158.16 (Steam, `E:\SteamLibrary\...\Grand Theft Auto V Enhanced`). GTA V Legacy is
also installed on `D:`. ScriptHookV 3889.0 (Enhanced build 1158.13), the Enhanced ASI loader `xinput1_4.dll`, ReShade
6.8.0 as `dxgi.dll`, and VS 18 Community. No WSL.

**Enhanced (what the 10/9 logs showed):** the Enhanced loader loaded `MCPassthrough.asi`, and ScriptHookV started
it. ReShade under DX12 registered the add-on and compiled MCPassthrough.fx, and the add-on printed "connected to
Minecraft's frame export". The plugin code isn't tied to either edition (ScriptHookV keeps the native hashes). Only
the tooling was Legacy-only: `GTA5.exe`, app 271590, `dinput8.dll` and ReShade64.asi.
- Added Windows-native `gta/fetch_deps.ps1` and `gta/install.ps1`, both edition-aware. `install.sh` and
  `fetch_deps.sh` are now edition-aware too.
- `build.bat` passes `-prerelease` to vswhere: this VS 18 is only listed that way.
- The video helpers detect `GTA5_Enhanced.exe`.
- Still to check in game: DX12 depth under DLSS/FSR (debug view "GTA depth (1 m bands)"). Frame generation must be off.

**Flight (script.cpp only; the Minecraft mod is unchanged):**
- Creative flight is flown on the GTA side: the ped is frozen and moved with Minecraft's flying numbers (10.9 / 21.8
  m/s, 7.5 m/s up or down, drag 0.91 and 0.6 per tick). LOS probes at feet, waist and head stop it against the map,
  cars and the block props. Minecraft just follows the ped, as when walking.
- Space works as in Minecraft: a double tap within 350 ms toggles flight, and a tap after more than 150 ms in the
  air opens the elytra. The player's elytra is armed by default (`g_drive.user`). Long falls open it only when the
  director arms `armdrive user:1` (`autoOpen`). The director's `drive` op sets `user=false` for its shot.
- Gliding into a wall (map or vehicles, normal.z < 0.7) ends the glide there; flat hits land.
- The four new natives were checked against alloc8or's natives.json.

**Mod jar (2026-10-11):** `mc\gradlew.bat build` with JDK 25.0.4 failed with "Unable to establish loopback
connection". Java's NIO pipe uses AF_UNIX sockets, and connecting them under the user Temp folder gave "Invalid
argument". `JAVA_TOOL_OPTIONS=-Djdk.net.unixdomain.tmpdir=C:\jdkaf` (any short folder off a drive root) fixed it.
`make_dist.ps1` assembled `..\MCPassthrough-Mods`.

**2026-10-11: Minecraft input, all hostiles, Steve ghosting:**
- **Input.** Minecraft 26.3 uses SDL: InputConstants are USB HID scancodes (A=4, E=8, Esc=41, LCtrl=224), mouse
  buttons 1/2/3, mods SDL bits.
  - `HostInput` calls `KeyboardHandler.keyPress/charTyped` and `MouseHandler.onMove/onButton/onScroll` with
    Minecraft's own window handle.
  - `MouseHandlerMixin` redirects `isWindowActive` in `handleAccumulatedMovement` only, so hover and drag work
    without focus and Minecraft never grabs the cursor.
  - The script's keyboard handler queues keys: E Q T / X(=F) always on foot, everything while Minecraft reports a
    `screen`.
  - Screen mode: DisableAllControlActions(0..2), SET_MOUSE_CURSOR_THIS_FRAME, cursor from controls 239/240.
- **Mobs.** Every Mob+Enemy fights now. Brain mobs are driven through WALK_TARGET/LOOK_TARGET, with doHurtTarget
  in reach and breezes shooting BreezeWindCharge.
- **Ghosting** (user report: several Steves, blocks and arrows smearing in third person).
  - Minecraft writes the player position into slot desc +104..+120.
  - The compositor sends SteveDelta/SteveAt/SteveUp, and the shader marches twice: Steve shifted by his own motion,
    then the world with Steve's stale image rejected.
  - Also a "Pose lag" slider (ring of 8 poses).
- **Shader check.** Compiled clean in ReShade 6.8.0 via fakegta. That run wrote test blocks into the live world;
  undone, see memory.

**2026-10-11 noon, the Steve smear** (screenshot: Steve's head stair-stepped sideways, the held TNT repeated 3-4x).
- **Cause:** the re-projection march took the first sample behind *any* Minecraft surface, with infinite thickness.
  Rays passing behind Steve or his item hit him anyway, smearing him along the camera's motion. The fixed-point
  refine and 24 log steps made it stair-step.
- **Fix:** 32 steps, then a 6-step bisection to the crossing. A crossing counts only if the ray is within
  0.12 + 0.02·z m of the surface there; otherwise marching goes on. The Steve pass runs only for rays within 1.4 m
  of him.
- Hot-swapped into running GTA (the add-on reloads MCPassthrough.fx when it changes); compiled clean.

**2026-10-11 afternoon, "every visual lags":**
- **Measured live** (shared memory read-only; the link listened to): GTA ~30 fps; Minecraft renders ~100 fps but
  publishes 54; capture→publish 18 ms.
- **settings.xml:** all ray tracing at quality 3, native 1440p (`ResScalingType 0`), TAA, Reflex on,
  frame generation off.
- **Changes:** Enhanced defaults to pose lag 1, F9 cycles 0-3, and the plugin sends `hostfps` so Minecraft caps
  itself at 1.25x GTA's fps (40-120).
- **Advised:** DLSS Quality, and trimming RT GI and reflections to reach 60 fps.

**2026-10-11 evening, the overhaul spec** (the user pasted a long requirements document; done here is what can be built and
checked; FEATURES.md has the status of everything).
- **Environment sync.** `EnvSync` (server) watches the clock (`getOverworldClockTime`), `isRaining` / `isThundering`
  and `GameRules.ADVANCE_TIME` / `ADVANCE_WEATHER`.
  - It sends `env` with seq and origin: a jump eases GTA's clock, and a running clock is followed once a second.
  - The mod's own commands go through `WorldBridge.command`, which re-takes the baseline instead.
  - In GTA, `env_message` / `env_tick` use SET_CLOCK_TIME, PAUSE_CLOCK and SET_WEATHER_TYPE_OVERTIME_PERSIST, and are
    ignored while the Nether is open.
- **Lighting.** `LightmapMixin` forces skyFactor to 1 at extract TAIL (26.3: LightmapRenderStateExtractor, environment
  attributes), so a Minecraft night doesn't darken twice.
- **Config.** `Config`: config/passthrough.properties, validated, with defaults written back.
- **Frames.** The compositor keeps two texture sets, and the back set is shown only if the slot's seq survived the copy.
  Before this, a torn frame was copied into the shown textures and only then refused. A frame that stops for 1.5 s is
  treated as stale. Stats are kept.
- **Overlay.** F10 overlay with GTA text natives.
- **Disconnect.** `KeyMapping.releaseAll` plus mouse release when the host disconnects.
- **Link.** WebSocket frames are capped at 4 MB (`Draft_6455`).
- **Dev-client end-to-end test** (`runClient`, a PowerShell WebSocket script as host, input only through
  rawkey/char): every step passed. /weather thunder arrives as rain, then thunder (Minecraft ramps it). The script is
  `host/e2e_test.ps1`. Run it only against the dev client, never your real world: it types commands into chat.

**2026-10-11, blocks see-through and dropping out while looking around** (user report).
- **Cause:** found in the 26.3 bytecode. `RenderSection.getVisibility(now, fade)` = (now − uploadedTime) / fade.
  `uploadedTime` is reset by `SectionOcclusionGraph.runUpdates` (a section back in the graph) and `setSectionMesh`
  (every rebuild). In the passthrough both happen constantly: the camera swings, and barrier columns rebuild sections.
- **Fix:** `LevelRendererMixin` redirects the `chunkSectionFadeInTime` reads in `extractSectionDrawGroups` and
  `render` to 0 while attached. `-Dpassthrough.keepFadeIn=true` restores the fade, to compare.
- **Also:** `render.overscan` 1.12 (`HostState.renderFov` everywhere, written to desc +40; the compositor's tanHost is
  now always the host's fov). Crisp texel sampling (`sample_crisp`) for world and overlay. Haze toward the global mean
  colour from 40 m, instead of the blurred background behind (which read as see-through). Tint from a wider blur.
  EdgeSoftness 0.35.
- **Shader** hot-swapped into running GTA at 13:36 and compiled clean. The jar and asi wait until the games are closed.
  `host/frame_test.ps1` (dev client only) measures non-opaque block pixels while spinning; to be run when the games
  are closed.

**2026-10-11 late, master-spec round (measured, dev client).**
- **Fade:** the fade-in theory was corrected. terrain.fsh mixes toward the fog colour (black here) beyond
  chunkFullyVisibleRange; it never touches alpha (0.00% non-opaque in 736 frames, fade on or off). The fix stays and
  removes black flashes on distant blocks.
- **Torn slots:** found by measurement, 4 of 259 reads torn. Fixed with a reader slot at header +48: the compositor
  maps the header read/write (InterlockedExchange), and FrameExporter skips that slot. After: 0 of 257. The compositor
  also validates the header, dimensions and pose.
- **Poses:** `mv` bits from GTA (IS_PED_SPRINTING, GET_PED_STEALTH_MOVEMENT / IS_PED_DUCKING, IS_PED_SWIMMING(_UNDER_WATER),
  IS_PED_SITTING_IN_ANY_VEHICLE, IS_PED_CLIMBING) are applied in AvatarRendererMixin as isCrouching, swimAmount (eased)
  and isPassenger. Frames checked: standing, crouching, swimming and seated all render as one whole model.
- **Updater:** backup, verify and rollback, plus a log. The rollback was tested with -SimulateFailure.

**2026-10-11 16:07, the toolkit folders were added** (skills/, knowledge/, .claude/ and others; no `um` CLI; git not
installed).
- **Read:** knowledge/games/gta-v/minecraft-passthrough.md (the author's note: pose lag 1 measured;
  gotcha 16, a stale overlay over the pause menu), frame-compositing-depth-and-pose-sync.md,
  d3d12-frame-readback-buffers-only.md, and the mod-any-game skill and safety rules.
- **Built, not installed:** the pause-menu fix. The compositor hides Minecraft when no host pose has arrived for
  250 ms, because the pause menu stops ScriptHookV scripts. The games were running, so the updater refused; it
  installs with the next update .bat run.
- **Still to do:**
  - a token on the WebSocket (the safety rule asks for one);
  - updating the knowledge note with these Enhanced findings (publishing only with the user's OK);
  - `.claude/settings.json` runs `hooks/add-to-path.sh`, which isn't in this folder.

**2026-10-11 21:36, EldenCraft's low-latency design** (the user's `..\EldenCraft-main`, MIT, Adam Grzegorzewski).
- **Why it lags less:**
  1. Shared GPU textures (ECGT): GL imports D3D12 shared resources and a fence, so there's no CPU readback.
  2. Native blocks: baked block meshes are drawn by the compositor with the host camera at Present.
  3. A per-Present camera history.
- **Phase A done (GPU frames):**
  - Ported: GpuTransport.java (3 planes; the control block is `Local\MCPassthroughGpuFrame`, magic MCGT) and
    gta/src/gpu_host.{h,cpp} (D3D12 only; the add-on copies into its back set, fenced, and acks after the copy).
  - The MCPT slot gets flag 64, with gen/set at 1024 + 32·slot.
  - Once GPU frames are active, the script sizes Minecraft to GTA's full back buffer.
  - Test `gta/tests/gpu_frames_test.exe` (a D3D12 stand-in host) against the dev client: PASS, 1309 of 1309 frames
    with content, fence wait 0.06 ms. The frame image was checked.
  - Installed with update_games (UPDATE OK). Not yet run in GTA.
- **Phase B, not started (native blocks):** BlockMeshClient (bakes meshes, atlas, lightmap, animated sprites),
  block_renderer.cpp (draws with the host camera and depth, sorts translucents), and leaving chunk geometry out of
  the RGB-D capture once a mesh covers the view. This is the step that makes blocks fully lag-free.

**Installed:** `install.ps1 -Edition Enhanced`. The files it replaced are in `gta/build/game_backup_20261010/`.

**2026-10-11 22:40, Phase C: Steve in a layer of his own, see-through fix** (user: "i can still see trough blocks like
they are samll transparent ... the steve also is samll transparetn ... see your own skin and the armor ... tearing apart
on the head").
- **What was measured first:**
  - Minecraft's world and avatar layers are 100% opaque (0.00% non-opaque pixels), so the see-through look came from
    the effect.
  - The effect's lighting match took each block's brightness and colour from GTA's picture blurred over about 37 px,
    so the shapes behind a block showed through it.
  - The head tearing: Steve was cut out of the world picture with a 1.84 m box round his middle. His head, helmet and
    hat stuck out of it, so they were re-projected with the world and left behind.
- **Ported from EldenCraft (MIT):**
  - HostAvatarRenderer.java: the local player drawn alone with vanilla's renderer, the world pass's camera and lens.
  - AvatarLayerMixin: the world pass leaves the player out (from SceneLevelRendererMixin).
  - The effect's camera-relative avatar sample (from EcAvatar).
  - The transport carries 5 planes: GPU planes 3 and 4, slot layers 3 and 4, slot flag 32. The CPU slots are
    capped at 2560x1440 (5 layers, 221 MB), and GPU frames still go up to 4K.
  - An add-on with 3 planes keeps Steve in the world.
- **Also:**
  - Own skin by default (render.ownSkin).
  - Light blur mip 3.2 → 4.6 with narrower gain and tint, premultiplied haze, edge softening 0.15.
  - The avatar is kept out of the bloom.
  - Debug views 4 to 6 for the avatar layer.
- **Tests (dev client only; GTA and Modrinth Minecraft were closed):**
  - pose_test: every pose whole, with the helmet and chestplate.
  - gpu_frames_test: PASS with 5 planes (941 of 941 frames with avatar) and with 3 planes (927 frames, none).
  - frame_test: 0.00% non-opaque in 232 frames.
  - fakegta third person (new `third` mode plus BMP screenshots): Steve solid in every shot while walking and while
    the camera swings, ordered right against GTA and the blocks.
  - Found and fixed on the way:
    - A host that left with 3 planes kept the avatar layer off on the CPU path (now it follows the path of the
      newest capture).
    - fakegta's clear (`fill` of 63k blocks) was over Minecraft's 32,768 limit, so it silently failed and earlier
      tests' blocks buried the player.
- **Still not ported (Phase B):** EldenCraft's native block meshes. Blocks are still re-projected RGB-D, so GTA can
  show for a frame where a turn uncovers what Minecraft didn't draw.

**2026-10-12, Phase B: native blocks (EldenCraft's design, ported), and three bugs** (user: "make like 1:1 so its works
prefectly also fixs any bugs you find").
- **Ported (MIT, credited in THIRD_PARTY_NOTICES.md):**
  - Minecraft side: BlockMeshClient (vanilla ModelBlockRenderer/FluidRenderer into one mesh, rebuilt per changed
    section in 2 ms a frame), the mailbox/handoff/changes/coverage/payload classes, the animated sprites, BlockDetails
    (cracks and the outline), and the mixins (chunk suppression, dirty tracking, frame begin, light, live blocks,
    details).
  - The exported frame names the mesh revision it left out: MCPT header 2048 + 64 * slot ("MCSC").
  - GTA side: gta/src/blocks/ (protocol, mapping readers, ack writer, shader, translucent sort, renderer). It draws
    with the presented GTA pose (the same lag as the re-projection) and tests against GTA's depth with the effect's
    calibration (HostReversedZ, HostDepthScale, DepthBias).
  - The effect composites three layers, nearest first: native blocks, the re-projected world (now entities only), and
    the avatar.
  - The script sends {"t":"host","pid":n} on connect.
  - New option render.nativeBlocks (true).
- **Bugs found and fixed:**
  1. ReShade 6.8's update_texture_bindings waits for the GPU to go idle, and the add-on rebound its five layers on every
     Minecraft frame, stalling GTA each time. Now one bound set gets GPU copies of each whole frame: from the shared
     set, from D3D11 staging textures, or from a D3D12 upload ring (no synchronous upload).
  2. Security: any web page could open ws://127.0.0.1:25599 and run op commands. Handshakes with an Origin header are
     now refused.
  3. The DirectX 11 driver crashed on a partial UpdateSubresource of the details' vertex buffer, every frame. Now it's
     a dynamic buffer written with discard. This was found by bisecting the stand-in.
- **Also:**
  - BlockMeshMailbox reads GetLastError through captured call state; EldenCraft's separate GetLastError call from Java
    could read a stale value.
  - The idle "waiting" log line is quiet while no host is attached.
- **Test host:**
  - fakegta has a D3D12 mode: Enhanced's path, GPU-shared frames included. Both modes bind their own depth to DEPTH
    (ReShade's generic depth never picked the stand-in's up, so occlusion against "GTA" had never actually been tested
    there).
- **Results (dev client; GTA and Modrinth Minecraft closed):**
  - Native blocks draw on both APIs and stay inside the stand-in's own block outlines through fast camera swings.
  - pose_test: every pose has the avatar layer.
  - frame_test: 0.00% non-opaque.
  - gpu_frames_test: PASS with 5 planes and with 3.
  - No errors in either log.
- **Not tested in real GTA yet.** On Enhanced, F10 should say "GPU shared, avatar layer, native blocks". If the blocks
  swim while turning, F9 picks the pose lag (Enhanced starts at 1).

**2026-10-12 16:00, README rewritten, LowBacon credited, unneeded files out** (user: "redo the Readme.md ... who made this
and it should be LowBacon ... how to enable this mod and how to add this with full instruction"; "delete all unnecessary
files that the mod doesnt need").
- **README.md, rewritten:**
  - what the mod does, who made it, why it's possible, and how it works step by step;
  - install steps for the Modrinth App and the official launcher, and a GTA file table for Enhanced and Legacy;
  - every setting (config, `-D` options, the ReShade effect), the F10 overlay and debug views, and troubleshooting;
  - updating and removing, safety, the link messages, the shared memory, and every mixin.
- **Authorship:**
  - fabric.mod.json: name "Minecraft x GTA V Passthrough", authors LowBacon, contributors Rehan and Adam Grzegorzewski,
    and the GitHub links;
  - the ReShade add-on exports NAME/DESCRIPTION (shown in ReShade's Add-ons tab);
  - THIRD_PARTY_NOTICES.md gained universal-modder's MIT notice (the passthrough started as its example), next to
    EldenCraft's.
- **Moved out** to `..\MCPassthrough-Backups\2026-10-12_16-06-55_removed-from-project\` (347 files, 32 MB), not deleted:
  - the toolkit: `.agents`, `.claude`, `.claude-plugin`, `skills`, `knowledge`, `plugins`, `state` (empty), and the
    toolkit's AGENTS.md/CLAUDE.md;
  - the video pipeline (`video/`), and the WSL scripts (`gradle.sh`, `gta/*.sh`): no WSL or Python on this PC;
  - the Python host tools (superseded by the PowerShell and C++ tests);
  - the test output PNGs and the build intermediates.
- **Also:**
  - New short AGENTS.md (the rules for this mod) and CLAUDE.md (`@AGENTS.md`). The old `.claude/settings.json` hook
    pointed at a missing script.
  - `build.bat` and `build_fakegta.bat` delete their intermediates after a successful build.
  - A root .gitignore keeps the test output out of git.
  - The docs say `mc\gradlew.bat -p mc ...`, which works from the repo root.
- **Rebuilt both halves, and installed:** update_games UPDATE OK (backup `MCPassthrough-Backups\2026-10-12_16-19-05`);
  make_dist refreshed `MCPassthrough-Mods`.
- **Left alone in GTA's folder:** the user's own NativeTrainer.asi and OpenRPF.asi, ReShade's stock shaders, and an unused
  `dinput8.dll` (Legacy's loader; Enhanced loads `xinput1_4.dll`).
