# Install the passthrough into GTA V (story mode), Legacy or Enhanced, from Windows PowerShell (install.sh does the
# same from WSL): ScriptHookV + its ASI loader, MCPassthrough.asi, and ReShade with MCPassthrough.fx. Only adds files;
# -Remove deletes exactly those.
#   Legacy (GTA5.exe):            ASI loader dinput8.dll; ReShade as ReShade64.asi (Legacy loads the system dxgi.dll
#                                 ahead of one in its folder, so the ASI loader loads ReShade instead)
#   Enhanced (GTA5_Enhanced.exe): ASI loader xinput1_4.dll; ReShade as dxgi.dll (DirectX 12; Enhanced does load it)
# ScriptHookV only runs with BattlEye off (args.txt: -nobattleye), i.e. story mode only.
#   -GtaDir    the game folder (default: GTA_DIR, else found in your Steam libraries / Rockstar's registry keys)
#   -Edition   Legacy or Enhanced, when both are installed
#   -Runtime   ScriptHookV.dll, its loaders and ReShade64.dll from fetch_deps (default third_party\runtime). A runtime
#              file that isn't there is skipped if the game folder already has it (e.g. you installed ReShade yourself).
#   -Build     the folder with MCPassthrough.asi (default build\, where build.bat puts it)
#   -Force     replace a loader, ReShade or args.txt that some other mod (or you) put there
#   -WhereOnly print the game folder it would install into, and stop
param([string]$GtaDir = $env:GTA_DIR, [ValidateSet('', 'Legacy', 'Enhanced')][string]$Edition = '', [string]$Runtime = '',
	[string]$Build = '', [switch]$Force, [switch]$Remove, [switch]$WhereOnly)
$ErrorActionPreference = 'Stop'
$here = $PSScriptRoot
if (-not $Runtime) { $Runtime = Join-Path $here 'third_party\runtime' }
if (-not $Build) { $Build = Join-Path $here 'build' }
$argsLine = '-nobattleye -noBE'
$editions = @{
	Legacy   = @{ Exe = 'GTA5.exe'; AppId = 271590; Loader = 'dinput8.dll'; ReShade = 'ReShade64.asi' }
	Enhanced = @{ Exe = 'GTA5_Enhanced.exe'; AppId = 3240220; Loader = 'xinput1_4.dll'; ReShade = 'dxgi.dll' }
}

function Steam-Libraries {
	$roots = @()
	foreach ($k in 'HKCU:\Software\Valve\Steam', 'HKLM:\SOFTWARE\WOW6432Node\Valve\Steam') {
		try { $v = Get-ItemProperty $k -ErrorAction Stop; $roots += $v.SteamPath, $v.InstallPath } catch {}
	}
	$roots += "${env:ProgramFiles(x86)}\Steam", "$env:ProgramFiles\Steam"
	$libs = @()
	foreach ($r in ($roots | Where-Object { $_ } | ForEach-Object { $_ -replace '/', '\' } | Select-Object -Unique)) {
		if (-not (Test-Path "$r\steamapps")) { continue }
		$libs += $r
		$vdf = "$r\steamapps\libraryfolders.vdf"
		if (Test-Path $vdf) {
			Select-String -Path $vdf -Pattern '^\s*"path"\s*"(.*)"' | ForEach-Object { $libs += $_.Matches[0].Groups[1].Value -replace '\\\\', '\' }
		}
	}
	$libs | Select-Object -Unique
}

function Find-Game([string]$name) {
	$e = $editions[$name]
	foreach ($lib in Steam-Libraries) {
		$acf = "$lib\steamapps\appmanifest_$($e.AppId).acf"
		if (-not (Test-Path $acf)) { continue }
		$m = Select-String -Path $acf -Pattern '^\s*"installdir"\s*"(.*)"' | Select-Object -First 1
		if ($m) {
			$dir = "$lib\steamapps\common\$($m.Matches[0].Groups[1].Value)"
			if (Test-Path (Join-Path $dir $e.Exe)) { return $dir }
		}
	}
	# Rockstar's own keys (Rockstar Games Launcher or Epic installs too)
	foreach ($k in Get-ChildItem 'HKLM:\SOFTWARE\WOW6432Node\Rockstar Games' -ErrorAction SilentlyContinue) {
		$p = Get-ItemProperty $k.PSPath
		foreach ($dir in $p.InstallFolderSteam, $p.InstallFolderEpic, $p.InstallFolder) {
			if ($dir -and (Test-Path (Join-Path $dir $e.Exe))) { return $dir.TrimEnd('\') }
		}
	}
	return $null
}

if (-not $GtaDir) {
	$found = @{}
	foreach ($n in 'Enhanced', 'Legacy') { if (-not $Edition -or $Edition -eq $n) { $d = Find-Game $n; if ($d) { $found[$n] = $d } } }
	if ($found.Count -eq 0) { throw "GTA V not found in your Steam libraries: pass -GtaDir <the folder with GTA5.exe or GTA5_Enhanced.exe>" }
	if ($found.Count -gt 1) { throw "both GTA V Legacy ($($found.Legacy)) and Enhanced ($($found.Enhanced)) are installed: pass -Edition Legacy or -Edition Enhanced" }
	$Edition = @($found.Keys)[0]
	$GtaDir = $found[$Edition]
}
if (-not $Edition) {
	if (Test-Path (Join-Path $GtaDir 'GTA5_Enhanced.exe')) { $Edition = 'Enhanced' }
	elseif (Test-Path (Join-Path $GtaDir 'GTA5.exe')) { $Edition = 'Legacy' }
	else { throw "neither GTA5.exe nor GTA5_Enhanced.exe in: $GtaDir" }
}
$e = $editions[$Edition]
if (-not (Test-Path (Join-Path $GtaDir $e.Exe))) { throw "$($e.Exe) not found in: $GtaDir" }
if ($WhereOnly) { return $GtaDir }
Write-Output "GTA V $Edition in $GtaDir"

$shaders = 'reshade-shaders\Shaders'
$files = @('ScriptHookV.dll', $e.Loader, 'args.txt', 'MCPassthrough.asi', $e.ReShade, 'ReShade.ini', 'ReShadePreset.ini',
	"$shaders\MCPassthrough.fx", "$shaders\ReShade.fxh", "$shaders\ReShadeUI.fxh")
if ($Remove) {
	foreach ($f in $files) {
		$p = Join-Path $GtaDir $f
		if (Test-Path $p) { Remove-Item $p -Force; Write-Output "removed $f" }
	}
	foreach ($d in $shaders, 'reshade-shaders') {
		$p = Join-Path $GtaDir $d
		if ((Test-Path $p) -and -not (Get-ChildItem $p -Force)) { Remove-Item $p }
	}
	return
}

$asi = Join-Path $Build 'MCPassthrough.asi'
if (-not (Test-Path $asi)) { throw "build it first: gta\build.bat" }
foreach ($f in 'ReShade.fxh', 'ReShadeUI.fxh') {
	if (-not (Test-Path (Join-Path $here "third_party\$f"))) { throw "fetch the dependencies first: gta\fetch_deps.ps1" }
}
function Same([string]$a, [string]$b) { (Get-FileHash $a).Hash -eq (Get-FileHash $b).Hash }
# runtime file -> name in the game folder
$runtimeFiles = [ordered]@{ 'ScriptHookV.dll' = 'ScriptHookV.dll'; $e.Loader = $e.Loader; 'ReShade64.dll' = $e.ReShade }
$copy = @()
$clash = @()
foreach ($src in $runtimeFiles.Keys) {
	$from = Join-Path $Runtime $src
	$to = Join-Path $GtaDir $runtimeFiles[$src]
	if (-not (Test-Path $from)) {
		if (Test-Path $to) { Write-Output "keeping the $($runtimeFiles[$src]) already there (no $src in $Runtime)"; continue }
		throw "$src is missing: run gta\fetch_deps.ps1 (or put it in $Runtime)"
	}
	if ((Test-Path $to) -and -not (Same $from $to) -and $src -ne 'ScriptHookV.dll') { $clash += $runtimeFiles[$src] }
	$copy += , @($from, $to)
}
$argsFile = Join-Path $GtaDir 'args.txt'
if ((Test-Path $argsFile) -and ((Get-Content $argsFile -Raw).Trim() -ne $argsLine)) { $clash += 'args.txt' }
if ($clash -and -not $Force) { throw "not replacing what is already in ${GtaDir}: $($clash -join ', ') (-Force to replace)" }

foreach ($c in $copy) { Copy-Item $c[0] $c[1] -Force; Write-Output "  $([IO.Path]::GetFileName($c[1]))" }
Copy-Item $asi $GtaDir -Force; Write-Output "  MCPassthrough.asi"
# ScriptHookV's own args.txt: story mode without BattlEye (no GTA Online while it is there)
[IO.File]::WriteAllText($argsFile, $argsLine)
New-Item -ItemType Directory -Force (Join-Path $GtaDir $shaders) | Out-Null
Copy-Item (Join-Path $here 'shaders\MCPassthrough.fx'), (Join-Path $here 'third_party\ReShade.fxh'), (Join-Path $here 'third_party\ReShadeUI.fxh') (Join-Path $GtaDir $shaders) -Force
Write-Output "  $shaders\MCPassthrough.fx, ReShade.fxh, ReShadeUI.fxh"
$ini = Join-Path $GtaDir 'ReShade.ini'
if (-not (Test-Path $ini)) {
	[IO.File]::WriteAllText($ini, "[GENERAL]`r`nEffectSearchPaths=.\reshade-shaders\Shaders\`r`nTextureSearchPaths=.\reshade-shaders\Textures\`r`nPresetPath=.\ReShadePreset.ini`r`nPreprocessorDefinitions=RESHADE_DEPTH_INPUT_IS_REVERSED=1,RESHADE_DEPTH_INPUT_IS_UPSIDE_DOWN=0,RESHADE_DEPTH_INPUT_IS_LOGARITHMIC=0,RESHADE_DEPTH_LINEARIZATION_FAR_PLANE=1000`r`n`r`n[OVERLAY]`r`nTutorialProgress=4`r`nShowClock=0`r`nShowFPS=0`r`n`r`n[SCREENSHOT]`r`nSavePath=.\`r`n")
	Write-Output "  ReShade.ini"
}
# the technique is always on; its McActive uniform (set by the add-on) keeps it a pure passthrough until Minecraft
# frames arrive
[IO.File]::WriteAllText((Join-Path $GtaDir 'ReShadePreset.ini'), "Techniques=MCPassthrough@MCPassthrough.fx`r`nTechniqueSorting=MCPassthrough@MCPassthrough.fx`r`n")
Write-Output "  ReShadePreset.ini"
Write-Output "installed into $GtaDir"
