package dev.rehan.passthrough.client.mixin;

import dev.rehan.passthrough.client.BlockMeshClient;
import net.minecraft.client.multiplayer.ClientChunkCache;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.LightLayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Propagated light changes (they bypass setBlocksDirty in 26.3) refresh the mesh's vertex light. Adapted from EldenCraft (MIT). */
@Mixin(ClientChunkCache.class)
abstract class BlockMeshLightMixin {
	@Inject(method = "onLightUpdate", at = @At("HEAD"))
	private void passthrough$propagatedLight(final LightLayer layer, final SectionPos section, final CallbackInfo ci) {
		BlockMeshClient.lightDirty(section);
	}
}