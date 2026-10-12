# Feature registry

The status of each Minecraft feature in the passthrough. The labels mean:

- **Synchronized**: it works in Minecraft and also affects GTA, through a mapping described here.
- **Native**: it works in Minecraft as usual and shows in the passthrough. GTA doesn't need to know.
- **Minecraft-only**: it works in Minecraft, but GTA has no full equivalent. What GTA gets, if anything, is listed.
- **Not done**: it doesn't work, or isn't mapped.

A feature only counts as complete for the part named here, never because something looks close enough. "Verified"
says how each row was checked:

- **in game**: by playing GTA with Minecraft.
- **dev client**: end to end against a real Minecraft client, with a script standing in for GTA (see "Tests").
- **built**: compiled only.

## Rendering

| Feature | Status | How | Verified |
|---|---|---|---|
| Minecraft's real renderer: blocks, block entities, entities, Steve, items, particles, water, glass, signs | Native | Minecraft draws its own frame with its own renderers and models. Nothing is rebuilt as GTA geometry. | in game |
| Depth against GTA's world | Synchronized | Minecraft's depth (float32, reversed Z) and GTA's (reversed Z) are both turned into metres, and Minecraft shows only where it is nearer. | in game |
| Re-projection to GTA's current camera | Synchronized | A ray march with bisection and a thickness test, so there's no smear behind thin things. With the avatar layer the world layer has no Steve in it, so nothing of it moves with the player. | in game (smear) |
| Native blocks (EldenCraft's design, ported) | Synchronized | Minecraft bakes every real block in its render area with vanilla's own renderers (`ModelBlockRenderer`, `FluidRenderer`: AO, biome tint, every model, water and lava) into one mesh, and publishes it with the block atlas (all mip levels), the lightmap and the current frame of every animated sprite (`BlockMeshClient`, mappings `Local\MCPassthroughBlock*`). The GTA add-on uploads it once per revision and draws it every frame with the camera of the GTA frame being presented, tested against GTA's depth, translucent faces sorted back to front (`gta/src/blocks/`). Once GTA acknowledges a revision covering the whole vanilla view, Minecraft leaves its chunks out of the exported picture and every frame names that revision, so a block is never drawn twice or not at all; a change left undisplayed for 1 s, a teleport beyond the prebuilt border or a lost link brings the chunks back. Blocks therefore never lag, swim, smear or show holes when the camera turns: they're drawn like GTA's own geometry. Mining cracks and the selection outline go native too (`BlockDetails`). Edits rebuild only the changed sections (2 ms a frame). The host's barrier ground isn't part of it. `render.nativeBlocks=false` (or `-Dpassthrough.noNativeBlocks=true`) keeps the old picture path. F10 says "native blocks"; DebugView 7 shows the native layer alone. Works on D3D12 (Enhanced) and D3D11 (Legacy). | dev client: `fakegta.exe ... d3d12` and D3D11 composites, blocks locked inside the stand-in's own block outlines through fast camera swings; in game pending |
| No GPU stall per Minecraft frame | Synchronized (fixed) | ReShade 6.8's `update_texture_bindings` waits for the whole GPU to go idle. The add-on rebound its five layers for every Minecraft frame (it flipped between two texture sets), stalling GTA each time. Now one set stays bound, and each frame reaches it by a GPU copy queued ahead of the effects, only once it's known whole (GPU frames: from the shared set; slot frames: from staging textures on D3D11, from an upload ring on D3D12, never a synchronous upload). | dev client (both APIs) |
| Steve in a layer of his own (adapted from EldenCraft) | Synchronized | Third person: the world pass leaves the local player out (`AvatarLayerMixin`). `HostAvatarRenderer` then draws them alone with vanilla's own renderer (skin, armour, cape, held items, every pose) into cleared colour and depth, with the world pass's camera and lens. That goes out as layers 3 and 4 (slot flag 32: GPU planes 3 and 4, or slot layers 3 and 4 on the readback path). The effect shows that layer as drawn, camera-relative: GTA's current lens only, never re-projected. It is depth-tested against GTA and against the world layer (a block between the camera and Steve hides him), relit like the world, and kept out of the glow. That is what fixes the head tearing apart and the doubled Steves: before, Steve was cut out of the world picture with a 1.84 m box, so his head, helmet and hat stuck out of it and were left behind. If the layer can't be captured, the frame is dropped, never shown without him. An add-on with only three GPU planes, or `render.avatarLayer=false`, keeps the old path. | dev client: `host/pose_test.ps1` (every pose whole, 100% opaque), `gpu_frames_test.exe` PASS with 5 planes (941 of 941 frames carry him) and with 3 (none, Steve stays in the world), `fakegta.exe 12 0 third` composites checked; in game pending |
| Your own skin and armour | Synchronized | `render.ownSkin=true` (the default): the account's skin, as in EldenCraft. `false` or `-Dpassthrough.classicSteve=true` brings back classic Steve. Armour, elytra, cape and held items come with the avatar layer. | dev client |
| Pose lag: which of GTA's cameras the shown picture used | Synchronized | F9 (0 to 3), or the ReShade slider. Enhanced starts at 1. | built |
| Torn frames | Synchronized | Two texture sets: a frame is shown only if Minecraft didn't rewrite its slot during the copy. | built |
| Minecraft crashed or froze | Synchronized | After 1.5 s without a new frame, the effect passes GTA's picture through untouched until frames come back. | built |
| Minecraft lighting vs GTA's time | Synchronized | render.alwaysDaylight: Minecraft lights its blocks as at noon, and GTA's light is applied by the effect, so a night doesn't darken them twice. Minecraft's real time is unchanged. | built |
| Distant blocks flashing dark / dropping out while the camera turns | Synchronized (fixed) | Minecraft 26.3 fades a chunk section in over `chunkSectionFadeInTime` (0.75 s). `terrain.fsh` mixes its colour toward the fog colour, which is black in the passthrough, for sections beyond `chunkFullyVisibleRange`. The fade restarts whenever the section comes back into the visibility graph (`SectionOcclusionGraph.runUpdates`) and on every rebuild (`setSectionMesh`). Here that's all the time: GTA's camera swings, and the host's ground keeps rebuilding the sections round the player. `LevelRendererMixin` sets the fade to 0 while attached; the option itself is untouched. `host/frame_test.ps1` measured that it never affects alpha or coverage: 0.00% non-opaque in 479 frames, even with the fade on. | built (in-game pending) |
| Slightly see-through blocks and Steve | Synchronized (fixed) | Minecraft's layers are 100% opaque (measured: 0.00% non-opaque in the world and avatar layers). The see-through look came from the effect. "Match GTA lighting" took each block's brightness and colour from GTA's picture blurred over only about 37 px (mip 3.2), so the shapes of what was behind a block (a bright window, the sky, a car) showed through it, faintly. Now the light comes from a far wider blur (mip 4.6, about 1/100 of the screen) with a narrower range (gain 0.6 to 1.2, tint 0.75 to 1.25, strength 0.3, match 0.7). The haze is premultiplied, so it only lands where Minecraft covers. Edge softening is 0.15. Earlier: the haze had faded far blocks toward the GTA picture behind them. | dev client (fakegta composites); in game pending |
| GPU frames (adapted from EldenCraft) | Synchronized | On GTA V Enhanced (D3D12), the add-on shares three sets of five D3D12 textures (world colour, depth, HUD, avatar colour, avatar depth) plus a fence. Minecraft imports them (GL_EXT_memory_object_win32 / semaphore), copies its world colour, depth and HUD into a free set on the GPU, signals the fence and publishes right away. The add-on copies texture to texture once the fence is reached, and gives the set back once its copy is done. No pixel crosses the CPU: no ~18 ms readback, no 25 MB copy each way. Minecraft also renders at GTA's full resolution. Legacy (D3D11), or any failure, keeps the shared-memory path. `-Dpassthrough.cpuFrames=true` forces it. F10 shows "GPU shared" or "CPU copy". | dev client: `gta\tests\gpu_frames_test.exe` PASS, 1309 of 1309 frames real, 0.06 ms average fence wait; in game pending |
| GTA's pause menu or map open | Synchronized (fixed) | The pause menu stops ScriptHookV scripts. The add-on notices that no camera pose has arrived for 250 ms and shows GTA's picture untouched, instead of a stale Minecraft overlay. | built; in game pending |
| Torn frames from the shared memory | Synchronized (fixed) | Measured: 4 of 259 reads (1.5%) overlapped Minecraft rewriting the same slot. These are the stair-step tears. Now the reader names the slot it's copying (header +48) and Minecraft skips that slot: 0 of 257 under the same load. GTA also refuses any torn frame (double buffering), and checks the header, dimensions and pose before reading, so a bad frame can never read past the mapping. | dev client |
| Empty edge while turning | Synchronized | `render.overscan` (1.12): Minecraft renders a wider view than GTA's (third person) and exports the real fov, so turning re-projects into picture that's there. | built |
| Minecraft's pixel art | Synchronized | "Crisp Minecraft pixels": each texel flat, blended across only about one screen pixel, for the world and the HUD. Before, it was bilinear smearing. | in game (shader live) |
| Haze and lighting | Synchronized | Haze goes toward the colour of GTA's whole picture, from 40 m. Lighting comes from a very wide blur (mip 4.6). Neither shows what's behind a block. Edge softening defaults to 0.15. Debug views 4 to 6 show the avatar layer, its depth test against GTA, and its ordering against the world. | in game (shader live) |
| Translucency (glass, water) over GTA | Minecraft-only | Minecraft composites it into its own frame. Only the nearest depth is exported, so GTA geometry *behind* glass but in front of Minecraft blocks behind it isn't layered correctly. | in game |
| Disocclusion behind Steve | Synchronized (fixed) | The world layer is drawn without the player, so what's behind him is always there. | dev client |
| Independently moving things (arrows, mobs) between Minecraft frames | Minecraft-only | Camera re-projection can't move them. They're at most one Minecraft frame behind, and Minecraft caps itself at 1.25x GTA's fps. | in game |

## Player

| Feature | Status | How | Verified |
|---|---|---|---|
| Steve stands where GTA's player is, faces where it faces | Synchronized | `cam` / `pl` every GTA frame. | in game |
| Walk animation | Synchronized | It's driven by how far GTA's player moved each tick. | in game |
| Creative flight (double-tap Space) | Synchronized | GTA's player is frozen and moved at Minecraft's flying speeds. Line-of-sight probes against GTA's map, cars and block props stop it. | built |
| Elytra (Space in mid-air) | Synchronized | Minecraft's physics move Steve and GTA follows with a chase camera. Hitting a wall ends the glide. | in game (earlier build) |
| Crouch, swim and seated poses from GTA's movement | Synchronized | GTA sends what its player is doing (`mv` in `cam`: sprint, stealth/duck, swimming, under water, seated in a vehicle, climbing). Steve gets Minecraft's own pose for it through the render state: `isCrouching`, swimming eased over about 0.25 s, `isPassenger`. The whole model takes the pose at once. | dev client (`host/pose_test.ps1`, frames checked) |
| Sprint | Native | Minecraft's walk animation speeds up with GTA's speed. There's no separate sprint pose in Minecraft. | dev client |
| Climbing | Not done | It's reported (`mv` 32), but Minecraft has no climbing pose for a player off a ladder. | |
| Vehicles | Synchronized | Steve sits (Minecraft's riding pose) at GTA's seat. | dev client (pose); in game pending (seat height) |
| /tp, /teleport, ender pearls | Synchronized | Minecraft's teleport moves GTA's player there (`pteleport`). | in game (pearls) |
| /kill, damage to the player | Minecraft-only | Minecraft respawns right away. GTA's player is invincible by design. | |
| /gamemode | Native | Survival works in Minecraft. GTA isn't affected. | |
| /effect | Native | | |

## Input and menus

| Feature | Status | How | Verified |
|---|---|---|---|
| Attack, use, hotbar (wheel, 1 to 9) | Synchronized | Forwarded from GTA's controls. | in game |
| E inventory / creative menu, Q drop, T chat, / command, X swap hands, middle mouse pick block | Synchronized | Raw keys go to Minecraft's own keyboard handler (SDL scancodes). | dev client |
| A Minecraft screen open: every key, typed text, cursor, clicks, wheel | Synchronized | Minecraft reports `screen`, and GTA's controls are all disabled meanwhile. The cursor is drawn by the effect. | dev client |
| Keys or buttons left held when GTA disconnects | Synchronized | Minecraft releases all keys and buttons when the host closes. | built |
| Rebinding these keys | Not done | They're fixed in the plugin for now. | |

## World, blocks, commands

| Feature | Status | How | Verified |
|---|---|---|---|
| Place and break blocks, /setblock, /fill, /clone, /place, explosions | Synchronized | Every server block change goes to GTA. Solid blocks become invisible GTA props (at most 400), so people and cars collide. | in game |
| Block collision shapes in GTA | Minecraft-only | Each prop is a full box. Slabs, stairs and fences are boxes in GTA, though Minecraft's own collision is exact. | |
| GTA's ground in Minecraft | Synchronized | Barrier columns, 40 blocks around the player. | in game |
| Redstone, block entities, crops | Native | Minecraft simulates them. GTA gets only the solid blocks. | |
| /summon, hostile mobs | Synchronized | Every hostile mob fights GTA's people. Goal-based mobs get a target goal. Brain-based ones (piglins, hoglins, wardens, breezes...) are driven. | built (brain mobs) |
| GTA's police vs mobs | Synchronized | Each mob has an invisible GTA double. The damage police do goes back to the mob. | in game |
| Passive animals, villagers | Native | Rendered, but not part of the fight. | |
| /particle, /playsound, /title, /tellraw, chat | Native | Minecraft draws and plays them; they show in the overlay layer. | |
| /time set, /time add (and sleeping, functions) | Synchronized | EnvSync watches Minecraft's clock: a jump eases GTA's clock to it (tick 0 = 06:00, 1000 ticks an hour). | dev client |
| gamerule advance_time true | Synchronized | GTA's clock holds on Minecraft's while Minecraft's clock runs. | dev client |
| /time query | Native | Minecraft's own time, never GTA's. | |
| /weather clear / rain / thunder | Synchronized | GTA blends to CLEAR, RAIN or THUNDER over 15 s. Only the look is matched: GTA's rain isn't Minecraft's. | dev client |
| gamerule advance_weather | Synchronized | The weather is followed while it's on. | built |
| /difficulty, /worldborder, /gamerule (others) | Native | | |
| /give, /item, /clear, /enchant | Native | Real Minecraft items. Nothing is turned into a GTA item unless mapped. The mapped ones are listed under "Weapons". | |
| Nether portal | Synchronized | The Nether opens around it, GTA's sky goes red, and people and cars on lava or fire burn. | in game |
| The End | Not done | | |

## Weapons

| Feature | Status | How | Verified |
|---|---|---|---|
| Crossbow fireworks | Synchronized | A GTA rocket blast where they hit. | in game |
| Arrows | Synchronized | A GTA bullet into the person or car they hit. | in game |
| Sword | Synchronized | People in front are knocked over and cars are shoved. | in game |
| TNT, creepers, fireballs | Synchronized | A GTA explosion at the same place. | in game |
| Gun mode (Tab) | Synchronized | GTA's rifle, minigun or RPG in Steve's hands. | in game |

## Environment sync settings

These are in Minecraft's `config/passthrough.properties`. It's written with the defaults on the first start, and a bad
value is logged and replaced by its default.

```
env.time=commands            # commands | minecraft | off
env.weather=commands         # commands | minecraft | off
env.hourOffset=6             # GTA's hour at Minecraft tick 0
env.blendSeconds=3           # time blend
env.weatherBlendSeconds=15   # weather blend
render.alwaysDaylight=true
render.overscan=1.12         # 1.0..1.5: how much wider Minecraft's view is than GTA's (third person)
render.ownSkin=true          # your own skin (false: classic Steve)
render.avatarLayer=true      # you in a layer of your own, shown as drawn
render.nativeBlocks=true     # GTA draws Minecraft's blocks itself, with its own camera
```

- **commands**: GTA keeps its own clock and weather until Minecraft's change, by a command or anything else. GTA also
  follows Minecraft's clock whenever advance_time is on.
- **minecraft**: GTA always follows Minecraft.
- The mod's own time and weather commands, from world setup and the Nether, are never mirrored.
- While the Nether is open, the Nether owns GTA's sky and clock.

## Diagnostics

**F10** shows the overlay. It gives:
- the link state and session, and GTA's fps;
- Minecraft frames per second, the shown frame's age, its size, torn frames refused, and STALE when Minecraft stops;
- the pose lag, the input state (GTA only, play keys, or a Minecraft screen) and the flight state;
- Minecraft's time and weather, and whether GTA follows them;
- mobs, doubles, props and people.

## Not done, and why

- **Rebuilding entities as GTA geometry.** Minecraft's own renderers draw everything (blocks through the native mesh,
  Steve, mobs), which is why they look exactly like Minecraft. GTA's props are only for collision.
- **Chunk-batched GTA collision.** Collision is one prop per solid block, at most 400, because GTA crashes at about
  1,500 script objects. Merging blocks into larger boxes would raise that limit; not done yet.
- **Per-entity motion vectors.** Fast mobs and arrows would need Minecraft to export motion per pixel. Only Steve gets
  a correction.
- **WebSocket schemas, heartbeats, authentication.** Messages are validated where they're parsed, and frames are
  capped at 4 MB. A handshake carrying an Origin header is refused (every browser sends one; native clients don't), so a
  web page can no longer reach the link and run server commands. There's no token: a local program can still connect.

## Tests

- **Dev client end-to-end:** `mc\gradlew.bat -p mc runClient`, then `powershell -File host\e2e_test.ps1`, a script playing GTA
  on the link. It types commands into chat, so only point it at the dev client. All steps passed on 2026-10-11. It
  covers:
  - the input path: T, typing, Enter, E, the mouse, Esc;
  - the `screen` reports;
  - `/time set night`, `/weather thunder`, `/gamerule advance_time true|false`, `/time add 1000`, `/weather clear`
    producing the right `env` messages.
- **In game** (needs GTA, not automated):
  - F10 overlay readings;
  - F9 while turning, then keep the steadiest;
  - `/time set night` and see GTA ease to 19:00 (13000 ticks with the 6:00 offset);
  - `/weather thunder`, `/weather clear`;
  - kill Minecraft while GTA runs: the overlay says STALE and GTA's picture is untouched; start Minecraft again and it
    returns.

- **Frames** (dev client): `host\frame_test.ps1 [-Rebuild]` spins and walks the camera while sections rebuild, and
  measures non-opaque block pixels, coverage drops and torn reads. 2026-10-11: 0.00% non-opaque in 736 frames; torn
  reads went from 4 of 259 to 0 of 257.
- **Poses** (dev client): `host\pose_test.ps1` saves Minecraft's frame for each movement state to check by eye, the
  avatar layer separately.
- **Stand-in GTA** (dev client only: it builds test blocks): `gta\tests\fakegta.exe <seconds> <shake 0|1> <third|orbit>
  <screenshot prefix> [d3d12]` with ReShade (dxgi.dll) beside it. D3D12 runs Enhanced's path, GPU-shared frames
  included. It binds its own depth buffer to DEPTH (disable ReShade's Generic Depth in its ReShade.ini) and draws thin
  yellow outlines where the Minecraft test blocks are: native blocks must sit inside them through fast camera swings.
  2026-10-12: both APIs, blocks locked; this test caught a DirectX 11 driver crash in the details pass (fixed).
- **GPU frames** (dev client): `gta\tests\gpu_frames_test.exe <seconds> <5|3>` with `host\pose_test.ps1` playing the
  host. 2026-10-12: PASS with 5 planes (933 of 933 avatar frames) and with 3 (none).
- **Update and rollback:** `update_games.ps1` backs up, installs and verifies byte for byte.
  `update_games.ps1 -SimulateFailure` checks the rollback: 2026-10-11, every file was identical to before.

## Rollback

- **Each update:** every update backs up what it replaces into `Documents\Universal\MCPassthrough-Backups\<time>\` (with
  `update.log`), and puts it back by itself if the update fails.
- **GTA:** the files replaced by the first install are in `gta\build\game_backup_20261010\`. `gta\install.ps1
  -Remove -Edition Enhanced` takes out everything the installer adds.
- **Minecraft:** remove `passthrough-0.1.0.jar` from the profile's `mods` folder. `config\passthrough.properties` can
  be deleted; it's written again with the defaults.
