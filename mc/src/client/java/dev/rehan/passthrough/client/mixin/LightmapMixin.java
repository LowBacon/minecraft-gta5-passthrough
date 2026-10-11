package dev.rehan.passthrough.client.mixin;

import dev.rehan.passthrough.Config;
import dev.rehan.passthrough.Passthrough;
import net.minecraft.client.renderer.LightmapRenderStateExtractor;
import net.minecraft.client.renderer.state.LightmapRenderState;
import org.joml.Vector3f;
import org.joml.Vector3fc;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * While a host is attached, Minecraft's blocks are lit as in full daylight whatever Minecraft's time (render.alwaysDaylight):
 * the effect lights them by the host's picture afterwards, so a Minecraft night would darken them twice. Minecraft's real
 * time is untouched (mobs, crops, /time query all see it). The sky light colour is the last daylight one seen.
 */
@Mixin(LightmapRenderStateExtractor.class)
abstract class LightmapMixin {
	@Unique private static Vector3fc passthrough$daySkyColor = new Vector3f(1.0F, 1.0F, 1.0F);

	@Inject(method = "extract", at = @At("TAIL"))
	private void passthrough$daylight(final LightmapRenderState state, final float partialTick, final CallbackInfo ci) {
		if (state.skyFactor >= 0.999F && state.skyLightColor != null) {
			passthrough$daySkyColor = new Vector3f(state.skyLightColor);
		}

		if (Passthrough.active && Config.alwaysDaylight && state.skyFactor < 0.999F) {
			state.skyFactor = 1.0F;
			state.skyLightColor = passthrough$daySkyColor;
		}
	}
}
