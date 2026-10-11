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

**Installed:** `install.ps1 -Edition Enhanced`. The files it replaced are in `gta/build/game_backup_20261010/`.
