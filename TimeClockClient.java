package com.enzo.timeclock.client;

import com.enzo.timeclock.TimeClockMod;
import com.enzo.timeclock.mixin.KeyBindingAccessor;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.PacketByteBufs;
import net.minecraft.util.Identifier;
import org.lwjgl.glfw.GLFW;

public final class TimeClockClient implements ClientModInitializer {
    private static KeyBinding rewindKey;
    private static KeyBinding forwardKey;
    private static KeyBinding pauseKey;
    private static KeyBinding freezeAllKey;

    private static boolean rewindSent;
    private static boolean forwardSent;
    private static boolean swapKeySuppressed;
    private static InputUtil.Key previousSwapKey;

    @Override
    public void onInitializeClient() {
        rewindKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.timeclock.rewind", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_F, "key.categories.timeclock"));
        forwardKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.timeclock.forward", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_G, "key.categories.timeclock"));
        pauseKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.timeclock.pause", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_R, "key.categories.timeclock"));
        freezeAllKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.timeclock.freeze_all", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_Y, "key.categories.timeclock"));

        ClientTickEvents.END_CLIENT_TICK.register(TimeClockClient::tick);
    }

    private static void tick(MinecraftClient client) {
        boolean hasClock = client.player != null && TimeClockMod.isTimeClock(client.player.getMainHandStack());
        updateSwapKey(client, hasClock);

        if (client.player == null || client.getNetworkHandler() == null || client.world == null) {
            rewindSent = false;
            forwardSent = false;
            return;
        }

        if (!hasClock) {
            if (rewindSent) sendControl(0, false);
            if (forwardSent) sendControl(1, false);
            rewindSent = false;
            forwardSent = false;
            return;
        }

        boolean rewindNow = rewindKey.isPressed();
        if (rewindNow != rewindSent) {
            sendControl(0, rewindNow);
            rewindSent = rewindNow;
        }

        boolean forwardNow = forwardKey.isPressed();
        if (forwardNow != forwardSent) {
            sendControl(1, forwardNow);
            forwardSent = forwardNow;
        }

        while (pauseKey.wasPressed()) {
            sendControl(2, true);
        }
        while (freezeAllKey.wasPressed()) {
            sendControl(3, true);
        }
    }

    private static void updateSwapKey(MinecraftClient client, boolean hasClock) {
        if (client.options == null) return;

        if (hasClock && !swapKeySuppressed) {
            previousSwapKey = ((KeyBindingAccessor) (Object) client.options.swapHandsKey).timeclock$getBoundKey();
            client.options.swapHandsKey.setBoundKey(InputUtil.UNKNOWN_KEY);
            KeyBinding.updateKeysByCode();
            swapKeySuppressed = true;
        } else if (!hasClock && swapKeySuppressed) {
            if (previousSwapKey != null) {
                client.options.swapHandsKey.setBoundKey(previousSwapKey);
                KeyBinding.updateKeysByCode();
            }
            swapKeySuppressed = false;
            previousSwapKey = null;
        }
    }

    private static void sendControl(int action, boolean pressed) {
        PacketByteBuf buf = PacketByteBufs.create();
        buf.writeByte(action);
        buf.writeBoolean(pressed);
        ClientPlayNetworking.send(TimeClockMod.CONTROL, buf);
    }
}
