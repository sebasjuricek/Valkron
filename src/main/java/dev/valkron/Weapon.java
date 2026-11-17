package dev.valkron;

import net.minecraft.core.particles.DustParticleOptions;
import org.joml.Vector3f;

import java.util.Locale;

public enum Weapon {
    PULSE_RIFLE("pulse_cell", 28, 3, 29, 4.5f, 3.8, 72, 0.018, 0x39BCFF, 0.65f, true),
    PLASMA_CANNON("plasma_capsule", 4, 24, 47, 17.0f, 1.32, 52, 0.012, 0xA6F348, 1.55f, false),
    RAILGUN("rail_slug", 5, 17, 39, 14.0f, 13.5, 108, 0.0015, 0xFFB34D, 1.25f, false),
    LASER_CARBINE("laser_cell", 20, 4, 23, 5.5f, 0.0, 80, 0.001, 0xFF526D, 0.36f, true),
    ION_BLASTER("ion_shell", 6, 13, 14, 2.5f, 2.75, 18, 0.105, 0xB594FF, 1.12f, false);

    private static final Weapon[] ALL = values();

    public final String id = name().toLowerCase(Locale.ROOT);
    public final String ammoId;
    public final int magazine;
    public final int cooldown;
    public final int reloadTicks;
    public final float damage;
    public final double speed;
    public final int range;
    public final double spread;
    public final int color;
    public final float recoil;
    public final boolean automatic;

    Weapon(String ammoId, int magazine, int cooldown, int reloadTicks, float damage, double speed,
           int range, double spread, int color, float recoil, boolean automatic) {
        this.ammoId = ammoId;
        this.magazine = magazine;
        this.cooldown = cooldown;
        this.reloadTicks = reloadTicks;
        this.damage = damage;
        this.speed = speed;
        this.range = range;
        this.spread = spread;
        this.color = color;
        this.recoil = recoil;
        this.automatic = automatic;
    }

    public static Weapon byIndex(int index) {
        return index >= 0 && index < ALL.length ? ALL[index] : PULSE_RIFLE;
    }

    public DustParticleOptions dust(float size) {
        return new DustParticleOptions(new Vector3f(
                (color >> 16 & 255) / 255.0f, (color >> 8 & 255) / 255.0f, (color & 255) / 255.0f), size);
    }
}
