# Steve's poses, against the DEV client only (mc\gradlew.bat runClient): plays the host with a third-person camera on
# Steve and sends each movement state the GTA plugin sends ("mv": 0 standing, 1 sprint, 2 crouch, 4 swim, 16 seated),
# saving Minecraft's exported world layer for each as a PNG in -Out (look at them: one whole Steve, in that pose).
param([string]$Out = "$PSScriptRoot\pose_test_out")
$ErrorActionPreference = 'Stop'
New-Item -ItemType Directory -Force $Out | Out-Null
Add-Type -ReferencedAssemblies System.Drawing -TypeDefinition @"
using System;
using System.Drawing;
using System.Drawing.Imaging;
using System.IO.MemoryMappedFiles;
using System.Runtime.InteropServices;
public static class McShot
{
	public static long Save(string png)
	{
		using (var mmf = MemoryMappedFile.OpenExisting("Local\\MCPassthroughFrame", MemoryMappedFileRights.ReadWrite))
		using (var view = mmf.CreateViewAccessor(0, 0, MemoryMappedFileAccess.ReadWrite))
		{
			int slot = view.ReadInt32(40);
			view.Write(48, slot); // the slot being copied: Minecraft leaves it alone meanwhile
			try
			{
				long desc = 256 + 128L * slot;
				int w = view.ReadInt32(desc + 24), h = view.ReadInt32(desc + 28);
				long baseOff = 4096 + view.ReadInt64(16) * slot;
				var bmp = new Bitmap(w, h, PixelFormat.Format32bppArgb);
				var data = bmp.LockBits(new Rectangle(0, 0, w, h), ImageLockMode.WriteOnly, PixelFormat.Format32bppArgb);
				byte[] row = new byte[w * 4];
				for (int y = 0; y < h; y++)
				{
					view.ReadArray(baseOff + (long)(h - 1 - y) * w * 4, row, 0, row.Length);
					for (int x = 0; x < w; x++) { byte r = row[x * 4], b = row[x * 4 + 2]; row[x * 4] = b; row[x * 4 + 2] = r; if (row[x * 4 + 3] == 0) { row[x * 4] = 40; row[x * 4 + 1] = 40; row[x * 4 + 2] = 60; } row[x * 4 + 3] = 255; }
					Marshal.Copy(row, 0, data.Scan0 + y * data.Stride, row.Length);
				}
				bmp.UnlockBits(data);
				bmp.Save(png, ImageFormat.Png);
				bmp.Dispose();
				return view.ReadInt64(desc + 8);
			}
			finally { view.Write(48, -1); }
		}
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
# Steve's feet at (0.5, 65, 0.5) on grass; the camera 3.2 m away on the diagonal, a little above, looking at his chest
$f = 0
function Cam([int]$mv) { Send ("{`"t`":`"cam`",`"f`":$((++$script:f)),`"p`":[2.8,66.9,2.8],`"r`":[135,18,0],`"fov`":60,`"fp`":false,`"pl`":[0.5,65,0.5],`"h`":150,`"mv`":$mv}") }
for ($i = 0; $i -lt 30; $i++) { Cam 0; Start-Sleep -Milliseconds 33; Drain }
foreach ($cmd in 'fill -6 64 -6 6 64 6 minecraft:grass_block', 'fill -6 65 -6 6 70 6 minecraft:air') { Send "{`"t`":`"cmd`",`"c`":`"$cmd`"}"; Start-Sleep -Milliseconds 80 }
foreach ($state in @(@(0, 'standing'), @(1, 'sprinting'), @(2, 'crouching'), @(4, 'swimming'), @(16, 'seated'), @(0, 'standing_again'))) {
	for ($i = 0; $i -lt 45; $i++) { Cam $state[0]; Start-Sleep -Milliseconds 33; Drain } # 1.5 s: let it settle (swimming eases in)
	$frame = [McShot]::Save((Join-Path $Out ("pose_{0:00}_{1}.png" -f $state[0], $state[1])))
	"{0,-15} mv={1,-2} Minecraft frame {2}" -f $state[1], $state[0], $frame
}
$c.Close()
"PNGs: $Out"
