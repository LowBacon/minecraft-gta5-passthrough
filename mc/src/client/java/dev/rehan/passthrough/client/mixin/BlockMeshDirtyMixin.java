package dev.rehan.passthrough.client.mixin;

import dev.rehan.passthrough.client.BlockMeshClient;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Block edits, chunk streaming and tint changes mark the native mesh's sections dirty. Adapted from EldenCraft (MIT). */
@Mixin(ClientLevel.class)
abstract class BlockMeshDirtyMixin {
	@Inject(method = "setBlocksDirty", at = @At("HEAD"))
	private void passthrough$stateChanged(final BlockPos pos, final BlockState oldState, final BlockState newState, final CallbackInfo ci) {
		BlockMeshClient.dirty(pos, oldState, newState);
	}

	@Inject(method = "onChunkLoaded", at = @At("TAIL"))
	private void passthrough$chunkLoaded(final ChunkPos pos, final CallbackInfo ci) {
		BlockMeshClient.chunkChanged(pos, ((ClientLevel)(Object)this).getChunkSource().getChunk(pos.x(), pos.z(), ChunkStatus.FULL, false));
	}

	@Inject(method = "unload", at = @At("TAIL"))
	private void passthrough$chunkUnloaded(final LevelChunk chunk, final CallbackInfo ci) {
		BlockMeshClient.chunkChanged(chunk.getPos(), chunk);
	}

	@Inject(method = "clearTintCaches", at = @At("HEAD"))
	private void passthrough$tintChanged(final CallbackInfo ci) {
		BlockMeshClient.tintDirty();
	}
}