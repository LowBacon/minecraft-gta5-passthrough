package dev.rehan.passthrough.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.QuadInstance;
import com.mojang.blaze3d.vertex.SheetedDecalTextureGenerator;
import com.mojang.blaze3d.vertex.VertexConsumer;
import java.util.List;
import net.fabricmc.fabric.api.client.renderer.v1.Renderer;
import net.fabricmc.fabric.api.client.renderer.v1.model.FabricBlockStateModel;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Mining cracks from the same baked quads and sheeted decal generator as vanilla's breaking-block feature. Adapted from
 * EldenCraft's BlockCrackGeometry (MIT, Copyright (c) 2026 Adam Grzegorzewski).
 */
final class BlockCrackGeometry {
	private static final Direction[] SIDES = Direction.values();
	private static final int[] TRIANGLES = {0, 1, 2, 0, 2, 3};

	@FunctionalInterface
	interface Sink {
		void vertex(float x, float y, float z, float u, float v);
	}

	private BlockCrackGeometry() {
	}

	/** Fallback for custom Fabric models; returns how many quads the model emitted. */
	static int emitFabric(final PoseStack pose, final FabricBlockStateModel model, final BlockPos pos, final BlockState state, final RandomSource random,
		final Renderer renderer, final int stage, final int size, final Sink output) {
		if (stage < 0 || stage > 9 || size < 1 || size > 256) {
			throw new IllegalArgumentException("destroy atlas bounds");
		}

		Quads sink = new Quads(stage, size, output);
		SheetedDecalTextureGenerator vanilla = new SheetedDecalTextureGenerator(sink, pose.last(), 1);
		int[] quads = {0};
		// Fabric's own breaking-model route: the EMPTY tint getter and a never-culling predicate keep all faces
		var emitter = renderer.quadEmitter(quad -> {
			quads[0]++;
			quad.buffer(OverlayTexture.NO_OVERLAY, pose.last(), vanilla);
		});
		model.emitQuads(emitter, BlockAndTintGetter.EMPTY, pos, state, random, side -> false);
		sink.finish();
		return quads[0];
	}

	static void emit(final PoseStack pose, final List<BlockStateModelPart> parts, final int stage, final int size, final Sink output) {
		if (stage < 0 || stage > 9 || size < 1 || size > 256) {
			throw new IllegalArgumentException("destroy atlas bounds");
		}

		Quads sink = new Quads(stage, size, output);
		SheetedDecalTextureGenerator vanilla = new SheetedDecalTextureGenerator(sink, pose.last(), 1);
		QuadInstance instance = new QuadInstance();
		instance.setLightCoords(15728880);
		instance.setOverlayCoords(OverlayTexture.NO_OVERLAY);
		for (BlockStateModelPart part : parts) {
			for (Direction side : SIDES) {
				for (var quad : part.getQuads(side)) {
					vanilla.putBakedQuad(pose.last(), quad, instance);
				}
			}

			for (var quad : part.getQuads(null)) {
				vanilla.putBakedQuad(pose.last(), quad, instance);
			}
		}

		sink.finish();
	}

	private static final class Quads implements VertexConsumer {
		private final int stage, size, height;
		private final Sink output;
		private final float[][] vertices = new float[4][5];
		private int index = -1;

		Quads(final int stage, final int size, final Sink output) {
			this.stage = stage;
			this.size = size;
			this.height = size * 10 + 1;
			this.output = output;
		}

		@Override
		public VertexConsumer addVertex(final float x, final float y, final float z) {
			if (this.index == 3) {
				this.emit();
				this.index = -1;
			}

			float[] v = this.vertices[++this.index];
			v[0] = x;
			v[1] = y;
			v[2] = z;
			return this;
		}

		@Override
		public VertexConsumer setUv(final float u, final float v) {
			this.vertices[this.index][3] = u;
			this.vertices[this.index][4] = v;
			return this;
		}

		void finish() {
			if (this.index == 3) {
				this.emit();
			} else if (this.index != -1) {
				throw new IllegalArgumentException("incomplete crack quad");
			}

			this.index = -1;
		}

		private void emit() {
			float minU = Float.POSITIVE_INFINITY, minV = minU;
			for (float[] p : this.vertices) {
				minU = Math.min(minU, p[3]);
				minV = Math.min(minV, p[4]);
			}

			float baseU = (float)Math.floor(minU + 1e-5), baseV = (float)Math.floor(minV + 1e-5);
			for (int i : TRIANGLES) {
				float[] p = this.vertices[i];
				float u = p[3] - baseU, v = p[4] - baseV;
				if (!Float.isFinite(u) || !Float.isFinite(v) || u < -.001 || u > 1.001 || v < -.001 || v > 1.001) {
					throw new IllegalArgumentException("destroy UV repeat exceeds a tile");
				}

				this.output.vertex(p[0], p[1], p[2], (.5f + Math.clamp(u, 0, 1) * (this.size - 1)) / this.size,
					(this.stage * this.size + .5f + Math.clamp(v, 0, 1) * (this.size - 1)) / this.height);
			}
		}

		@Override
		public VertexConsumer setColor(final int r, final int g, final int b, final int a) {
			return this;
		}

		@Override
		public VertexConsumer setColor(final int c) {
			return this;
		}

		@Override
		public VertexConsumer setUv1(final int u, final int v) {
			return this;
		}

		@Override
		public VertexConsumer setUv2(final int u, final int v) {
			return this;
		}

		@Override
		public VertexConsumer setUv3(final float u, final float v) {
			return this;
		}

		@Override
		public VertexConsumer setNormal(final float x, final float y, final float z) {
			return this;
		}

		@Override
		public VertexConsumer setLineWidth(final float width) {
			return this;
		}
	}
}
