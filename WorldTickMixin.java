package com.enzo.timeclock.mixin;

import com.enzo.timeclock.time.TimeManager;
import net.minecraft.entity.Entity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.world.World;
import java.util.function.Consumer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(World.class)
public abstract class WorldTickMixin {
    @Inject(method = "tickEntity", at = @At("HEAD"), cancellable = true)
    private <T extends Entity> void timeclock$freezeEntities(Consumer<T> tickConsumer, T entity, CallbackInfo ci) {
        if ((Object) this instanceof ServerWorld && TimeManager.shouldFreezeEntity(entity)) {
            ci.cancel();
        }
    }
}
