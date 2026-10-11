"""Record the GTA window + GTA and Minecraft audio until <PASSTHROUGH_WIN_DIR>\\takes\\STOP appears (max 40 min).

    python backup_rec.py [name]   # from WSL; the take is <PASSTHROUGH_WIN_DIR>\\takes\\<name> (default "session")
"""
import sys, time
from pathlib import Path
sys.path.insert(0, str(Path(__file__).parent))
import record

name = sys.argv[1] if len(sys.argv) > 1 else "session"
stop = Path(record.wsl(record.TAKES + r"\STOP"))
stop.unlink(missing_ok=True)
game, gta_pid = record.gta()  # Enhanced or Legacy, whichever runs
pids = {"gta": gta_pid, "mc": record.minecraft_pid()}
rec = record.Recorder(record.TAKES + "\\" + name, pids, window_exe=(game or "GTA5") + ".exe")
rec.start()
t0 = time.time()
print("recording", name, pids, flush=True)
while not stop.exists() and time.time() - t0 < 40 * 60 and record.pid_of(game or "GTA5"):
    time.sleep(2)
print("stopping after %.0f s" % (time.time() - t0), flush=True)
print(rec.stop(), flush=True)
