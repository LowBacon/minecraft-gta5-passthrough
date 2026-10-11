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
| Re-projection to GTA's current camera | Synchronized | A ray march with bisection and a thickness test, so there's no smear behind thin things. Steve gets his own pass, moved by the player's motion. | in game (smear) |
| Pose lag: which of GTA's cameras the shown picture used | Synchronized | F9 (0 to 3), or the ReShade slider. Enhanced starts at 1. | built |
| Torn frames | Synchronized | Two texture sets: a frame is shown only if Minecraft didn't rewrite its slot during the copy. | built |
| Minecraft crashed or froze | Synchronized | After 1.5 s without a new frame, the effect passes GTA's picture through untouched until frames come back. | built |
| Minecraft lighting vs GTA's time | Synchronized | render.alwaysDaylight: Minecraft lights its blocks as at noon, and GTA's light is applied by the effect, so a night doesn't darken them twice. Minecraft's real time is unchanged. | built |
| Distant blocks flashing dark / dropping out while the camera turns | Synchronized (fixed) | Minecraft 26.3 fades a chunk section in over `chunkSectionFadeInTime` (0.75 s). `terrain.fsh` mixes its colour toward the fog colour, which is black in the passthrough, for sections beyond `chunkFullyVisibleRange`. The fade restarts whenever the section comes back into the visibility graph (`SectionOcclusionGraph.runUpdates`) and on every rebuild (`setSectionMesh`). Here that's all the time: GTA's camera swings, and the host's ground keeps rebuilding the sections round the player. `LevelRendererMixin` sets the fade to 0 while attached; the option itself is untouched. `host/frame_test.ps1` measured that it never affects alpha or coverage: 0.00% non-opaque in 479 frames, even with the fade on. | built (in-game pending) |
| Slightly see-through blocks | Synchronized (fixed) | The shader's haze faded far blocks toward the blurred GTA picture *behind* them. Edge softening at 1.0 blended edge pixels half into GTA. Now the haze goes toward GTA's average air colour from 40 m, and edge softening is 0.35. | in game (shader live) |
| Torn frames from the shared memory | Synchronized (fixed) | Measured: 1.5–1.8% of reads overlapped Minecraft rewriting the same slot. These are the stair-step tears. GTA refuses them now (double buffering). Minecraft also never rewrites the slot the host is reading: the host's read slot is in header +48. | dev client |
| Empty edge while turning | Synchronized | `render.overscan` (1.12): Minecraft renders a wider view than GTA's (third person) and exports the real fov, so turning re-projects into picture that's there. | built |
| Minecraft's pixel art | Synchronized | "Crisp Minecraft pixels": each texel flat, blended across only about one screen pixel, for the world and the HUD. Before, it was bilinear smearing. | in game (shader live) |
| Haze and lighting | Synchronized | Haze goes toward the colour of GTA's whole picture, from 40 m. Lighting tint comes from a wide blur. Neither shows what's behind a block. Edge softening defaults to 0.35. | in game (shader live) |
| Translucency (glass, water) over GTA | Minecraft-only | Minecraft composites it into its own frame. Only the nearest depth is exported, so GTA geometry *behind* glass but in front of Minecraft blocks behind it isn't layered correctly. | in game |
| Disocclusion behind Steve | Minecraft-only | Where Steve stood a frame ago, Minecraft never drew what's behind him, so GTA shows there for that frame. | built |
| Independently moving things (arrows, mobs) between Minecraft frames | Minecraft-only | Camera re-projection can't move them. They're at most one Minecraft frame behind, and Minecraft caps itself at 1.25x GTA's fps. | in game |

## Player

| Feature | Status | How | Verified |
|---|---|---|---|
| Steve stands where GTA's player is, faces where it faces | Synchronized | `cam` / `pl` every GTA frame. | in game |
| Walk animation | Synchronized | It's driven by how far GTA's player moved each tick. | in game |
| Creative flight (double-tap Space) | Synchronized | GTA's player is frozen and moved at Minecraft's flying speeds. Line-of-sight probes against GTA's map, cars and block props stop it. | built |
| Elytra (Space in mid-air) | Synchronized | Minecraft's physics move Steve and GTA follows with a chase camera. Hitting a wall ends the glide. | in game (earlier build) |
| Sprint, crouch and swim poses from GTA's movement | Not done | Steve walks or stands; GTA's crouch, swim and sprint don't change his pose. | |
| Vehicles | Minecraft-only | Steve sits at GTA's player position in the car, without a seated pose. | in game |
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

- **Rebuilding blocks and entities as GTA geometry.** Minecraft's own renderer draws everything (blocks, Steve,
  mobs), which is why they look exactly like Minecraft. GTA's props are only for collision.
- **Chunk-batched GTA collision.** Collision is one prop per solid block, at most 400, because GTA crashes at about
  1,500 script objects. Merging blocks into larger boxes would raise that limit; not done yet.
- **Per-entity motion vectors.** Fast mobs and arrows would need Minecraft to export motion per pixel. Only Steve gets
  a correction.
- **WebSocket schemas, heartbeats, authentication.** Messages are validated where they're parsed, and frames are
  capped at 4 MB. There's no token: any local program can still connect.

## Tests

- **Dev client end-to-end:** `mc\gradlew.bat runClient`, then `powershell -File host\e2e_test.ps1`, a script playing GTA
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

## Rollback

- **GTA:** the files replaced by the first install are in `gta\build\game_backup_20261010\`. `gta\install.ps1
  -Remove -Edition Enhanced` takes out everything the installer adds.
- **Minecraft:** remove `passthrough-0.1.0.jar` from the profile's `mods` folder. `config\passthrough.properties` can
  be deleted; it's written again with the defaults.
