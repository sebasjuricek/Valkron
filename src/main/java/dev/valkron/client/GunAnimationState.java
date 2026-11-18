package dev.valkron.client;

import dev.valkron.GunNetwork;
import dev.valkron.Weapon;

/** Client presentation state. Only the server can start a firing or reload clip. */
final class GunAnimationState {
    enum Phase { IDLE, FIRE, RELOAD, INSPECT }

    record Cue(Phase phase, int startTick, int endTick) {
        static final Cue IDLE = new Cue(Phase.IDLE, 0, 0);

        Cue at(int clock) {
            return clock < endTick ? this : IDLE;
        }

        static Cue confirmed(GunNetwork.Phase phase, Weapon weapon, int clock) {
            return switch (phase) {
                case FIRE -> new Cue(Phase.FIRE, clock, clock + weapon.cooldown);
                case RELOAD -> new Cue(Phase.RELOAD, clock, clock + weapon.reloadTicks);
                case READY -> IDLE;
            };
        }
    }

    private static final int INSPECT_TICKS = 46;
    private Cue current = Cue.IDLE;

    Cue at(int clock) {
        return current.at(clock);
    }

    void confirm(GunNetwork.Phase phase, Weapon weapon, int clock) {
        if (phase != GunNetwork.Phase.READY || current.phase() == Phase.RELOAD) {
            current = Cue.confirmed(phase, weapon, clock);
        }
    }

    boolean inspect(int clock) {
        if (at(clock).phase() != Phase.IDLE) return false;
        current = new Cue(Phase.INSPECT, clock, clock + INSPECT_TICKS);
        return true;
    }

    void interruptInspect() {
        if (current.phase() == Phase.INSPECT) reset();
    }

    void reset() {
        current = Cue.IDLE;
    }
}
