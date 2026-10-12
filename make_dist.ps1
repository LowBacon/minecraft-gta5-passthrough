# Put everything for one machine in one folder, ready to copy into the games: the Minecraft mod (+ Fabric API and
# Fabric's installer) and the GTA V files (MCPassthrough.asi, ScriptHookV, its ASI loader, ReShade and the effect).
#   .\make_dist.ps1 [-Out <folder>] [-Edition Enhanced|Legacy]
# Build first: mc\gradlew.bat -p mc build (JDK 25), gta\fetch_deps.ps1, gta\build.bat. The folder holds ScriptHookV and
# ReShade, which may not be redistributed: it's for your own games, not for sharing or committing (it's made outside
# the repo by default).
param([string]$Out = (Join-Path (Split-Path $PSScriptRoot -Parent) 'MCPassthrough-Mods'),
	[ValidateSet('Enhanced', 'Legacy')][string]$Edition = 'Enhanced')
$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'
[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
$here = $PSScriptRoot
$props = @{}
Get-Content (Join-Path $here 'mc\gradle.properties') | Where-Object { $_ -match '^\s*([\w.]+)\s*=\s*(.*)$' } | ForEach-Object { $props[$Matches[1]] = $Matches[2].Trim() }
$jar = Join-Path $here "mc\build\libs\passthrough-$($props.version).jar"
$asi = Join-Path $here 'gta\build\MCPassthrough.asi'
$rt = Join-Path $here 'gta\third_party\runtime'
foreach ($need in $jar, $asi, (Join-Path $rt 'ScriptHookV.dll'), (Join-Path $rt 'ReShade64.dll')) {
	if (-not (Test-Path $need)) { throw "missing $need (build and fetch first, see the top of this script)" }
}
$e = @{
	Enhanced = @{ Loader = 'xinput1_4.dll'; ReShade = 'dxgi.dll'; Exe = 'GTA5_Enhanced.exe' }
	Legacy   = @{ Loader = 'dinput8.dll'; ReShade = 'ReShade64.asi'; Exe = 'GTA5.exe' }
}[$Edition]

if (Test-Path $Out) { Remove-Item -Recurse -Force $Out }
$mods = Join-Path $Out '1 - Minecraft mods folder'
$gta = Join-Path $Out "2 - GTA V $Edition folder"
$shaders = Join-Path $gta 'reshade-shaders\Shaders'
New-Item -ItemType Directory -Force $mods, $shaders | Out-Null

# Minecraft: the mod and Fabric API (from Gradle's cache, where the build put it; else Fabric's maven)
Copy-Item $jar $mods
$api = $props.fabric_api_version
$cached = Get-ChildItem (Join-Path $env:USERPROFILE '.gradle\caches\modules-2\files-2.1\net.fabricmc.fabric-api\fabric-api') -Recurse -Filter "fabric-api-$api.jar" -ErrorAction SilentlyContinue | Select-Object -First 1
if ($cached) { Copy-Item $cached.FullName $mods }
else { Invoke-WebRequest -UseBasicParsing "https://maven.fabricmc.net/net/fabricmc/fabric-api/fabric-api/$api/fabric-api-$api.jar" -OutFile (Join-Path $mods "fabric-api-$api.jar") }
# Fabric's installer (puts Fabric Loader into the Minecraft launcher)
$meta = [xml](Invoke-WebRequest -UseBasicParsing 'https://maven.fabricmc.net/net/fabricmc/fabric-installer/maven-metadata.xml').Content
$iv = $meta.metadata.versioning.release
$installer = "fabric-installer-$iv.jar"
Invoke-WebRequest -UseBasicParsing "https://maven.fabricmc.net/net/fabricmc/fabric-installer/$iv/$installer" -OutFile (Join-Path $Out $installer)

# GTA V: what install.ps1 would put in the game folder
Copy-Item $asi, (Join-Path $rt 'ScriptHookV.dll') $gta
$loader = Join-Path $rt $e.Loader
if (-not (Test-Path $loader)) { throw "the ScriptHookV download has no $($e.Loader) (the $Edition ASI loader)" }
Copy-Item $loader $gta
Copy-Item (Join-Path $rt 'ReShade64.dll') (Join-Path $gta $e.ReShade)
[IO.File]::WriteAllText((Join-Path $gta 'args.txt'), '-nobattleye -noBE')
[IO.File]::WriteAllText((Join-Path $gta 'ReShade.ini'), "[GENERAL]`r`nEffectSearchPaths=.\reshade-shaders\Shaders\`r`nTextureSearchPaths=.\reshade-shaders\Textures\`r`nPresetPath=.\ReShadePreset.ini`r`nPreprocessorDefinitions=RESHADE_DEPTH_INPUT_IS_REVERSED=1,RESHADE_DEPTH_INPUT_IS_UPSIDE_DOWN=0,RESHADE_DEPTH_INPUT_IS_LOGARITHMIC=0,RESHADE_DEPTH_LINEARIZATION_FAR_PLANE=1000`r`n`r`n[OVERLAY]`r`nTutorialProgress=4`r`nShowClock=0`r`nShowFPS=0`r`n`r`n[SCREENSHOT]`r`nSavePath=.\`r`n")
[IO.File]::WriteAllText((Join-Path $gta 'ReShadePreset.ini'), "Techniques=MCPassthrough@MCPassthrough.fx`r`nTechniqueSorting=MCPassthrough@MCPassthrough.fx`r`n")
Copy-Item (Join-Path $here 'gta\shaders\MCPassthrough.fx'), (Join-Path $here 'gta\third_party\ReShade.fxh'), (Join-Path $here 'gta\third_party\ReShadeUI.fxh') $shaders

$readme = @"
MINECRAFT x GTA V PASSTHROUGH by LowBacon: what goes where
==========================================================
(How it all works, every setting and troubleshooting: README.md in the mod's project folder.)

1) MINECRAFT (Java Edition $($props.minecraft_version), Fabric)
   a. Run $installer (double-click; needs Java). Pick Minecraft $($props.minecraft_version), Fabric Loader $($props.loader_version)
      or newer, and Install. It adds a "fabric-loader-...-$($props.minecraft_version)" installation to the launcher.
   b. In the Minecraft launcher, edit that installation and give it a GAME DIRECTORY OF ITS OWN
      (for example C:\dev\passthrough\mcgame). The mod changes options and makes its own world, so keep it
      apart from your normal worlds.
   c. Copy everything in "1 - Minecraft mods folder" into <that game directory>\mods
      (create the mods folder if it isn't there).

2) GTA V $Edition
   Copy everything in "2 - GTA V $Edition folder" into the folder that has $($e.Exe)
   (Steam: right-click GTA V $Edition > Manage > Browse local files). Keep the reshade-shaders folder as it is.
   If GTA already has a ReShade.ini you like, you can skip that one file.
   args.txt (-nobattleye) keeps BattlEye off, which is what lets ScriptHookV run: STORY MODE ONLY. Never take a
   modded game into GTA Online.

3) PLAY
   - GTA settings: windowed or borderless, "Pause game on focus loss" off, depth of field off.$(if ($Edition -eq 'Enhanced') { "`r`n     Enhanced: frame generation (DLSS-G / FSR 3 FG) off, HDR off. Upscaling is fine." })
   - Start Minecraft with the Fabric installation first. Leave its window open (not minimized).
   - Start GTA V, pick Story Mode yourself. "Minecraft passthrough connected" appears in the corner.

   Keys: F7 passthrough on/off, F8 re-level the ground, left/right mouse = Minecraft attack/use, wheel and 1-9 =
   hotbar, Tab = gun mode. Double-tap Space = creative flight (WASD, Space up, Shift down, Ctrl faster; land by
   flying down onto the ground). Space in mid-air = elytra (mouse steers, right-click fireworks to boost).
   Minecraft's own keys: E = inventory / CREATIVE MENU, Q = drop (Ctrl+Q: the stack), T = chat, / = command,
   X = swap hands (Minecraft's F is GTA's car key), middle mouse = pick block. While a Minecraft screen is open,
   all keys and the mouse go to Minecraft (search, drag items, shift-click, type); E or Esc closes it.

   Minecraft lagging behind GTA / doubled when moving?
   - F9 cycles the pose lag 0-3 (Enhanced starts at 1). Turn the camera and keep the value where Minecraft's blocks
     stay glued to GTA's ground.
   - GTA's frame rate matters most: at 30 fps everything Minecraft steps behind. Ray tracing at native 1440p plus
     Minecraft on the same GPU is too much; in Graphics turn on DLSS (Quality) and lower or turn off the ray traced
     GI / reflections until GTA holds 60. Minecraft now caps itself just above GTA's fps to leave it the GPU.
   - Frame generation (DLSS-G / FSR 3 FG) must stay OFF.

   UPDATING: after a new build, close both games and double-click "Update my games (close GTA and Minecraft first).bat".

4) REMOVE
   Delete the files you copied: from GTA's folder MCPassthrough.asi, ScriptHookV.dll, $($e.Loader), $($e.ReShade),
   args.txt, ReShade.ini, ReShadePreset.ini and reshade-shaders\Shaders\MCPassthrough.fx; from the mods folder the
   two jars.

The mod is by LowBacon, with code adapted from Rehan's universal-modder and Adam Grzegorzewski's EldenCraft (MIT).
ScriptHookV (c) Alexander Blade, dev-c.com. ReShade by crosire, reshade.me. Fabric, fabricmc.net. These are for your
own copy of the games; don't re-upload this folder.
"@
[IO.File]::WriteAllText((Join-Path $Out 'READ ME FIRST.txt'), $readme)
# one click for this machine: close both games, double-click, and the newest build goes into them
[IO.File]::WriteAllText((Join-Path $Out 'Update my games (close GTA and Minecraft first).bat'),
	"@echo off`r`npowershell.exe -NoProfile -ExecutionPolicy Bypass -File `"$(Join-Path $here 'update_games.ps1')`" -Edition $Edition`r`npause`r`n")
Get-ChildItem $Out -Recurse -File | ForEach-Object { '{0,10:N0}  {1}' -f $_.Length, $_.FullName.Substring($Out.Length + 1) }
Write-Output "made $Out"
