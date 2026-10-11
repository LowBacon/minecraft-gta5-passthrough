# Put the newest build into the games: the mod jar into a Minecraft profile's mods folder (replacing an older
# passthrough jar there) and the GTA side into GTA V (gta\install.ps1). Both games must be closed: Windows keeps
# their files locked while they run.
#   .\update_games.ps1 [-McMods <profile>\mods] [-Edition Enhanced|Legacy]
# -McMods defaults to the Modrinth App profile "Gta", else the first Modrinth or launcher profile with a passthrough jar.
param([string]$McMods = '', [ValidateSet('Enhanced', 'Legacy')][string]$Edition = 'Enhanced')
$ErrorActionPreference = 'Stop'
$here = $PSScriptRoot
$running = @(Get-Process GTA5_Enhanced, GTA5 -ErrorAction SilentlyContinue | ForEach-Object { "GTA V ($($_.Name).exe)" })
$running += @(Get-CimInstance Win32_Process -Filter "Name='java.exe' or Name='javaw.exe'" | Where-Object { $_.CommandLine -match 'fabric|Knot' } | ForEach-Object { 'Minecraft' })
if ($running) { throw "close these first (their files are locked while they run): $(($running | Select-Object -Unique) -join ', ')" }

if (-not $McMods) {
	$candidates = @("$env:APPDATA\ModrinthApp\profiles\Gta\mods")
	$candidates += Get-ChildItem "$env:APPDATA\ModrinthApp\profiles\*\mods", "$env:APPDATA\.minecraft\mods" -ErrorAction SilentlyContinue |
		Where-Object { Get-ChildItem $_.FullName -Filter 'passthrough-*.jar' -ErrorAction SilentlyContinue } | ForEach-Object FullName
	$McMods = $candidates | Where-Object { Test-Path $_ } | Select-Object -First 1
	if (-not $McMods) { throw "no Minecraft profile with the mod found: pass -McMods <your profile>\mods" }
}
$version = (Select-String -Path (Join-Path $here 'mc\gradle.properties') -Pattern '^version=(.*)$').Matches[0].Groups[1].Value.Trim()
$jar = Join-Path $here "mc\build\libs\passthrough-$version.jar"
if (-not (Test-Path $jar)) { throw "build the mod first: mc\gradlew.bat build" }
Get-ChildItem $McMods -Filter 'passthrough-*.jar' | ForEach-Object { Remove-Item $_.FullName; Write-Output "removed old $($_.Name)" }
Copy-Item $jar $McMods
Write-Output "Minecraft: $(Split-Path $jar -Leaf) -> $McMods"
& (Join-Path $here 'gta\install.ps1') -Edition $Edition
