package com.enzo.timeclock.mixin;

import com.enzo.timeclock.time.TimeManager;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(World.class)
public abstract class WorldBlockEntityTickMixin {
    @Inject(method = "tickBlockEntities", at = @At("HEAD"), cancellable = true)
    private void timeclock$freezeBlockEntities(CallbackInfo ci) {
        if ((Object) this instanceof ServerWorld && TimeManager.isWorldFrozen()) {
            ci.cancel();
        }
    }
}
