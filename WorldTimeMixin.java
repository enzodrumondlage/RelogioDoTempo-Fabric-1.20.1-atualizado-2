package com.enzo.timeclock.mixin;

import com.enzo.timeclock.time.TimeManager;
import net.minecraft.server.world.ServerWorld;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ServerWorld.class)
public abstract class WorldTimeMixin {
    @Inject(method = "tickTime", at = @At("HEAD"), cancellable = true)
    private void timeclock$pauseWorldClock(CallbackInfo ci) {
        if (TimeManager.isWorldFrozen()) {
            ci.cancel();
        }
    }
}
