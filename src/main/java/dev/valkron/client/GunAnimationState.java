package dev.valkron.client;

import dev.valkron.GunNetwork;
import dev.valkron.GunItem;
import dev.valkron.Weapon;
import net.minecraft.world.item.ItemStack;
import software.bernie.geckolib.animatable.GeoItem;

/** Client presentation state. Only the server can start a firing or reload clip. */
final class GunAnimationState {
    enum Phase { IDLE, FIRE, RELOAD, INSPECT }

    record Cue(Phase phase, int startTick, int endTick) {
        static final Cue IDLE = new Cue(Phase.IDLE, 0, 0);

        static Cue confirmed(GunNetwork.Phase phase, Weapon weapon, int clock) {
            return switch (phase) {
                case FIRE -> new Cue(Phase.FIRE, clock, clock + Math.min(weapon.cooldown, 8));
                case RELOAD -> new Cue(Phase.RELOAD, clock, clock + weapon.reloadTicks);
                case READY -> IDLE;
            };
        }
    }

    private static final int EQUIPMENT_SYNC_GRACE_TICKS = 5;
    private Cue current = Cue.IDLE;
    private GunNetwork.State confirmed;
    private boolean equipmentMatched;
    private ItemStack inspectedStack = ItemStack.EMPTY;

    Cue at(ItemStack stack, int localSlot, int clock) {
        if (current.phase() == Phase.IDLE) return current;
        if (clock >= current.endTick()) {
            reset();
        } else if (current.phase() == Phase.INSPECT) {
            if (inspectedStack != stack) reset();
        } else if (matches(stack, localSlot)) {
            equipmentMatched = true;
        } else {
            // State packets can arrive before the matching vanilla inventory or equipment update.
            if (equipmentMatched || clock - current.startTick() >= EQUIPMENT_SYNC_GRACE_TICKS) reset();
            return Cue.IDLE;
        }
        return current;
    }

    void confirm(GunNetwork.State state, ItemStack stack, int localSlot, int clock) {
        if (state.phase() == GunNetwork.Phase.READY) {
            if (current.phase() == Phase.RELOAD && confirmed.stackId() == state.stackId()
                    && confirmed.slot() == state.slot() && confirmed.weapon() == state.weapon()) reset();
            return;
        }
        inspectedStack = ItemStack.EMPTY;
        confirmed = state;
        current = Cue.confirmed(state.phase(), state.weapon(), clock);
        equipmentMatched = matches(stack, localSlot);
    }

    void inspect(Weapon weapon, ItemStack stack, int slot, int clock) {
        at(stack, slot, clock);
        if (current.phase() != Phase.IDLE) return;
        int duration = switch (weapon) {
            case PULSE_RIFLE -> 46;
            case PLASMA_CANNON -> 60;
            case RAILGUN -> 54;
            case LASER_CARBINE -> 48;
            case ION_BLASTER -> 56;
        };
        inspectedStack = stack;
        current = new Cue(Phase.INSPECT, clock, clock + duration);
    }

    void interruptInspect() {
        if (current.phase() == Phase.INSPECT) reset();
    }

    void reset() {
        current = Cue.IDLE;
        confirmed = null;
        equipmentMatched = false;
        inspectedStack = ItemStack.EMPTY;
    }

    boolean isIdle() {
        return current.phase() == Phase.IDLE;
    }

    private boolean matches(ItemStack stack, int localSlot) {
        return (localSlot < 0 || confirmed.slot() == localSlot)
                && stack.getItem() instanceof GunItem gun && gun.weapon() == confirmed.weapon()
                && GeoItem.getId(stack) == confirmed.stackId();
    }
}
