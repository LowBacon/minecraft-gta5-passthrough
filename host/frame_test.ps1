# Frame test against the DEV client only (mc\gradlew.bat runClient): it builds test blocks with commands.
# Plays the host (camera poses at 30 Hz, spinning `-Spin` degrees a second round a fixed spot inside a ring of stone with
# a glass pane), and reads Minecraft's exported frames from the shared memory: for each, how many pixels Minecraft drew
# geometry at (depth set) and how many of those aren't fully opaque (alpha < 250: before the fade-in fix, chunk
# sections fading in again as the camera turned). Writes a few frames as PNGs into -Out.
# -Rebuild: as GTA does while you walk, keep sending ground (barrier) columns, here into the walls' own sections (y 69,
# invisible), so those sections are rebuilt all the time, and walk the camera back and forth (new sections enter view).
param([double]$Spin = 120, [double]$Seconds = 12, [string]$Out = "$PSScriptRoot\frame_test_out", [switch]$Rebuild)
$ErrorActionPreference = 'Stop'
New-Item -ItemType Directory -Force $Out | Out-Null
Add-Type -ReferencedAssemblies System.Drawing -TypeDefinition @"
using System;
using System.Drawing;
using System.Drawing.Imaging;
using System.IO.MemoryMappedFiles;
using System.Runtime.InteropServices;
public static class McFrame
{
	static MemoryMappedFile mmf;
	static MemoryMappedViewAccessor view;
	public static bool Open()
	{
		// read + write, like the GTA add-on: +48 names the slot being copied, and Minecraft doesn't write into it meanwhile
		try { mmf = MemoryMappedFile.OpenExisting("Local\\MCPassthroughFrame", MemoryMappedFileRights.ReadWrite); }
		catch { return false; }
		view = mmf.CreateViewAccessor(0, 0, MemoryMappedFileAccess.ReadWrite);
		return true;
	}
	public static long Published() { return view.ReadInt64(32); }
	// [publish, w, h, geometry samples, non-opaque among them, glass-ish (alpha 1..249) count]
	public static long[] Stats(int stride, string png)
	{
		int slot = view.ReadInt32(40);
		view.Write(48, slot);
		try { return Read(slot, stride, png); }
		finally { view.Write(48, -1); }
	}
	static long[] Read(int slot, int stride, string png)
	{
		long desc = 256 + 128L * slot;
		long seq = view.ReadInt64(desc);
		int w = view.ReadInt32(desc + 24), h = view.ReadInt32(desc + 28);
		long strideBytes = view.ReadInt64(16);
		long baseOff = 4096 + strideBytes * slot;
		long n = (long)w * h * 4;
		long geo = 0, translucent = 0;
		for (int y = 0; y < h; y += stride)
			for (int x = 0; x < w; x += stride)
			{
				long i = ((long)y * w + x) * 4;
				float d = view.ReadSingle(baseOff + n + i);
				if (d <= 0f) continue;
				geo++;
				byte a = view.ReadByte(baseOff + i + 3);
				if (a < 250) translucent++;
			}
		if (!string.IsNullOrEmpty(png)) // (PowerShell hands `$null` over as "")
		{
			var bmp = new Bitmap(w, h, PixelFormat.Format32bppArgb);
			var data = bmp.LockBits(new Rectangle(0, 0, w, h), ImageLockMode.WriteOnly, PixelFormat.Format32bppArgb);
			byte[] row = new byte[w * 4];
			for (int y = 0; y < h; y++)
			{
				view.ReadArray(baseOff + (long)(h - 1 - y) * w * 4, row, 0, row.Length); // rows bottom-up
				for (int x = 0; x < w; x++) { byte r = row[x * 4], b = row[x * 4 + 2]; row[x * 4] = b; row[x * 4 + 2] = r; row[x * 4 + 3] = 255; } // RGBA -> BGRA, opaque for viewing
				Marshal.Copy(row, 0, data.Scan0 + y * data.Stride, row.Length);
			}
			bmp.UnlockBits(data);
			bmp.Save(png, ImageFormat.Png);
			bmp.Dispose();
		}
		return new long[] { view.ReadInt64(32), w, h, geo, translucent, view.ReadInt64(desc) == seq ? 0 : 1 };
	}
}
"@
$c = [Net.Sockets.TcpClient]::new('127.0.0.1', 25599); $s = $c.GetStream()
$key = [Convert]::ToBase64String([byte[]](1..16 | ForEach-Object { Get-Random -Max 256 }))
$req = "GET / HTTP/1.1`r`nHost: 127.0.0.1:25599`r`nUpgrade: websocket`r`nConnection: Upgrade`r`nSec-WebSocket-Key: $key`r`nSec-WebSocket-Version: 13`r`n`r`n"
$rb = [Text.Encoding]::ASCII.GetBytes($req); $s.Write($rb, 0, $rb.Length); Start-Sleep -Milliseconds 300
$hb = New-Object byte[] 4096; [void]$s.Read($hb, 0, 4096)
function Send([string]$text) {
	$p = [Text.Encoding]::UTF8.GetBytes($text); $mask = [byte[]](1..4 | ForEach-Object { Get-Random -Max 256 })
	$h = New-Object System.Collections.Generic.List[byte]; $h.Add(0x81)
	if ($p.Length -lt 126) { $h.Add([byte](0x80 -bor $p.Length)) } else { $h.Add(0xFE); $h.Add([byte]($p.Length -shr 8)); $h.Add([byte]($p.Length -band 0xFF)) }
	$h.AddRange($mask); for ($i = 0; $i -lt $p.Length; $i++) { $p[$i] = $p[$i] -bxor $mask[$i % 4] }; $h.AddRange($p)
	$a = $h.ToArray(); $s.Write($a, 0, $a.Length)
}
function Drain { while ($c.Available -gt 0) { $b = New-Object byte[] $c.Available; [void]$s.Read($b, 0, $b.Length) } }
function Cam([int]$f, [double]$yaw, [double]$x = 0.5, [double]$z = 0.5) {
	$xs = [math]::Round($x, 3); $zs = [math]::Round($z, 3)
	Send ("{`"t`":`"cam`",`"f`":$f,`"p`":[$xs,67.6,$zs],`"r`":[$([math]::Round($yaw, 2)),12,0],`"fov`":60,`"fp`":true,`"pl`":[$xs,66,$zs],`"h`":0}")
}
# attach first (the mod exports only while a host drives the camera), then the test blocks round the spot
$f = 0; for ($i = 0; $i -lt 30; $i++) { Cam (++$f) 0; Start-Sleep -Milliseconds 33; Drain }
foreach ($cmd in 'fill -12 64 -12 12 70 12 minecraft:air', 'fill -12 64 -12 12 64 12 minecraft:grass_block', 'fill -12 65 -12 12 68 -12 minecraft:stone_bricks',
	'fill -12 65 12 12 68 12 minecraft:stone_bricks', 'fill -12 65 -12 -12 68 12 minecraft:oak_planks', 'fill 12 65 -12 12 68 12 minecraft:oak_planks',
	'fill -2 65 6 2 67 6 minecraft:glass', 'setblock 4 65 4 minecraft:diamond_block', 'setblock -4 65 4 minecraft:gold_block') {
	Send ("{`"t`":`"cmd`",`"c`":`"$cmd`"}"); Start-Sleep -Milliseconds 50; Cam (++$f) 0; Drain
}
for ($i = 0; $i -lt 45; $i++) { Cam (++$f) 0; Start-Sleep -Milliseconds 33; Drain } # let the sections build
if (-not [McFrame]::Open()) { throw "no shared memory: is the dev client running with the mod?" }
$rows = @(); $sw = [Diagnostics.Stopwatch]::StartNew(); $last = -1; $shot = 0
while ($sw.Elapsed.TotalSeconds -lt $Seconds) {
	$yaw = ($sw.Elapsed.TotalSeconds * $Spin) % 360
	if ($Rebuild) {
		# walk 6 m/s back and forth across the ring, and rebuild the walls' sections with invisible barriers (then clear them)
		$x = 0.5 + 8.0 * [math]::Sin($sw.Elapsed.TotalSeconds * 0.75)
		Cam (++$f) $yaw $x 0.5
		if ($f % 6 -eq 0) {
			$cols = (1..24 | ForEach-Object { "$(Get-Random -Minimum -12 -Maximum 13),$(Get-Random -Minimum -12 -Maximum 13),69,69" }) -join ','
			Send "{`"t`":`"ground`",`"c`":[$cols]}"
		}
		if ($f % 60 -eq 0) { Send '{"t":"clear"}' }
	}
	else { Cam (++$f) $yaw }
	Drain
	$pub = [McFrame]::Published()
	if ($pub -ne $last) {
		$last = $pub
		$png = $null
		if ($sw.Elapsed.TotalSeconds -ge $shot * ($Seconds / 4) + 1) { $png = Join-Path $Out ("frame_{0}_yaw{1:000}.png" -f $shot, $yaw); $shot++ }
		$st = [McFrame]::Stats(6, $png)
		$rows += [pscustomobject]@{ t = [math]::Round($sw.Elapsed.TotalSeconds, 2); yaw = [int]$yaw; geo = $st[3]; notOpaque = $st[4]; torn = $st[5]; size = "$($st[1])x$($st[2])" }
	}
	Start-Sleep -Milliseconds 33
}
$c.Close()
$rows | Export-Csv (Join-Path $Out 'frames.csv') -NoTypeInformation
$frames = $rows.Count; $geoMin = ($rows | Measure-Object geo -Minimum).Minimum; $geoMax = ($rows | Measure-Object geo -Maximum).Maximum
$bad = @($rows | Where-Object { $_.geo -gt 0 -and $_.notOpaque / $_.geo -gt 0.08 })
"frames read: $frames over $Seconds s at $Spin deg/s  (size $($rows[0].size))"
"geometry samples per frame: min $geoMin, max $geoMax"
"non-opaque share per frame: avg {0:P2}, max {1:P2}" -f (($rows | ForEach-Object { if ($_.geo) { $_.notOpaque / $_.geo } else { 0 } }) | Measure-Object -Average).Average, (($rows | ForEach-Object { if ($_.geo) { $_.notOpaque / $_.geo } else { 0 } }) | Measure-Object -Maximum).Maximum
"frames with more than 8% non-opaque geometry (fading sections): $($bad.Count)"
$median = ($rows | Sort-Object geo)[[int]($rows.Count / 2)].geo
$holes = @($rows | Where-Object { $_.geo -lt $median * 0.85 })
"frames with geometry coverage 15%+ below the median ($median) - blocks missing/dithered out: $($holes.Count)"
"PNGs: $Out"
