package dev.valkron;

import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.EventHooks;
import net.neoforged.neoforge.entity.IEntityWithComplexSpawn;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

public final class EnergyBolt extends Projectile implements IEntityWithComplexSpawn {
    private static final EntityDataAccessor<Integer> WEAPON =
            SynchedEntityData.defineId(EnergyBolt.class, EntityDataSerializers.INT);
    private static final int RAIL_PIERCE = 3;

    private final Set<UUID> struck = new HashSet<>();
    private double distance;

    public EnergyBolt(EntityType<? extends EnergyBolt> type, Level level) {
        super(type, level);
        noPhysics = true;
    }

    void launch(Entity owner, Weapon weapon, Vec3 origin, Vec3 direction) {
        setOwner(owner);
        entityData.set(WEAPON, weapon.ordinal());
        setPos(origin);
        setDeltaMovement(direction.scale(weapon.speed));
    }

    public Weapon weapon() {
        return Weapon.byIndex(entityData.get(WEAPON));
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        builder.define(WEAPON, Weapon.PULSE_RIFLE.ordinal());
    }

    @Override
    public void tick() {
        super.tick();
        Vec3 start = position();
        Vec3 velocity = getDeltaMovement();
        Weapon weapon = weapon();
        if (!(level() instanceof ServerLevel server)) {
            Vec3 end = level().clip(new ClipContext(start, start.add(velocity),
                    ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, this)).getLocation();
            trail(start, end, weapon);
            setPos(end);
            return;
        }

        double step = Math.min(velocity.length(), weapon.range - distance);
        if (step <= 0.0001 || tickCount > 80) {
            discard();
            return;
        }
        Vec3 destination = start.add(velocity.normalize().scale(step));
        var block = Ballistics.traceBlock(server, this, start, destination);
        Vec3 end = block.getLocation();
        if (hitEntities(server, weapon, start, end)) return;
        if (block.getType() == HitResult.Type.BLOCK && !EventHooks.onProjectileImpact(this, block)) {
            onHitBlock(block);
            Vec3 normal = Vec3.atLowerCornerOf(block.getDirection().getNormal());
            finish(server, weapon, end.add(normal.scale(0.045)), null);
            return;
        }

        setPos(destination);
        distance += step;
        if (distance >= weapon.range - 0.0001) {
            if (weapon == Weapon.PLASMA_CANNON) Ballistics.detonate(server, this, destination, null);
            discard();
        }
    }

    private boolean hitEntities(ServerLevel level, Weapon weapon, Vec3 start, Vec3 end) {
        Entity owner = getOwner();
        Set<UUID> canceled = new HashSet<>();
        while (true) {
            var hit = Ballistics.traceEntity(level, this, start, end, 0.10,
                    target -> Ballistics.canTarget(level, owner, target)
                            && !struck.contains(target.getUUID()) && !canceled.contains(target.getUUID()));
            if (hit == null) return false;

            Entity target = hit.getEntity();
            if (EventHooks.onProjectileImpact(this, hit)) {
                canceled.add(target.getUUID());
                continue;
            }
            Vec3 point = hit.getLocation();
            if (weapon != Weapon.PLASMA_CANNON) {
                float damage = weapon.damage;
                if (weapon == Weapon.ION_BLASTER) {
                    double traveled = distance + start.distanceTo(point);
                    damage *= (float) (1 - 0.6 * traveled / weapon.range);
                }
                target.hurt(Ballistics.source(level, this, owner), damage);
            }

            if (weapon == Weapon.RAILGUN) {
                struck.add(target.getUUID());
                if (struck.size() < RAIL_PIERCE) {
                    Ballistics.impact(level, weapon, point);
                    continue;
                }
            }
            finish(level, weapon, point, target);
            return true;
        }
    }

    private void finish(ServerLevel level, Weapon weapon, Vec3 point, Entity target) {
        setPos(point);
        if (weapon == Weapon.PLASMA_CANNON) {
            Ballistics.detonate(level, this, point, target);
        } else {
            Ballistics.impact(level, weapon, point);
        }
        discard();
    }

    private void trail(Vec3 start, Vec3 end, Weapon weapon) {
        int count = switch (weapon) {
            case RAILGUN -> 23;
            case PULSE_RIFLE -> 5;
            case PLASMA_CANNON -> 4;
            case ION_BLASTER -> 3;
            case LASER_CARBINE -> 0;
        };
        float size = switch (weapon) {
            case PLASMA_CANNON -> 1.5f;
            case RAILGUN -> 0.85f;
            case PULSE_RIFLE -> 0.65f;
            default -> 0.48f;
        };
        var color = weapon.dust(size);
        for (int step = 0; step < count; step++) {
            Vec3 point = start.lerp(end, (step + 0.5) / count);
            level().addParticle(color, point.x, point.y, point.z, 0, 0, 0);
        }
        if (weapon == Weapon.ION_BLASTER || weapon == Weapon.RAILGUN) {
            level().addParticle(ParticleTypes.ELECTRIC_SPARK, end.x, end.y, end.z, 0.07, 0.03, 0.07);
        } else if (weapon == Weapon.PLASMA_CANNON) {
            level().addParticle(ParticleTypes.SMOKE, start.x, start.y, start.z, 0, 0.025, 0);
        }
    }

    // The vanilla spawn packet caps motion at 3.9 blocks per tick per axis.
    @Override
    public void writeSpawnData(RegistryFriendlyByteBuf buffer) {
        Vec3 velocity = getDeltaMovement();
        buffer.writeDouble(velocity.x);
        buffer.writeDouble(velocity.y);
        buffer.writeDouble(velocity.z);
    }

    @Override
    public void readSpawnData(RegistryFriendlyByteBuf buffer) {
        setDeltaMovement(buffer.readDouble(), buffer.readDouble(), buffer.readDouble());
    }

    @Override
    protected void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        tag.putInt("Weapon", weapon().ordinal());
        tag.putDouble("Distance", distance);
        ListTag hits = new ListTag();
        for (UUID id : struck) hits.add(NbtUtils.createUUID(id));
        tag.put("Struck", hits);
    }

    @Override
    protected void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        entityData.set(WEAPON, Weapon.byIndex(tag.getInt("Weapon")).ordinal());
        distance = Math.max(0, tag.getDouble("Distance"));
        struck.clear();
        for (Tag id : tag.getList("Struck", Tag.TAG_INT_ARRAY)) struck.add(NbtUtils.loadUUID(id));
    }
}
