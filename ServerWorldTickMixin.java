package com.enzo.timeclock.mixin;

import com.enzo.timeclock.time.TimeManager;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.world.chunk.WorldChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ServerWorld.class)
public abstract class ServerWorldTickMixin {
    @Inject(method = "tickChunk", at = @At("HEAD"), cancellable = true)
    private void timeclock$freezeRandomChunkTicks(WorldChunk chunk, int randomTickSpeed, CallbackInfo ci) {
        if (TimeManager.isWorldFrozen()) {
            ci.cancel();
        }
    }

    @Inject(method = "tickSpawners", at = @At("HEAD"), cancellable = true)
    private void timeclock$freezeSpawners(boolean spawnMonsters, boolean spawnAnimals, CallbackInfo ci) {
        if (TimeManager.isWorldFrozen()) {
            ci.cancel();
        }
    }
}
