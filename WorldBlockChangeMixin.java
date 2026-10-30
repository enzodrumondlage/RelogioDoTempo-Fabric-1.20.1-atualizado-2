package com.enzo.timeclock.mixin;

import com.enzo.timeclock.time.TimeManager;
import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(World.class)
public abstract class WorldBlockChangeMixin {
    @Inject(method = "onBlockChanged", at = @At("HEAD"))
    private void timeclock$rememberBlock(BlockPos pos, BlockState oldState, BlockState newState, CallbackInfo ci) {
        if ((Object) this instanceof ServerWorld world) {
            TimeManager.recordBlockChange(world, pos, oldState, newState);
        }
    }

    @Inject(method = "addBlockEntity", at = @At("TAIL"))
    private void timeclock$trackBlockEntity(BlockEntity blockEntity, CallbackInfo ci) {
        if ((Object) this instanceof ServerWorld world) {
            TimeManager.trackBlockEntity(world, blockEntity);
        }
    }

    @Inject(method = "removeBlockEntity", at = @At("HEAD"))
    private void timeclock$untrackBlockEntity(BlockPos pos, CallbackInfo ci) {
        if ((Object) this instanceof ServerWorld world) {
            TimeManager.untrackBlockEntity(world, pos);
        }
    }
}
