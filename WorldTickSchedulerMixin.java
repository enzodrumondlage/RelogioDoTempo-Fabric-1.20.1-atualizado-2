package com.enzo.timeclock.mixin;

import com.enzo.timeclock.time.TimeManager;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.tick.WorldTickScheduler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.function.BiConsumer;

/** Keeps scheduled block/fluid ticks from progressing while the timeline is frozen. */
@Mixin(WorldTickScheduler.class)
public abstract class WorldTickSchedulerMixin {
    @Inject(method = "tick(JILjava/util/function/BiConsumer;)V", at = @At("HEAD"), cancellable = true)
    private void timeclock$freezeScheduledTicks(long time, int maxTicks, BiConsumer<BlockPos, Object> ticker, CallbackInfo ci) {
        if (TimeManager.isWorldFrozen()) {
            ci.cancel();
        }
    }
}
