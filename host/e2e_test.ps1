# End-to-end test of the Minecraft mod without GTA: plays the host on ws://127.0.0.1:25599 (camera poses at 30 Hz so the
# mod attaches), then drives Minecraft only through the new input messages (rawkey / char) and logs what comes back.
param([string]$Log = "$PSScriptRoot\e2e_log.txt")
$ErrorActionPreference = 'Stop'
$c = [Net.Sockets.TcpClient]::new('127.0.0.1', 25599)
$s = $c.GetStream()
$key = [Convert]::ToBase64String([byte[]](1..16 | ForEach-Object { Get-Random -Max 256 }))
$req = "GET / HTTP/1.1`r`nHost: 127.0.0.1:25599`r`nUpgrade: websocket`r`nConnection: Upgrade`r`nSec-WebSocket-Key: $key`r`nSec-WebSocket-Version: 13`r`n`r`n"
$rb = [Text.Encoding]::ASCII.GetBytes($req); $s.Write($rb, 0, $rb.Length)
Start-Sleep -Milliseconds 300
$hb = New-Object byte[] 4096; $n = $s.Read($hb, 0, 4096)
$status = ([Text.Encoding]::ASCII.GetString($hb, 0, $n) -split "`r`n")[0]
"handshake: $status" | Out-File $Log -Encoding utf8
$pending = New-Object System.Collections.Generic.List[byte]
function Send([string]$text) {
	$p = [Text.Encoding]::UTF8.GetBytes($text); $mask = [byte[]](1..4 | ForEach-Object { Get-Random -Max 256 })
	$h = New-Object System.Collections.Generic.List[byte]; $h.Add(0x81)
	if ($p.Length -lt 126) { $h.Add([byte](0x80 -bor $p.Length)) } else { $h.Add(0xFE); $h.Add([byte]($p.Length -shr 8)); $h.Add([byte]($p.Length -band 0xFF)) }
	$h.AddRange($mask); for ($i = 0; $i -lt $p.Length; $i++) { $p[$i] = $p[$i] -bxor $mask[$i % 4] }; $h.AddRange($p)
	$a = $h.ToArray(); $s.Write($a, 0, $a.Length)
}
function Pump {
	while ($c.Available -gt 0) { $b = New-Object byte[] $c.Available; $r = $s.Read($b, 0, $b.Length); $pending.AddRange([byte[]]$b[0..($r - 1)]) }
	while ($pending.Count -ge 2) {
		$len = $pending[1] -band 0x7F; $off = 2
		if ($len -eq 126) { if ($pending.Count -lt 4) { return }; $len = ($pending[2] -shl 8) -bor $pending[3]; $off = 4 }
		elseif ($len -eq 127) { if ($pending.Count -lt 10) { return }; $len = 0; for ($i = 2; $i -lt 10; $i++) { $len = $len * 256 + $pending[$i] }; $off = 10 }
		if ($pending.Count -lt $off + $len) { return }
		$msg = [Text.Encoding]::UTF8.GetString($pending.GetRange($off, $len).ToArray()); $pending.RemoveRange(0, $off + $len)
		if ($msg -match '"t":"screen","open":(true|false)') { $script:screenOpen = $Matches[1] -eq 'true' }
		if ($msg -notmatch '"t":"(proj|mobs|gtastate)"') { "{0,6:N2}s  {1}" -f $sw.Elapsed.TotalSeconds, $msg | Out-File $Log -Append -Encoding utf8 }
	}
}
function Key([int]$vk) { Send "{`"t`":`"rawkey`",`"vk`":$vk,`"sc`":0,`"ext`":false,`"down`":true,`"rep`":false,`"mods`":0}"; Start-Sleep -Milliseconds 60; Send "{`"t`":`"rawkey`",`"vk`":$vk,`"sc`":0,`"ext`":false,`"down`":false,`"rep`":false,`"mods`":0}" }
function TypeText([string]$text) { foreach ($ch in $text.ToCharArray()) { Send "{`"t`":`"char`",`"c`":$([int]$ch)}"; Start-Sleep -Milliseconds 15 } }
function Chat([string]$text) { Key 0x54; Start-Sleep -Milliseconds 400; TypeText $text; Start-Sleep -Milliseconds 200; Key 0x0D }
$script:screenOpen = $false
$steps = [ordered]@{
	1.5  = { if ($script:screenOpen) { '--- a screen was left open: Esc' | Out-File $Log -Append -Encoding utf8; Key 0x1B } }
	3.0  = { "--- T, /time set night, Enter" | Out-File $Log -Append -Encoding utf8; Chat '/time set night' }
	6.0  = { "--- T, /weather thunder, Enter" | Out-File $Log -Append -Encoding utf8; Chat '/weather thunder' }
	9.0  = { "--- T, /gamerule advance_time true, Enter (GTA should follow, ~1/s)" | Out-File $Log -Append -Encoding utf8; Chat '/gamerule advance_time true' }
	13.5 = { "--- T, /gamerule advance_time false, Enter" | Out-File $Log -Append -Encoding utf8; Chat '/gamerule advance_time false' }
	16.0 = { "--- T, /time add 1000, Enter" | Out-File $Log -Append -Encoding utf8; Chat '/time add 1000' }
	18.5 = { "--- T, /weather clear, Enter" | Out-File $Log -Append -Encoding utf8; Chat '/weather clear' }
	21.0 = { "--- E (inventory = creative menu)" | Out-File $Log -Append -Encoding utf8; Key 0x45 }
	23.0 = { "--- mouse move + click inside it, then Esc" | Out-File $Log -Append -Encoding utf8; Send '{"t":"mouse","x":0.5,"y":0.5}'; Send '{"t":"mbtn","b":1,"down":true,"mods":0}'; Start-Sleep -Milliseconds 80; Send '{"t":"mbtn","b":1,"down":false,"mods":0}'; Start-Sleep -Milliseconds 400; Key 0x1B }
	25.5 = { "--- end" | Out-File $Log -Append -Encoding utf8 }
}
$sw = [Diagnostics.Stopwatch]::StartNew(); $frame = 0; $done = @{}
while ($sw.Elapsed.TotalSeconds -lt 26.0) {
	$frame++
	Send ("{`"t`":`"cam`",`"f`":$frame,`"p`":[0.5,67.6,-3.5],`"r`":[0,10,0],`"fov`":70,`"fp`":false,`"pl`":[0.5,66,0.5],`"h`":0}")
	foreach ($t in $steps.Keys) { if (-not $done[$t] -and $sw.Elapsed.TotalSeconds -ge $t) { $done[$t] = $true; & $steps[$t] } }
	Pump
	Start-Sleep -Milliseconds 33
}
Pump
$c.Close()

