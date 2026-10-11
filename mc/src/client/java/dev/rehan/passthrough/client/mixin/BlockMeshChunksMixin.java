package dev.rehan.passthrough.client.mixin;

import dev.rehan.passthrough.client.BlockMeshClient;
import net.minecraft.client.renderer.chunk.ChunkSectionsToRender;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * While the host draws the acknowledged native mesh, vanilla's chunk groups leave the exported world picture (only them:
 * entities, particles and the rest stay). Adapted from EldenCraft (MIT, Copyright (c) 2026 Adam Grzegorzewski).
 */
@Mixin(ChunkSectionsToRender.class)
abstract class BlockMeshChunksMixin {
	@Inject(method = {"renderGroup", "renderOit"}, at = @At("HEAD"), cancellable = true)
	private void passthrough$nativeBlocks(final CallbackInfo ci) {
		if (BlockMeshClient.suppressChunks()) {
			ci.cancel();
		}
	}
}