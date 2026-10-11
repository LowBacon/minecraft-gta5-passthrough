package dev.rehan.passthrough.client.mixin;

import dev.rehan.passthrough.Passthrough;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.state.OptionsRenderState;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * No chunk fade-in while a host is attached. For chunkSectionFadeInTime after a section is uploaded or comes back into the
 * visibility graph, terrain.fsh mixes its colour towards the fog colour (sections beyond chunkFullyVisibleRange). The
 * passthrough's fog colour is black (FogRendererMixin), and the restarts happen all the time here: the host's camera
 * swings and jumps (sections leave and re-enter the graph), and the host's ground keeps rebuilding the sections round the
 * player. So distant blocks flashed black and faded back whenever the camera turned. The player's own option is untouched.
 */
@Mixin(LevelRenderer.class)
abstract class LevelRendererMixin {
	/** -Dpassthrough.keepFadeIn=true: Minecraft's own fade-in even while attached (to compare, see host/frame_test.ps1). */
	private static final boolean KEEP_FADE_IN = Boolean.getBoolean("passthrough.keepFadeIn");

	@Redirect(method = {
		"extractSectionDrawGroups",
		"render(Lcom/mojang/blaze3d/resource/GraphicsResourceAllocator;ZLnet/minecraft/client/renderer/state/level/CameraRenderState;Lcom/mojang/renderpearl/api/buffers/GpuBufferSlice;Lorg/joml/Vector4f;ZZ)V"
	}, at = @At(value = "FIELD", target = "Lnet/minecraft/client/renderer/state/OptionsRenderState;chunkSectionFadeInTime:D", opcode = Opcodes.GETFIELD))
	private double passthrough$noFadeIn(final OptionsRenderState options) {
		return Passthrough.active && !KEEP_FADE_IN ? 0.0 : options.chunkSectionFadeInTime;
	}
}
