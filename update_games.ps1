# Put the newest build into the games: the mod jar into a Minecraft profile's mods folder (replacing an older
# passthrough jar there) and the GTA side into GTA V (gta\install.ps1). Both games must be closed: Windows keeps their
# files locked while they run.
#   .\update_games.ps1 [-McMods <profile>\mods] [-Edition Enhanced|Legacy]
# -McMods defaults to the Modrinth App profile "Gta", else the first Modrinth or launcher profile with a passthrough jar.
#
# Safe by steps: every file about to be replaced is first copied to <Documents>\Universal\MCPassthrough-Backups\<time>\;
# after installing, each installed file is checked byte for byte against the build; if anything fails, the backup is put
# back. update.log in that folder records it all.
# -SimulateFailure: fail right after installing (tests that the backup comes back)
param([string]$McMods = '', [ValidateSet('Enhanced', 'Legacy')][string]$Edition = 'Enhanced', [switch]$SimulateFailure)
$ErrorActionPreference = 'Stop'
$here = $PSScriptRoot
$backup = Join-Path (Split-Path $here -Parent) ("MCPassthrough-Backups\" + (Get-Date -Format 'yyyy-MM-dd_HH-mm-ss'))
New-Item -ItemType Directory -Force $backup | Out-Null
$log = Join-Path $backup 'update.log'
function Log([string]$m) { $line = "{0:HH:mm:ss} {1}" -f (Get-Date), $m; Write-Output $line; Add-Content -Path $log -Value $line -Encoding utf8 }
function Same([string]$a, [string]$b) { (Test-Path $a) -and (Test-Path $b) -and (Get-FileHash $a).Hash -eq (Get-FileHash $b).Hash }

$running = @(Get-Process GTA5_Enhanced, GTA5 -ErrorAction SilentlyContinue | ForEach-Object { "GTA V ($($_.Name).exe)" })
$running += @(Get-CimInstance Win32_Process -Filter "Name='java.exe' or Name='javaw.exe'" | Where-Object { $_.CommandLine -match 'fabric|Knot' } | ForEach-Object { 'Minecraft' })
if ($running) { Log "STOP: close these first (their files are locked while they run): $(($running | Select-Object -Unique) -join ', ')"; exit 1 }

if (-not $McMods) {
	$candidates = @("$env:APPDATA\ModrinthApp\profiles\Gta\mods")
	$candidates += Get-ChildItem "$env:APPDATA\ModrinthApp\profiles\*\mods", "$env:APPDATA\.minecraft\mods" -ErrorAction SilentlyContinue |
		Where-Object { Get-ChildItem $_.FullName -Filter 'passthrough-*.jar' -ErrorAction SilentlyContinue } | ForEach-Object FullName
	$McMods = $candidates | Where-Object { Test-Path $_ } | Select-Object -First 1
	if (-not $McMods) { Log "STOP: no Minecraft profile with the mod found: pass -McMods <your profile>\mods"; exit 1 }
}
$version = (Select-String -Path (Join-Path $here 'mc\gradle.properties') -Pattern '^version=(.*)$').Matches[0].Groups[1].Value.Trim()
$jar = Join-Path $here "mc\build\libs\passthrough-$version.jar"
$asi = Join-Path $here 'gta\build\MCPassthrough.asi'
$fx = Join-Path $here 'gta\shaders\MCPassthrough.fx'
foreach ($need in $jar, $asi, $fx) { if (-not (Test-Path $need)) { Log "STOP: $need is missing: build first (mc\gradlew.bat build, gta\build.bat)"; exit 1 } }

# where the GTA files go (install.ps1 decides the same way)
$gtaDir = (& (Join-Path $here 'gta\install.ps1') -Edition $Edition -WhereOnly)
if (-not $gtaDir -or -not (Test-Path $gtaDir)) { Log "STOP: GTA V $Edition not found"; exit 1 }
Log "Minecraft mods: $McMods"
Log "GTA V ${Edition}: $gtaDir"
Log "backup: $backup"

# 1. back up everything that may be replaced
$gtaFiles = 'MCPassthrough.asi', 'ScriptHookV.dll', 'xinput1_4.dll', 'dinput8.dll', 'dxgi.dll', 'ReShade64.asi', 'args.txt', 'ReShade.ini', 'ReShadePreset.ini',
	'reshade-shaders\Shaders\MCPassthrough.fx', 'reshade-shaders\Shaders\ReShade.fxh', 'reshade-shaders\Shaders\ReShadeUI.fxh'
$saved = @()
foreach ($f in $gtaFiles) {
	$p = Join-Path $gtaDir $f
	if (Test-Path $p) { $dst = Join-Path $backup "gta\$f"; New-Item -ItemType Directory -Force (Split-Path $dst) | Out-Null; Copy-Item $p $dst; $saved += , @($dst, $p) }
}
$oldJars = @(Get-ChildItem $McMods -Filter 'passthrough-*.jar')
foreach ($j in $oldJars) { $dst = Join-Path $backup "mods\$($j.Name)"; New-Item -ItemType Directory -Force (Split-Path $dst) | Out-Null; Copy-Item $j.FullName $dst; $saved += , @($dst, $j.FullName) }
Log "backed up $($saved.Count) file(s)"

# 2. install, 3. verify; anything wrong: 4. put the backup back
try {
	foreach ($j in $oldJars) { Remove-Item $j.FullName }
	Copy-Item $jar $McMods
	Log "Minecraft: $(Split-Path $jar -Leaf) -> $McMods"
	& (Join-Path $here 'gta\install.ps1') -Edition $Edition | ForEach-Object { Log "  $_" }
	if ($SimulateFailure) { throw "simulated failure (-SimulateFailure)" }
	$checks = @(@($jar, (Join-Path $McMods (Split-Path $jar -Leaf))), @($asi, (Join-Path $gtaDir 'MCPassthrough.asi')), @($fx, (Join-Path $gtaDir 'reshade-shaders\Shaders\MCPassthrough.fx')))
	foreach ($c in $checks) {
		if (-not (Same $c[0] $c[1])) { throw "verification failed: $($c[1]) isn't the build's $(Split-Path $c[0] -Leaf)" }
		Log "verified $($c[1])"
	}
	Log "UPDATE OK"
}
catch {
	Log "FAILED: $($_.Exception.Message)"
	Log "putting the backup back"
	foreach ($j in Get-ChildItem $McMods -Filter 'passthrough-*.jar' -ErrorAction SilentlyContinue) { Remove-Item $j.FullName -ErrorAction SilentlyContinue }
	foreach ($s in $saved) {
		try { New-Item -ItemType Directory -Force (Split-Path $s[1]) | Out-Null; Copy-Item $s[0] $s[1] -Force; Log "  restored $($s[1])" }
		catch { Log "  COULD NOT restore $($s[1]): copy it back by hand from $($s[0])" }
	}
	Log "the previous installation is back. Log: $log"
	exit 1
}
