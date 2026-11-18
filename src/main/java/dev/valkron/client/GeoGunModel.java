package dev.valkron.client;

import dev.valkron.GeoGunItem;
import dev.valkron.Valkron;
import dev.valkron.Weapon;
import net.minecraft.resources.ResourceLocation;
import software.bernie.geckolib.model.GeoModel;

final class GeoGunModel extends GeoModel<GeoGunItem> {
    private final ResourceLocation geometry;
    private final ResourceLocation texture;
    private final ResourceLocation animations;

    GeoGunModel(Weapon weapon) {
        geometry = Valkron.id("geo/" + weapon.id + ".geo.json");
        texture = Valkron.id("textures/geo/" + weapon.id + ".png");
        animations = Valkron.id("animations/" + weapon.id + ".animation.json");
    }

    @Override
    public ResourceLocation getModelResource(GeoGunItem item) {
        return geometry;
    }

    @Override
    public ResourceLocation getTextureResource(GeoGunItem item) {
        return texture;
    }

    @Override
    public ResourceLocation getAnimationResource(GeoGunItem item) {
        return animations;
    }
}
