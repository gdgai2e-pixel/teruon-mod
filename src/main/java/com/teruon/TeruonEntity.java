package com.teruon;

import java.util.EnumSet;
import java.util.UUID;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.ai.control.FlightMoveControl;
import net.minecraft.entity.ai.goal.Goal;
import net.minecraft.entity.ai.goal.LookAroundGoal;
import net.minecraft.entity.ai.goal.LookAtEntityGoal;
import net.minecraft.entity.ai.pathing.BirdNavigation;
import net.minecraft.entity.ai.pathing.EntityNavigation;
import net.minecraft.entity.attribute.DefaultAttributeContainer;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.damage.DamageTypes;
import net.minecraft.entity.data.DataTracker;
import net.minecraft.entity.data.TrackedData;
import net.minecraft.entity.data.TrackedDataHandlerRegistry;
import net.minecraft.entity.mob.PathAwareEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import org.jetbrains.annotations.Nullable;

public class TeruonEntity extends PathAwareEntity {
    private static final TrackedData<Boolean> TALKING =
            DataTracker.registerData(TeruonEntity.class, TrackedDataHandlerRegistry.BOOLEAN);

    @Nullable
    private UUID ownerUuid;
    private int talkTicksLeft;

    public TeruonEntity(EntityType<? extends TeruonEntity> type, World world) {
        super(type, world);
        this.moveControl = new FlightMoveControl(this, 20, true);
        this.setPersistent();
    }

    public static DefaultAttributeContainer.Builder createTeruonAttributes() {
        return PathAwareEntity.createMobAttributes()
                .add(EntityAttributes.GENERIC_MAX_HEALTH, 20.0)
                .add(EntityAttributes.GENERIC_MOVEMENT_SPEED, 0.3)
                .add(EntityAttributes.GENERIC_FLYING_SPEED, 0.6);
    }

    @Nullable
    public static TeruonEntity spawnFor(World world, PlayerEntity owner, Vec3d pos) {
        return spawnAt(world, owner.getUuid(), pos, owner.getYaw());
    }

    @Nullable
    public static TeruonEntity spawnAt(World world, @Nullable UUID owner, Vec3d pos, float yaw) {
        TeruonEntity e = Teruon.TERUON_ENTITY.create(world);
        if (e == null) return null;
        e.refreshPositionAndAngles(pos.x, pos.y, pos.z, yaw, 0f);
        e.setOwner(owner);
        e.setCustomName(Text.literal("Teruon"));
        e.setCustomNameVisible(true);
        world.spawnEntity(e);
        return e;
    }

    // ---------- данные ----------
    @Override
    protected void initDataTracker(DataTracker.Builder builder) {
        super.initDataTracker(builder);
        builder.add(TALKING, false);
    }

    public boolean isTalking() {
        return this.dataTracker.get(TALKING);
    }

    /** Открыть "рот" (спрайт 2) на N тиков. Только на сервере. */
    public void startTalking(int ticks) {
        this.talkTicksLeft = Math.max(10, Math.min(ticks, 400));
        this.dataTracker.set(TALKING, true);
    }

    @Nullable
    public UUID getOwnerUuid() {
        return ownerUuid;
    }

    public void setOwner(@Nullable UUID uuid) {
        this.ownerUuid = uuid;
    }

    @Nullable
    public PlayerEntity findOwner() {
        if (ownerUuid != null) return getWorld().getPlayerByUuid(ownerUuid);
        return getWorld().getClosestPlayer(this, 32.0);
    }

    @Override
    public void writeCustomDataToNbt(NbtCompound nbt) {
        super.writeCustomDataToNbt(nbt);
        if (ownerUuid != null) nbt.putUuid("Owner", ownerUuid);
    }

    @Override
    public void readCustomDataFromNbt(NbtCompound nbt) {
        super.readCustomDataFromNbt(nbt);
        if (nbt.containsUuid("Owner")) ownerUuid = nbt.getUuid("Owner");
    }

    // ---------- поведение ----------
    @Override
    protected void initGoals() {
        this.goalSelector.add(1, new FollowOwnerFlyGoal(this));
        this.goalSelector.add(2, new LookAtEntityGoal(this, PlayerEntity.class, 8.0f));
        this.goalSelector.add(3, new LookAroundGoal(this));
    }

    @Override
    protected EntityNavigation createNavigation(World world) {
        return new BirdNavigation(this, world);
    }

    @Override
    public boolean hasNoGravity() {
        return true;
    }

    @Override
    public boolean handleFallDamage(float fallDistance, float damageMultiplier, DamageSource damageSource) {
        return false;
    }

    @Override
    public void tick() {
        super.tick();
        if (!getWorld().isClient && talkTicksLeft > 0 && --talkTicksLeft == 0) {
            this.dataTracker.set(TALKING, false);
        }
    }

    /** Телепорт к игроку (в пределах одного измерения). */
    public void teleportNear(Entity target) {
        Vec3d off = Vec3d.fromPolar(0f, target.getYaw() + 45f).multiply(1.8);
        this.refreshPositionAndAngles(target.getX() + off.x, target.getY() + 1.0, target.getZ() + off.z,
                this.getYaw(), this.getPitch());
        this.getNavigation().stop();
    }

    // ---------- нельзя ударить ----------
    @Override
    public boolean isAttackable() {
        return false;
    }

    @Override
    public boolean handleAttack(Entity attacker) {
        return true; // удар "съеден"
    }

    @Override
    public boolean damage(DamageSource source, float amount) {
        // урон получает только от пустоты и /kill
        if (source.isOf(DamageTypes.OUT_OF_WORLD) || source.isOf(DamageTypes.GENERIC_KILL)) {
            return super.damage(source, amount);
        }
        return false;
    }

    // ---------- взять в руку (ПКМ пустой рукой) ----------
    @Override
    protected ActionResult interactMob(PlayerEntity player, Hand hand) {
        ItemStack held = player.getStackInHand(hand);
        if (held.isEmpty()) {
            if (!getWorld().isClient) {
                player.setStackInHand(hand, new ItemStack(Teruon.TERUON_ITEM));
                this.discard();
            }
            return ActionResult.success(getWorld().isClient);
        }
        return super.interactMob(player, hand);
    }

    // ---------- цель: летать за хозяином ----------
    static class FollowOwnerFlyGoal extends Goal {
        private final TeruonEntity mob;
        private int cooldown;

        FollowOwnerFlyGoal(TeruonEntity mob) {
            this.mob = mob;
            this.setControls(EnumSet.of(Control.MOVE));
        }

        @Override
        public boolean canStart() {
            return mob.findOwner() != null;
        }

        @Override
        public boolean shouldContinue() {
            return mob.findOwner() != null;
        }

        @Override
        public void tick() {
            PlayerEntity owner = mob.findOwner();
            if (owner == null) return;
            double d = mob.squaredDistanceTo(owner);
            if (d > 24 * 24) {
                mob.teleportNear(owner);
            } else if (d > 3 * 3) {
                if (--cooldown <= 0) {
                    cooldown = 10;
                    mob.getNavigation().startMovingTo(owner, 1.2);
                }
            } else {
                mob.getNavigation().stop();
            }
            mob.getLookControl().lookAt(owner, 10.0f, mob.getMaxLookPitchChange());
        }
    }
}
