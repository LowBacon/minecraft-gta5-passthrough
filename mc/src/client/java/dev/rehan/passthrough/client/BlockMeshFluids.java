package dev.rehan.passthrough.client;

import com.mojang.blaze3d.vertex.VertexConsumer;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import net.minecraft.client.renderer.block.FluidRenderer;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;

/**
 * Captures vanilla FluidRenderer quads (section-local positions) as native mesh triangles in the same 28-byte layout as block
 * quads: world position, atlas UV, ARGB colour, packed light. Water joins the translucent pass and lava the solid one, so
 * fluids stay in the native mesh. Adapted from EldenCraft's BlockMeshFluids (MIT, Copyright (c) 2026 Adam Grzegorzewski).
 */
final class BlockMeshFluids implements FluidRenderer.Output {
	private final ByteArrayOutputStream[] layers = {new ByteArrayOutputStream(), new ByteArrayOutputStream(), new ByteArrayOutputStream()};
	private final Layer[] consumers = {new Layer(0), new Layer(1), new Layer(2)};
	private float ox, oy, oz;

	/** World position of the section origin: fluid vertices are relative to it. */
	void begin(final float x, final float y, final float z) {
		this.ox = x;
		this.oy = y;
		this.oz = z;
		for (ByteArrayOutputStream layer : this.layers) {
			layer.reset();
		}

		for (Layer consumer : this.consumers) {
			consumer.count = 0;
		}
	}

	/** Complete triangles captured for a layer since begin. */
	byte[] layer(final int index) {
		return this.layers[index].toByteArray();
	}

	static int layerIndex(final ChunkSectionLayer layer) {
		return switch (layer) {
			case SOLID -> 0;
			case CUTOUT -> 1;
			case TRANSLUCENT -> 2;
		};
	}

	@Override
	public VertexConsumer getBuilder(final ChunkSectionLayer layer) {
		return this.consumers[layerIndex(layer)];
	}

	private final class Layer implements VertexConsumer {
		private final int index;
		private final float[] x = new float[4], y = new float[4], z = new float[4];
		private final float[] u = new float[4], v = new float[4];
		private final int[] color = new int[4], light = new int[4];
		private final ByteBuffer vertex = ByteBuffer.allocate(28).order(ByteOrder.LITTLE_ENDIAN);
		int count;

		Layer(final int index) {
			this.index = index;
		}

		@Override
		public void addVertex(final float px, final float py, final float pz, final int argb, final float pu, final float pv, final int overlay,
			final int packedLight, final float nx, final float ny, final float nz) {
			this.x[this.count] = px;
			this.y[this.count] = py;
			this.z[this.count] = pz;
			this.u[this.count] = pu;
			this.v[this.count] = pv;
			this.color[this.count] = argb;
			this.light[this.count] = packedLight;
			if (++this.count < 4) {
				return;
			}

			this.count = 0;
			for (int i : new int[] {0, 1, 2, 0, 2, 3}) {
				this.vertex.clear();
				this.vertex.putFloat(BlockMeshFluids.this.ox + this.x[i]).putFloat(BlockMeshFluids.this.oy + this.y[i]).putFloat(BlockMeshFluids.this.oz + this.z[i])
					.putFloat(this.u[i]).putFloat(this.v[i]).putInt(this.color[i]).putInt(this.light[i]);
				BlockMeshFluids.this.layers[this.index].writeBytes(this.vertex.array());
			}
		}

		// FluidRenderer emits whole vertices through the method above; the element-wise builder calls below are unused by it
		@Override
		public VertexConsumer addVertex(final float px, final float py, final float pz) {
			return this;
		}

		@Override
		public VertexConsumer setColor(final int r, final int g, final int b, final int a) {
			return this;
		}

		@Override
		public VertexConsumer setColor(final int argb) {
			return this;
		}

		@Override
		public VertexConsumer setUv(final float pu, final float pv) {
			return this;
		}

		@Override
		public VertexConsumer setUv1(final int pu, final int pv) {
			return this;
		}

		@Override
		public VertexConsumer setUv2(final int pu, final int pv) {
			return this;
		}

		@Override
		public VertexConsumer setUv3(final float pu, final float pv) {
			return this;
		}

		@Override
		public VertexConsumer setNormal(final float nx, final float ny, final float nz) {
			return this;
		}

		@Override
		public VertexConsumer setLineWidth(final float width) {
			return this;
		}
	}
}
