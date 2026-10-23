package com.theecho.entity;

import com.theecho.EchoManager;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.attribute.DefaultAttributeContainer;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.data.DataTracker;
import net.minecraft.entity.data.TrackedData;
import net.minecraft.entity.data.TrackedDataHandlerRegistry;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.entity.mob.PathAwareEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

import java.util.Optional;
import java.util.UUID;

/**
 * El Clon: un "jugador" con tu skin, tu armadura y tu nombre. Nunca ataca.
 * Se desvanece si te acercas o si lo miras demasiado tiempo.
 */
public class EchoEntity extends PathAwareEntity {

    public enum Mode {
        WATCH,  // se queda quieto mirándote
        WALK,   // camina lentamente hacia ti
        CHOP,   // imita que tala un árbol
        STALK,  // (reino espejo) solo se mueve cuando no lo miras; si te toca, te devuelve al inicio
        GLIMPSE,// aparece de reojo y desaparece cuando lo miras
        JUMP    // susto: aparece frente a ti un instante
    }

    private static final TrackedData<Optional<UUID>> OWNER =
            DataTracker.registerData(EchoEntity.class, TrackedDataHandlerRegistry.OPTIONAL_UUID);

    private Mode mode = Mode.WATCH;
    private int life = 0;
    private int maxLife = 20 * 100;
    private int seenTicks = 0;

    private BlockPos chopTarget;
    private int chopTicks = 0;
    private boolean chopped = false;
    private int afterChop = 0;

    public EchoEntity(EntityType<? extends EchoEntity> type, World world) {
        super(type, world);
        this.setSilent(true);
    }

    public static DefaultAttributeContainer.Builder createEchoAttributes() {
        return MobEntity.createMobAttributes()
                .add(EntityAttributes.GENERIC_MAX_HEALTH, 20.0)
                .add(EntityAttributes.GENERIC_MOVEMENT_SPEED, 0.25)
                .add(EntityAttributes.GENERIC_FOLLOW_RANGE, 64.0);
    }

    @Override
    protected void initDataTracker(DataTracker.Builder builder) {
        super.initDataTracker(builder);
        builder.add(OWNER, Optional.empty());
    }

    public UUID getOwnerUuid() {
        return this.dataTracker.get(OWNER).orElse(null);
    }

    public void setOwner(ServerPlayerEntity player) {
        this.dataTracker.set(OWNER, Optional.of(player.getUuid()));
        this.setCustomName(player.getName());
        this.setCustomNameVisible(true);
    }

    public void copyAppearance(ServerPlayerEntity p) {
        EquipmentSlot[] slots = {EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS,
                EquipmentSlot.FEET, EquipmentSlot.MAINHAND, EquipmentSlot.OFFHAND};
        for (EquipmentSlot s : slots) {
            this.equipStack(s, p.getEquippedStack(s).copy());
            this.setEquipmentDropChance(s, 0.0f);
        }
    }

    public void setMode(Mode mode) {
        this.mode = mode;
        if (mode == Mode.STALK) this.maxLife = Integer.MAX_VALUE;
        if (mode == Mode.GLIMPSE) this.maxLife = 20 * 6;
        if (mode == Mode.JUMP) this.maxLife = 40;
        if (mode == Mode.CHOP) {
            this.equipStack(EquipmentSlot.MAINHAND, new ItemStack(Items.IRON_AXE));
            this.maxLife = 20 * 40;
        }
    }

    public void setChopTarget(BlockPos pos) {
        this.chopTarget = pos;
    }

    private ServerPlayerEntity getOwnerPlayer() {
        UUID id = getOwnerUuid();
        if (id == null || !(getWorld() instanceof ServerWorld sw)) return null;
        return sw.getServer().getPlayerManager().getPlayer(id);
    }

    public void vanish() {
        if (getWorld() instanceof ServerWorld sw) {
            sw.spawnParticles(ParticleTypes.REVERSE_PORTAL, getX(), getY() + 1.0, getZ(), 25, 0.3, 0.6, 0.3, 0.05);
        }
        this.discard();
    }

    private boolean isBeingLookedAt(ServerPlayerEntity p, double minDot) {
        Vec3d look = p.getRotationVec(1.0f).normalize();
        Vec3d to = this.getEyePos().subtract(p.getEyePos()).normalize();
        return look.dotProduct(to) > minDot && p.canSee(this);
    }

    @Override
    public void tick() {
        super.tick();
        if (getWorld().isClient) return;
        ServerWorld world = (ServerWorld) getWorld();

        life++;
        if (life > maxLife) {
            vanish();
            return;
        }
        ServerPlayerEntity owner = getOwnerPlayer();
        if (owner == null || owner.isRemoved() || owner.getServerWorld() != world || !owner.isAlive()) {
            this.discard();
            return;
        }
        if (mode != Mode.STALK && life % 20 == 0 && EchoManager.isProtected(world, getBlockPos())) {
            vanish();
            return;
        }

        double d2 = this.squaredDistanceTo(owner);

        switch (mode) {
            case WATCH -> {
                getNavigation().stop();
                getLookControl().lookAt(owner, 30.0f, 30.0f);
                if (d2 < 9.0 * 9.0) { vanish(); return; }
                tickSeen(owner, d2);
            }
            case WALK -> {
                getLookControl().lookAt(owner, 30.0f, 30.0f);
                if (life % 10 == 0) getNavigation().startMovingTo(owner, 0.55);
                if (d2 < 7.0 * 7.0) { vanish(); return; }
                tickSeen(owner, d2);
            }
            case STALK -> {
                getLookControl().lookAt(owner, 30.0f, 30.0f);
                boolean watched = isBeingLookedAt(owner, 0.55);
                if (d2 < 1.9 * 1.9) {
                    EchoManager.caught(owner, this);
                    return;
                }
                if (d2 < 14.0 * 14.0 && life % 40 == 0) {
                    world.playSound(null, getBlockPos(), SoundEvents.ENTITY_WARDEN_NEARBY_CLOSE,
                            SoundCategory.HOSTILE, 0.8f, 0.8f);
                }
                if (watched) {
                    getNavigation().stop();
                } else if (life % 5 == 0) {
                    getNavigation().startMovingTo(owner, 1.15);
                }
            }
            case GLIMPSE -> {
                getNavigation().stop();
                getLookControl().lookAt(owner, 30.0f, 30.0f);
                if (d2 < 4.0 * 4.0) { vanish(); return; }
                if (life > 4 && isBeingLookedAt(owner, 0.82)) { vanish(); return; }
            }
            case JUMP -> {
                getNavigation().stop();
                getLookControl().lookAt(owner, 60.0f, 60.0f);
            }
            case CHOP -> tickChop(owner, world, d2);
        }
    }

    private void tickSeen(ServerPlayerEntity owner, double d2) {
        if (d2 < 40.0 * 40.0 && isBeingLookedAt(owner, 0.97)) {
            seenTicks++;
            if (seenTicks > 100) vanish();
        } else if (seenTicks > 0) {
            seenTicks--;
        }
    }

    private void tickChop(ServerPlayerEntity owner, ServerWorld world, double d2) {
        if (chopTarget == null) { vanish(); return; }
        if (d2 < 10.0 * 10.0) { vanish(); return; }

        if (chopped) {
            if (++afterChop > 40) vanish();
            return;
        }

        Vec3d tp = Vec3d.ofCenter(chopTarget);
        if (this.squaredDistanceTo(tp) > 6.25) {
            if (life % 10 == 0) getNavigation().startMovingTo(tp.x, tp.y, tp.z, 0.9);
            if (life > 200) vanish();
            return;
        }
        getNavigation().stop();
        getLookControl().lookAt(tp.x, tp.y, tp.z);
        chopTicks++;
        if (chopTicks % 10 == 0) {
            this.swingHand(Hand.MAIN_HAND);
            world.playSound(null, chopTarget, SoundEvents.BLOCK_WOOD_HIT, SoundCategory.BLOCKS, 1.0f, 0.9f);
        }
        if (chopTicks >= 60) {
            for (int i = 0; i < 10; i++) {
                BlockPos p = chopTarget.up(i);
                if (world.getBlockState(p).isIn(BlockTags.LOGS)) {
                    world.breakBlock(p, true);
                } else {
                    break;
                }
            }
            chopped = true;
        }
    }

    @Override
    public boolean damage(DamageSource source, float amount) {
        if (!getWorld().isClient) vanish();
        return false;
    }

    @Override
    public boolean isPushable() {
        return false;
    }

    @Override
    public boolean shouldSave() {
        return false; // el Eco nunca persiste: desaparece al recargar
    }
}
