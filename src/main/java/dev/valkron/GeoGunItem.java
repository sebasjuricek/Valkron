package dev.valkron;

import dev.valkron.client.GeoGunRenderer;
import dev.valkron.client.ValkronClient;
import net.minecraft.client.renderer.BlockEntityWithoutLevelRenderer;
import software.bernie.geckolib.animatable.GeoItem;
import software.bernie.geckolib.animatable.client.GeoRenderProvider;
import software.bernie.geckolib.animatable.instance.AnimatableInstanceCache;
import software.bernie.geckolib.animation.AnimatableManager;
import software.bernie.geckolib.animation.AnimationController;
import software.bernie.geckolib.animation.RawAnimation;
import software.bernie.geckolib.constant.DataTickets;
import software.bernie.geckolib.util.GeckoLibUtil;

import java.util.Objects;
import java.util.function.Consumer;

public final class GeoGunItem extends GunItem implements GeoItem {
    private final AnimatableInstanceCache cache = GeckoLibUtil.createInstanceCache(this);
    private final RawAnimation idle;
    private final RawAnimation fire;
    private final RawAnimation reload;

    public GeoGunItem(Weapon weapon) {
        super(weapon);
        String prefix = "animation." + weapon.id + ".";
        idle = RawAnimation.begin().thenLoop(prefix + "idle");
        fire = RawAnimation.begin().thenPlayAndHold(prefix + "fire");
        reload = RawAnimation.begin().thenPlayAndHold(prefix + "reload");
    }

    @Override
    public void registerControllers(AnimatableManager.ControllerRegistrar controllers) {
        var previous = new Object() {
            ValkronClient.GunAnimationCue cue;
        };
        controllers.add(new AnimationController<>(this, "mechanism", 0, state -> {
            var holder = ValkronClient.renderedGunHolder(state.getData(DataTickets.ITEMSTACK),
                    state.getData(DataTickets.ITEM_RENDER_PERSPECTIVE));
            var cue = ValkronClient.gunAnimationCue(holder, weapon());
            if (!Objects.equals(previous.cue, cue)) {
                // Consecutive confirmed shots can select the same clip without passing through idle.
                state.resetCurrentAnimation();
                previous.cue = cue;
            }
            return state.setAndContinue(cue == null ? idle : cue.phase() == GunNetwork.Phase.FIRE ? fire : reload);
        }));
    }

    @Override
    public AnimatableInstanceCache getAnimatableInstanceCache() {
        return cache;
    }

    @Override
    public void createGeoRenderer(Consumer<GeoRenderProvider> consumer) {
        consumer.accept(new GeoRenderProvider() {
            private GeoGunRenderer renderer;

            @Override
            public BlockEntityWithoutLevelRenderer getGeoItemRenderer() {
                if (renderer == null) renderer = new GeoGunRenderer(weapon());
                return renderer;
            }
        });
    }
}
