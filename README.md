# Minecraft x GTA V Passthrough

**Made by LowBacon.**

Real Minecraft Java Edition 26.3 runs next to GTA V (story mode) and is drawn into GTA's picture, as if Minecraft were part
of Los Santos. GTA's camera moves Minecraft's camera. GTA's streets are solid ground in Minecraft. The blocks you build are
solid for GTA's people and cars. TNT, creepers, arrows, fireworks, swords and ender pearls work in both games at once.
Minecraft's monsters fight GTA's people and police, and a nether portal brings the Nether to the city.

Nothing is faked or remade. Both games are real and run at the same time on one PC. The mod is two halves that talk to each
other:
- a Fabric mod inside Minecraft;
- a ScriptHookV script inside GTA V, which is also a ReShade add-on.

> **Story mode only.** Never take a modded GTA V into GTA Online. See [Safety and fair play](#safety-and-fair-play).

## Contents

- [What you can do](#what-you-can-do)
- [Who made it](#who-made-it)
- [Why this is possible](#why-this-is-possible)
- [How it works](#how-it-works)
- [Requirements](#requirements)
- [Installing](#installing)
- [Turning it on and playing](#turning-it-on-and-playing)
- [Controls](#controls)
- [Settings](#settings)
- [Checking that it works](#checking-that-it-works)
- [Troubleshooting](#troubleshooting)
- [Updating and removing](#updating-and-removing)
- [Safety and fair play](#safety-and-fair-play)
- [Technical reference](#technical-reference)
- [Project files](#project-files)
- [Building from source](#building-from-source)
- [Tests](#tests)
- [Known limits](#known-limits)
- [License](#license)

## What you can do

- **Build in Los Santos** with any Minecraft block, from the creative menu (E) or your hotbar. GTA's people and cars stop
  at your walls.
- **Blocks that stay put.** GTA draws your Minecraft blocks itself, with its own camera, so they never lag behind, swim
  or tear when you turn ("native blocks").
- **You, as you look in Minecraft:** your own skin, armour, elytra, cape and what you hold, in every pose.
- **Explosions in both games.** TNT, creepers and ghast fireballs blow up in Minecraft and set off a matching GTA explosion
  in the same place.
- **Minecraft weapons with GTA effects:**
  - Crossbow fireworks burst as GTA rockets where they hit.
  - Arrows that hit a person or a car land as GTA bullets.
  - A sword swing knocks people over and shoves cars.
  - An ender pearl teleports GTA's player.
- **GTA's guns in Steve's hands** (Tab): carbine rifle, minigun and RPG.
- **Creative flight**, as in Minecraft: double-tap Space.
- **Elytra flight:** press Space in mid-air, steer with the mouse, boost with fireworks.
- **Mobs vs people.** Every hostile Minecraft mob hunts GTA's people, GTA's police shoot back, and damage crosses over both
  ways.
- **The Nether.** Walk through a lit nether portal and the ground around it turns into the Nether. Nether mobs pour out,
  GTA's sky goes red and its clock races to midnight, and people and cars on lava or fire burn.
- **Time and weather.** Minecraft's `/time` and `/weather` change GTA's clock and weather.
- **All of Minecraft's own controls:** inventory, the creative menu, chat, commands, dropping items, swapping hands, picking
  blocks.
- **GTA's world as usual.** You walk, run, drive and swim in GTA, and Minecraft follows. Steve takes GTA's moves: sprinting,
  crouching, swimming, sitting in a car.

## Who made it

| Who | What |
|---|---|
| **LowBacon** | **Made this mod**: GTA V Enhanced support, creative flight and the elytra, the creative menu and the rest of Minecraft's controls, every hostile mob against GTA's people, the time and weather link, the shared GPU frames, your own layer, the native blocks, the fixes and the installer. |
| Rehan ([universal-modder](https://github.com/rehan-remade/universal-modder)) | The first passthrough this mod started from: the Fabric mod, the ScriptHookV script, the ReShade add-on and the effect (MIT). |
| Adam Grzegorzewski ([EldenCraft](https://github.com/SocketByte/EldenCraft)) | The designs and code for the shared GPU frames, the avatar layer and the native blocks were adapted from EldenCraft, his Minecraft-in-Elden-Ring passthrough (MIT). |
| Alexander Blade | [ScriptHookV](https://www.dev-c.com/gtav/scripthookv/) and its ASI loaders, which run scripts in GTA V. |
| crosire | [ReShade](https://reshade.me) and its add-on API, and `ReShade.fxh` from [crosire/reshade-shaders](https://github.com/crosire/reshade-shaders). |
| FabricMC | [Fabric](https://fabricmc.net): Fabric Loader, Fabric API and Loom. |
| TooTallNate | [Java-WebSocket](https://github.com/TooTallNate/Java-WebSocket), bundled in the mod for the link. |
| alloc8or and the NativeDB authors | The GTA V native names and hashes in `natives.h` ([native DB](https://github.com/alloc8or/gta5-nativedb-data)). |

- **Inspiration:** chasm's Minecraft-in-Skyrim passthrough, and TobynJacobs' Minecraft-in-Elden-Ring.
- **Tools:** written with the help of Claude Code.
- **Licences:** the licence notices for the adapted code are in [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).
- **Trademarks:** Minecraft belongs to Mojang Studios and Microsoft, and GTA V to Rockstar Games and Take-Two Interactive.
  This is a fan project, not affiliated with or endorsed by any of them.

## Why this is possible

It sounds impossible to put one game inside another, but six facts make it work.

1. **Both games can be modded on PC.**
   - **Minecraft Java Edition** runs on Java, and Fabric loads mods into it. With *Mixin*, a mod can change what any of
     Minecraft's own methods do as the game loads. This mod makes about 30 such small changes: it takes over the camera,
     copies the picture out, removes the sky, and so on (all listed in the [technical reference](#minecraft-hooks-mixins)).
   - **GTA V story mode** runs scripts through ScriptHookV. A script can call GTA's own built-in functions ("natives").
     Through them it can read where the camera is and what the player does, spawn objects, set off explosions, and set
     the clock and the weather.
   - **ReShade** sits between GTA and DirectX and sees every finished frame before it's shown. That includes GTA's *depth
     buffer*: how far away every pixel is. Its add-on API lets this mod's code draw into the frame.
2. **Two programs on one PC can share memory and talk.**
   - **Messages.** Small messages, like the camera, keys and events, travel over a WebSocket on 127.0.0.1, the PC's own
     private network address. They go 60 to 120 times a second.
   - **Pictures and meshes.** These are big, so they go through named shared memory that both games open. On GTA V
     Enhanced, the pictures go straight from GPU texture to GPU texture, so no pixel passes through the CPU.
3. **Both worlds use the same scale.** A Minecraft block is 1 metre, and GTA measures in metres. With a simple swap of axes,
   a camera in GTA is exactly a camera in Minecraft: same place, same direction, same lens. So Minecraft drawn from GTA's
   camera lines up with GTA's picture pixel for pixel.
4. **Depth makes it one world.** Both games know how far away every pixel is. Comparing the two pixel by pixel shows
   whichever is nearer:
   - a GTA car driving in front of your wall hides that part of the wall;
   - your wall standing in front of a GTA lamp post hides the lamp post.
5. **Collision goes both ways.**
   - GTA's ground becomes invisible barrier blocks in Minecraft, so Minecraft's mobs, items and TNT stand on Los Santos.
   - Minecraft's solid blocks become invisible GTA objects, so GTA's people and cars bump into them.
6. **The delay is hidden.** Minecraft's picture always arrives a little later than GTA's camera has moved on. Three
   things hide that:
   - The effect *re-projects* Minecraft's picture onto GTA's current camera.
   - GTA draws the blocks itself, at its own frame rate.
   - You are drawn in a layer of your own that moves with the camera.

**Why story mode only:** GTA Online is protected by BattlEye, an anti-cheat. ScriptHookV only runs with BattlEye off, and
it closes the game if it tries to go online. That's how it should be: mods have no place in Online.

## How it works

### The pieces

| Piece | Where it comes from | What it does |
|---|---|---|
| `passthrough-0.1.0.jar` | this repo, `mc/` (Java) | The Minecraft half: the link, the camera, the picture export, the native block meshes, your layer, ground and blocks, mobs, the Nether, time and weather. |
| `MCPassthrough.asi` | this repo, `gta/src` (C++) | The GTA half. It's a ScriptHookV script (camera, player, ground, input, explosions, flight, mobs, the Nether) and a ReShade add-on (receives Minecraft's frames, draws the native blocks). |
| `MCPassthrough.fx` | this repo, `gta/shaders` | The ReShade effect that puts Minecraft into GTA's picture by depth, and lights it like GTA. |
| ScriptHookV + its ASI loader | Alexander Blade (download) | Loads `.asi` files into GTA V and runs their scripts. |
| ReShade 6.8.0 with add-on support | crosire (download) | Hooks DirectX, runs the effect, and loads the add-on. |
| Fabric Loader + Fabric API | FabricMC (download) | Loads the mod into Minecraft. |

### The big picture

```
GTA V (story mode)                                        Minecraft Java 26.3 + Fabric
 MCPassthrough.asi
  script.cpp (ScriptHookV)  -- cam, ground, keys -------->  HostLink (WebSocket server, 127.0.0.1:25599)
                            <-- blocks, explosions,           PlayerSync + CameraMixin: Minecraft's camera = GTA's
                                projectiles, mobs, env ---    WorldBridge, MobWar, Nether, EnvSync
  compositor.cpp (ReShade)  <== frames: shared GPU         ==  FrameExporter / GpuTransport (world, HUD, you)
                                textures, or shared memory
  blocks/ (block renderer)  <== block meshes, atlas,       ==  BlockMeshClient (vanilla's own block renderers)
                                light, animations
 MCPassthrough.fx: GTA + native blocks + Minecraft + you, nearest wins; then lighting, haze, HUD on top
```

### One frame, step by step

1. **GTA reads its camera.** Every GTA frame, the script reads:
   - the camera GTA is about to render with: its position, its rotation with roll, its vertical field of view and its
     near and far clip;
   - where the player's feet are and which way they face;
   - what the player is doing: sprinting, crouching, swimming, under water, sitting in a vehicle, climbing.

   It converts that to Minecraft's coordinates and sends it as `cam`. The compositor keeps the last 8 camera poses.
2. **Minecraft takes GTA's camera.** The link stores the newest pose. When Minecraft starts its next frame, it latches that
   pose, so every part of the frame agrees on it. Then:
   - the player is placed exactly where GTA's player is;
   - the camera becomes GTA's: position, yaw, pitch, roll, field of view, and first or third person;
   - in third person, Minecraft renders 12% wider (`render.overscan`), so turning the camera never shows an empty edge;
   - there's no sky, fog or clouds: where Minecraft has nothing, the pixel stays empty and GTA shows through.
3. **Minecraft draws three layers.**
   - **The world:** colour and depth. With native blocks on, it holds only mobs, items, particles and the rest, because
     GTA draws the blocks.
   - **The overlay:** the hand, the HUD and any open screen (inventory, chat). It's captured separately and always
     goes on top.
   - **You:** in third person, the player is drawn alone, with vanilla's own player renderer, into a colour and depth
     layer of their own.
4. **The layers reach GTA.**
   - **GTA V Enhanced (DirectX 12):** the add-on makes 3 sets of 5 shared textures and a fence. Minecraft's OpenGL
     imports them (`GL_EXT_memory_object_win32`, `GL_EXT_semaphore_win32`). Each frame, Minecraft copies its layers into
     a free set on the GPU, signals the fence and publishes. GTA copies them texture to texture once the fence is
     reached, then hands the set back. Minecraft renders at GTA's full resolution, up to 4K.
   - **GTA V Legacy (DirectX 11), or if the GPU route fails:** Minecraft reads its layers back asynchronously into one of
     3 slots of the shared memory `Local\MCPassthroughFrame` (about 221 MB). The add-on copies the newest complete slot.
     This path renders at up to about 1080p worth of pixels, and the effect scales it up.
   - **No torn frames either way.** A frame is used only if Minecraft didn't rewrite it during the copy. GTA also tells
     Minecraft which slot it's reading, so Minecraft writes to another one.
5. **The native blocks are drawn.** This happens alongside step 4: GTA draws the newest block mesh with the camera of the
   frame on screen (see [Native blocks](#native-blocks)).
6. **The effect puts it all together**, per pixel:
   - It **re-projects** Minecraft's world layer from the camera it was rendered with to the camera of the GTA frame being
     shown, so turning doesn't drag Minecraft behind.
   - It **takes the nearest** of four things: GTA, the native blocks, Minecraft's world layer, and you.
   - It **lights** Minecraft from GTA's picture. It also gives it GTA's colour grade and haze, softens its edges, and
     adds contact shadows and a glow for bright things.
   - It puts the hand, the HUD, the open screen and the cursor **on top**.
7. **GTA shows the frame.** Two safety rules apply:
   - After 1.5 s without a new Minecraft frame (Minecraft froze or closed), GTA's picture is shown untouched.
   - When the script stops sending poses for 250 ms, Minecraft is hidden. That happens in the pause menu, the map,
     cutscenes, loading screens and character switches.

### Coordinates

- 1 GTA metre = 1 Minecraft block.
- GTA (x, y, z), with z up, becomes Minecraft (x, z + yOffset, -y).
- Minecraft yaw = 180 - GTA heading, Minecraft pitch = -GTA pitch, and roll stays roll.
- `yOffset` puts the ground where you stand exactly on a whole block. F8 picks it again, and it's picked on its own when
  you (re)connect.

### The ground: GTA to Minecraft

- **Probing.** The script probes GTA's ground in a spiral of columns around the player, up to 40 blocks out, 160 probes a
  frame. It sends them as `ground`.
- **Barriers.** Each surface becomes barrier blocks (invisible and solid), 2 layers under the surface. Mobs, items, TNT and
  arrows rest on GTA's world.
- **Your builds are safe.** Barriers only replace air, so they never overwrite your blocks, and a reset removes only the
  barriers the mod placed.
- **Re-levelling** (F8, or a reconnect) clears the barriers and starts over.
- **Never drawn.** The barrier ground isn't part of the native block mesh.

### Your blocks: Minecraft to GTA

- **Reported every tick.** Every block change on Minecraft's server is collected each tick: placed, broken, blown up, or
  changed by `/setblock`, `/fill`, `/clone` or `/place`. It goes to GTA as `blocks`.
- **GTA collision.** Each solid block becomes an invisible GTA object, a box of 0.97 x 0.96 x 0.80 m that sits just inside
  the block. GTA's people and cars collide with it.
- **Limits.** At most 400 of them, because GTA crashes at around 1,500 script objects. Each one is a full box, so slabs,
  stairs and fences are boxes for GTA (Minecraft's own collision stays exact).
- **On a re-level**, every solid block within 64 blocks is sent again.

### Native blocks

This design was adapted from EldenCraft. It's what makes blocks look glued to GTA's world.

1. **Baking.** Minecraft bakes every real block in its view distance into one mesh, with vanilla's own renderers. That
   includes:
   - every block model;
   - ambient occlusion and biome tints;
   - water and lava;
   - light levels.
2. **Publishing.** Minecraft publishes, through shared memory:
   - the mesh;
   - the block texture atlas, with all its mip levels;
   - the lightmap;
   - the current frame of every animated texture (water, lava, fire, portals).
3. **Acknowledging.** GTA's add-on uploads a new revision once. When it holds the exact mesh and atlas, it writes an
   acknowledgement.
4. **Leaving the chunks out.** Only once GTA has acknowledged a revision that covers the whole view does Minecraft leave
   its chunks out of the picture it sends. Every frame then names the revision it left out, so a block is never drawn
   twice, or not at all.
5. **Drawing.** Every frame, GTA draws exactly that revision with the camera of the frame on screen. Each pixel is tested
   against GTA's depth, and see-through faces (glass, water) are sorted back to front.
6. **Editing.** A change rebuilds only the 16-block sections it touched, with at most 2 ms of work a frame. Light changes
   refresh twice a second.
7. **Mining cracks and the block outline** are drawn by GTA too.
8. **Falling back.** If something goes wrong, the old way comes back by itself: blocks become part of Minecraft's picture
   again. That happens when:
   - a change goes undisplayed for 1 s;
   - you teleport past the prepared area;
   - the link drops.

It works on DirectX 12 (Enhanced) and DirectX 11 (Legacy). `render.nativeBlocks=false` turns it off.

### You: the avatar layer

This was adapted from EldenCraft too.

- **Drawn alone.** In third person, Minecraft draws the world without you, then draws you alone with vanilla's own player
  renderer: skin, armour, elytra, cape, held items and every pose. It uses the same camera and lens, into a cleared colour
  and depth layer.
- **Shown as drawn.** The effect shows that layer camera-relative, with GTA's current lens, and never re-projects it, so you
  never tear apart or show twice. It's depth-tested against GTA and against the world layer: a block between the camera
  and you hides you.
- **Never shown half.** If the layer can't be captured, that frame is dropped instead of being shown without you.
- **Turning it off.** `render.avatarLayer=false` puts you back into the world picture.

### Re-projection and pose lag

- **Re-projection.** Minecraft's world layer was rendered from a camera that's a little older than the one on screen. The
  effect re-projects it with a ray march: 32 steps, then a 6-step bisection to the exact crossing. A crossing only counts
  where the ray is within 0.12 m + 2% of the distance of the surface, so rays passing behind a thin thing don't pick it
  up. That's what stops smearing.
- **Pose lag** is how many frames after the script reads GTA's camera that picture is actually shown. Legacy measured 0.
  Enhanced (DirectX 12) shows a frame later, so it starts at 1. F9 cycles 0 to 3. The native blocks and the re-projection
  both use the pose it picks.
- **Steve without the avatar layer.** If the avatar layer is off, Steve's pixels are moved by how far GTA's player has
  gone since Minecraft drew him ("Move Steve with the player").
- **Things that move on their own** (arrows, mobs) can't be re-projected by the camera. They're at most one Minecraft frame
  behind, and Minecraft caps itself at 1.25 times GTA's frame rate (40 to 120 fps), so it leaves GTA the GPU.

### Lighting and look

- **No double night.** With `render.alwaysDaylight` on, Minecraft lights its blocks as at noon, and the effect then applies
  GTA's light, so a night doesn't darken them twice. Minecraft's real time still runs: mobs, crops and `/time query` see
  it.
- **Relighting.** Each Minecraft pixel takes brightness and colour from GTA's picture, blurred over a very wide area, about
  1/100 of the screen. The blur is that wide so the shapes of what's behind a block never show through it. Gain is
  limited to 0.6 to 1.2 and tint to 0.75 to 1.25.
- **GTA's look.**
  - **Colour grade:** Minecraft takes on the colour of GTA's whole picture: an orange sunset, a red Nether night.
  - **Haze:** from 40 m, far blocks fade toward the colour of GTA's air.
  - **Edges:** Minecraft's edges are softened against GTA.
  - **Contact shadows:** under mobs' feet and around blocks.
  - **Glow:** lava, fire and portals glow over GTA. You're kept out of the glow.
- **Crisp pixels.** Minecraft's textures are scaled up sharp, each texel flat.
- **Cleaned-up picture.**
  - GTA's camera motion blur is off, because an explosion would smear GTA but not Minecraft.
  - Minecraft's fires don't puff big black smoke squares.
  - Minecraft's pop-ups (advancements, recipes, tutorials) don't show.

### Input: playing Minecraft through GTA

GTA's window has the focus, so the script hands Minecraft what it needs.

- **On foot.** The mouse buttons, the wheel, the number keys, and E, Q, T, /, X and the middle button go to Minecraft.
  GTA's own attack, aim, melee and weapon controls are switched off meanwhile, except in gun mode.
- **With a Minecraft screen open** (inventory, creative menu, chat, a command), Minecraft reports it, and GTA hands it
  everything:
  - every key, with the text it types (your keyboard layout, Shift, AltGr);
  - the cursor position, the buttons and the wheel;
  - GTA acts on none of it meanwhile, and the effect draws the cursor.

  If Minecraft stops reporting for 3 s, GTA takes its input back.
- **No fighting over the cursor.** Minecraft's window never needs the focus, and Minecraft never takes the cursor.
- **No stuck keys.** When GTA disconnects, every key and button GTA was holding in Minecraft is released.

### What Minecraft sends back to GTA

| In Minecraft | In GTA |
|---|---|
| TNT explodes (radius 4) | a sticky-bomb explosion at the same spot, and the screen shakes |
| A creeper or a smaller blast | a grenade explosion |
| A ghast fireball, a wither skull, a dragon fireball | a rocket's blast and burning fuel |
| Your arrow flies | GTA traces it through its own world. If it hits a person or a car, it becomes a GTA bullet into them; if it hits a wall, the arrow sticks there in Minecraft. |
| Your crossbow firework flies | GTA traces it, and it bursts as a GTA rocket where it hits |
| You swing (attack) | people in front of you are knocked over, and cars are shoved |
| An ender pearl or `/tp` moves you | GTA's player is moved there |
| A block is placed or broken | an invisible GTA collision box appears or goes |
| Hostile mobs move and attack | GTA keeps an invisible stand-in for each one, and their hits hurt GTA's people |
| The Nether opens | GTA's sky goes red and its clock runs to midnight; Minecraft's lava and fire set GTA's people and cars alight and light up GTA's world |
| You stand in a portal | the picture warps |
| The time or weather changes | GTA's clock and weather follow (see below) |

### Mobs vs GTA's people

- **Every hostile mob fights.** Two kinds are handled differently:
  - Goal-driven mobs (zombies, skeletons, creepers, spiders, endermen, slimes, ghasts, pillagers and more) get a target
    goal for GTA's people.
  - Brain-driven mobs (piglins, piglin brutes, hoglins, zoglins, breezes, the creaking and wardens) are walked to the
    nearest person and attack in reach. Breezes shoot wind charges.
- **GTA's people in Minecraft.** GTA lists its people within 96 blocks of you. Each one gets an invisible stand-in in
  Minecraft: an AI-less villager that follows them, and that mobs hunt within 48 blocks. A mob's hit on a stand-in hurts
  the real GTA person, at 12 times Minecraft's damage.
- **Minecraft's mobs in GTA.** Each mob gets an invisible, frozen GTA stand-in (up to 48 of them, 5,000 health each) in a
  group GTA's police hate, so the police really shoot at them. The health a stand-in loses goes back to its mob, at 1/8.
- **Your own shots skip the stand-ins.** Your arrows and rockets fly through them, because GTA traces them into the real
  people instead.
- **You bring the mobs.** Natural spawning is off in the passthrough world: use creeper eggs, spawn eggs from the creative
  menu, `/summon`, or the Nether.
- **Your GTA player is safe:** invincible, never wanted, and ignored by the police.

### The Nether

1. **Open it.** Build a nether portal (an obsidian frame, at least 4 wide and 5 tall), light it with flint and steel, and
   walk into it while GTA is connected.
2. **The ground turns.** The ground around the portal, 26 blocks out, turns into the Nether bit by bit over 10 seconds:
   netherrack, magma, crimson nylium, soul sand, fire, and lava rivers running out of the portal's front and back.
3. **Mobs pour out.** Nether mobs keep coming out of the portal, up to 30 at a time.
4. **GTA joins in.**
   - GTA's sky goes red and its clock races to midnight.
   - People and cars standing on Minecraft's lava or fire burn.
   - Minecraft's lava lights GTA's world.
5. **Stepping into the portal** warps the picture, but nobody travels: the Nether comes out instead.
6. **Closing it.** When the world closes (quit Minecraft or leave the world), everything the Nether changed is put back
   and the portal goes out, so nothing of it is saved. Lighting the frame again opens it again.

### Time and weather

- **What it watches.** Minecraft watches its own clock, its rain and thunder, and the `advance_time` and `advance_weather`
  gamerules every tick, whatever changes them: commands, sleeping, functions.
- **The clock.** Tick 0 is 06:00 in GTA (`env.hourOffset`), and 1,000 ticks make an hour. A set time eases in over 3 s.
- **The weather.** It eases in over 15 s, to GTA's CLEAR, RAIN or THUNDER. Only the look is matched: GTA's rain isn't
  Minecraft's.
- **Modes:**
  - `commands`, the default: GTA keeps its own clock and weather until Minecraft's change, and follows Minecraft's clock
    while `advance_time` is on.
  - `minecraft`: GTA always follows.
  - `off`: GTA never follows.
- **What isn't mirrored.** The mod's own time and weather commands (world setup, the Nether) aren't mirrored, and while the
  Nether is open, the Nether owns GTA's sky.

### Flight

- **Creative flight**, the way Minecraft does it:
  - Double-tap Space within 350 ms to fly.
  - W/A/S/D fly along the camera, Space goes up, Shift down, and Ctrl flies faster.
  - The speeds are Minecraft's: 10.9 m/s, 21.8 m/s with Ctrl, and 7.5 m/s up or down.
  - GTA's player is frozen and moved, so GTA's own camera follows.
  - GTA's buildings, its cars and your blocks stop you.
  - To land, fly down onto the ground, or double-tap again to drop.
- **Elytra flight:**
  - Press Space in mid-air, at least 150 ms into a jump or a fall (not the tap that jumped).
  - Minecraft's own physics fly Steve, and GTA follows with a chase camera.
  - The mouse steers, and right-click fireworks to boost.
  - Flying into a wall ends the glide where you hit it, and you fall. Space opens the elytra again.
  - Touching down lands you back on foot. A double tap swaps the elytra for creative flight.

### What the mod changes in Minecraft

The mod is meant for a Minecraft profile of its own (see [Installing](#installing)), because it changes these things:

- **Options:**
  - keeps running when unfocused, and only slows down when minimized;
  - clouds off, view bobbing off, vignette off;
  - FOV effects 0, damage tilt 0, menu blur 0;
  - vsync off and a 120 fps cap.
- **The world.** From the title screen, it opens a world called `passthrough` by itself, or creates it first. It's a
  creative world of empty air (a void), so nothing is Minecraft but what you build.
- **On joining, it sets up the world:**
  - gamerules: time and weather stand still, no mob spawning, patrols, phantoms or wandering traders, no command feedback,
    keep inventory, no advancement messages, no movement check;
  - normal difficulty, noon, clear weather;
  - your hotbar (see [Controls](#controls)).
- **No death screen.** If you die, you respawn straight away, so the death screen never covers GTA.
- **While GTA is connected:**
  - no fog;
  - no chunk fade-in, which flashed distant blocks dark while the camera turned;
  - undead don't burn in the daylight;
  - nothing travels through portals;
  - the player doesn't collide with blocks, because GTA moves them.

## Requirements

- **A Windows 10 or 11 PC that can run both games at once.** They share the GPU, so leave GTA some headroom.
- **GTA V on Steam**, story mode:
  - Enhanced (`GTA5_Enhanced.exe`, Steam app 3240220, DirectX 12). This is the main target, tested with game build
    1.0.1158 and ScriptHookV 3889.0.
  - Legacy (`GTA5.exe`, Steam app 271590, DirectX 11). This is the original target, tested with game build 3889.
- **ScriptHookV** that matches your GTA build, with its ASI loader. After a GTA update, wait for a new ScriptHookV.
- **ReShade 6.8.0 with add-on support** (the "Addon" download, not the normal one).
- **Minecraft Java Edition 26.3**, with Fabric Loader 0.19.5 or newer and Fabric API for 26.3 (0.161.0+26.3 or newer).
- **Java 25.** The Minecraft launcher and the Modrinth App bring their own.

ScriptHookV and ReShade may not be redistributed, so they never come with this repo. Get them from their own sites, or
let `gta\fetch_deps.ps1` download them for you.

## Installing

You need the mod's own files:
- `passthrough-0.1.0.jar` for Minecraft;
- `MCPassthrough.asi` and the effect `MCPassthrough.fx` for GTA.

Build them from this repo (see [Building from source](#building-from-source)). They land in `mc\build\libs\`, `gta\build\`
and `gta\shaders\`. Then there are three steps: Minecraft, GTA V, GTA's settings.

### Step 1: Minecraft

Give the mod a Minecraft profile of its own, with its own game folder. It changes options and makes its own world, and your
normal worlds should stay untouched.

**With the Modrinth App:**
1. Create a profile: Minecraft **26.3**, loader **Fabric**, loader version 0.19.5 or newer.
2. Add **Fabric API** to it: search for it in the profile's content browser.
3. Add `passthrough-0.1.0.jar`. You can drag it onto the profile, or open the profile's folder and put it into `mods`.

**With the official Minecraft Launcher:**
1. Download Fabric's installer from [fabricmc.net](https://fabricmc.net/use/installer/) and run it (double-click it; it needs
   Java). Choose **Client**, Minecraft **26.3**, loader 0.19.5 or newer, and press **Install**. It adds a
   `fabric-loader-...-26.3` installation to the launcher.
2. In the launcher, open **Installations**, edit that installation, and set its **Game directory** to a new empty folder,
   for example `C:\Games\MinecraftGTA`. If the installer couldn't add the installation (it can fail with the Microsoft
   Store launcher), add it yourself with that version.
3. Put `passthrough-0.1.0.jar` and the Fabric API jar for 26.3 (from [Modrinth](https://modrinth.com/mod/fabric-api)) into
   `<that game directory>\mods`. Create the folder if it isn't there.

**Check it:** start that profile once. The game should open a world called "Passthrough" by itself, and Minecraft's log
should say `host link listening on 127.0.0.1:25599`.

### Step 2: GTA V

Close GTA first. Then let the installer put the files in, or copy them by hand.

**With the installer**, from PowerShell in this folder:

```powershell
gta\fetch_deps.ps1                  # once: downloads ScriptHookV and ReShade 6.8.0 (the add-on build)
gta\install.ps1 -Edition Enhanced   # or -Edition Legacy
```

- **What it does.** `install.ps1` finds GTA in your Steam libraries (or pass `-GtaDir <folder>`) and copies in every file in
  the table below. A `ReShade.ini` that's already there is kept.
- **What it won't do.** It refuses to replace a loader, ReShade or `args.txt` that another mod put there (`-Force`
  overrides). It also keeps no copies of what it replaces, so back up anything you want to keep first.

These are the files and what each one does:

| File in GTA's folder | Enhanced | Legacy | What it is |
|---|---|---|---|
| `ScriptHookV.dll` | yes | yes | ScriptHookV, by Alexander Blade |
| ASI loader | `xinput1_4.dll` | `dinput8.dll` | loads `.asi` files into GTA (from ScriptHookV's download for your edition) |
| ReShade 6.8.0 (add-on build) | as `dxgi.dll` | as `ReShade64.asi` | Enhanced loads a `dxgi.dll` from its folder. Legacy loads the system one first, so there the ASI loader loads ReShade instead. |
| `MCPassthrough.asi` | yes | yes | this mod's GTA half |
| `args.txt` | yes | yes | `-nobattleye -noBE`: starts GTA with BattlEye off, which ScriptHookV needs (and which keeps Online out) |
| `ReShade.ini` | yes | yes | ReShade's settings: where the effects are, and GTA's depth format (reversed) |
| `ReShadePreset.ini` | yes | yes | turns on the MCPassthrough effect |
| `reshade-shaders\Shaders\MCPassthrough.fx` | yes | yes | the effect |
| `reshade-shaders\Shaders\ReShade.fxh`, `ReShadeUI.fxh` | yes | yes | ReShade's headers, which the effect includes |

**By hand:**
1. **Find GTA's folder.** In Steam, right-click GTA V Enhanced, then Manage, then Browse local files. It's the folder with
   `GTA5_Enhanced.exe` (`GTA5.exe` for Legacy).
2. **ScriptHookV:** from [dev-c.com](https://www.dev-c.com/gtav/scripthookv/), copy `ScriptHookV.dll` and the ASI loader for
   your edition into GTA's folder.
3. **ReShade:** download **ReShade 6.8.0 with full add-on support** from [reshade.me](https://reshade.me).
   - **Enhanced:** run its setup, pick `GTA5_Enhanced.exe` and DirectX 12. It installs `dxgi.dll`.
   - **Legacy:** take `ReShade64.dll` out of the setup (it's a zip) and put it into GTA's folder as `ReShade64.asi`.
4. **This mod's files:**
   - `MCPassthrough.asi` goes into GTA's folder.
   - `MCPassthrough.fx` (from `gta\shaders\`) goes into `reshade-shaders\Shaders`.
   - So do `ReShade.fxh` and `ReShadeUI.fxh`. They're in `gta\third_party\` after `fetch_deps`, or in
     [crosire/reshade-shaders](https://github.com/crosire/reshade-shaders).
5. **The settings files**, in GTA's folder:
   - `args.txt`, one line: `-nobattleye -noBE`
   - `ReShadePreset.ini`:
     ```ini
     Techniques=MCPassthrough@MCPassthrough.fx
     TechniqueSorting=MCPassthrough@MCPassthrough.fx
     ```
   - `ReShade.ini`, if you don't have one already (if you do, make sure its `[GENERAL]` section has these lines):
     ```ini
     [GENERAL]
     EffectSearchPaths=.\reshade-shaders\Shaders\
     TextureSearchPaths=.\reshade-shaders\Textures\
     PresetPath=.\ReShadePreset.ini
     PreprocessorDefinitions=RESHADE_DEPTH_INPUT_IS_REVERSED=1,RESHADE_DEPTH_INPUT_IS_UPSIDE_DOWN=0,RESHADE_DEPTH_INPUT_IS_LOGARITHMIC=0,RESHADE_DEPTH_LINEARIZATION_FAR_PLANE=1000

     [OVERLAY]
     TutorialProgress=4
     ShowClock=0
     ShowFPS=0

     [SCREENSHOT]
     SavePath=.\
     ```

**Other mods:**
- In Enhanced, a leftover `dinput8.dll` does nothing (Enhanced's loader is `xinput1_4.dll`).
- Other ASI mods (for example ScriptHookV's NativeTrainer) can stay.
- If you already use another ReShade or a `dxgi.dll` from another mod, keep only one.

### Step 3: GTA's settings

- **Windowed or borderless**, and **"Pause game on focus loss" off.**
- **Depth of field off, and post effects lowered.** They blur GTA's picture but not Minecraft's.
- **Frame generation off** (DLSS frame generation, FSR 3 frame generation). ReShade would put Minecraft into GTA's real
  frames but not into the generated frames between them, so Minecraft would flicker.
- **HDR off.** The effect expects a normal SDR picture.
- **Upscaling is fine** (DLSS, FSR, XeSS): the effect runs on the finished picture.
- **Aim for 60 fps.** At 30 fps everything Minecraft visibly steps behind. Ray tracing at native resolution, with Minecraft
  rendering on the same GPU, can sink GTA that low, so use DLSS (Quality) and lower the ray-traced GI and reflections.

## Turning it on and playing

1. **Start Minecraft** with the mod's profile. It opens the Passthrough world by itself. Leave its window open, even behind
   GTA. Don't minimize it: minimized, it renders slowly.
2. **Start GTA V** from Steam. BattlEye is off through `args.txt`; the BattlEye toggle in the Rockstar Games Launcher's
   settings works too.
3. **Pick Story Mode** on the landing page yourself.
4. **Wait for "Minecraft passthrough connected"** in the corner. Then the passthrough is on:
   - Minecraft's window is resized to GTA's picture;
   - the ground starts to fill in around you;
   - your GTA character turns into you, in Minecraft.

The two games can start in either order, because the GTA side keeps reconnecting. If Minecraft closes, GTA goes back to its
own picture, and when Minecraft comes back, it reconnects.

**Turning it on and off:**
- **F7** turns the whole passthrough off and on, in game. Off, GTA's player shows again, and the collision boxes and mob
  stand-ins go.
- **Features** turn on and off in the [settings](#settings): native blocks, your layer, your skin, time and weather.
- **The ReShade effect** is on through `ReShadePreset.ini`. If you switch ReShade presets, make sure **MCPassthrough** is
  ticked in ReShade's Home tab. It does nothing until Minecraft's frames arrive.
- **To remove it completely**, see [Removing](#removing).

## Controls

| Key | What it does |
|---|---|
| F7 | passthrough off and on |
| F8 | re-level Minecraft's ground to where you stand |
| F9 | cycle the pose lag 0 to 3 (keep the value where blocks stay glued to GTA while you turn) |
| F10 | the diagnostics overlay (see [Checking that it works](#checking-that-it-works)) |
| Left mouse | Minecraft's attack: break blocks, swing the sword |
| Right mouse | Minecraft's use: place blocks, light TNT, shoot, throw pearls, boost with fireworks |
| Mouse wheel, 1 to 9 | Minecraft's hotbar |
| Middle mouse | pick block |
| E | Minecraft's inventory, which in creative is the creative menu |
| Q, Ctrl+Q | drop the held item, or the whole stack |
| T, / | Minecraft's chat, or a command |
| X | swap hands (Minecraft's F is GTA's car key, so it's X here) |
| Tab | gun mode: GTA's carbine rifle, minigun, RPG, then back to Minecraft |
| Space, Space (double tap) | creative flight on and off; while gliding, it swaps the elytra for creative flight |
| W/A/S/D, Space, Shift, Ctrl | while flying: along the camera, up, down, faster |
| Space in mid-air | open the elytra; the mouse steers |
| Any key, with a Minecraft screen open | goes to Minecraft: typing, shift-click, dragging items, searching; Esc or E closes it |

Everything else (walking, running, driving, the camera, GTA's view key) is GTA's own, and Minecraft follows. In first person
(GTA's view key), you see Minecraft's hand and crosshair.

**The hotbar you start with:**
1. ender pearls
2. a diamond sword
3. a crossbow (Multishot, Quick Charge III)
4. a bow (Power V, Infinity)
5. TNT
6. flint and steel
7. creeper spawn eggs
8. grass blocks
9. fireworks

Your off hand holds explosive fireworks for the crossbow, and you get 64 arrows. Everything else is in the creative menu
(E).

## Settings

### Minecraft: config/passthrough.properties

The file is in the Minecraft profile's `config` folder. It's written with the defaults the first time Minecraft starts. A
bad value is logged and replaced by its default, never a crash. Restart Minecraft after editing it.

| Setting | Default | What it does |
|---|---|---|
| `env.time` | `commands` | How Minecraft's time reaches GTA: `commands` (when it's changed, and while `advance_time` runs), `minecraft` (always), `off` |
| `env.weather` | `commands` | The same for the weather |
| `env.hourOffset` | `6` | GTA's hour at Minecraft tick 0, from 0 to 23 (Minecraft's sunrise is 6:00) |
| `env.blendSeconds` | `3` | How long GTA takes to reach a new time, from 0 to 60 s |
| `env.weatherBlendSeconds` | `15` | How long GTA takes to reach new weather, from 0 to 120 s |
| `render.alwaysDaylight` | `true` | Minecraft lights its blocks as at noon, and GTA's light is put on them by the effect |
| `render.overscan` | `1.12` | How much wider Minecraft's view is than GTA's in third person, from 1.0 to 1.5 (room to turn into) |
| `render.ownSkin` | `true` | Your account's skin; `false` is classic Steve |
| `render.avatarLayer` | `true` | You in a layer of your own, shown as drawn; `false` puts you in the world picture |
| `render.nativeBlocks` | `true` | GTA draws Minecraft's blocks itself; `false` keeps the blocks in Minecraft's picture |

### Minecraft: Java options

These are for testing. Add them to the profile's Java arguments.

| Option | What it does |
|---|---|
| `-Dpassthrough.port=25599` | the link's port (the GTA side always uses 25599) |
| `-Dpassthrough.noAutoWorld=true` | don't open the Passthrough world from the title screen |
| `-Dpassthrough.noNativeBlocks=true` | native blocks off, whatever the config says |
| `-Dpassthrough.noAvatarLayer=true` | the avatar layer off |
| `-Dpassthrough.classicSteve=true` | classic Steve |
| `-Dpassthrough.cpuFrames=true` | no shared GPU frames: always the shared-memory copy |
| `-Dpassthrough.keepFadeIn=true` | keep Minecraft's chunk fade-in (only to compare) |

### GTA: the ReShade effect

Press **Home** in GTA to open ReShade, and pick **MCPassthrough**. Changes are saved into `ReShadePreset.ini`.

| Setting | Default | What it does |
|---|---|---|
| GTA depth is reversed | on | GTA's depth format; leave it on |
| Depth bias (m) | 0.04 | how far behind GTA's surface Minecraft may still show (blocks resting on the ground) |
| Depth bias per grazing slope | 0 | extra bias where GTA's ground is seen at a grazing angle |
| Largest depth bias (m) | 3.0 | the cap for the two above |
| GTA depth scale | 1.0 | calibration for GTA's distances |
| Match GTA lighting | 0.7 | how much Minecraft is relit by GTA's picture |
| Neutral brightness | 0.42 | the GTA brightness at which Minecraft keeps its own colours |
| Light colour | 0.3 | how much GTA's light tints Minecraft |
| Light blur (mip) | 4.6 | how wide an area of GTA lights each Minecraft pixel; low values look see-through |
| Match GTA colour grade | 0.55 | Minecraft takes on GTA's overall colour |
| Haze start (m) / Haze distance (m) / Haze strength | 40 / 220 / 0.6 | far Minecraft fades toward GTA's air |
| Crisp Minecraft pixels | on | sharp texels instead of a blur |
| Soften Minecraft's edges | 0.15 | anti-aliasing where Minecraft meets GTA; high values look like a see-through rim |
| Contact shadows / size (m) | 0.5 / 0.7 | darkens GTA right next to Minecraft things |
| Minecraft glow / Glow threshold | 0.55 / 0.62 | bright Minecraft things glow over GTA |
| Re-project to GTA's camera | on | moves Minecraft's slightly older frame onto GTA's current camera |
| Pose prediction (frames) | 0 | pushes GTA's camera rotation ahead by this many frames before re-projecting |
| Pose lag (frames) | 0 | the same as F9 (F9 overrides it; Enhanced starts at 1) |
| Move Steve with the player | on | used when the avatar layer is off |
| Debug view | Composite | see below |

## Checking that it works

**F10** shows six lines in the top left:
1. `MC passthrough on|off (F7)  link up|DOWN (session n)  GTA n fps`
2. `Minecraft frames n/s (GPU shared|CPU copy, avatar layer, native blocks)  age n ms  WxH  torn refused n`, plus
   `STALE: Minecraft stopped` when Minecraft froze or closed
3. `pose lag 0-3 (or slider)  input GTA only|play (E Q T / X to Minecraft)|MINECRAFT SCREEN  flight none|creative|elytra`
4. `env: Minecraft time HH:MM (GTA follows|GTA's own clock|blending)  weather ...`
5. `mobs n  doubles n  props n/400  people sent n  nether OPEN|closed`
6. the keys F7 to F10

On GTA V Enhanced with everything working, line 2 says **GPU shared, avatar layer, native blocks**. Legacy says **CPU
copy**.

**Debug views** (ReShade, MCPassthrough, "Debug view"):

| View | What it shows |
|---|---|
| GTA depth (1 m bands) | GTA's depth as even bands. They should cover the whole world, steady. If they're missing or flicker, pick the full-screen depth buffer in ReShade's Add-ons tab (Generic Depth). |
| Minecraft depth (1 m bands) | the same for Minecraft |
| Depth difference | where Minecraft is in front of GTA, and where it's behind |
| Avatar layer | your layer alone (red: none this frame) |
| Avatar depth test | green where you show, red where GTA is in front |
| Avatar vs world | blue where you're nearer, yellow where the world is nearer |
| Native blocks | the native block layer alone (red: off this frame) |

**Logs:**
- **Minecraft:** `logs\latest.log` in the profile, the lines with `passthrough`. Look for `host connected`, `GPU frames`,
  and `native blocks on: the host holds this exact mesh and atlas`.
- **GTA's folder:**
  - `ScriptHookV.log` should say `Registering script 'MCPassthrough.asi'`;
  - `asiloader.log` lists what was loaded;
  - `ReShade.log` has the lines with `MCPassthrough`, for example `native blocks: on (drawn)`.

## Troubleshooting

| Problem | What to check |
|---|---|
| "Minecraft passthrough connected" never shows | Is Minecraft running with the mod (`host link listening` in its log)? Does `ScriptHookV.log` say `INIT: Success` and register `MCPassthrough.asi`? After a GTA update, ScriptHookV refuses to run until there's a new version. In Enhanced, the loader is `xinput1_4.dll`. |
| Connected, but no Minecraft in the picture | Does Home open ReShade, and is MCPassthrough ticked? Does F10 show Minecraft frames? Minecraft must be in its world and not minimized. |
| Minecraft shows through GTA's things, or hides behind nothing | GTA's depth: use the debug view "GTA depth (1 m bands)", and pick the right depth buffer in ReShade's Add-ons tab (Generic Depth). Keep "GTA depth is reversed" on. |
| Blocks swim or double while turning | F9 through 0 to 3 while turning, and keep the steadiest. Frame generation must be off, and GTA should hold 60 fps. |
| Minecraft flickers | Frame generation off, HDR off. |
| Everything feels slow | GTA's frame rate matters most: DLSS (Quality), and less ray-traced GI and reflections. Minecraft already caps itself just above GTA's fps. |
| Minecraft looks soft in Legacy | Legacy's shared-memory path renders at about 1080p worth of pixels and scales up. Enhanced's GPU path renders at full resolution. |
| You look like classic Steve | Set `render.ownSkin=true`. Offline accounts only have default skins. |
| You want the old blocks back | Set `render.nativeBlocks=false`. |
| GTA closed when it tried to go online | That's ScriptHookV protecting you: modded GTA is story mode only. |
| A Minecraft key seems stuck | Press it again, or Esc. Keys are released when GTA disconnects. |
| The Passthrough world is cluttered | It's `saves\passthrough` in the profile. Delete it (with Minecraft closed) to start fresh. |
| A file can't be replaced while updating | Close both games first: Windows locks their files while they run. |

## Updating and removing

### Updating

After a new build, close both games, then:
1. **Minecraft:** replace `passthrough-0.1.0.jar` in the profile's `mods` folder with the new one from `mc\build\libs\`.
2. **GTA:** run `gta\install.ps1 -Edition Enhanced` again. It copies the new `MCPassthrough.asi` and `MCPassthrough.fx` over
   the old ones, and keeps your `ReShade.ini`.

Copy the old files somewhere first if you want to be able to go back.

### Removing

- **GTA:**
  - delete `MCPassthrough.asi`, `ScriptHookV.dll`, the ASI loader (`xinput1_4.dll` or `dinput8.dll`), ReShade (`dxgi.dll`
    or `ReShade64.asi`), `args.txt`, `ReShade.ini`, `ReShadePreset.ini` and `reshade-shaders\Shaders\MCPassthrough.fx`;
  - or run `gta\install.ps1 -Remove -Edition Enhanced`, which removes exactly the files it installs.

  Without `args.txt`, GTA starts with BattlEye again.
- **Minecraft:** delete `passthrough-0.1.0.jar` from the profile's `mods`. You can also delete `config\passthrough.properties`
  and `saves\passthrough`, or the whole profile.

## Safety and fair play

- **Story mode only, never GTA Online.**
  - BattlEye protects GTA Online. The passthrough runs with BattlEye off, which also keeps Online from starting, and
    ScriptHookV closes the game if it goes online anyway.
  - Don't try to get a modded game near Online.
  - Nothing in this mod targets other players.
- **Pick Story Mode by hand.** Nothing in this project clicks GTA's landing page for you, on purpose. Once, during
  development, keystrokes meant for another window landed in GTA's landing page, and GTA showed "attempting to access GTA
  Online servers with an altered version". ScriptHookV blocked it, but don't risk it.
- **The game folder.** The installer only adds its own files, refuses to replace another mod's loader or ReShade, and can
  remove exactly what it added.
- **The link.**
  - It listens on 127.0.0.1:25599 only, so nothing outside your PC can reach it.
  - It refuses web pages: browsers can open WebSockets to 127.0.0.1, so any connection a browser makes is turned away.
  - There's no password, so other programs on your own PC could still send it commands. Close Minecraft when you're done.
- **Redistribution.**
  - ScriptHookV and ReShade come from their own sites and aren't in this repo.
  - Neither is anything from Minecraft or GTA: no game files, extracted assets or decompiled code.

## Technical reference

### Link messages

All of them are JSON on the WebSocket `ws://127.0.0.1:25599`, in Minecraft's coordinates.

**From GTA to Minecraft:**

| Message | What it carries |
|---|---|
| `host` | GTA's process id, sent on connect (the native blocks are published for that process) |
| `cam` | every GTA frame: frame number `f`, camera `p` and `r` (yaw, pitch, roll), `fov` (vertical, degrees), first person `fp`, player feet `pl`, body yaw `h`, gun mode `gun`, moves `mv` (1 sprint, 2 crouch, 4 swim, 8 under water, 16 seated, 32 climb); during elytra flight, `drive` and `look` |
| `ground` | barrier columns `[x, z, yBottom, yTop, ...]` |
| `clear` | remove the barriers placed so far |
| `blocksync` | send every solid block within `r` blocks again |
| `view` | Minecraft's window size (GTA's picture, scaled to the frame budget) |
| `hostfps` | GTA's frame rate, once a second (Minecraft caps itself at 1.25 times it, 40 to 120) |
| `key`, `slot`, `scroll` | play input: `use`, `attack`, `pick`, `inventory`, `drop`, `swap`, `escape`; the hotbar |
| `rawkey`, `char`, `mouse`, `mbtn`, `mscroll` | everything, while a Minecraft screen is open (SDL scancodes, typed text, cursor) |
| `glide` | the elytra on or off, with a launch speed |
| `projhit` | GTA traced one of your projectiles into something: burst, gone or stuck |
| `peds` | GTA's people near you `[[handle, x, y, z], ...]` |
| `mobdmg` | damage GTA's police did to a mob |
| `cmd` | a server command (run as op, output to the log) |
| `spawnmobs`, `mobsclear`, `portal`, `netheroff`, `nethersync`, `hud` | scripted actions |
| `gta`, `gtastate`, `gtainfo`, `director` | passed on to the other connections (for scripted shots) |

**From Minecraft to GTA:**

| Message | What it carries |
|---|---|
| `hello` | protocol version, the shared memory's name, Minecraft's process id |
| `blocks` | block changes this tick: `set` and `clear` lists |
| `explosion` | where, the radius and the source (`tnt`, `creeper`, `fireball`, ...) |
| `proj` | your arrows and crossbow fireworks in flight `[[id, kind, x, y, z], ...]` |
| `melee` | you swung |
| `pteleport` | Minecraft moved you (ender pearl, `/tp`) |
| `mcpos` | during elytra flight: your position, velocity and timestamp |
| `mobs`, `mobhit` | the hostile mobs near you, and a mob's hit on a person's stand-in |
| `env` | time (`time`, `hoff`, `follow`, `jump`, `blend`) or weather, with a sequence number |
| `nether`, `hot`, `inportal` | the Nether opened or closed, Minecraft's lava and fire, you standing in a portal |
| `screen` | a Minecraft screen opened or closed |

A handshake that carries an `Origin` header (every browser sends one) is refused, and a message over 4 MB is too.

### Shared memory and textures

Every one of these is a named Windows mapping in the `Local\` namespace, so only programs in your own session can open it.

| Name | Size | Written by | What it holds |
|---|---|---|---|
| `Local\MCPassthroughFrame` | about 221 MB | Minecraft (GTA writes one field) | `MCPT`. A 4 KB header and 3 slots, each with 5 layers of up to 2560x1440: world colour (RGBA8), world depth (float), overlay (RGBA8), avatar colour (RGBA8), avatar depth (float). Each slot has a descriptor: sequence, size, near/far/fov, flags, the camera pose it was rendered with, the publish time and the player's feet. The header also holds the slot GTA is reading, which Minecraft skips, and per slot, the native mesh revision that frame left out (`MCSC`). |
| `Local\MCPassthroughGpuFrame` | small | GTA | `MCGT`, the GPU route's control block. GTA's add-on creates 3 sets of 5 D3D12 textures and a fence, shared by name (`Local\MCPassthroughGpu_<pid>_<generation>_...`). Minecraft imports them into OpenGL and copies its layers in, and GTA copies them out. |
| `Local\MCPassthroughBlockMesh` | 64 MB | Minecraft | the native block mesh: 28-byte vertices (position, texture, colour, light), by section |
| `Local\MCPassthroughBlockAtlas` | 90 MB | Minecraft | the block texture atlas, with every mip level |
| `Local\MCPassthroughBlockAnim` | 4 MB | Minecraft | the current frame of every animated texture |
| `Local\MCPassthroughBlockLight` | about 1 KB | Minecraft | the lightmap |
| `Local\MCPassthroughBlockDetailMesh`, `...DetailAtlas` | 8 MB, 64 MB | Minecraft | mining cracks and the block outline (24-byte vertices, lit already) |
| `Local\MCPassthroughBlockMeshAck` | 128 bytes | GTA | "I hold mesh revision n and atlas revision m", refreshed every frame (stale after 250 ms) |

All of them use a sequence lock. A writer bumps a counter before and after writing, and a reader that sees the counter move
reads again, so nobody ever uses half a frame or half a mesh. A mutex stops two Minecraft instances from writing at once.

### Minecraft hooks (mixins)

**Client:**

| Hook | What it changes |
|---|---|
| `CameraMixin` | Minecraft's camera is GTA's: position, rotation with roll, field of view, first or third person |
| `GameRendererMixin` | no sky; the frame is split into the world and the overlay; your layer is drawn after the world |
| `FogRendererMixin` | no fog, and a black fog colour, so empty pixels stay empty |
| `LevelRendererMixin` | no chunk fade-in while connected |
| `LightmapMixin` | always-daylight lighting (`render.alwaysDaylight`) |
| `AvatarLayerMixin` | the world pass leaves you out while you have your own layer |
| `AvatarRendererMixin` | your pose from GTA's moves (crouch, swim, seated), and holding GTA's guns |
| `AbstractClientPlayerMixin` | your own skin, or classic Steve |
| `EntityPickMixin` | in third person you aim along the camera's ray, so what's under the crosshair is what you hit |
| `LivingEntityMixin` | the walk animation comes from GTA's movement |
| `LocalPlayerMixin` | each tick, the player is put at GTA's player |
| `MouseHandlerMixin` | GTA's cursor drives Minecraft's screens without Minecraft having the focus |
| `ClientLevelMixin` | no black smoke squares from fires |
| `ToastManagerMixin` | no pop-ups |
| `GlCommandEncoderMixin` | fixes a Minecraft 26.3 bug: reading depth back broke every later colour readback |
| `BlockMeshChunksMixin` | leaves the chunks out of the picture while GTA draws the acknowledged mesh |
| `BlockMeshDirtyMixin`, `BlockMeshLightMixin` | block edits, chunk loading, tint and light changes mark the mesh's sections dirty |
| `BlockMeshLevelMixin` | the mesh's frame: it starts once Minecraft's view is current; changed blocks aren't drawn twice while they compile |
| `BlockDetailsMixin` | mining cracks and the selection outline go native |

**Server:**

| Hook | What it changes |
|---|---|
| `LevelMixin` | every block change goes to GTA |
| `ServerExplosionMixin` | every explosion goes to GTA |
| `ServerPlayerMixin` | Minecraft moving you (pearls, `/tp`) moves GTA's player too |
| `PlayerMixin` | the player doesn't collide with blocks (GTA moves them) |
| `ProjectileMixin` | your arrows and rockets fly through the people's stand-ins |
| `ProxyMixin` | the stand-ins take no damage (hits go to GTA), aren't pushed, and the crosshair ignores them |
| `MobMixin` | undead don't burn in the always-day world |
| `PortalMixin` | nothing travels through portals |

## Project files

| Path | What it is |
|---|---|
| `mc/` | the Fabric mod (Java 25, Loom). `src/main/java`: the server side (`Passthrough`, `Config`, `WorldBridge`, `EnvSync`, `MobWar`, `Nether`, and their mixins). `src/client/java`: the client side (`HostLink`, `HostState`, `HostInput`, `ClientInput`, `PlayerSync`, `FrameExporter`, `GpuTransport`, `SharedMemory`, `HostAvatarRenderer`, the `BlockMesh*` and `BlockDetails` classes, and their mixins). |
| `gta/src/script.cpp` | the ScriptHookV script: camera, ground, input, explosions, projectiles, flight, mobs vs people, the Nether, time and weather, the F10 overlay |
| `gta/src/compositor.cpp` | the ReShade add-on: receives Minecraft's frames, sets the effect's inputs, runs the native blocks |
| `gta/src/gpu_host.cpp` | the shared D3D12 textures and fence for GPU frames |
| `gta/src/blocks/` | the native block renderer: protocol, shared memory readers, the acknowledgement, the shader, the see-through sort, and the renderer (D3D12 and D3D11) |
| `gta/src/natives.h`, `ws.cpp` | the GTA natives the script calls (by hash), and a small WebSocket client |
| `gta/shaders/MCPassthrough.fx` | the ReShade effect |
| `gta/fetch_deps.ps1`, `build.bat`, `install.ps1` | download ScriptHookV and ReShade (into `gta/third_party/`, never committed), build with MSVC, install into GTA |
| `gta/tests/` | test hosts: `fakegta.cpp` (a GTA stand-in, DirectX 11 or 12), `gpu_frames_test.cpp`, `ws_test.cpp`, and `build_fakegta.bat` |
| `host/` | tests against the dev client: `e2e_test.ps1`, `frame_test.ps1`, `pose_test.ps1` |
| `FEATURES.md` | the status of every feature, and how each was checked |
| `THIRD_PARTY_NOTICES.md`, `LICENSE` | the licences |

## Building from source

You need:
- **JDK 25**, for example Temurin 25;
- **Visual Studio 2022 or newer** with "Desktop development with C++" (x64).

From PowerShell, in this folder:

```powershell
mc\gradlew.bat -p mc build          # the mod: mc\build\libs\passthrough-0.1.0.jar
gta\fetch_deps.ps1                  # once: ScriptHookV's SDK and runtime, ReShade 6.8.0 and its headers
gta\build.bat                       # gta\build\MCPassthrough.asi
gta\install.ps1 -Edition Enhanced   # the GTA side into GTA (close it first)
```

Then put `mc\build\libs\passthrough-0.1.0.jar` into your Minecraft profile's `mods` folder (see [Installing](#installing)).

- If Gradle fails with "Unable to establish loopback connection", set
  `$env:JAVA_TOOL_OPTIONS = '-Djdk.net.unixdomain.tmpdir=C:\jdkaf'` (any short folder off a drive root) and run it again.
- `gta\build.bat` finds the newest Visual Studio by itself. Set `VCVARS` to a `vcvars64.bat` to pick another.
- `mc\gradlew.bat -p mc runClient` starts a development Minecraft (its own folder, `mc\run`, and an offline account) for the
  tests.

## Tests

Run all of these only against the development Minecraft (`mc\gradlew.bat -p mc runClient`), never against your own: they build
test blocks and type commands into the world they connect to.

| Test | What it checks |
|---|---|
| `gta\tests\fakegta.exe <seconds> <shake 0 or 1> <third or orbit> <screenshot prefix> [d3d12]` | The whole GTA half except GTA itself: a window with GTA's camera and depth conventions, the compositor and the link, with ReShade beside it as `dxgi.dll`. It draws yellow outlines where the test blocks should be, so the native blocks must stay inside them through fast camera swings. `d3d12` runs Enhanced's path, GPU frames included. |
| `gta\tests\gpu_frames_test.exe <seconds> <5 or 3>` | the shared GPU frames, with 5 planes (your layer) or 3 (an older host) |
| `gta\tests\ws_test.exe` | the WebSocket: handshake, a 60 Hz camera, ground, a TNT command, and the explosion coming back |
| `host\e2e_test.ps1` | the input path (chat, inventory, the mouse) and the time and weather messages |
| `host\frame_test.ps1` | spins and walks the camera while sections rebuild, and measures see-through pixels and torn reads |
| `host\pose_test.ps1` | saves Minecraft's frame for each movement pose, and your layer on its own |

Build the test programs with `gta\tests\build_fakegta.bat`. [FEATURES.md](FEATURES.md) has the latest results.

## Known limits

- **Not yet played in the real GTA:** the newest parts, your own layer and native blocks. They were tested with the stand-in
  host on DirectX 11 and 12. On Enhanced, F10 should say "GPU shared, avatar layer, native blocks"; if blocks swim while you
  turn, F9 picks the pose lag.
- **Collision is boxes:** one GTA box per solid block, at most 400 of them. Slabs, stairs and fences are full boxes for
  GTA's people and cars.
- **Moving things lag a little.** Mobs and arrows come from Minecraft's picture, at most one Minecraft frame behind.
- **Glass and water over GTA** show only the nearest surface, so GTA geometry behind glass but in front of further
  Minecraft blocks isn't layered perfectly.
- **Missing:** no climbing pose (Minecraft has none off a ladder), no rebinding of the keys, and no End.
- **Death:** dying in Minecraft just respawns you, and GTA's player is invincible by design.

## License

MIT, copyright (c) 2026 LowBacon: see [LICENSE](LICENSE). It includes code adapted from universal-modder (Rehan) and
EldenCraft (Adam Grzegorzewski), both MIT. Their notices are in [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).
