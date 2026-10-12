# Fetch what the GTA side needs into third_party\, from Windows PowerShell.
# None of it may be redistributed here, so it isn't in the repo:
#   shv\       ScriptHookV SDK: main.h, nativeCaller.h, types.h, ScriptHookV.lib (dev-c.com)
#   reshade\   ReShade's add-on API headers (crosire/reshade at v6.8.0)
#   *.fxh      ReShade.fxh and ReShadeUI.fxh, which MCPassthrough.fx includes (crosire/reshade-shaders, slim branch)
#   runtime\   what install.ps1 puts in the game folder: ScriptHookV.dll, its ASI loaders (dinput8.dll for Legacy,
#              xinput1_4.dll for Enhanced, whichever the download has) and ReShade64.dll 6.8.0 with add-on support.
#   -Runtime <folder> puts them elsewhere; -SdkOnly skips the runtime (when the game folder already has it).
param([string]$Runtime = "", [switch]$SdkOnly)
$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'
[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
$here = $PSScriptRoot
if (-not $Runtime) { $Runtime = Join-Path $here 'third_party\runtime' }
$reshade = '6.8.0'
$ua = 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140.0.0.0 Safari/537.36'
$tp = Join-Path $here 'third_party'
$tmp = Join-Path ([IO.Path]::GetTempPath()) ('mcpt_deps_' + [Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Force $tmp, (Join-Path $tp 'shv'), (Join-Path $tp 'reshade') | Out-Null
if (-not $SdkOnly) { New-Item -ItemType Directory -Force $Runtime | Out-Null }
try {
	# ScriptHookV: the SDK and the runtime zips linked from its page (it wants browser-like headers)
	$shvPage = 'https://www.dev-c.com/gtav/scripthookv/'
	$page = (Invoke-WebRequest -UseBasicParsing -UserAgent $ua -Headers @{ 'Accept' = 'text/html'; 'Accept-Language' = 'en-US' } $shvPage).Content
	$zips = [regex]::Matches($page, '/files/ScriptHookV_[^"]*\.zip') | ForEach-Object { $_.Value } | Sort-Object -Unique
	foreach ($z in $zips) {
		if ($SdkOnly -and $z -notlike '*SDK*') { continue }
		Invoke-WebRequest -UseBasicParsing -UserAgent $ua -Headers @{ 'Referer' = $shvPage } ("https://www.dev-c.com" + $z) -OutFile (Join-Path $tmp ([IO.Path]::GetFileName($z)))
	}
	$sdk = Get-ChildItem $tmp -Filter 'ScriptHookV_SDK_*.zip' | Select-Object -First 1
	if (-not $sdk) { throw "no ScriptHookV downloads found: get the SDK and ScriptHookV from $shvPage" }
	Expand-Archive $sdk.FullName (Join-Path $tmp 'sdk') -Force
	foreach ($f in 'inc\main.h', 'inc\nativeCaller.h', 'inc\types.h', 'lib\ScriptHookV.lib') {
		Copy-Item (Join-Path $tmp "sdk\$f") (Join-Path $tp 'shv') -Force
	}
	if (-not $SdkOnly) {
		$rt = Get-ChildItem $tmp -Filter 'ScriptHookV_*.zip' | Where-Object { $_.Name -notlike '*SDK*' }
		foreach ($r in $rt) { Expand-Archive $r.FullName (Join-Path $tmp 'rt') -Force }
		$found = Get-ChildItem (Join-Path $tmp 'rt') -Recurse -Include 'ScriptHookV.dll', 'dinput8.dll', 'xinput1_4.dll'
		if (-not ($found | Where-Object Name -eq 'ScriptHookV.dll')) { throw "the ScriptHookV download has no ScriptHookV.dll" }
		$found | ForEach-Object { Copy-Item $_.FullName $Runtime -Force }

		# ReShade with add-on support: the setup exe carries its DLLs as an appended zip
		$setup = Join-Path $tmp 'reshade.exe'
		Invoke-WebRequest -UseBasicParsing -UserAgent $ua -Headers @{ 'Referer' = 'https://reshade.me/' } "https://reshade.me/downloads/ReShade_Setup_${reshade}_Addon.exe" -OutFile $setup
		New-Item -ItemType Directory -Force (Join-Path $tmp 'rs') | Out-Null
		& tar.exe -xf $setup -C (Join-Path $tmp 'rs') ReShade64.dll 2>$null
		if (-not (Test-Path (Join-Path $tmp 'rs\ReShade64.dll'))) {
			Add-Type -AssemblyName System.IO.Compression.FileSystem
			$zip = [IO.Compression.ZipFile]::OpenRead($setup)
			try { [IO.Compression.ZipFileExtensions]::ExtractToFile(($zip.Entries | Where-Object Name -eq 'ReShade64.dll'), (Join-Path $tmp 'rs\ReShade64.dll'), $true) }
			finally { $zip.Dispose() }
		}
		Copy-Item (Join-Path $tmp 'rs\ReShade64.dll') $Runtime -Force
	}

	foreach ($f in 'reshade.hpp', 'reshade_api.hpp', 'reshade_api_device.hpp', 'reshade_api_pipeline.hpp', 'reshade_api_resource.hpp',
			'reshade_api_format.hpp', 'reshade_events.hpp', 'reshade_overlay.hpp') {
		Invoke-WebRequest -UseBasicParsing "https://raw.githubusercontent.com/crosire/reshade/v$reshade/include/$f" -OutFile (Join-Path $tp "reshade\$f")
	}
	foreach ($f in 'ReShade.fxh', 'ReShadeUI.fxh') {
		Invoke-WebRequest -UseBasicParsing "https://raw.githubusercontent.com/crosire/reshade-shaders/slim/Shaders/$f" -OutFile (Join-Path $tp $f)
	}
	Get-ChildItem $tp -Recurse -File | ForEach-Object { $_.FullName.Substring($here.Length + 1) }
	if (-not $SdkOnly) { Get-ChildItem $Runtime -File | ForEach-Object { $_.FullName } }
}
finally {
	Remove-Item -Recurse -Force $tmp -ErrorAction SilentlyContinue
}
