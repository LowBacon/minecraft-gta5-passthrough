# Minecraft x GTA V passthrough: notes for AI coding agents

This repo is one mod in two halves: a Fabric mod for Minecraft Java 26.3 (`mc/`) and a ScriptHookV script that is also
a ReShade add-on, for GTA V (`gta/`). [README.md](README.md) explains how it all works, [FEATURES.md](FEATURES.md) has
the status of every feature, and [MODLOG.md](MODLOG.md) is the journal.

## Rules
- **Story mode only.** Never touch GTA Online or any online client protected by anti-cheat, never write cheats against
  other players, and never bypass anti-cheat, DRM or ownership checks.
- **Never automate clicks on GTA's landing page**, and ask before driving the user's mouse and keyboard.
- **Game folders:** back up before replacing game files (`update_games.ps1` does), and ask before installing loaders
  into game folders or changing the registry.
- **Processes:** kill by exact PID, never by name pattern.
- **Tests write into the Minecraft world they connect to** (`gta\tests\fakegta.exe`, `ws_test.exe`, `host\*.ps1`). Run
  them only against the dev client (`mc\gradlew.bat -p mc runClient`), never against the user's own Minecraft.
- **Never commit or publish** game files, extracted assets, decompiled code, ScriptHookV or ReShade (`gta/third_party/`
  is ignored), or the `MCPassthrough-Mods` folder.
- **Ask before publishing anything**, pull requests included.
- **Keep MODLOG.md** up to date, and keep [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) with any code adapted from
  EldenCraft or universal-modder (both MIT).

## Building
- **Minecraft:** `mc\gradlew.bat -p mc build` with JDK 25. If Gradle fails with "Unable to establish loopback connection", set
  `JAVA_TOOL_OPTIONS=-Djdk.net.unixdomain.tmpdir=C:\jdkaf` (any short folder off a drive root).
- **GTA:** `gta\fetch_deps.ps1` once, then `gta\build.bat` (MSVC).
- **Install** with `update_games.ps1` (both games closed), and **package** with `make_dist.ps1`.
