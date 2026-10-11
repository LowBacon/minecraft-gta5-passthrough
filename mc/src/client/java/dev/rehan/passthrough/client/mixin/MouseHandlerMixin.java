package dev.rehan.passthrough.client.mixin;

import dev.rehan.passthrough.Passthrough;
import net.minecraft.client.Minecraft;
import net.minecraft.client.MouseHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * The host's cursor moves Minecraft's screens (hover, drag) although Minecraft's window never has the focus. Only here:
 * grabbing the mouse still needs a focused window, so Minecraft never takes the real cursor from the host.
 */
@Mixin(MouseHandler.class)
abstract class MouseHandlerMixin {
	@Redirect(method = "handleAccumulatedMovement", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/Minecraft;isWindowActive()Z"))
	private boolean passthrough$hostFocus(final Minecraft minecraft) {
		return Passthrough.active || minecraft.isWindowActive();
	}
}
