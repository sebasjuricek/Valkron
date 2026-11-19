package dev.valkron;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;

import java.util.Map;
import java.util.WeakHashMap;

final class GunActions {
    private static final Map<ServerPlayer, State> STATES = new WeakHashMap<>();

    private record Reload(ItemStack stack, int slot, long endsAt) {}

    private static final class State {
        long nextShot;
        long nextDryClick;
        long lastTrigger = -1;
        Reload reload;
    }

    private GunActions() {}

    static void trigger(ServerPlayer player, boolean wantsReload) {
        ItemStack stack = player.getMainHandItem();
        if (!player.isAlive() || player.isSpectator() || player.containerMenu != player.inventoryMenu
                || !(stack.getItem() instanceof GunItem gun)) {
            return;
        }

        long now = player.serverLevel().getGameTime();
        State state = STATES.computeIfAbsent(player, ignored -> new State());
        if (state.lastTrigger == now) return;
        state.lastTrigger = now;
        updateReload(player, state, now);

        int rounds = gun.rounds(stack);
        Weapon weapon = gun.weapon();
        if (state.reload != null) {
            if (wantsReload || weapon != Weapon.ION_BLASTER || rounds == 0 || now < state.nextShot) return;
            state.reload = null;
        }
        if (wantsReload || (rounds == 0 && !player.getAbilities().instabuild)) {
            beginReload(player, stack, gun, rounds, state, now);
            return;
        }
        if (now < state.nextShot) return;

        state.nextShot = now + weapon.cooldown;
        if (!player.getAbilities().instabuild) {
            stack.set(Valkron.ROUNDS.get(), rounds - 1);
        }
        Ballistics.fire(player, weapon);
        player.swing(InteractionHand.MAIN_HAND, true);
        GunNetwork.sendState(player, GunNetwork.Phase.FIRE, weapon, stack, player.getInventory().selected);
    }

    private static void beginReload(ServerPlayer player, ItemStack stack, GunItem gun,
                                    int rounds, State state, long now) {
        Weapon weapon = gun.weapon();
        if (rounds == weapon.magazine || now < state.nextDryClick) return;
        if (!player.getAbilities().instabuild && reserve(player.getInventory(), weapon) == 0) {
            state.nextDryClick = now + 15;
            player.playNotifySound(Valkron.DRY.get(), SoundSource.PLAYERS, 0.7f, 1.0f);
            player.displayClientMessage(Component.translatable(
                    "hud.valkron.no_ammo", Valkron.kit(weapon).ammo().get().getDescription()), true);
            return;
        }

        scheduleReload(player, stack, weapon, state, now);
    }

    private static void scheduleReload(ServerPlayer player, ItemStack stack, Weapon weapon, State state, long now) {
        state.reload = new Reload(stack, player.getInventory().selected, now + weapon.reloadTicks);
        player.serverLevel().playSound(null, player, Valkron.kit(weapon).reload().get(),
                SoundSource.PLAYERS, 0.68f, 1.0f);
        GunNetwork.sendState(player, GunNetwork.Phase.RELOAD, weapon, stack, state.reload.slot());
    }

    static void tickPlayer(PlayerTickEvent.Post event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            State state = STATES.get(player);
            if (state != null && state.reload != null) updateReload(player, state, player.serverLevel().getGameTime());
        }
    }

    private static void updateReload(ServerPlayer player, State state, long now) {
        Reload reload = state.reload;
        if (reload == null) return;
        GunItem gun = (GunItem) reload.stack().getItem();
        Weapon weapon = gun.weapon();
        if (!player.isAlive() || player.isSpectator() || player.getInventory().selected != reload.slot()
                || player.getMainHandItem() != reload.stack() || player.containerMenu != player.inventoryMenu) {
            state.reload = null;
            GunNetwork.sendState(player, GunNetwork.Phase.READY, weapon, reload.stack(), reload.slot());
            return;
        }
        if (now < reload.endsAt()) return;

        ItemStack gunStack = reload.stack();
        int rounds = gun.rounds(gunStack);
        int added = weapon.magazine - rounds;
        if (!player.getAbilities().instabuild) {
            if (weapon == Weapon.ION_BLASTER) added = Math.min(1, added);
            added = consume(player.getInventory(), weapon, added);
        }
        int loaded = rounds + added;
        gunStack.set(Valkron.ROUNDS.get(), loaded);
        if (weapon == Weapon.ION_BLASTER && added > 0 && loaded < weapon.magazine
                && reserve(player.getInventory(), weapon) > 0) {
            scheduleReload(player, gunStack, weapon, state, now);
            return;
        }
        state.reload = null;
        GunNetwork.sendState(player, GunNetwork.Phase.READY, weapon, reload.stack(), reload.slot());
    }

    private static int reserve(Inventory inventory, Weapon weapon) {
        Item ammo = Valkron.kit(weapon).ammo().get();
        int count = 0;
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (stack.is(ammo)) count += stack.getCount();
        }
        return count;
    }

    private static int consume(Inventory inventory, Weapon weapon, int requested) {
        Item ammo = Valkron.kit(weapon).ammo().get();
        int remaining = requested;
        for (int slot = 0; remaining > 0 && slot < inventory.getContainerSize(); slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (!stack.is(ammo)) continue;
            int taken = Math.min(remaining, stack.getCount());
            stack.shrink(taken);
            if (stack.isEmpty()) inventory.setItem(slot, ItemStack.EMPTY);
            remaining -= taken;
        }
        inventory.setChanged();
        return requested - remaining;
    }
}
