package dev.rehan.passthrough;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Properties;
import net.fabricmc.loader.api.FabricLoader;

/**
 * config/passthrough.properties, written with the defaults the first time. A bad value is logged and its default used,
 * never a crash.
 *
 * <ul>
 * <li>env.time = commands | minecraft | off: how Minecraft's time reaches the host (EnvSync)</li>
 * <li>env.weather = commands | minecraft | off: the same for its weather</li>
 * <li>env.hourOffset = 0..23: the host's hour at Minecraft tick 0 (Minecraft's sunrise; 6 by default)</li>
 * <li>env.blendSeconds = 0..60: how long the host takes to reach a new time</li>
 * <li>env.weatherBlendSeconds = 0..120: the same for weather</li>
 * <li>render.alwaysDaylight = true | false: Minecraft lights its blocks as at noon whatever its time, because the host's
 * lighting is put on them afterwards (else night darkens them twice)</li>
 * <li>render.overscan = 1.0..1.5: Minecraft renders this much wider a view than the host's (third person), so turning the
 * camera re-projects into picture that's there instead of an empty edge</li>
 * <li>render.ownSkin = true | false: the player wears the account's own skin (false: classic Steve)</li>
 * <li>render.nativeBlocks = true | false: the host draws Minecraft's blocks itself, baked into a mesh, with its own camera at
 * its own frame rate (they never lag, swim or tear when the camera turns); false: blocks are part of the exported picture</li>
 * <li>render.avatarLayer = true | false: the player (skin, armour, held items) is drawn into a layer of its own that the
 * host shows as drawn, never re-projected (false: the player is part of the world picture)</li>
 * </ul>
 */
public final class Config {
	public enum Sync { OFF, COMMANDS, MINECRAFT }

	public static volatile Sync time = Sync.COMMANDS;
	public static volatile Sync weather = Sync.COMMANDS;
	public static volatile int hourOffset = 6;
	public static volatile int blendSeconds = 3;
	public static volatile int weatherBlendSeconds = 15;
	public static volatile boolean alwaysDaylight = true;
	/** Minecraft's view is this much wider (tan of the half angle) than the host's, third person: margin to re-project into when the camera turns. */
	public static volatile double overscan = 1.12;
	public static volatile boolean ownSkin = true;
	public static volatile boolean avatarLayer = true;
	public static volatile boolean nativeBlocks = true;

	private static final String HEADER = """
		Minecraft x GTA V passthrough.
		env.time / env.weather: commands = only what a command (or anything else) changes goes to GTA, which keeps its own
		  clock and weather otherwise (and follows Minecraft's clock whenever the gamerule advance_time is on);
		  minecraft = GTA always follows Minecraft; off = never.
		env.hourOffset: GTA's hour at Minecraft tick 0 (Minecraft's sunrise is 6:00).
		render.alwaysDaylight: Minecraft's blocks are lit as at noon; GTA's own light is put on them by the effect.
		render.ownSkin: your account's skin (false: classic Steve).
		render.avatarLayer: you (skin, armour, what you hold) in a layer of your own, shown as drawn (false: part of the world).
		render.nativeBlocks: GTA draws Minecraft's blocks itself with its own camera, so they never lag or swim (false: part of the picture).""";

	private Config() {
	}

	static void load() {
		Path file = FabricLoader.getInstance().getConfigDir().resolve("passthrough.properties");
		Properties p = new Properties();
		if (Files.exists(file)) {
			try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
				p.load(r);
			} catch (IOException | IllegalArgumentException e) {
				Passthrough.LOG.warn("config: can't read {} ({}), using the defaults", file, e.toString());
			}
		}

		time = sync(p, "env.time", time);
		weather = sync(p, "env.weather", weather);
		hourOffset = integer(p, "env.hourOffset", hourOffset, 0, 23);
		blendSeconds = integer(p, "env.blendSeconds", blendSeconds, 0, 60);
		weatherBlendSeconds = integer(p, "env.weatherBlendSeconds", weatherBlendSeconds, 0, 120);
		alwaysDaylight = bool(p, "render.alwaysDaylight", alwaysDaylight);
		overscan = decimal(p, "render.overscan", overscan, 1.0, 1.5);
		ownSkin = bool(p, "render.ownSkin", ownSkin);
		avatarLayer = bool(p, "render.avatarLayer", avatarLayer);
		nativeBlocks = bool(p, "render.nativeBlocks", nativeBlocks);
		Passthrough.LOG.info("config: time {}, weather {}, hourOffset {}, blend {} s / {} s, alwaysDaylight {}, overscan {}, ownSkin {}, avatarLayer {}, nativeBlocks {}",
			time, weather, hourOffset, blendSeconds, weatherBlendSeconds, alwaysDaylight, overscan, ownSkin, avatarLayer, nativeBlocks);

		// write back what is in effect, so every setting is there to edit
		Properties out = new Properties();
		out.setProperty("env.time", time.name().toLowerCase(Locale.ROOT));
		out.setProperty("env.weather", weather.name().toLowerCase(Locale.ROOT));
		out.setProperty("env.hourOffset", Integer.toString(hourOffset));
		out.setProperty("env.blendSeconds", Integer.toString(blendSeconds));
		out.setProperty("env.weatherBlendSeconds", Integer.toString(weatherBlendSeconds));
		out.setProperty("render.alwaysDaylight", Boolean.toString(alwaysDaylight));
		out.setProperty("render.overscan", String.format(Locale.ROOT, "%.2f", overscan));
		out.setProperty("render.ownSkin", Boolean.toString(ownSkin));
		out.setProperty("render.avatarLayer", Boolean.toString(avatarLayer));
		out.setProperty("render.nativeBlocks", Boolean.toString(nativeBlocks));
		try {
			Files.createDirectories(file.getParent());
			try (Writer w = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
				out.store(w, HEADER);
			}
		} catch (IOException e) {
			Passthrough.LOG.warn("config: can't write {} ({})", file, e.toString());
		}
	}

	private static Sync sync(final Properties p, final String key, final Sync fallback) {
		String v = p.getProperty(key);
		if (v == null) {
			return fallback;
		}

		try {
			return Sync.valueOf(v.trim().toUpperCase(Locale.ROOT));
		} catch (IllegalArgumentException e) {
			Passthrough.LOG.warn("config: {}={} isn't commands, minecraft or off: using {}", key, v, fallback.name().toLowerCase(Locale.ROOT));
			return fallback;
		}
	}

	private static int integer(final Properties p, final String key, final int fallback, final int min, final int max) {
		String v = p.getProperty(key);
		if (v == null) {
			return fallback;
		}

		try {
			int n = Integer.parseInt(v.trim());
			if (n >= min && n <= max) {
				return n;
			}
		} catch (NumberFormatException e) {
			// below
		}

		Passthrough.LOG.warn("config: {}={} isn't a whole number {}..{}: using {}", key, v, min, max, fallback);
		return fallback;
	}

	private static double decimal(final Properties p, final String key, final double fallback, final double min, final double max) {
		String v = p.getProperty(key);
		if (v == null) {
			return fallback;
		}

		try {
			double d = Double.parseDouble(v.trim());
			if (d >= min && d <= max) {
				return d;
			}
		} catch (NumberFormatException e) {
			// below
		}

		Passthrough.LOG.warn("config: {}={} isn't a number {}..{}: using {}", key, v, min, max, fallback);
		return fallback;
	}

	private static boolean bool(final Properties p, final String key, final boolean fallback) {
		String v = p.getProperty(key);
		if (v == null) {
			return fallback;
		}

		String t = v.trim().toLowerCase(Locale.ROOT);
		if (t.equals("true") || t.equals("false")) {
			return t.equals("true");
		}

		Passthrough.LOG.warn("config: {}={} isn't true or false: using {}", key, v, fallback);
		return fallback;
	}
}
