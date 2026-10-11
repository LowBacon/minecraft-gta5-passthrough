#!/bin/bash
# Install the passthrough into GTA V (story mode), Legacy or Enhanced: ScriptHookV + its ASI loader, MCPassthrough.asi
# and ReShade with MCPassthrough.fx. Only adds files; `install.sh --remove` deletes exactly those. (install.ps1 does the
# same from Windows PowerShell.)
#   Legacy (GTA5.exe): ASI loader dinput8.dll; ReShade goes in as ReShade64.asi, loaded by the ASI loader: GTA loads
#     the system dxgi.dll, so a ReShade dxgi.dll in the game folder never runs.
#   Enhanced (GTA5_Enhanced.exe): ASI loader xinput1_4.dll; ReShade goes in as dxgi.dll (DirectX 12; Enhanced loads it).
# ScriptHookV only runs with BattlEye off (Rockstar launcher setting, or -nobattleye), i.e. story mode only.
#   GTA_DIR   the game folder (default: found in your Steam libraries, Enhanced (app 3240220) or Legacy (271590))
#   EDITION   Legacy or Enhanced, when both are installed
#   RUNTIME   ScriptHookV.dll, its loaders, ReShade64.dll from fetch_deps.sh (default third_party/runtime)
#   BUILD     where build.sh put MCPassthrough.asi (default <PASSTHROUGH_WIN_DIR>\gta\build, C:\dev\passthrough\...)
#   FORCE=1   replace a loader, ReShade or args.txt that some other mod (or you) put there
set -e
HERE=$(cd "$(dirname "$0")" && pwd)
WIN=${PASSTHROUGH_WIN_DIR:-'C:\dev\passthrough'}
RUNTIME=${RUNTIME:-$HERE/third_party/runtime}
BUILD=${BUILD:-$(wslpath -u "$WIN\\gta\\build")}
ARGS='-nobattleye -noBE'

# the Steam libraries: the default ones and every other one listed in their libraryfolders.vdf
steam_libraries() {
	for steam in "/mnt/c/Program Files (x86)/Steam" "/mnt/c/Program Files/Steam"; do
		[ -d "$steam/steamapps" ] || continue
		echo "$steam"
		sed -n 's/^[[:space:]]*"path"[[:space:]]*"\(.*\)"/\1/p' "$steam/steamapps/libraryfolders.vdf" 2>/dev/null |
			sed 's/\\\\/\\/g' | while read -r p; do wslpath -u "$p"; done
	done
}
find_app() { # find_app <appid> <exe>
	while read -r lib; do
		acf="$lib/steamapps/appmanifest_$1.acf"
		[ -f "$acf" ] || continue
		dir="$lib/steamapps/common/$(sed -n 's/^[[:space:]]*"installdir"[[:space:]]*"\(.*\)"/\1/p' "$acf")"
		[ -f "$dir/$2" ] && { echo "$dir"; return; }
	done < <(steam_libraries)
}
if [ -z "$GTA_DIR" ]; then
	enhanced=; legacy=
	[ "$EDITION" = Legacy ] || enhanced=$(find_app 3240220 GTA5_Enhanced.exe)
	[ "$EDITION" = Enhanced ] || legacy=$(find_app 271590 GTA5.exe)
	if [ -n "$enhanced" ] && [ -n "$legacy" ]; then
		echo "both GTA V Legacy ($legacy) and Enhanced ($enhanced) are installed: set EDITION=Legacy or EDITION=Enhanced"; exit 1
	fi
	GTA_DIR=${enhanced:-$legacy}
fi
GTA=${GTA_DIR:?GTA V not found in your Steam libraries: set GTA_DIR to the folder with GTA5.exe or GTA5_Enhanced.exe}
case $GTA in [A-Za-z]:*) GTA=$(wslpath -u "$GTA") ;; esac
if [ -f "$GTA/GTA5_Enhanced.exe" ]; then
	EDITION=Enhanced LOADER=xinput1_4.dll RESHADE_AS=dxgi.dll
elif [ -f "$GTA/GTA5.exe" ]; then
	EDITION=Legacy LOADER=dinput8.dll RESHADE_AS=ReShade64.asi
else
	echo "neither GTA5.exe nor GTA5_Enhanced.exe in: $GTA"; exit 1
fi
echo "GTA V $EDITION in $GTA"
FILES=(ScriptHookV.dll "$LOADER" args.txt MCPassthrough.asi "$RESHADE_AS" ReShade.ini ReShadePreset.ini
	reshade-shaders/Shaders/MCPassthrough.fx reshade-shaders/Shaders/ReShade.fxh reshade-shaders/Shaders/ReShadeUI.fxh)

if [ "$1" = "--remove" ]; then
	for f in "${FILES[@]}"; do rm -fv "$GTA/$f"; done
	rmdir "$GTA/reshade-shaders/Shaders" "$GTA/reshade-shaders" 2>/dev/null || true
	exit 0
fi
[ -f "$RUNTIME/ScriptHookV.dll" ] || { echo "fetch the runtime first: gta/fetch_deps.sh"; exit 1; }
[ -f "$RUNTIME/$LOADER" ] || { echo "the ScriptHookV download in $RUNTIME has no $LOADER (the $EDITION ASI loader)"; exit 1; }
[ -f "$BUILD/MCPassthrough.asi" ] || { echo "build it first: gta/build.sh"; exit 1; }
# another mod's ASI loader or ReShade, or your own launch arguments, stay unless FORCE=1 (--remove would delete them)
clash=
[ ! -f "$GTA/$LOADER" ] || cmp -s "$RUNTIME/$LOADER" "$GTA/$LOADER" || clash+=" $LOADER"
[ ! -f "$GTA/$RESHADE_AS" ] || cmp -s "$RUNTIME/ReShade64.dll" "$GTA/$RESHADE_AS" || clash+=" $RESHADE_AS"
[ ! -f "$GTA/args.txt" ] || [ "$(cat "$GTA/args.txt")" = "$ARGS" ] || clash+=" args.txt"
[ -z "$clash" ] || [ -n "$FORCE" ] || { echo "not replacing what is already in $GTA:$clash (FORCE=1 to replace)"; exit 1; }
cp -v "$RUNTIME/ScriptHookV.dll" "$RUNTIME/$LOADER" "$BUILD/MCPassthrough.asi" "$GTA/"
# ScriptHookV's own args.txt: story mode without BattlEye (no GTA Online while it is there)
printf -- '%s' "$ARGS" > "$GTA/args.txt"
cp -v "$RUNTIME/ReShade64.dll" "$GTA/$RESHADE_AS"
mkdir -p "$GTA/reshade-shaders/Shaders"
cp -v "$HERE/shaders/MCPassthrough.fx" "$HERE/third_party/ReShade.fxh" "$HERE/third_party/ReShadeUI.fxh" "$GTA/reshade-shaders/Shaders/"
if [ ! -f "$GTA/ReShade.ini" ]; then
	printf '[GENERAL]\r\nEffectSearchPaths=.\\reshade-shaders\\Shaders\\\r\nTextureSearchPaths=.\\reshade-shaders\\Textures\\\r\nPresetPath=.\\ReShadePreset.ini\r\nPreprocessorDefinitions=RESHADE_DEPTH_INPUT_IS_REVERSED=1,RESHADE_DEPTH_INPUT_IS_UPSIDE_DOWN=0,RESHADE_DEPTH_INPUT_IS_LOGARITHMIC=0,RESHADE_DEPTH_LINEARIZATION_FAR_PLANE=1000\r\n\r\n[OVERLAY]\r\nTutorialProgress=4\r\nShowClock=0\r\nShowFPS=0\r\n\r\n[SCREENSHOT]\r\nSavePath=.\\\r\n' > "$GTA/ReShade.ini"
fi
# the technique is always on; its McActive uniform (set by the add-on) keeps it a pure passthrough until
# Minecraft frames arrive
printf 'Techniques=MCPassthrough@MCPassthrough.fx\r\nTechniqueSorting=MCPassthrough@MCPassthrough.fx\r\n' > "$GTA/ReShadePreset.ini"
echo "installed into $GTA"
