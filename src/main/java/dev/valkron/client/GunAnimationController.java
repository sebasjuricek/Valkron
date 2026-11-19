package dev.valkron.client;

import dev.valkron.GeoGunItem;
import software.bernie.geckolib.animation.AnimationController;
import software.bernie.geckolib.animation.AnimationState;
import software.bernie.geckolib.animation.PlayState;
import software.bernie.geckolib.animation.RawAnimation;
import software.bernie.geckolib.constant.DataTickets;

/** Samples action clips at the client cue's age, including when a new viewpoint starts rendering mid-action. */
public final class GunAnimationController extends AnimationController<GeoGunItem> {
    private final RawAnimation idle;
    private final RawAnimation fire;
    private final RawAnimation reload;
    private final RawAnimation inspect;
    private GunAnimationState.Cue cue = GunAnimationState.Cue.IDLE;
    private float partialTick;

    public GunAnimationController(GeoGunItem gun) {
        super(gun, "mechanism", 0, state -> PlayState.CONTINUE);
        String prefix = "animation." + gun.weapon().id + ".";
        idle = RawAnimation.begin().thenLoop(prefix + "idle");
        fire = RawAnimation.begin().thenPlayAndHold(prefix + "fire");
        reload = RawAnimation.begin().thenPlayAndHold(prefix + "reload");
        inspect = RawAnimation.begin().thenPlayAndHold(prefix + "inspect");
    }

    @Override
    protected PlayState handleAnimationState(AnimationState<GeoGunItem> state) {
        var holder = ValkronClient.renderedGunHolder(state.getData(DataTickets.ITEMSTACK),
                state.getData(DataTickets.ITEM_RENDER_PERSPECTIVE));
        var next = ValkronClient.gunAnimationCue(holder, state.getAnimatable().weapon());
        partialTick = state.getPartialTick();
        if (cue != next) {
            // Automatic fire can issue another confirmed shot without an intervening idle tick.
            forceAnimationReset();
            cue = next;
        }
        setAnimation(switch (cue.phase()) {
            case IDLE -> idle;
            case FIRE -> fire;
            case RELOAD -> reload;
            case INSPECT -> inspect;
        });
        return PlayState.CONTINUE;
    }

    @Override
    protected double adjustTick(double tick) {
        double elapsed = super.adjustTick(tick);
        // GeckoLib still handles transition setup and idle looping on its own clock.
        return getAnimationState() == State.RUNNING && cue.phase() != GunAnimationState.Phase.IDLE
                ? ValkronClient.gunAnimationAge(cue, partialTick) : elapsed;
    }
}
