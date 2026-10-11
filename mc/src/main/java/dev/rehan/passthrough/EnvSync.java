package dev.rehan.passthrough;

import java.util.Locale;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.gamerules.GameRules;

/**
 * Minecraft's time and weather, mirrored to the host's environment. It watches Minecraft's actual state (its clock,
 * weather and the advance_time / advance_weather gamerules) every server tick, whatever changed it: /time, /weather,
 * /gamerule, /execute, a function or datapack, sleeping. Minecraft's command system stays as it is.
 *
 * <p>The flow is one way (Minecraft to the host), so there's no feedback loop. Every message says where the change came
 * from ("origin") and carries a sequence number.
 *
 * <ul>
 * <li>{"t":"env","seq":n,"origin":"minecraft","time":ticks,"hoff":h,"follow":bool,"jump":bool,"blend":s}:
 * follow = Minecraft's clock runs (or env.time=minecraft) and the host's clock holds on Minecraft's; jump = the time was
 * set (blend to it); else follow ends and the host's clock runs on by itself from where it is.</li>
 * <li>{"t":"env","seq":n,"origin":"minecraft","weather":"clear|rain|thunder","follow":bool,"blend":s}</li>
 * </ul>
 * Server thread.
 */
public final class EnvSync {
	private static final long DAY = 24000L;
	private static long seq;
	private static long lastTime = Long.MIN_VALUE;
	private static boolean lastAdvanceTime;
	private static boolean lastFollowTime;
	private static String lastWeather;
	private static boolean lastFollowWeather;
	private static int resendIn;

	private EnvSync() {
	}

	/**
	 * The mod changed the time or weather itself (WorldBridge.command): take the new state as the starting point without
	 * telling the host, which handles those its own way. Server thread.
	 */
	static void rebase() {
		boolean wasFollowing = lastFollowTime;
		lastTime = Long.MIN_VALUE;
		lastWeather = null;
		lastFollowTime = wasFollowing; // a follow already running carries on (it's re-sent within a second)
	}

	static void tick(final MinecraftServer s) {
		if (!Passthrough.active) {
			// nobody to tell: start over when a host attaches again (its own clock and weather stand until something changes)
			lastTime = Long.MIN_VALUE;
			lastWeather = null;
			lastFollowTime = lastFollowWeather = false;
			return;
		}

		ServerLevel level = s.overworld();
		time(level);
		weather(level);
	}

	private static void time(final ServerLevel level) {
		long time = level.getOverworldClockTime();
		boolean advance = level.getGameRules().get(GameRules.ADVANCE_TIME);
		Config.Sync mode = Config.time;
		boolean follow = mode == Config.Sync.MINECRAFT || mode == Config.Sync.COMMANDS && advance;
		if (lastTime == Long.MIN_VALUE) {
			lastTime = time;
			lastAdvanceTime = advance;
			// commands mode: the host keeps its own time until Minecraft's changes; minecraft mode: it follows from now on
			if (mode != Config.Sync.OFF && follow) {
				send(time, true, true);
			}

			lastFollowTime = follow && mode != Config.Sync.OFF;
			return;
		}

		// a running clock moves one tick a tick: anything else is a set (a command, sleeping, a function)
		long expected = lastTime + (lastAdvanceTime ? 1L : 0L);
		boolean jumped = Math.abs(time - expected) > 2L;
		lastTime = time;
		lastAdvanceTime = advance;
		if (mode == Config.Sync.OFF) {
			if (lastFollowTime) {
				send(time, false, false); // let the host's clock run on its own again
				lastFollowTime = false;
			}

			return;
		}

		if (jumped || follow != lastFollowTime || follow && --resendIn <= 0) {
			send(time, follow, jumped);
			lastFollowTime = follow;
		}
	}

	private static void send(final long time, final boolean follow, final boolean jump) {
		resendIn = 20; // following: once a second is plenty (a Minecraft second is 1.2 of the host's minutes)
		Passthrough.events.accept(String.format(Locale.ROOT,
			"{\"t\":\"env\",\"seq\":%d,\"origin\":\"minecraft\",\"time\":%d,\"hoff\":%d,\"follow\":%b,\"jump\":%b,\"blend\":%d}",
			++seq, Math.floorMod(time, DAY), Config.hourOffset, follow, jump, jump ? Config.blendSeconds : 0));
	}

	private static void weather(final ServerLevel level) {
		Config.Sync mode = Config.weather;
		String now = level.isThundering() ? "thunder" : level.isRaining() ? "rain" : "clear";
		boolean follow = mode == Config.Sync.MINECRAFT || mode == Config.Sync.COMMANDS && level.getGameRules().get(GameRules.ADVANCE_WEATHER);
		if (lastWeather == null) {
			lastWeather = now;
			lastFollowWeather = follow;
			if (mode == Config.Sync.MINECRAFT) {
				sendWeather(now, true);
			}

			return;
		}

		if (mode == Config.Sync.OFF || now.equals(lastWeather) && follow == lastFollowWeather) {
			lastWeather = now;
			return;
		}

		lastWeather = now;
		lastFollowWeather = follow;
		sendWeather(now, follow);
	}

	private static void sendWeather(final String weather, final boolean follow) {
		Passthrough.events.accept(String.format(Locale.ROOT, "{\"t\":\"env\",\"seq\":%d,\"origin\":\"minecraft\",\"weather\":\"%s\",\"follow\":%b,\"blend\":%d}",
			++seq, weather, follow, Config.weatherBlendSeconds));
	}
}
