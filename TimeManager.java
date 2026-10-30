package com.enzo.timeclock.time;

import com.enzo.timeclock.TimeClockMod;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.registry.Registries;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Experimental short-range timeline. It stores loaded entity NBT, changed block states,
 * loaded block-entity NBT, and day time. It is not a byte-for-byte rollback of the game engine.
 */
public final class TimeManager {
    private static final int MAX_FRAMES = 400; // about 20 seconds at 20 TPS
    private static final int FORWARD_STEPS_PER_TICK = 3;

    // Action bit 0 = F (rewind), bit 1 = G (forward).
    private static final Map<UUID, Integer> KEYS = new HashMap<>();
    private static final List<Frame> HISTORY = new ArrayList<>();
    private static final Map<BlockKey, BlockChangeBuilder> PENDING_BLOCK_CHANGES = new LinkedHashMap<>();
    private static final Map<BlockKey, BlockEntity> TRACKED_BLOCK_ENTITIES = new LinkedHashMap<>();

    private static MinecraftServer currentServer;
    private static int cursor = -1;
    private static boolean paused;
    private static boolean freezePlayerWhenPaused;
    private static boolean applyingHistory;
    private static Playback playback = Playback.NONE;

    private TimeManager() {
    }

    public static boolean isPaused() {
        return paused;
    }

    public static boolean isWorldFrozen() {
        return paused || playback != Playback.NONE;
    }

    /** Players can walk during a normal R-pause, but stay suspended after F/G playback. */
    public static boolean shouldFreezeEntity(Entity entity) {
        if (!isWorldFrozen()) return false;
        if (playback != Playback.NONE) return true;
        if (entity instanceof ServerPlayerEntity && !freezePlayerWhenPaused) return false;
        return true;
    }

    public static void control(ServerPlayerEntity player, int action, boolean down) {
        if (action < 0 || action > 3) return;

        // Controls only work while the custom clock is in the main hand.
        if (!TimeClockMod.isTimeClock(player.getMainHandStack())) {
            KEYS.remove(player.getUuid());
            return;
        }

        if (action == 2) {
            // R: pause the world while allowing the clock holder to move.
            if (down) {
                if (paused) {
                    paused = false;
                    freezePlayerWhenPaused = false;
                } else {
                    paused = true;
                    freezePlayerWhenPaused = false;
                }
            }
            return;
        }

        if (action == 3) {
            // Y: toggle a full freeze, including the player, when no rewind/forward is active.
            if (down) {
                if (paused && freezePlayerWhenPaused && playback == Playback.NONE) {
                    paused = false;
                    freezePlayerWhenPaused = false;
                } else {
                    paused = true;
                    freezePlayerWhenPaused = true;
                    playback = Playback.NONE;
                    KEYS.remove(player.getUuid());
                }
            }
            return;
        }

        int bit = 1 << action;
        int mask = KEYS.getOrDefault(player.getUuid(), 0);
        boolean wasDown = (mask & bit) != 0;
        int updated = down ? (mask | bit) : (mask & ~bit);
        KEYS.put(player.getUuid(), updated);

        // Releasing F or G leaves the timeline frozen at that exact historical frame.
        if (wasDown && !down && playback != Playback.NONE) {
            paused = true;
            freezePlayerWhenPaused = true;
        }
    }

    public static void onStartTick(MinecraftServer server) {
        ensureServer(server);

        // Create a true starting point before the first simulation tick is recorded.
        if (HISTORY.isEmpty()) {
            HISTORY.add(snapshotFrame(server, Collections.emptyList()));
            cursor = 0;
        }

        boolean rewind = false;
        boolean forward = false;
        for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
            UUID id = player.getUuid();
            if (!TimeClockMod.isTimeClock(player.getMainHandStack())) {
                KEYS.remove(id);
                continue;
            }
            int keys = KEYS.getOrDefault(id, 0);
            rewind |= (keys & 1) != 0;
            forward |= (keys & 2) != 0;
        }

        if (rewind) {
            paused = true;
            freezePlayerWhenPaused = true;
            playback = Playback.REWIND;
            if (cursor > 0) {
                Frame leaving = HISTORY.get(cursor);
                applyBlockChanges(server, leaving, false);
                cursor--;
                restoreFrame(server, HISTORY.get(cursor));
            } else if (cursor >= 0) {
                restoreFrame(server, HISTORY.get(cursor));
            }
            return;
        }

        if (forward && cursor >= 0 && cursor < HISTORY.size() - 1) {
            paused = true;
            freezePlayerWhenPaused = true;
            playback = Playback.FORWARD;
            for (int i = 0; i < FORWARD_STEPS_PER_TICK && cursor < HISTORY.size() - 1; i++) {
                Frame next = HISTORY.get(cursor + 1);
                applyBlockChanges(server, next, true);
                cursor++;
                restoreFrame(server, HISTORY.get(cursor));
            }
            return;
        }

        playback = Playback.NONE;
        if (paused) return;

        // Once the player resumes from the past, later frames form a new branch.
        if (cursor >= 0 && cursor < HISTORY.size() - 1) {
            while (HISTORY.size() > cursor + 1) {
                HISTORY.remove(HISTORY.size() - 1);
            }
        }
    }

    public static void onEndTick(MinecraftServer server) {
        ensureServer(server);
        if (playback != Playback.NONE) return;
        // In the special post-rewind freeze, nothing should advance or be recorded.
        if (paused && freezePlayerWhenPaused) return;

        List<BlockChange> changes = takePendingBlockChanges(server);
        HISTORY.add(snapshotFrame(server, changes));
        cursor = HISTORY.size() - 1;

        while (HISTORY.size() > MAX_FRAMES) {
            HISTORY.remove(0);
            cursor--;
        }
    }

    private static void ensureServer(MinecraftServer server) {
        if (currentServer == server) return;
        currentServer = server;
        HISTORY.clear();
        KEYS.clear();
        PENDING_BLOCK_CHANGES.clear();
        TRACKED_BLOCK_ENTITIES.clear();
        cursor = -1;
        paused = false;
        freezePlayerWhenPaused = false;
        applyingHistory = false;
        playback = Playback.NONE;
        // Keep the tracker map; each entry is validated against the current world's loaded BE.
    }

    public static void recordBlockChange(ServerWorld world, BlockPos pos, BlockState oldState, BlockState newState) {
        if (applyingHistory || oldState.equals(newState)) return;
        BlockKey key = new BlockKey(worldId(world), pos.toImmutable());
        PENDING_BLOCK_CHANGES.computeIfAbsent(key, ignored -> new BlockChangeBuilder(key, oldState));
    }

    public static void trackBlockEntity(ServerWorld world, BlockEntity blockEntity) {
        if (applyingHistory || blockEntity == null) return;
        BlockKey key = new BlockKey(worldId(world), blockEntity.getPos().toImmutable());
        TRACKED_BLOCK_ENTITIES.put(key, blockEntity);
    }

    public static void untrackBlockEntity(ServerWorld world, BlockPos pos) {
        if (applyingHistory) return;
        TRACKED_BLOCK_ENTITIES.remove(new BlockKey(worldId(world), pos.toImmutable()));
    }

    private static List<BlockChange> takePendingBlockChanges(MinecraftServer server) {
        List<BlockChange> result = new ArrayList<>();
        for (BlockChangeBuilder pending : PENDING_BLOCK_CHANGES.values()) {
            ServerWorld world = getWorld(server, pending.key.worldId);
            if (world == null || !isChunkLoaded(world, pending.key.pos)) continue;
            BlockState after = world.getBlockState(pending.key.pos);
            if (!pending.before.equals(after)) {
                result.add(new BlockChange(pending.key, pending.before, after));
            }
        }
        PENDING_BLOCK_CHANGES.clear();
        return result;
    }

    private static Frame snapshotFrame(MinecraftServer server, List<BlockChange> changes) {
        Map<UUID, EntitySnapshot> entities = new HashMap<>();
        Map<String, WorldClock> worldClocks = new HashMap<>();
        Map<BlockKey, NbtCompound> blockEntities = new HashMap<>();

        for (ServerWorld world : server.getWorlds()) {
            String worldId = worldId(world);
            worldClocks.put(worldId, new WorldClock(world.getTimeOfDay()));

            for (Entity entity : world.iterateEntities()) {
                NbtCompound nbt = entity.writeNbt(new NbtCompound());
                nbt.putString("id", Registries.ENTITY_TYPE.getId(entity.getType()).toString());
                Vec3d velocity = entity.getVelocity();
                entities.put(entity.getUuid(), new EntitySnapshot(
                        worldId, nbt.copy(), entity.getX(), entity.getY(), entity.getZ(),
                        entity.getYaw(), entity.getPitch(), velocity));
            }
        }

        // Block entity NBT captures inventories, signs, furnaces and similar tile data.
        // Only currently loaded block entities are copied; unloaded chunks are never forced open.
        for (Map.Entry<BlockKey, BlockEntity> entry : new ArrayList<>(TRACKED_BLOCK_ENTITIES.entrySet())) {
            BlockKey key = entry.getKey();
            ServerWorld world = getWorld(server, key.worldId);
            BlockEntity blockEntity = entry.getValue();
            if (world == null || blockEntity == null || blockEntity.isRemoved() || !isChunkLoaded(world, key.pos)) {
                continue;
            }
            if (world.getBlockEntity(key.pos) != blockEntity) continue;
            try {
                blockEntities.put(key, blockEntity.createNbtWithIdentifyingData().copy());
            } catch (RuntimeException ignored) {
                // A modded block entity that cannot serialize is omitted from this frame.
            }
        }

        return new Frame(entities, new ArrayList<>(changes), blockEntities, worldClocks);
    }

    private static void applyBlockChanges(MinecraftServer server, Frame frame, boolean forward) {
        boolean wasApplying = applyingHistory;
        applyingHistory = true;
        try {
            List<BlockChange> changes = frame.blockChanges;
            if (forward) {
                for (BlockChange change : changes) applyBlockChange(server, change, true);
            } else {
                for (int i = changes.size() - 1; i >= 0; i--) applyBlockChange(server, changes.get(i), false);
            }
        } finally {
            applyingHistory = wasApplying;
        }
    }

    private static void applyBlockChange(MinecraftServer server, BlockChange change, boolean forward) {
        ServerWorld world = getWorld(server, change.key.worldId);
        if (world == null || !isChunkLoaded(world, change.key.pos)) return;
        BlockState target = forward ? change.after : change.before;
        world.setBlockState(change.key.pos, target, Block.NOTIFY_ALL | Block.FORCE_STATE);
    }

    private static void restoreFrame(MinecraftServer server, Frame frame) {
        boolean wasApplying = applyingHistory;
        applyingHistory = true;
        try {
            for (Map.Entry<String, WorldClock> entry : frame.worldClocks.entrySet()) {
                ServerWorld world = getWorld(server, entry.getKey());
                if (world != null) world.setTimeOfDay(entry.getValue().timeOfDay);
            }

            restoreBlockEntities(server, frame);
            restoreEntities(server, frame);
        } finally {
            applyingHistory = wasApplying;
        }
    }

    private static void restoreBlockEntities(MinecraftServer server, Frame frame) {
        for (Map.Entry<BlockKey, NbtCompound> entry : frame.blockEntities.entrySet()) {
            BlockKey key = entry.getKey();
            ServerWorld world = getWorld(server, key.worldId);
            if (world == null || !isChunkLoaded(world, key.pos)) continue;
            BlockEntity blockEntity = world.getBlockEntity(key.pos);
            if (blockEntity == null) continue;
            try {
                blockEntity.readNbt(entry.getValue().copy());
                blockEntity.markDirty();
                TRACKED_BLOCK_ENTITIES.put(key, blockEntity);
                world.updateListeners(key.pos, world.getBlockState(key.pos), world.getBlockState(key.pos), Block.NOTIFY_ALL);
            } catch (RuntimeException ignored) {
                // If a modded block entity has incompatible NBT, keep the world running.
            }
        }
    }

    private static void restoreEntities(MinecraftServer server, Frame frame) {
        Map<UUID, Entity> current = new HashMap<>();
        for (ServerWorld world : server.getWorlds()) {
            for (Entity entity : world.iterateEntities()) current.put(entity.getUuid(), entity);
        }

        // Remove entities created after the selected frame. Players are never discarded here.
        for (Map.Entry<UUID, Entity> entry : current.entrySet()) {
            if (!frame.entities.containsKey(entry.getKey()) && !(entry.getValue() instanceof ServerPlayerEntity)) {
                entry.getValue().discard();
            }
        }

        // Restore entities that carry passengers first. Recreating a vehicle also recreates
        // its passenger tree, so index that tree before processing the passengers' own snapshots.
        List<Map.Entry<UUID, EntitySnapshot>> snapshots = new ArrayList<>(frame.entities.entrySet());
        snapshots.sort((left, right) -> Boolean.compare(
                right.getValue().nbt.contains("Passengers"), left.getValue().nbt.contains("Passengers")));

        for (Map.Entry<UUID, EntitySnapshot> entry : snapshots) {
            UUID uuid = entry.getKey();
            EntitySnapshot snapshot = entry.getValue();
            ServerWorld targetWorld = getWorld(server, snapshot.worldId);
            if (targetWorld == null) continue;

            Entity existing = current.get(uuid);
            if (existing instanceof ServerPlayerEntity player) {
                // A connected player is restored only when the snapshot is in that same dimension.
                if (worldId((ServerWorld) player.getWorld()).equals(snapshot.worldId)) {
                    restorePlayer(player, snapshot);
                }
                continue;
            }

            boolean wrongWorld = existing != null && !worldId((ServerWorld) existing.getWorld()).equals(snapshot.worldId);
            boolean wrongType = existing != null && !Registries.ENTITY_TYPE.getId(existing.getType()).toString()
                    .equals(snapshot.nbt.getString("id"));

            if (existing == null || wrongWorld || wrongType) {
                if (existing != null) existing.discard();
                if (!isChunkLoaded(targetWorld, snapshot.x, snapshot.z)) continue;
                try {
                    Entity recreated = EntityType.loadEntityWithPassengers(snapshot.nbt.copy(), targetWorld, entity -> entity);
                    if (recreated != null) {
                        targetWorld.spawnNewEntityAndPassengers(recreated);
                        indexEntityTree(current, recreated);
                    }
                } catch (RuntimeException ignored) {
                    // Some modded entities do not support being reconstructed from a saved NBT frame.
                }
                continue;
            }

            try {
                existing.readNbt(snapshot.nbt.copy());
                existing.refreshPositionAndAngles(snapshot.x, snapshot.y, snapshot.z, snapshot.yaw, snapshot.pitch);
                existing.setVelocity(snapshot.velocity);
            } catch (RuntimeException ignored) {
                // Do not crash a world if a third-party entity rejects historical NBT.
            }
        }
    }


    private static void indexEntityTree(Map<UUID, Entity> entities, Entity entity) {
        entities.put(entity.getUuid(), entity);
        for (Entity passenger : entity.getPassengers()) {
            indexEntityTree(entities, passenger);
        }
    }

    private static void restorePlayer(ServerPlayerEntity player, EntitySnapshot snapshot) {
        int oldSelectedSlot = player.getInventory().selectedSlot;
        ItemStack clockInHand = TimeClockMod.isTimeClock(player.getMainHandStack())
                ? player.getMainHandStack().copy() : ItemStack.EMPTY;
        try {
            player.readNbt(snapshot.nbt.copy());
            if (!clockInHand.isEmpty()) {
                player.getInventory().selectedSlot = oldSelectedSlot;
                player.getInventory().setStack(oldSelectedSlot, clockInHand);
            }
            player.refreshPositionAndAngles(snapshot.x, snapshot.y, snapshot.z, snapshot.yaw, snapshot.pitch);
            player.setVelocity(snapshot.velocity);
            player.networkHandler.requestTeleport(snapshot.x, snapshot.y, snapshot.z, snapshot.yaw, snapshot.pitch);
        } catch (RuntimeException ignored) {
            // Position still gets synced by the normal server tick if a custom player extension rejects NBT.
            player.networkHandler.requestTeleport(snapshot.x, snapshot.y, snapshot.z, snapshot.yaw, snapshot.pitch);
        }
    }

    private static ServerWorld getWorld(MinecraftServer server, String worldId) {
        try {
            RegistryKey<World> key = RegistryKey.of(RegistryKeys.WORLD, new Identifier(worldId));
            return server.getWorld(key);
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private static String worldId(ServerWorld world) {
        return world.getRegistryKey().getValue().toString();
    }

    private static boolean isChunkLoaded(ServerWorld world, BlockPos pos) {
        return isChunkLoaded(world, pos.getX(), pos.getZ());
    }

    private static boolean isChunkLoaded(ServerWorld world, double x, double z) {
        return world.isChunkLoaded(((int) Math.floor(x)) >> 4, ((int) Math.floor(z)) >> 4);
    }

    private static final class Frame {
        private final Map<UUID, EntitySnapshot> entities;
        private final List<BlockChange> blockChanges;
        private final Map<BlockKey, NbtCompound> blockEntities;
        private final Map<String, WorldClock> worldClocks;

        private Frame(Map<UUID, EntitySnapshot> entities, List<BlockChange> blockChanges,
                      Map<BlockKey, NbtCompound> blockEntities, Map<String, WorldClock> worldClocks) {
            this.entities = entities;
            this.blockChanges = blockChanges;
            this.blockEntities = blockEntities;
            this.worldClocks = worldClocks;
        }
    }

    private static final class EntitySnapshot {
        private final String worldId;
        private final NbtCompound nbt;
        private final double x, y, z;
        private final float yaw, pitch;
        private final Vec3d velocity;

        private EntitySnapshot(String worldId, NbtCompound nbt, double x, double y, double z,
                               float yaw, float pitch, Vec3d velocity) {
            this.worldId = worldId;
            this.nbt = nbt;
            this.x = x;
            this.y = y;
            this.z = z;
            this.yaw = yaw;
            this.pitch = pitch;
            this.velocity = velocity;
        }
    }

    private static final class BlockChangeBuilder {
        private final BlockKey key;
        private final BlockState before;

        private BlockChangeBuilder(BlockKey key, BlockState before) {
            this.key = key;
            this.before = before;
        }
    }

    private static final class BlockChange {
        private final BlockKey key;
        private final BlockState before;
        private final BlockState after;

        private BlockChange(BlockKey key, BlockState before, BlockState after) {
            this.key = key;
            this.before = before;
            this.after = after;
        }
    }

    private record BlockKey(String worldId, BlockPos pos) {
        private BlockKey {
            pos = pos.toImmutable();
        }
    }

    private record WorldClock(long timeOfDay) {
    }

    private enum Playback {
        NONE,
        REWIND,
        FORWARD
    }
}
