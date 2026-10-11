package dev.rehan.passthrough.client;

import com.mojang.blaze3d.platform.NativeImage;
import dev.rehan.passthrough.Passthrough;
import java.io.ByteArrayOutputStream;
import java.lang.reflect.AccessibleObject;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.renderer.texture.SpriteContents;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;

/**
 * Animated block sprites (water, lava, fire, portals, magma) for the native mesh. The atlas snapshot freezes them, so every
 * game tick the current frame of each sprite the mesh uses is republished for every mip level, and the host copies it into
 * its resident atlas.
 *
 * <p>26.3 keeps the frame strips per mip level in SpriteContents.byMipLevel; vanilla draws them into the atlas with a render
 * pass. They're read here through guarded reflection on the unobfuscated runtime. If anything doesn't match, supported() is
 * false and animated blocks keep their live vanilla rendering. Adapted from EldenCraft's BlockMeshAnimation (MIT, Copyright
 * (c) 2026 Adam Grzegorzewski).
 */
final class BlockMeshAnimation {
	private record Frame(int index, int time) {
	}

	/** One animated sprite: its padded atlas rectangle and frame strips per mip. */
	private record Sprite(int x, int y, int width, int height, int padding, NativeImage[] mips, int rowSize, boolean interpolate,
		List<Frame> frames, int period) {
	}

	private static Field byMipLevel, animatedTexture, frames, frameRowSize, interpolateFrames;
	private static Method frameIndex, frameTime;
	private static boolean resolved, supported;

	private final Map<TextureAtlasSprite, Sprite> sprites = new LinkedHashMap<>();
	private long publishedTick = Long.MIN_VALUE, publishedAtlas;
	private int publishedCount = -1;

	static boolean supported() {
		if (!resolved) {
			resolved = true;
			try {
				byMipLevel = SpriteContents.class.getDeclaredField("byMipLevel");
				animatedTexture = SpriteContents.class.getDeclaredField("animatedTexture");
				Class<?> animation = animatedTexture.getType();
				frames = animation.getDeclaredField("frames");
				frameRowSize = animation.getDeclaredField("frameRowSize");
				interpolateFrames = animation.getDeclaredField("interpolateFrames");
				Class<?> info = Class.forName(SpriteContents.class.getName() + "$FrameInfo");
				frameIndex = info.getDeclaredMethod("index");
				frameTime = info.getDeclaredMethod("time");
				for (AccessibleObject member : new AccessibleObject[] {byMipLevel, animatedTexture, frames, frameRowSize, interpolateFrames, frameIndex, frameTime}) {
					member.setAccessible(true);
				}

				supported = byMipLevel.getType() == NativeImage[].class && frames.getType() == List.class && frameRowSize.getType() == int.class
					&& interpolateFrames.getType() == boolean.class && frameIndex.getReturnType() == int.class && frameTime.getReturnType() == int.class;
			} catch (ReflectiveOperationException | RuntimeException failure) {
				supported = false;
				Passthrough.LOG.warn("animated block sprites stay live in the scene capture: {}", failure.toString());
			}
		}

		return supported;
	}

	void clear() {
		this.sprites.clear();
		this.publishedTick = Long.MIN_VALUE;
		this.publishedCount = -1;
	}

	/** Registers a sprite the mesh uses; false if it can't be animated natively. */
	boolean add(final TextureAtlasSprite sprite, final int atlasWidth) {
		if (this.sprites.containsKey(sprite)) {
			return true;
		}

		if (!supported() || this.sprites.size() >= 1024) {
			return false;
		}

		try {
			SpriteContents contents = sprite.contents();
			Object animation = animatedTexture.get(contents);
			if (animation == null) {
				return false;
			}

			NativeImage[] mips = (NativeImage[])byMipLevel.get(contents);
			int rowSize = frameRowSize.getInt(animation);
			List<Frame> list = new ArrayList<>();
			int period = 0;
			for (Object info : (List<?>)frames.get(animation)) {
				Frame frame = new Frame((int)frameIndex.invoke(info), (int)frameTime.invoke(info));
				if (frame.index < 0 || frame.time < 1) {
					return false;
				}

				list.add(frame);
				period += frame.time;
			}

			int width = contents.width(), height = contents.height();
			int padding = Math.round(sprite.getU0() * atlasWidth) - sprite.getX();
			if (mips == null || mips.length == 0 || list.isEmpty() || rowSize < 1 || width < 1 || height < 1 || padding < 0 || padding > 64) {
				return false;
			}

			for (Frame frame : list) {
				int fx = frame.index % rowSize * width, fy = frame.index / rowSize * height;
				if (fx + width > mips[0].getWidth() || fy + height > mips[0].getHeight()) {
					return false;
				}
			}

			this.sprites.put(sprite, new Sprite(sprite.getX(), sprite.getY(), width, height, padding, mips, rowSize,
				interpolateFrames.getBoolean(animation), List.copyOf(list), period));
			return true;
		} catch (ReflectiveOperationException | RuntimeException failure) {
			Passthrough.LOG.warn("animated sprite {} stays live: {}", sprite, failure.toString());
			return false;
		}
	}

	boolean isEmpty() {
		return this.sprites.isEmpty();
	}

	/** Publishes this tick's frames once per game tick (and whenever the sprite set or atlas changes). */
	void publish(final BlockMeshMailbox mailbox, final BlockMeshProtocol.Identity identity, final long atlasRevision, final int atlasWidth,
		final int atlasHeight, final int atlasMips, final long tick, final long stamp, final boolean active) {
		if (identity == null || atlasRevision <= 0 || this.sprites.isEmpty()) {
			return;
		}

		if (tick == this.publishedTick && atlasRevision == this.publishedAtlas && this.sprites.size() == this.publishedCount) {
			return;
		}

		ByteArrayOutputStream pixels = new ByteArrayOutputStream();
		List<int[]> regions = new ArrayList<>();
		for (Sprite sprite : this.sprites.values()) {
			long t = Math.floorMod(tick, (long)sprite.period);
			int current = 0;
			while (t >= sprite.frames.get(current).time) {
				t -= sprite.frames.get(current).time;
				current++;
			}

			Frame frame = sprite.frames.get(current);
			Frame next = sprite.frames.get((current + 1) % sprite.frames.size());
			float blend = sprite.interpolate ? (float)t / frame.time : 0;
			for (int mip = 0; mip < Math.min(atlasMips, sprite.mips.length); mip++) {
				int pw = (sprite.width + 2 * sprite.padding) >> mip, ph = (sprite.height + 2 * sprite.padding) >> mip;
				int x = sprite.x >> mip, y = sprite.y >> mip;
				if (pw < 1 || ph < 1 || x + pw > BlockMeshProtocol.mipExtent(atlasWidth, mip) || y + ph > BlockMeshProtocol.mipExtent(atlasHeight, mip)) {
					continue;
				}

				regions.add(new int[] {x, y, pw, ph, mip, pixels.size()});
				writeFrame(pixels, sprite, mip, pw, ph, frame.index, next.index, blend);
			}
		}

		if (regions.isEmpty() || regions.size() > BlockMeshProtocol.MAX_ANIM_REGIONS) {
			return;
		}

		int tableBytes = regions.size() * BlockMeshProtocol.ANIM_REGION;
		byte[] data = new byte[tableBytes + pixels.size()];
		if (data.length > BlockMeshProtocol.ANIM_BYTES - BlockMeshProtocol.HEADER) {
			return;
		}

		ByteBuffer table = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
		for (int i = 0; i < regions.size(); i++) {
			int[] r = regions.get(i);
			int at = i * BlockMeshProtocol.ANIM_REGION;
			table.putShort(at, (short)r[0]).putShort(at + 2, (short)r[1]).putShort(at + 4, (short)r[2]).putShort(at + 6, (short)r[3])
				.put(at + 8, (byte)r[4]).putInt(at + 12, tableBytes + r[5]);
		}

		System.arraycopy(pixels.toByteArray(), 0, data, tableBytes, pixels.size());
		mailbox.animation(BlockMeshProtocol.header(BlockMeshProtocol.ANIM_MAGIC, identity, stamp, Math.max(1, tick + 1), atlasRevision,
			regions.size(), 0, data.length, 0, 0, 0, active), data);
		this.publishedTick = tick;
		this.publishedAtlas = atlasRevision;
		this.publishedCount = this.sprites.size();
	}

	/** The padded sprite rectangle at one mip: frame texels, edge-clamped into the padding. */
	private static void writeFrame(final ByteArrayOutputStream out, final Sprite sprite, final int mip, final int pw, final int ph, final int index,
		final int nextIndex, final float blend) {
		NativeImage image = sprite.mips[mip];
		int w = Math.max(1, sprite.width >> mip), h = Math.max(1, sprite.height >> mip);
		int pad = sprite.padding >> mip;
		int fx = (index % sprite.rowSize * sprite.width) >> mip, fy = (index / sprite.rowSize * sprite.height) >> mip;
		int nx = (nextIndex % sprite.rowSize * sprite.width) >> mip, ny = (nextIndex / sprite.rowSize * sprite.height) >> mip;
		int iw = image.getWidth(), ih = image.getHeight();
		byte[] row = new byte[pw * 4];
		for (int j = 0; j < ph; j++) {
			int cy = Math.clamp(j - pad, 0, h - 1);
			for (int i = 0; i < pw; i++) {
				int cx = Math.clamp(i - pad, 0, w - 1);
				int argb = image.getPixel(Math.min(iw - 1, fx + cx), Math.min(ih - 1, fy + cy));
				if (blend > 0) {
					argb = mix(argb, image.getPixel(Math.min(iw - 1, nx + cx), Math.min(ih - 1, ny + cy)), blend);
				}

				row[i * 4] = (byte)(argb >>> 16);
				row[i * 4 + 1] = (byte)(argb >>> 8);
				row[i * 4 + 2] = (byte)argb;
				row[i * 4 + 3] = (byte)(argb >>> 24);
			}

			out.writeBytes(row);
		}
	}

	/** Vanilla frame interpolation: current * (1 - t) + next * t, per channel. */
	static int mix(final int current, final int next, final float t) {
		int out = 0;
		for (int shift = 0; shift < 32; shift += 8) {
			float a = (current >>> shift) & 255, b = (next >>> shift) & 255;
			out |= Math.clamp(Math.round(a + (b - a) * t), 0, 255) << shift;
		}

		return out;
	}
}
