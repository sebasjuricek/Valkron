package dev.valkron;

import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.function.Predicate;

final class Ballistics {
    static final int ION_PELLETS = 7;
    private static final double PLASMA_RADIUS = 3.6;

    private Ballistics() {}

    static void fire(ServerPlayer player, Weapon weapon) {
        ServerLevel level = player.serverLevel();
        Vec3 sight = player.getLookAngle();
        Vec3 eye = player.getEyePosition();
        Vec3 right = Vec3.directionFromRotation(0.0f, player.getYRot() + 90.0f);
        Vec3 up = right.cross(sight).normalize();
        double side = player.getMainArm() == HumanoidArm.RIGHT ? 1 : -1;
        Vec3 muzzle = eye.add(sight.scale(1.25)).add(right.scale(0.28 * side)).subtract(up.scale(0.24));
        if (traceBlock(level, player, eye, muzzle).getType() != HitResult.Type.MISS) muzzle = eye;
        level.playSound(null, player, Valkron.kit(weapon).fire().get(), SoundSource.PLAYERS,
                weapon == Weapon.PLASMA_CANNON || weapon == Weapon.RAILGUN ? 1.25f : 0.82f,
                0.96f + player.getRandom().nextFloat() * 0.08f);
        flash(level, weapon, muzzle, sight);

        double spread = weapon.spread * (player.isCrouching() ? 0.52 : 1.0);
        RandomSource random = player.getRandom();
        if (weapon == Weapon.ION_BLASTER) {
            spawn(level, player, weapon, eye, sight);
            double spin = random.nextDouble() * Math.PI * 2;
            for (int pellet = 0; pellet < ION_PELLETS - 1; pellet++) {
                double angle = spin + pellet * Math.TAU / (ION_PELLETS - 1);
                double radius = spread * (0.68 + random.nextDouble() * 0.32);
                Vec3 angleOffset = right.scale(Math.cos(angle) * radius).add(up.scale(Math.sin(angle) * radius));
                spawn(level, player, weapon, eye, sight.add(angleOffset).normalize());
            }
            return;
        }

        Vec3 direction = sight.add(right.scale(random.nextGaussian() * spread))
                .add(up.scale(random.nextGaussian() * spread)).normalize();
        if (weapon == Weapon.LASER_CARBINE) {
            laser(level, player, muzzle, eye, direction);
        } else {
            spawn(level, player, weapon, eye, direction);
        }
    }

    private static void spawn(ServerLevel level, ServerPlayer player, Weapon weapon, Vec3 origin, Vec3 direction) {
        EnergyBolt bolt = new EnergyBolt(Valkron.BOLT.get(), level);
        bolt.launch(player, weapon, origin, direction);
        level.addFreshEntity(bolt);
    }

    private static void flash(ServerLevel level, Weapon weapon, Vec3 muzzle, Vec3 sight) {
        float scale = 0.6f + 0.48f * weapon.recoil;
        level.sendParticles(weapon.dust(scale), muzzle.x, muzzle.y, muzzle.z, 7, 0.08, 0.08, 0.08, 0.015);
        level.sendParticles(ParticleTypes.ELECTRIC_SPARK, muzzle.x, muzzle.y, muzzle.z,
                0, sight.x, sight.y, sight.z, 0.28);
        if (weapon == Weapon.PLASMA_CANNON || weapon == Weapon.RAILGUN) {
            level.sendParticles(ParticleTypes.FLASH, muzzle.x, muzzle.y, muzzle.z, 1, 0, 0, 0, 0);
        }
    }

    private static void laser(ServerLevel level, ServerPlayer player, Vec3 muzzle, Vec3 eye, Vec3 direction) {
        Weapon weapon = Weapon.LASER_CARBINE;
        BlockHitResult block = traceBlock(level, player, eye, eye.add(direction.scale(weapon.range)));
        Vec3 end = block.getLocation();
        var hit = traceEntity(level, player, eye, end, 0.04, target -> canTarget(level, player, target));
        if (hit != null) {
            hit.getEntity().hurt(source(level, player, player), weapon.damage);
            end = hit.getLocation();
        }
        GunNetwork.sendBeam(level, muzzle, end);
        if (hit != null || block.getType() != HitResult.Type.MISS) impact(level, weapon, end);
    }

    static BlockHitResult traceBlock(ServerLevel level, Entity context, Vec3 from, Vec3 to) {
        return level.clip(new ClipContext(from, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, context));
    }

    @Nullable
    static EntityHitResult traceEntity(ServerLevel level, Entity context, Vec3 from, Vec3 to,
                                       double margin, Predicate<Entity> filter) {
        EntityHitResult closest = null;
        double closestDistance = Double.POSITIVE_INFINITY;
        for (Entity target : level.getEntities(context, new AABB(from, to).inflate(margin), filter)) {
            AABB hitbox = target.getBoundingBox().inflate(margin);
            Vec3 point = hitbox.contains(from) ? from : hitbox.clip(from, to).orElse(null);
            if (point != null && from.distanceToSqr(point) < closestDistance) {
                closestDistance = from.distanceToSqr(point);
                closest = new EntityHitResult(target, point);
            }
        }
        return closest;
    }

    static boolean canTarget(ServerLevel level, Entity owner, Entity target) {
        if (target == owner || !target.isAlive() || target.isSpectator() || !target.isPickable()
                || owner != null && owner.isPassengerOfSameVehicle(target)) return false;
        return !(target instanceof Player defender && owner instanceof Player attacker)
                || level.getServer().isPvpAllowed() && attacker.canHarmPlayer(defender);
    }

    static DamageSource source(ServerLevel level, Entity immediate, Entity owner) {
        return new DamageSource(level.registryAccess().registryOrThrow(Registries.DAMAGE_TYPE)
                .getHolderOrThrow(Valkron.ENERGY), immediate, owner);
    }

    static void impact(ServerLevel level, Weapon weapon, Vec3 point) {
        var particles = switch (weapon) {
            case PULSE_RIFLE -> ParticleTypes.END_ROD;
            case PLASMA_CANNON -> ParticleTypes.SMOKE;
            case RAILGUN -> ParticleTypes.CRIT;
            case LASER_CARBINE, ION_BLASTER -> ParticleTypes.ELECTRIC_SPARK;
        };
        level.sendParticles(weapon.dust(0.88f), point.x, point.y, point.z,
                weapon == Weapon.ION_BLASTER ? 4 : 10, 0.105, 0.105, 0.105, 0.12);
        level.sendParticles(particles, point.x, point.y, point.z, 5, 0.09, 0.09, 0.09, 0.17);
        level.playSound(null, point.x, point.y, point.z, Valkron.kit(weapon).impact().get(),
                SoundSource.PLAYERS, weapon == Weapon.ION_BLASTER ? 0.22f : 0.47f,
                0.9f + level.random.nextFloat() * 0.2f);
    }

    static void detonate(ServerLevel level, EnergyBolt bolt, Vec3 center, Entity directHit) {
        Weapon weapon = Weapon.PLASMA_CANNON;
        Entity owner = bolt.getOwner();
        DamageSource source = source(level, bolt, owner);
        if (directHit != null) directHit.hurt(source, weapon.damage);
        for (LivingEntity target : level.getEntitiesOfClass(LivingEntity.class,
                new AABB(center, center).inflate(PLASMA_RADIUS),
                entity -> entity != directHit && canTarget(level, owner, entity))) {
            Vec3 targetCenter = target.getBoundingBox().getCenter();
            double distance = targetCenter.distanceTo(center);
            if (distance >= PLASMA_RADIUS || traceBlock(level, bolt, center, targetCenter).getType() != HitResult.Type.MISS) {
                continue;
            }
            double falloff = 1 - distance / PLASMA_RADIUS;
            if (target.hurt(source, weapon.damage * (float) falloff * 0.72f)) {
                target.knockback(0.95 * falloff, center.x - target.getX(), center.z - target.getZ());
            }
        }

        level.sendParticles(ParticleTypes.EXPLOSION, center.x, center.y, center.z, 1, 0, 0, 0, 0);
        level.sendParticles(ParticleTypes.CAMPFIRE_COSY_SMOKE, center.x, center.y, center.z,
                9, 0.35, 0.34, 0.35, 0.08);
        level.sendParticles(weapon.dust(1.5f), center.x, center.y, center.z, 32, 0.65, 0.58, 0.65, 0.09);
        for (int segment = 0; segment < 16; segment++) {
            double angle = segment * Math.TAU / 16;
            level.sendParticles(ParticleTypes.ELECTRIC_SPARK, center.x, center.y, center.z,
                    0, Math.cos(angle), 0.10, Math.sin(angle), 0.44);
        }
        level.playSound(null, center.x, center.y, center.z, Valkron.kit(weapon).impact().get(),
                SoundSource.PLAYERS, 1.35f, 1.0f);
    }
}
