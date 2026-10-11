package dev.rehan.passthrough.client.mixin;

import com.llamalad7.mixinextras.injector.v2.WrapWithCondition;
import com.mojang.blaze3d.vertex.PoseStack;
import dev.rehan.passthrough.client.BlockMeshClient;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.block.MovingBlockRenderState;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The native mesh's frame: options that rebuild chunks rebuild it too; each frame begins once vanilla's ViewArea is current
 * and before any chunk or transient block is submitted; transient blocks (26.3 draws a changed block that way while its
 * section compiles) aren't drawn twice; animated blocks the mesh couldn't take render live. Adapted from EldenCraft (MIT).
 */
@Mixin(LevelRenderer.class)
abstract class BlockMeshLevelMixin {
	@Inject(method = "invalidateCompiledGeometry", at = @At("HEAD"))
	private void passthrough$optionsChanged(final CallbackInfo ci) {
		BlockMeshClient.dirty();
	}

	@Inject(method = "render", at = @At(value = "INVOKE",
		target = "Lnet/minecraft/client/renderer/LevelRenderer;repositionCamera(Lnet/minecraft/client/renderer/state/level/CameraRenderState;)V", shift = At.Shift.AFTER))
	private void passthrough$beginNativeBlocks(final CallbackInfo ci) {
		BlockMeshClient.beginFrame();
	}

	@WrapWithCondition(method = "submitTransientBlocks", at = @At(value = "INVOKE",
		target = "Lnet/minecraft/client/renderer/SubmitNodeCollector;submitMovingBlock(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/block/MovingBlockRenderState;I)V"))
	private boolean passthrough$noDuplicateTransientBlocks(final SubmitNodeCollector collector, final PoseStack pose, final MovingBlockRenderState state, final int outline) {
		return !BlockMeshClient.suppressChunks();
	}

	@Inject(method = "submitTransientBlocks", at = @At("TAIL"))
	private void passthrough$liveBlocks(final PoseStack pose, final SubmitNodeCollector collector, final LevelRenderState state, final CallbackInfo ci) {
		BlockMeshClient.submitLiveBlocks(pose, collector, state.cameraRenderState.pos);
	}
}