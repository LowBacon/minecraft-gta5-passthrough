package dev.rehan.passthrough.client.mixin;

import dev.rehan.passthrough.client.HostState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.renderer.entity.player.AvatarRenderer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.world.entity.Avatar;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Steve's pose from what the host's player is doing (Minecraft's own animations, whole model at once): crouching
 * (stealth), swimming (eased in and out as Minecraft does), seated in a vehicle. Holding one of the host's guns (drawn by
 * the host): he aims with both arms forward and holds nothing of his own. Elytra flight keeps Minecraft's own glide pose.
 */
@Mixin(AvatarRenderer.class)
abstract class AvatarRendererMixin {
	@Unique private static float passthrough$swim;
	@Unique private static long passthrough$swimNanos;

	@Inject(method = "extractRenderState(Lnet/minecraft/world/entity/Avatar;Lnet/minecraft/client/renderer/entity/state/AvatarRenderState;F)V", at = @At("TAIL"))
	private void passthrough$hostPose(final Avatar entity, final AvatarRenderState state, final float partialTick, final CallbackInfo ci) {
		HostState.Pose p = HostState.frame();
		if (p == null || entity != Minecraft.getInstance().player) {
			return;
		}

		if (!p.drive()) {
			// swimming eases over about a quarter second (Minecraft's swimAmount does the same over a few ticks)
			long now = System.nanoTime();
			float dt = passthrough$swimNanos == 0L ? 0.0F : Math.min((now - passthrough$swimNanos) * 1.0E-9F, 0.1F);
			passthrough$swimNanos = now;
			float target = p.is(HostState.MOVE_SWIM) || p.is(HostState.MOVE_UNDERWATER) ? 1.0F : 0.0F;
			passthrough$swim += Math.clamp((target - passthrough$swim) * dt * 8.0F, -1.0F, 1.0F);
			passthrough$swim = Math.clamp(passthrough$swim, 0.0F, 1.0F);
			boolean seated = p.is(HostState.MOVE_SEATED);
			boolean crouching = p.is(HostState.MOVE_CROUCH) && !seated && passthrough$swim < 0.5F;
			state.isPassenger = seated;
			state.isCrouching = crouching;
			state.swimAmount = passthrough$swim;
			state.isVisuallySwimming = passthrough$swim > 0.0F;
			state.pose = passthrough$swim >= 0.5F ? Pose.SWIMMING : crouching ? Pose.CROUCHING : Pose.STANDING;
		}

		if (p.gun()) {
			state.rightArmPose = HumanoidModel.ArmPose.CROSSBOW_HOLD;
			state.leftArmPose = HumanoidModel.ArmPose.EMPTY;
			state.rightHandItemState.clear();
			state.leftHandItemState.clear();
			state.rightHandItemStack = ItemStack.EMPTY;
			state.leftHandItemStack = ItemStack.EMPTY;
		}
	}
}
