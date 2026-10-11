package dev.rehan.passthrough.client.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.blaze3d.vertex.PoseStack;
import dev.rehan.passthrough.client.BlockDetails;
import java.util.List;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.state.level.BlockBreakingRenderState;
import net.minecraft.util.RandomSource;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Mining cracks and the selection outline into the native details while native blocks are on. Adapted from EldenCraft (MIT). */
@Mixin(LevelRenderer.class)
abstract class BlockDetailsMixin {
	@Inject(method = "submitFeatures", at = @At("HEAD"))
	private void passthrough$detailBegin(final CallbackInfo ci) {
		BlockDetails.begin();
	}

	@Inject(method = "submitFeatures", at = @At("TAIL"))
	private void passthrough$detailFinish(final CallbackInfo ci) {
		BlockDetails.finish();
	}

	@WrapOperation(method = "submitBlockDestroyAnimation", at = @At(value = "INVOKE",
		target = "Lnet/minecraft/client/renderer/SubmitNodeCollector;submitBreakingBlockModel(Lcom/mojang/blaze3d/vertex/PoseStack;Ljava/util/List;IZ)V"))
	private void passthrough$cracks(final SubmitNodeCollector nodes, final PoseStack pose, final List<BlockStateModelPart> parts, final int stage,
		final boolean translucent, final Operation<Void> original, @Local final BlockStateModel model, @Local final BlockBreakingRenderState state,
		@Local final RandomSource random) {
		// on failure the chained original still reaches Fabric's genuine submission
		if (!BlockDetails.breaking(pose, parts, stage, model, state, random)) {
			original.call(nodes, pose, parts, stage, translucent);
		}
	}

	@WrapOperation(method = "submitHitOutline", at = @At(value = "INVOKE",
		target = "Lnet/minecraft/client/renderer/SubmitNodeCollector;submitShapeOutline(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/world/phys/shapes/VoxelShape;Lnet/minecraft/client/renderer/rendertype/RenderType;IFZ)V"))
	private void passthrough$outline(final SubmitNodeCollector nodes, final PoseStack pose, final VoxelShape shape, final RenderType type, final int color,
		final float width, final boolean after, final Operation<Void> original) {
		if (!BlockDetails.outline(pose, shape, color, width)) {
			original.call(nodes, pose, shape, type, color, width, after);
		}
	}
}