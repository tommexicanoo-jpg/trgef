package com.theecho;

import com.theecho.entity.EchoEntity;
import com.theecho.net.DreadPayload;
import net.fabricmc.fabric.api.entity.event.v1.EntitySleepEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.block.*;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.SignBlockEntity;
import net.minecraft.block.enums.DoubleBlockHalf;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.entity.passive.AnimalEntity;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.network.packet.s2c.play.PlaySoundS2CPacket;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.Registries;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvent;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Formatting;
import net.minecraft.util.Hand;
import net.minecraft.util.Identifier;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.Heightmap;
import net.minecraft.world.World;
import net.minecraft.world.chunk.WorldChunk;
import org.jetbrains.annotations.Nullable;

import java.util.*;

public final class EchoManager {

    public static final RegistryKey<World> REALM =
            RegistryKey.of(RegistryKeys.WORLD, TheEcho.id("mirror_realm"));
    public static final String STARE_TAG = "the_echo_stare";

    private static final Random RNG = new Random();

    private static final Map<UUID, EchoEntity> ACTIVE = new HashMap<>();
    private static final Map<UUID, Boolean> ACTIVE_FLAG = new HashMap<>();
    private static final Map<UUID, ArrayDeque<Rec>> RECORDINGS = new HashMap<>();
    private static final Map<UUID, Flicker> FLICKERS = new HashMap<>();
    private static final Map<UUID, Integer> REALM_TICKS = new HashMap<>();
    private static final Map<UUID, BlockPos> PENDING_HIJACK = new HashMap<>();
    private static final List<Delayed> DELAYED = new ArrayList<>();
    private static final Set<MobEntity> FROZEN = new HashSet<>();
    private static final Map<UUID, List<BlockPos>> TRAPS = new HashMap<>();
    private static final Map<UUID, List<BlockPos>> REALM_CELLS = new HashMap<>();

    private record Rec(long time, SoundEvent sound) {}
    private record Delayed(int tick, Runnable action) {}
    private record Flicker(int until, List<BlockPos> torches) {}
    private record ChopSpot(BlockPos log, BlockPos stand) {}

    private EchoManager() {}

    // ------------------------------------------------------------------ registro

    public static void register() {
        ServerTickEvents.END_SERVER_TICK.register(EchoManager::tick);
        PlayerBlockBreakEvents.AFTER.register(EchoManager::onBlockBroken);
        ServerLivingEntityEvents.ALLOW_DAMAGE.register((entity, source, amount) -> {
            if (entity instanceof ServerPlayerEntity p) record(p, SoundEvents.ENTITY_PLAYER_HURT);
            return true;
        });
        EntitySleepEvents.STOP_SLEEPING.register(EchoManager::onWake);
        UseEntityCallback.EVENT.register(EchoManager::onUseEntity);
        UseBlockCallback.EVENT.register((player, world, hand, hit) -> {
            if (!world.isClient && player instanceof ServerPlayerEntity sp && hand == Hand.MAIN_HAND) {
                BlockState bs = world.getBlockState(hit.getBlockPos());
                if (bs.isOf(Blocks.CHEST) || bs.isOf(Blocks.TRAPPED_CHEST) || bs.isOf(Blocks.ENDER_CHEST)) {
                    record(sp, SoundEvents.BLOCK_CHEST_OPEN);
                } else if (bs.isOf(Blocks.BARREL)) {
                    record(sp, SoundEvents.BLOCK_BARREL_OPEN);
                } else if (bs.isIn(BlockTags.WOODEN_DOORS)) {
                    record(sp, SoundEvents.BLOCK_WOODEN_DOOR_OPEN);
                }
            }
            return ActionResult.PASS;
        });
        ServerEntityEvents.ENTITY_LOAD.register((entity, world) -> {
            if (entity instanceof MobEntity m && m.getCommandTags().contains(STARE_TAG)) release(m);
        });
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            UUID id = handler.player.getUuid();
            dismissEcho(id);
            ACTIVE_FLAG.remove(id);
            FLICKERS.remove(id);
            REALM_TICKS.remove(id);
            PENDING_HIJACK.remove(id);
            TRAPS.remove(id);
            REALM_CELLS.remove(id);
        });
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
            ACTIVE.clear(); FROZEN.clear(); ACTIVE_FLAG.clear(); RECORDINGS.clear(); FLICKERS.clear();
            REALM_TICKS.clear(); PENDING_HIJACK.clear(); DELAYED.clear(); TRAPS.clear(); REALM_CELLS.clear();
        });
    }

    // ------------------------------------------------------------------ tick principal

    private static void tick(MinecraftServer server) {
        int t = server.getTicks();
        if (!DELAYED.isEmpty()) {
            DELAYED.removeIf(d -> {
                if (d.tick() <= t) {
                    try { d.action().run(); } catch (Exception e) { TheEcho.LOGGER.warn("Tarea retrasada falló", e); }
                    return true;
                }
                return false;
            });
        }
        if (t % 100 == 0 && !FROZEN.isEmpty()) sweepFrozen(server);
        EchoState state = EchoState.get(server);
        for (ServerPlayerEntity p : new ArrayList<>(server.getPlayerManager().getPlayerList())) {
            BlockPos hij = PENDING_HIJACK.remove(p.getUuid());
            if (hij != null) hijack(server, state, p, hij);
            tickPlayer(server, state, p, t);
        }
    }

    /** Libera mobs congelados cuyo jugador ya no está cerca (cambió de dimensión, se desconectó...). */
    private static void sweepFrozen(MinecraftServer server) {
        FROZEN.removeIf(m -> {
            boolean near = false;
            if (!m.isRemoved() && m.isAlive()) {
                for (ServerPlayerEntity pl : server.getPlayerManager().getPlayerList()) {
                    if (pl.getWorld() == m.getWorld() && pl.squaredDistanceTo(m) < 56 * 56) { near = true; break; }
                }
            }
            if (!near) {
                m.removeCommandTag(STARE_TAG);
                m.setAiDisabled(false);
                return true;
            }
            return false;
        });
    }

    private static void schedule(int tick, Runnable r) {
        DELAYED.add(new Delayed(tick, r));
    }

    private static void tickPlayer(MinecraftServer server, EchoState state, ServerPlayerEntity p, int t) {
        EchoConfig cfg = EchoConfig.INSTANCE;
        EchoState.PlayerData d = state.data(p.getUuid());
        ServerWorld w = p.getServerWorld();
        ServerWorld ow = server.getOverworld();
        long gameTime = ow.getTime();
        long day = ow.getTimeOfDay() / 24000L;
        boolean inRealm = w.getRegistryKey().equals(REALM);

        // Salió del reino por otra vía (muerte, comando...)
        if (d.inRealm && !inRealm) {
            d.inRealm = false;
            d.dread = Math.min(d.dread, 30);
            d.banishedUntil = gameTime + 24000L;
            REALM_TICKS.remove(p.getUuid());
            state.markDirty();
        }
        if (inRealm) {
            if (d.inRealm) tickRealm(p, w, d);
            return;
        }
        if (p.isSpectator()) return;

        // Foto del inventario del primer día (para las ofrendas)
        if (!d.snapshotLocked && t % 100 == 0) {
            snapshotInventory(p, d);
            if (day >= 1) d.snapshotLocked = true;
            state.markDirty();
        }

        int phase = (t + p.getId()) % 20;
        if (phase == 0) {
            boolean active = isActive(server, w, p.getBlockPos(), d);
            ACTIVE_FLAG.put(p.getUuid(), active);
            sendDread(p, active ? (int) Math.min(100, d.dread) : 0);
            if (!active) {
                dismissEcho(p.getUuid());
            } else {
                secondTick(server, state, p, w, d, t, day);
            }
        }

        boolean active = ACTIVE_FLAG.getOrDefault(p.getUuid(), false);
        if (t % 2 == 0) tickStares(p, w, d, active && day >= cfg.villagerDay);

        Flicker f = FLICKERS.get(p.getUuid());
        if (f != null) {
            if (t > f.until()) {
                FLICKERS.remove(p.getUuid());
            } else if (t % 3 == 0) {
                for (BlockPos pos : f.torches()) {
                    w.spawnParticles(ParticleTypes.SOUL_FIRE_FLAME, pos.getX() + 0.5, pos.getY() + 0.7, pos.getZ() + 0.5,
                            2, 0.04, 0.04, 0.04, 0.0);
                }
            }
        }
    }

    private static void secondTick(MinecraftServer server, EchoState state, ServerPlayerEntity p, ServerWorld w,
                                   EchoState.PlayerData d, int t, long day) {
        EchoConfig cfg = EchoConfig.INSTANCE;
        long sec = t / 20;

        if (sec % 30 == 0) {
            d.dread += cfg.dreadPerMinute / 2.0;
            state.markDirty();
        }
        if (sec % 5 == 0) trySighting(p, w, d);
        if (w.getLightLevel(p.getBlockPos()) < 5) { d.dread += 0.08; state.markDirty(); }
        if (RNG.nextDouble() < Math.min(0.08, 0.015 + d.dread * 0.0007)) spawnGlimpse(p, w);
        if (d.dread > 70 && RNG.nextDouble() < 0.02) {
            p.addStatusEffect(new StatusEffectInstance(StatusEffects.DARKNESS, 100, 0, false, false, false));
        }
        if (day >= cfg.audioDay) paranoia(p, w, d, t);
        if (day >= cfg.interfaceDay) {
            if (RNG.nextDouble() < Math.min(0.04, 0.01 + d.dread * 0.0003)) startFlicker(p, w, t);
            if (sec % 10 == 0 && RNG.nextDouble() < Math.min(0.5, 0.15 + d.dread * 0.004)) mutateNearbySign(p, w);
        }
    }

    private static boolean isActive(MinecraftServer server, ServerWorld w, BlockPos pos, EchoState.PlayerData d) {
        if (!w.getRegistryKey().equals(World.OVERWORLD)) return false;
        ServerWorld ow = server.getOverworld();
        long day = ow.getTimeOfDay() / 24000L;
        return day >= EchoConfig.INSTANCE.startDay && ow.getTime() >= d.banishedUntil && !isProtected(w, pos);
    }

    private static void snapshotInventory(ServerPlayerEntity p, EchoState.PlayerData d) {
        for (int i = 0; i < p.getInventory().size(); i++) {
            ItemStack s = p.getInventory().getStack(i);
            if (!s.isEmpty()) d.day1Items.add(Registries.ITEM.getId(s.getItem()).toString());
        }
    }

    // ------------------------------------------------------------------ 1. clon silencioso

    private static boolean hasActive(UUID id) {
        EchoEntity e = ACTIVE.get(id);
        return e != null && e.isAlive() && !e.isRemoved();
    }

    public static void dismissEcho(UUID id) {
        EchoEntity e = ACTIVE.remove(id);
        if (e != null && e.isAlive()) e.vanish();
    }

    private static void trySighting(ServerPlayerEntity p, ServerWorld w, EchoState.PlayerData d) {
        double chance = Math.min(0.2, 0.06 + d.dread * 0.001);
        if (w.isRaining()) chance *= 1.6;
        if (w.getTimeOfDay() % 24000L > 13000L) chance *= 1.4;
        if (RNG.nextDouble() > chance) return;
        spawnSighting(p, w, d, 26, 40);
    }

    public static boolean spawnSighting(ServerPlayerEntity p, ServerWorld w, EchoState.PlayerData d, int min, int max) {
        if (hasActive(p.getUuid())) return false;
        BlockPos spot = findSurfaceSpot(w, p, min, max);
        if (spot == null) return false;
        double walkChance = Math.min(0.8, 0.25 + d.dread * 0.004);
        EchoEntity.Mode mode = RNG.nextDouble() < walkChance ? EchoEntity.Mode.WALK : EchoEntity.Mode.WATCH;
        return spawnEcho(w, p, spot, mode, true) != null;
    }

    private static BlockPos findSurfaceSpot(ServerWorld w, ServerPlayerEntity p, int min, int max) {
        for (int i = 0; i < 12; i++) {
            double ang = RNG.nextDouble() * Math.PI * 2;
            double dist = min + RNG.nextDouble() * (max - min);
            int x = (int) Math.floor(p.getX() + Math.cos(ang) * dist);
            int z = (int) Math.floor(p.getZ() + Math.sin(ang) * dist);
            if (!w.isChunkLoaded(x >> 4, z >> 4)) continue;
            int y = w.getTopY(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, x, z);
            if (Math.abs(y - p.getY()) > 12) continue;
            BlockPos pos = new BlockPos(x, y, z);
            if (!w.getBlockState(pos.down()).isSolidBlock(w, pos.down())) continue;
            if (!w.getBlockState(pos).isAir() || !w.getBlockState(pos.up()).isAir()) continue;
            return pos;
        }
        return null;
    }

    private static EchoEntity spawnEcho(ServerWorld w, ServerPlayerEntity p, BlockPos pos, EchoEntity.Mode mode, boolean track) {
        EchoEntity e = ModEntities.ECHO.create(w);
        if (e == null) return null;
        e.refreshPositionAndAngles(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5, RNG.nextFloat() * 360f, 0f);
        e.setOwner(p);
        e.copyAppearance(p);
        e.setMode(mode);
        w.spawnEntity(e);
        if (track) ACTIVE.put(p.getUuid(), e);
        return e;
    }

    public static boolean debugSpawn(ServerPlayerEntity p) {
        ServerWorld w = p.getServerWorld();
        dismissEcho(p.getUuid());
        EchoState.PlayerData d = EchoState.get(w.getServer()).data(p.getUuid());
        return spawnSighting(p, w, d, 14, 22);
    }

    // ------------------------------------------------------------------ imitación

    private static void onBlockBroken(World world, PlayerEntity player, BlockPos pos, BlockState state, @Nullable BlockEntity be) {
        if (!(world instanceof ServerWorld w) || !(player instanceof ServerPlayerEntity sp)) return;
        record(sp, state.getSoundGroup().getBreakSound());
        if (!state.isIn(BlockTags.LOGS)) return;

        MinecraftServer server = w.getServer();
        EchoState st = EchoState.get(server);
        EchoState.PlayerData d = st.data(sp.getUuid());
        long day = server.getOverworld().getTimeOfDay() / 24000L;
        if (day < EchoConfig.INSTANCE.mimicDay) return;
        if (!isActive(server, w, sp.getBlockPos(), d) || hasActive(sp.getUuid())) return;
        if (RNG.nextDouble() > Math.min(0.8, 0.3 + d.dread * 0.004)) return;

        ChopSpot spot = findChopSpot(w, sp, 22, 38);
        if (spot == null) return;
        EchoEntity e = spawnEcho(w, sp, spot.stand(), EchoEntity.Mode.CHOP, true);
        if (e != null) e.setChopTarget(spot.log());
    }

    private static ChopSpot findChopSpot(ServerWorld w, ServerPlayerEntity p, int min, int max) {
        for (int i = 0; i < 40; i++) {
            double ang = RNG.nextDouble() * Math.PI * 2;
            double dist = min + RNG.nextDouble() * (max - min);
            int x = (int) Math.floor(p.getX() + Math.cos(ang) * dist);
            int z = (int) Math.floor(p.getZ() + Math.sin(ang) * dist);
            if (!w.isChunkLoaded(x >> 4, z >> 4)) continue;
            int top = w.getTopY(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, x, z);
            BlockPos.Mutable m = new BlockPos.Mutable(x, top - 1, z);
            if (!w.getBlockState(m).isIn(BlockTags.LOGS)) continue;
            while (w.getBlockState(m.down()).isIn(BlockTags.LOGS)) m.move(Direction.DOWN);
            BlockPos log = m.toImmutable();
            for (Direction dir : Direction.Type.HORIZONTAL) {
                BlockPos s = log.offset(dir);
                if (w.getBlockState(s).isAir() && w.getBlockState(s.up()).isAir()
                        && w.getBlockState(s.down()).isSolidBlock(w, s.down())) {
                    return new ChopSpot(log, s);
                }
            }
        }
        return null;
    }

    private static void onWake(LivingEntity entity, BlockPos sleepingPos) {
        if (!(entity instanceof ServerPlayerEntity p)) return;
        ServerWorld w = p.getServerWorld();
        if (!w.getRegistryKey().equals(World.OVERWORLD)) return;
        if (w.getTimeOfDay() % 24000L > 2000L) return; // no es una mañana real

        MinecraftServer server = w.getServer();
        EchoConfig cfg = EchoConfig.INSTANCE;
        EchoState st = EchoState.get(server);
        EchoState.PlayerData d = st.data(p.getUuid());
        long day = server.getOverworld().getTimeOfDay() / 24000L;
        if (!isActive(server, w, sleepingPos, d)) return;

        if (cfg.enableRealm && day >= cfg.hijackDay && d.dread >= cfg.hijackDread && !d.inRealm) {
            PENDING_HIJACK.put(p.getUuid(), sleepingPos.toImmutable());
            return;
        }
        if (day >= cfg.mimicDay && RNG.nextDouble() < 0.7) leaveTraces(w, sleepingPos);
    }

    private static void leaveTraces(ServerWorld w, BlockPos bed) {
        double ang = RNG.nextDouble() * Math.PI * 2;
        double cos = Math.cos(ang), sin = Math.sin(ang);
        double px = -sin, pz = cos;
        for (int i = 0; i < 10; i++) {
            double dist = 12 - i * 1.1;
            double side = (i % 2 == 0) ? 0.45 : -0.45;
            int x = (int) Math.floor(bed.getX() + 0.5 + cos * dist + px * side);
            int z = (int) Math.floor(bed.getZ() + 0.5 + sin * dist + pz * side);
            if (!w.isChunkLoaded(x >> 4, z >> 4)) continue;
            int y = w.getTopY(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, x, z) - 1;
            BlockPos g = new BlockPos(x, y, z);
            BlockState gs = w.getBlockState(g);
            if ((gs.isOf(Blocks.GRASS_BLOCK) || gs.isOf(Blocks.DIRT)) && w.getBlockState(g.up()).isAir()) {
                w.setBlockState(g, Blocks.DIRT_PATH.getDefaultState());
            }
        }
        for (BlockPos pos : BlockPos.iterate(bed.add(-8, -2, -8), bed.add(8, 4, 8))) {
            BlockState s = w.getBlockState(pos);
            if (s.isIn(BlockTags.WOODEN_DOORS) && s.get(DoorBlock.HALF) == DoubleBlockHalf.LOWER && RNG.nextInt(3) == 0) {
                ((DoorBlock) s.getBlock()).setOpen(null, w, s, pos.toImmutable(), !s.get(DoorBlock.OPEN));
            }
        }
    }

    // ------------------------------------------------------------------ 2. aldeanos y animales

    private static void tickStares(ServerPlayerEntity p, ServerWorld w, EchoState.PlayerData d, boolean enabled) {
        Box box = p.getBoundingBox().expand(48);
        List<MobEntity> staring = w.getEntitiesByClass(MobEntity.class, box, m -> m.getCommandTags().contains(STARE_TAG));
        for (MobEntity m : staring) {
            if (!enabled || m.isRemoved() || m.squaredDistanceTo(p) > 52 * 52) {
                release(m);
            } else {
                faceTo(m, p);
            }
        }
        if (!enabled || p.age % 40 != 0 || staring.size() >= 6) return;
        if (RNG.nextDouble() > Math.min(0.5, 0.08 + d.dread * 0.003)) return;
        List<MobEntity> cands = w.getEntitiesByClass(MobEntity.class, p.getBoundingBox().expand(24),
                m -> (m instanceof VillagerEntity || m instanceof AnimalEntity) && m.isAlive()
                        && !m.getCommandTags().contains(STARE_TAG) && !m.isAiDisabled());
        if (cands.isEmpty()) return;
        MobEntity pick = cands.get(RNG.nextInt(cands.size()));
        pick.addCommandTag(STARE_TAG);
        pick.getNavigation().stop();
        pick.setAiDisabled(true);
        FROZEN.add(pick);
    }

    private static void faceTo(MobEntity m, ServerPlayerEntity p) {
        double dx = p.getX() - m.getX();
        double dy = p.getEyeY() - m.getEyeY();
        double dz = p.getZ() - m.getZ();
        float yaw = (float) (Math.atan2(dz, dx) * (180.0 / Math.PI)) - 90f;
        float pitch = (float) -(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz)) * (180.0 / Math.PI));
        m.setYaw(yaw);
        m.setPitch(pitch);
        m.setHeadYaw(yaw);
        m.setBodyYaw(yaw);
    }

    private static void release(MobEntity m) {
        m.removeCommandTag(STARE_TAG);
        m.setAiDisabled(false);
        FROZEN.remove(m);
    }

    private static ActionResult onUseEntity(PlayerEntity player, World world, Hand hand, Entity entity, @Nullable EntityHitResult hit) {
        if (world.isClient || !(entity instanceof MobEntity m) || !m.getCommandTags().contains(STARE_TAG)) {
            return ActionResult.PASS;
        }
        if (player instanceof ServerPlayerEntity sp && world instanceof ServerWorld w) {
            replayRecording(sp, w, m);
            EchoState st = EchoState.get(w.getServer());
            st.data(sp.getUuid()).dread += 1.0;
            st.markDirty();
        }
        return ActionResult.SUCCESS; // cancela el comercio
    }

    /** Reproduce, deformado, un fragmento reciente de tus propias acciones. */
    private static void replayRecording(ServerPlayerEntity p, ServerWorld w, MobEntity m) {
        Rec r = pickRecording(p.getUuid(), w.getServer().getOverworld().getTime());
        SoundEvent s = r != null ? r.sound() : SoundEvents.ENTITY_PLAYER_HURT;
        double x = m.getX(), y = m.getY() + 1.4, z = m.getZ();
        int t = w.getServer().getTicks();
        w.playSound(null, x, y, z, s, SoundCategory.NEUTRAL, 1.0f, 0.55f);
        schedule(t + 4, () -> w.playSound(null, x, y, z, s, SoundCategory.NEUTRAL, 0.9f, 0.5f));
        schedule(t + 9, () -> w.playSound(null, x, y, z, SoundEvents.ENTITY_PLAYER_HURT, SoundCategory.NEUTRAL, 0.7f, 0.5f));
    }

    // ------------------------------------------------------------------ grabación de acciones

    private static void record(ServerPlayerEntity p, SoundEvent sound) {
        ArrayDeque<Rec> q = RECORDINGS.computeIfAbsent(p.getUuid(), k -> new ArrayDeque<>());
        q.addLast(new Rec(p.getServer().getOverworld().getTime(), sound));
        while (q.size() > 16) q.removeFirst();
    }

    private static Rec pickRecording(UUID id, long now) {
        ArrayDeque<Rec> q = RECORDINGS.get(id);
        if (q == null) return null;
        List<Rec> recent = new ArrayList<>();
        for (Rec r : q) if (now - r.time() < 6000) recent.add(r);
        return recent.isEmpty() ? null : recent.get(RNG.nextInt(recent.size()));
    }

    // ------------------------------------------------------------------ 3. paranoia auditiva e interfaz

    private static void playTo(ServerPlayerEntity p, SoundEvent s, SoundCategory c, Vec3d pos, float vol, float pitch) {
        if (p.isRemoved()) return;
        p.networkHandler.sendPacket(new PlaySoundS2CPacket(Registries.SOUND_EVENT.getEntry(s), c,
                pos.x, pos.y, pos.z, vol, pitch, p.getRandom().nextLong()));
    }

    private static Vec3d horizontalBack(ServerPlayerEntity p) {
        Vec3d look = p.getRotationVec(1.0f);
        Vec3d b = new Vec3d(-look.x, 0, -look.z);
        return b.lengthSquared() < 1e-4 ? new Vec3d(0, 0, 1) : b.normalize();
    }

    private static Vec3d ring(Vec3d base, double dist) {
        double a = RNG.nextDouble() * Math.PI * 2;
        return base.add(Math.cos(a) * dist, 0, Math.sin(a) * dist);
    }

    private static final String[] WHISPERS = {
            "no mires atrás", "ya estoy en tu casa", "¿me ves?", "soy yo", "mírame", "estoy detrás de ti", "no estás solo"
    };

    private static void paranoia(ServerPlayerEntity p, ServerWorld w, EchoState.PlayerData d, int t) {
        if (RNG.nextDouble() > Math.min(0.10, 0.03 + d.dread * 0.0006)) return;
        boolean underground = !w.isSkyVisible(p.getBlockPos()) && p.getY() < 55;
        int max = d.dread > 60 ? 11 : (d.dread > 35 ? 10 : 8);
        int roll = RNG.nextInt(max);
        if (underground && RNG.nextInt(3) == 0) roll = 0;
        Vec3d back = horizontalBack(p);
        Vec3d base = p.getPos();
        switch (roll) {
            case 0 -> { // pasos detrás de ti
                SoundEvent step = w.getBlockState(p.getBlockPos().down()).getSoundGroup().getStepSound();
                for (int i = 0; i < 6; i++) {
                    final int k = i;
                    schedule(t + i * 10, () -> playTo(p, step, SoundCategory.PLAYERS,
                            base.add(back.multiply(6.0 - k * 0.9)), 0.8f, 0.9f + RNG.nextFloat() * 0.2f));
                }
            }
            case 1 -> { // un cofre abriéndose
                Vec3d pos = ring(base, 5);
                playTo(p, SoundEvents.BLOCK_CHEST_OPEN, SoundCategory.BLOCKS, pos, 0.7f, 0.95f);
                schedule(t + 50, () -> playTo(p, SoundEvents.BLOCK_CHEST_CLOSE, SoundCategory.BLOCKS, pos, 0.7f, 0.95f));
            }
            case 2 -> { // una puerta
                Vec3d pos = ring(base, 6);
                playTo(p, SoundEvents.BLOCK_WOODEN_DOOR_OPEN, SoundCategory.BLOCKS, pos, 0.8f, 0.9f);
                schedule(t + 40, () -> playTo(p, SoundEvents.BLOCK_WOODEN_DOOR_CLOSE, SoundCategory.BLOCKS, pos, 0.8f, 0.9f));
            }
            case 3 -> { // tu propio sonido, hace unos minutos
                Rec r = pickRecording(p.getUuid(), p.getServer().getOverworld().getTime());
                SoundEvent s = r != null ? r.sound() : SoundEvents.ENTITY_PLAYER_HURT;
                playTo(p, s, SoundCategory.PLAYERS, base.add(back.multiply(4)), 0.7f, 0.6f);
            }
            case 4 -> // un creeper que no está ahí
                playTo(p, SoundEvents.ENTITY_CREEPER_PRIMED, SoundCategory.HOSTILE, base.add(back.multiply(3)), 1.0f, 1.0f);
            case 5 -> // un grito lejano
                playTo(p, RNG.nextBoolean() ? SoundEvents.ENTITY_GHAST_SCREAM : SoundEvents.ENTITY_ENDERMAN_SCREAM,
                        SoundCategory.HOSTILE, ring(base, 28), 0.6f, 0.6f + RNG.nextFloat() * 0.2f);
            case 6 -> // un susurro grave pegado a tu espalda
                playTo(p, SoundEvents.ENTITY_PHANTOM_AMBIENT, SoundCategory.AMBIENT, base.add(back.multiply(2)), 0.7f, 0.5f);
            case 7 -> // algo enorme respira cerca
                playTo(p, SoundEvents.ENTITY_WARDEN_NEARBY_CLOSE, SoundCategory.HOSTILE, ring(base, 8), 0.8f, 0.7f);
            case 8 -> spawnGlimpse(p, w);
            case 9 -> { // alucinación: un mensaje en el chat con tu propio nombre
                String name = p.getName().getString();
                p.sendMessage(Text.literal("<" + name + "> " + WHISPERS[RNG.nextInt(WHISPERS.length)]), false);
            }
            default -> { // grito del sculk + sobresalto
                playTo(p, SoundEvents.BLOCK_SCULK_SHRIEKER_SHRIEK, SoundCategory.HOSTILE, base.add(back.multiply(5)), 1.0f, 0.7f);
                sendScare(p, 1);
            }
        }
    }

    /** Un Eco que aparece de reojo y desaparece en cuanto lo miras. */
    private static void spawnGlimpse(ServerPlayerEntity p, ServerWorld w) {
        Vec3d look = p.getRotationVec(1.0f);
        double base = Math.atan2(look.z, look.x);
        for (int i = 0; i < 10; i++) {
            double off = Math.toRadians(55 + RNG.nextDouble() * 35) * (RNG.nextBoolean() ? 1 : -1);
            double dist = 7 + RNG.nextDouble() * 9;
            int x = (int) Math.floor(p.getX() + Math.cos(base + off) * dist);
            int z = (int) Math.floor(p.getZ() + Math.sin(base + off) * dist);
            if (!w.isChunkLoaded(x >> 4, z >> 4)) continue;
            int y = w.getTopY(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, x, z);
            if (Math.abs(y - p.getY()) > 5) continue;
            BlockPos pos = new BlockPos(x, y, z);
            if (!w.getBlockState(pos.down()).isSolidBlock(w, pos.down())) continue;
            if (!w.getBlockState(pos).isAir() || !w.getBlockState(pos.up()).isAir()) continue;
            spawnEcho(w, p, pos, EchoEntity.Mode.GLIMPSE, false);
            return;
        }
    }

    private static void sendDread(ServerPlayerEntity p, int dread) {
        if (ServerPlayNetworking.canSend(p, DreadPayload.ID)) ServerPlayNetworking.send(p, new DreadPayload(dread, 0));
    }

    private static void sendScare(ServerPlayerEntity p, int level) {
        if (ServerPlayNetworking.canSend(p, DreadPayload.ID)) ServerPlayNetworking.send(p, new DreadPayload(-1, level));
    }

    private static void startFlicker(ServerPlayerEntity p, ServerWorld w, int t) {
        List<BlockPos> torches = new ArrayList<>();
        BlockPos c = p.getBlockPos();
        for (BlockPos pos : BlockPos.iterate(c.add(-12, -6, -12), c.add(12, 6, 12))) {
            BlockState s = w.getBlockState(pos);
            if (s.isOf(Blocks.TORCH) || s.isOf(Blocks.WALL_TORCH)) {
                torches.add(pos.toImmutable());
                if (torches.size() >= 10) break;
            }
        }
        if (!torches.isEmpty()) FLICKERS.put(p.getUuid(), new Flicker(t + 60 + RNG.nextInt(60), torches));
    }

    private static void mutateNearbySign(ServerPlayerEntity p, ServerWorld w) {
        int cx = p.getChunkPos().x, cz = p.getChunkPos().z;
        List<SignBlockEntity> signs = new ArrayList<>();
        for (int dx = -3; dx <= 3; dx++) {
            for (int dz = -3; dz <= 3; dz++) {
                if (!w.isChunkLoaded(cx + dx, cz + dz)) continue;
                WorldChunk ch = w.getChunk(cx + dx, cz + dz);
                for (BlockEntity be : ch.getBlockEntities().values()) {
                    if (be instanceof SignBlockEntity s) signs.add(s);
                }
            }
        }
        if (signs.isEmpty()) return;
        SignBlockEntity sign = signs.get(RNG.nextInt(signs.size()));
        sign.changeText(text -> {
            List<Integer> lines = new ArrayList<>();
            for (int i = 0; i < 4; i++) {
                if (!text.getMessage(i, false).getString().trim().isEmpty()) lines.add(i);
            }
            if (lines.isEmpty()) return text;
            int line = lines.get(RNG.nextInt(lines.size()));
            Text original = text.getMessage(line, false);
            String str = original.getString();
            List<Integer> letters = new ArrayList<>();
            for (int i = 0; i < str.length(); i++) if (Character.isLetter(str.charAt(i))) letters.add(i);
            if (letters.isEmpty()) return text;
            int idx = letters.get(RNG.nextInt(letters.size()));
            String mutated = str.substring(0, idx) + mutate(str.charAt(idx)) + str.substring(idx + 1);
            return text.withMessage(line, Text.literal(mutated).setStyle(original.getStyle()));
        }, true);
    }

    private static char mutate(char c) {
        String vowels = "aeiou";
        char lc = Character.toLowerCase(c);
        char n;
        if (vowels.indexOf(lc) >= 0) {
            do { n = vowels.charAt(RNG.nextInt(5)); } while (n == lc);
        } else {
            n = lc == 'z' ? 'a' : (char) (lc + 1);
        }
        return Character.isUpperCase(c) ? Character.toUpperCase(n) : n;
    }

    // ------------------------------------------------------------------ 4. espejo y ofrendas

    public static void registerMirror(World world, BlockPos pos) {
        if (!(world instanceof ServerWorld w)) return;
        EchoState st = EchoState.get(w.getServer());
        st.mirrors.add(new EchoState.MirrorPos(w.getRegistryKey().getValue().toString(), pos.toImmutable()));
        st.markDirty();
    }

    /** ¿Hay un espejo que proteja esta posición? */
    public static boolean isProtected(ServerWorld w, BlockPos pos) {
        EchoState st = EchoState.get(w.getServer());
        String dim = w.getRegistryKey().getValue().toString();
        long r = EchoConfig.INSTANCE.mirrorRadius;
        boolean dirty = false;
        boolean result = false;
        Iterator<EchoState.MirrorPos> it = st.mirrors.iterator();
        while (it.hasNext()) {
            EchoState.MirrorPos m = it.next();
            if (!dim.equals(m.dim())) continue;
            if (pos.getSquaredDistance(m.pos()) > r * r) continue;
            if (w.isChunkLoaded(m.pos().getX() >> 4, m.pos().getZ() >> 4)
                    && !w.getBlockState(m.pos()).isOf(ModBlocks.ECHO_MIRROR)) {
                it.remove();
                dirty = true;
                continue;
            }
            result = true;
            break;
        }
        if (dirty) st.markDirty();
        return result;
    }

    public static void offer(ServerPlayerEntity p, ItemStack stack, BlockPos mirrorPos) {
        ServerWorld w = p.getServerWorld();
        MinecraftServer server = w.getServer();
        EchoConfig cfg = EchoConfig.INSTANCE;
        EchoState st = EchoState.get(server);
        EchoState.PlayerData d = st.data(p.getUuid());
        String id = Registries.ITEM.getId(stack.getItem()).toString();

        if (!d.day1Items.contains(id)) {
            p.sendMessage(Text.literal("El espejo no reconoce esto. No viene de tu primer día.").formatted(Formatting.GRAY), true);
            return;
        }
        if (d.offered.contains(id)) {
            p.sendMessage(Text.literal("Ya le diste esto. Quiere algo distinto.").formatted(Formatting.GRAY), true);
            return;
        }
        stack.decrementUnlessCreative(1, p);
        d.offered.add(id);
        long now = server.getOverworld().getTime();
        long base = Math.max(d.banishedUntil, now) + cfg.offeringBanishDays * 24000L;
        d.banishedUntil = Math.min(base, now + cfg.maxBanishDays * 24000L);
        d.dread = Math.max(0, d.dread - cfg.offeringDreadReduction);
        st.markDirty();
        dismissEcho(p.getUuid());

        w.spawnParticles(ParticleTypes.REVERSE_PORTAL, mirrorPos.getX() + 0.5, mirrorPos.getY() + 0.8, mirrorPos.getZ() + 0.5,
                30, 0.3, 0.5, 0.3, 0.1);
        w.playSound(null, mirrorPos, SoundEvents.BLOCK_AMETHYST_BLOCK_RESONATE, SoundCategory.BLOCKS, 1.0f, 0.7f);
        p.sendMessage(Text.literal("El reflejo retrocede... por ahora.").formatted(Formatting.AQUA, Formatting.ITALIC), true);
    }

    public static Text mirrorMessage(ServerPlayerEntity p) {
        double dread = EchoState.get(p.getServer()).data(p.getUuid()).dread;
        String s;
        if (dread < 30) s = "Tu reflejo te devuelve la mirada... un instante tarde.";
        else if (dread < 70) s = "Tu reflejo sonríe un momento antes que tú.";
        else s = "No eres tú quien mira. Ofrécele algo de tu primer día.";
        return Text.literal(s).formatted(Formatting.GRAY, Formatting.ITALIC);
    }

    // ------------------------------------------------------------------ 5. clímax: el reino espejo

    private static void hijack(MinecraftServer server, EchoState st, ServerPlayerEntity p, BlockPos sleepPos) {
        ServerWorld realm = server.getWorld(REALM);
        if (realm == null) {
            TheEcho.LOGGER.warn("La dimensión the_echo:mirror_realm no está cargada; se cancela el secuestro.");
            p.sendMessage(Text.literal("[El Eco] La dimensión the_echo:mirror_realm no está cargada. Prueba en un mundo nuevo.")
                    .formatted(Formatting.RED), false);
            return;
        }
        ServerWorld src = p.getServerWorld();
        EchoState.PlayerData d = st.data(p.getUuid());
        if (d.slot < 0) d.slot = st.nextSlot++;
        BlockPos dstOrigin = new BlockPos(d.slot * 160, 20, 0);

        d.returnPos = sleepPos;
        d.returnDim = src.getRegistryKey().getValue().toString();
        RealmBuilder.Result res = RealmBuilder.build(realm, dstOrigin, RNG);
        d.realmBase = res.start();
        d.realmOriginal = res.original();
        TRAPS.put(p.getUuid(), new ArrayList<>(res.traps()));
        REALM_CELLS.put(p.getUuid(), res.cells());
        d.inRealm = true;
        d.offered.clear();
        st.markDirty();

        REALM_TICKS.put(p.getUuid(), 0);
        dismissEcho(p.getUuid());
        BlockPos st0 = res.start();
        p.teleport(realm, st0.getX() + 0.5, st0.getY(), st0.getZ() + 0.5, p.getYaw(), p.getPitch());
        sendScare(p, 1);
        for (int i = 0; i < 3; i++) {
            BlockPos c = pickCell(res.cells(), st0, 14);
            if (c != null) spawnEcho(realm, p, c, EchoEntity.Mode.STALK, false);
        }
        p.sendMessage(Text.literal("Despiertas.").formatted(Formatting.DARK_GRAY, Formatting.ITALIC), true);
    }

    public static void forceHijack(ServerPlayerEntity p) {
        MinecraftServer server = p.getServer();
        hijack(server, EchoState.get(server), p, p.getBlockPos());
    }

    private static BlockPos pickCell(List<BlockPos> cells, BlockPos from, int minDist) {
        if (cells == null || cells.isEmpty()) return null;
        for (int i = 0; i < 30; i++) {
            BlockPos c = cells.get(RNG.nextInt(cells.size()));
            if (c.getSquaredDistance(from) >= (double) minDist * minDist) return c;
        }
        return null;
    }

    private static void tickRealm(ServerPlayerEntity p, ServerWorld w, EchoState.PlayerData d) {
        int rt = REALM_TICKS.merge(p.getUuid(), 1, Integer::sum);
        if (rt % 20 == 0) sendDread(p, 100);
        if (rt % 40 == 0) {
            p.addStatusEffect(new StatusEffectInstance(StatusEffects.DARKNESS, 140, 0, false, false, false));
        }
        if (d.realmBase != null && p.getY() < d.realmBase.getY() - 6) {
            p.teleport(w, d.realmBase.getX() + 0.5, d.realmBase.getY(), d.realmBase.getZ() + 0.5, p.getYaw(), p.getPitch());
        }
        // Trampas de susto en los callejones
        if (rt % 5 == 0) {
            List<BlockPos> traps = TRAPS.get(p.getUuid());
            if (traps != null) {
                for (Iterator<BlockPos> it = traps.iterator(); it.hasNext(); ) {
                    BlockPos tp = it.next();
                    double dx = p.getX() - (tp.getX() + 0.5), dz = p.getZ() - (tp.getZ() + 0.5);
                    if (dx * dx + dz * dz < 2.5 * 2.5 && Math.abs(p.getY() - tp.getY()) < 3) {
                        it.remove();
                        jumpscare(p, w);
                        break;
                    }
                }
            }
        }
        // Más Ecos con el tiempo (hasta 5)
        if (rt % 240 == 0) {
            List<BlockPos> cells = REALM_CELLS.get(p.getUuid());
            int want = Math.min(5, 3 + rt / 2400);
            int have = w.getEntitiesByClass(EchoEntity.class, p.getBoundingBox().expand(150),
                    e -> p.getUuid().equals(e.getOwnerUuid())).size();
            for (int i = have; i < want; i++) {
                BlockPos c = pickCell(cells, p.getBlockPos(), 14);
                if (c != null) spawnEcho(w, p, c, EchoEntity.Mode.STALK, false);
            }
        }
        // Ambiente sonoro constante
        if (rt % 160 == 0 && RNG.nextInt(2) == 0) realmAmbient(p, w, rt);
        // Después de 2 minutos, el Original empieza a llamarte
        if (rt > 2400 && rt % 80 == 0 && d.realmOriginal != null) {
            BlockPos o = d.realmOriginal;
            w.spawnParticles(p, ParticleTypes.END_ROD, true, o.getX() + 0.5, o.getY() + 1.0, o.getZ() + 0.5,
                    10, 0.15, 1.5, 0.15, 0.01);
        }
    }

    private static void realmAmbient(ServerPlayerEntity p, ServerWorld w, int rt) {
        Vec3d back = horizontalBack(p);
        Vec3d base = p.getPos();
        SoundEvent[] pool = {
                SoundEvents.ENTITY_WARDEN_NEARBY_CLOSE, SoundEvents.ENTITY_PHANTOM_AMBIENT, SoundEvents.ENTITY_GHAST_AMBIENT,
                SoundEvents.ENTITY_WITHER_AMBIENT, SoundEvents.ENTITY_VEX_AMBIENT, SoundEvents.ENTITY_ENDERMAN_AMBIENT,
                SoundEvents.ENTITY_ELDER_GUARDIAN_CURSE, SoundEvents.ENTITY_ZOMBIE_AMBIENT
        };
        if (RNG.nextInt(3) == 0) {
            SoundEvent step = SoundEvents.BLOCK_DEEPSLATE_STEP;
            int t = w.getServer().getTicks();
            for (int i = 0; i < 5; i++) {
                final int k = i;
                schedule(t + i * 8, () -> playTo(p, step, SoundCategory.PLAYERS,
                        base.add(back.multiply(5.0 - k * 0.8)), 0.9f, 0.8f));
            }
        } else {
            playTo(p, pool[RNG.nextInt(pool.length)], SoundCategory.AMBIENT, ring(base, 6 + RNG.nextInt(8)),
                    0.8f, 0.5f + RNG.nextFloat() * 0.3f);
        }
    }

    /** Susto: pantallazo + grito + un Eco aparece justo frente a ti un instante. */
    private static void jumpscare(ServerPlayerEntity p, ServerWorld w) {
        sendScare(p, 2);
        w.playSound(null, p.getBlockPos(), SoundEvents.ENTITY_GHAST_SCREAM, SoundCategory.HOSTILE, 1.0f, 0.6f);
        w.playSound(null, p.getBlockPos(), SoundEvents.ENTITY_WARDEN_ROAR, SoundCategory.HOSTILE, 1.0f, 0.9f);
        Vec3d look = p.getRotationVec(1.0f);
        Vec3d h = new Vec3d(look.x, 0, look.z);
        h = h.lengthSquared() < 1e-4 ? new Vec3d(0, 0, 1) : h.normalize();
        double[] dists = {2.6, 2.0, 1.5};
        for (double dist : dists) {
            BlockPos spot = BlockPos.ofFloored(p.getX() + h.x * dist, p.getY(), p.getZ() + h.z * dist);
            if (w.getBlockState(spot).isAir() && w.getBlockState(spot.up()).isAir()
                    && w.getBlockState(spot.down()).isSolidBlock(w, spot.down())) {
                spawnEcho(w, p, spot, EchoEntity.Mode.JUMP, false);
                break;
            }
        }
    }

    /** Un Eco te alcanzó en el laberinto: susto y vuelves al inicio. */
    public static void caught(ServerPlayerEntity p, EchoEntity e) {
        MinecraftServer server = p.getServer();
        EchoState st = EchoState.get(server);
        EchoState.PlayerData d = st.data(p.getUuid());
        ServerWorld w = p.getServerWorld();
        e.vanish();
        sendScare(p, 2);
        w.playSound(null, p.getBlockPos(), SoundEvents.ENTITY_ENDERMAN_SCREAM, SoundCategory.HOSTILE, 1.0f, 0.5f);
        w.playSound(null, p.getBlockPos(), SoundEvents.ENTITY_GHAST_SCREAM, SoundCategory.HOSTILE, 1.0f, 0.7f);
        p.addStatusEffect(new StatusEffectInstance(StatusEffects.NAUSEA, 100, 0, false, false, false));
        p.addStatusEffect(new StatusEffectInstance(StatusEffects.BLINDNESS, 30, 0, false, false, false));
        BlockPos b = d.realmBase;
        if (b != null) p.teleport(w, b.getX() + 0.5, b.getY(), b.getZ() + 0.5, p.getYaw(), p.getPitch());
    }

    public static void leaveRealm(ServerPlayerEntity p, boolean found) {
        MinecraftServer server = p.getServer();
        EchoState st = EchoState.get(server);
        EchoState.PlayerData d = st.data(p.getUuid());

        ServerWorld dest = server.getOverworld();
        if (d.returnDim != null) {
            Identifier id = Identifier.tryParse(d.returnDim);
            if (id != null) {
                ServerWorld w = server.getWorld(RegistryKey.of(RegistryKeys.WORLD, id));
                if (w != null) dest = w;
            }
        }
        BlockPos r = d.returnPos != null ? d.returnPos : dest.getSpawnPos();
        p.removeStatusEffect(StatusEffects.DARKNESS);
        p.teleport(dest, r.getX() + 0.5, r.getY() + 0.6, r.getZ() + 0.5, p.getYaw(), p.getPitch());

        d.inRealm = false;
        REALM_TICKS.remove(p.getUuid());
        TRAPS.remove(p.getUuid());
        REALM_CELLS.remove(p.getUuid());
        sendDread(p, 0);
        if (found) {
            d.dread = Math.min(d.dread, 25);
            d.banishedUntil = server.getOverworld().getTime() + 2 * 24000L;
        }
        st.markDirty();
        p.sendMessage(Text.literal(found ? "Encontraste el original." : "Estás de vuelta.")
                .formatted(Formatting.GRAY, Formatting.ITALIC), true);
    }

    // ------------------------------------------------------------------ utilidades para comandos

    public static String status(ServerPlayerEntity p) {
        MinecraftServer server = p.getServer();
        EchoState.PlayerData d = EchoState.get(server).data(p.getUuid());
        ServerWorld ow = server.getOverworld();
        long day = ow.getTimeOfDay() / 24000L;
        long calm = Math.max(0, d.banishedUntil - ow.getTime());
        return String.format("Día %d | miedo %.1f/%.0f | calma restante %.1f días | en reino: %s | ofrendas válidas: %d | protegido: %s",
                day, d.dread, EchoConfig.INSTANCE.hijackDread, calm / 24000.0, d.inRealm, d.day1Items.size(),
                isProtected(p.getServerWorld(), p.getBlockPos()));
    }

    public static void setDread(ServerPlayerEntity p, double value) {
        EchoState st = EchoState.get(p.getServer());
        st.data(p.getUuid()).dread = value;
        st.markDirty();
    }

    public static void reset(ServerPlayerEntity p) {
        EchoState.get(p.getServer()).reset(p.getUuid());
        dismissEcho(p.getUuid());
    }
}
