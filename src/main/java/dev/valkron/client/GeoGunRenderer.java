package dev.valkron.client;

import dev.valkron.GeoGunItem;
import dev.valkron.Weapon;
import software.bernie.geckolib.renderer.GeoItemRenderer;
import software.bernie.geckolib.renderer.layer.AutoGlowingGeoLayer;

public final class GeoGunRenderer extends GeoItemRenderer<GeoGunItem> {
    public GeoGunRenderer(Weapon weapon) {
        super(new GeoGunModel(weapon));
        addRenderLayer(new AutoGlowingGeoLayer<>(this));
    }

    @Override
    public long getInstanceId(GeoGunItem item) {
        var holder = ValkronClient.renderedGunHolder(currentItemStack, renderPerspective);
        // Inventory and ground items can share a neutral pose. Held poses must be independent.
        return holder == null ? 0 : ((long) holder.getId() << 8) | renderPerspective.ordinal();
    }
}
