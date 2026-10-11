package dev.rehan.passthrough.client.mixin;

import com.llamalad7.mixinextras.injector.v2.WrapWithCondition;
import com.mojang.blaze3d.vertex.PoseStack;
import dev.rehan.passthrough.client.HostAvatarRenderer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * While the player has a layer of their own (HostAvatarRenderer), the world pass leaves them out. Adapted from
 * EldenCraft's SceneLevelRendererMixin (MIT, Copyright (c) 2026 Adam Grzegorzewski).
 */
@Mixin(LevelRenderer.class)
abstract class AvatarLayerMixin {
	@WrapWithCondition(
		method = "submitEntities",
		at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/entity/EntityRenderDispatcher;submit(Lnet/minecraft/client/renderer/entity/state/EntityRenderState;Lnet/minecraft/client/renderer/state/level/CameraRenderState;DDDLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;)V")
	)
	private boolean passthrough$separateLocalAvatar(final EntityRenderDispatcher dispatcher, final EntityRenderState state, final CameraRenderState camera,
		final double x, final double y, final double z, final PoseStack poses, final SubmitNodeCollector nodes) {
		return !HostAvatarRenderer.belongsToIsolatedLayer(state);
	}
}
