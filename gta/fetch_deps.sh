#!/bin/bash
# Fetch what the GTA side needs into third_party/ (none of it may be redistributed here, so it isn't in the repo):
#   shv/       ScriptHookV SDK: main.h, nativeCaller.h, types.h, ScriptHookV.lib (dev-c.com; needs browser headers)
#   reshade/   ReShade's add-on API headers (crosire/reshade at v6.8.0)
#   *.fxh      ReShade.fxh and ReShadeUI.fxh, which MCPassthrough.fx includes (crosire/reshade-shaders, slim branch)
#   runtime/   what install.sh puts in the game folder: ScriptHookV.dll, its ASI loaders (dinput8.dll for Legacy,
#              xinput1_4.dll for Enhanced) and ReShade64.dll 6.8.0 with add-on support (installed as ReShade64.asi in
#              Legacy, dxgi.dll in Enhanced). RUNTIME=<folder> puts them elsewhere. fetch_deps.ps1 does this from Windows.
set -e
HERE=$(cd "$(dirname "$0")" && pwd)
RUNTIME=${RUNTIME:-$HERE/third_party/runtime}
RESHADE=6.8.0
UA="Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140.0.0.0 Safari/537.36"
TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT
mkdir -p "$HERE/third_party/shv" "$HERE/third_party/reshade" "$RUNTIME"
page=$(curl -fsSL -A "$UA" -H "Accept: text/html" -H "Accept-Language: en-US" https://www.dev-c.com/gtav/scripthookv/)
for f in $(echo "$page" | grep -o '/files/ScriptHookV_[^"]*\.zip' | sort -u); do
	curl -fsSL -A "$UA" -H "Referer: https://www.dev-c.com/gtav/scripthookv/" "https://www.dev-c.com$f" -o "$TMP/$(basename "$f")"
done
ls "$TMP"/ScriptHookV_SDK_*.zip >/dev/null 2>&1 ||
	{ echo "no ScriptHookV downloads found: get the SDK and ScriptHookV from https://www.dev-c.com/gtav/scripthookv/"; exit 1; }
unzip -qo "$TMP"/ScriptHookV_SDK_*.zip -d "$TMP/sdk"
cp "$TMP"/sdk/inc/{main.h,nativeCaller.h,types.h} "$TMP"/sdk/lib/ScriptHookV.lib "$HERE/third_party/shv/"
for z in $(ls "$TMP"/ScriptHookV_*.zip | grep -v SDK); do unzip -qo "$z" -d "$TMP/rt"; done
# ScriptHookV.dll and the ASI loaders it ships: dinput8.dll (Legacy), xinput1_4.dll (Enhanced)
find "$TMP/rt" -type f \( -iname ScriptHookV.dll -o -iname dinput8.dll -o -iname xinput1_4.dll \) -exec cp {} "$RUNTIME/" \;
[ -f "$RUNTIME/ScriptHookV.dll" ] || { echo "the ScriptHookV download has no ScriptHookV.dll"; exit 1; }
curl -fsSL -A "$UA" -H "Referer: https://reshade.me/" "https://reshade.me/downloads/ReShade_Setup_${RESHADE}_Addon.exe" -o "$TMP/reshade.exe"
# the setup exe carries its DLLs as an appended zip (unzip warns about the exe in front of it)
unzip -qo "$TMP/reshade.exe" ReShade64.dll -d "$TMP" 2>/dev/null || true
cp "$TMP/ReShade64.dll" "$RUNTIME/"
for f in reshade.hpp reshade_api.hpp reshade_api_device.hpp reshade_api_pipeline.hpp reshade_api_resource.hpp reshade_api_format.hpp reshade_events.hpp reshade_overlay.hpp; do
	curl -fsSL "https://raw.githubusercontent.com/crosire/reshade/v$RESHADE/include/$f" -o "$HERE/third_party/reshade/$f"
done
for f in ReShade.fxh ReShadeUI.fxh; do
	curl -fsSL "https://raw.githubusercontent.com/crosire/reshade-shaders/slim/Shaders/$f" -o "$HERE/third_party/$f"
done
ls "$HERE/third_party" "$RUNTIME"
