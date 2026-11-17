package dev.valkron;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;

import java.util.List;

public final class GunItem extends Item {
    private final Weapon weapon;

    public GunItem(Weapon weapon) {
        super(new Item.Properties().stacksTo(1).component(Valkron.ROUNDS.get(), 0));
        this.weapon = weapon;
    }

    public Weapon weapon() {
        return weapon;
    }

    public int rounds(ItemStack stack) {
        return Mth.clamp(stack.getOrDefault(Valkron.ROUNDS.get(), 0), 0, weapon.magazine);
    }

    @Override
    public boolean isBarVisible(ItemStack stack) {
        return true;
    }

    @Override
    public int getBarWidth(ItemStack stack) {
        return Math.round(13.0f * rounds(stack) / weapon.magazine);
    }

    @Override
    public int getBarColor(ItemStack stack) {
        return weapon.color;
    }

    @Override
    public boolean shouldCauseReequipAnimation(ItemStack oldStack, ItemStack newStack, boolean slotChanged) {
        return slotChanged || !oldStack.is(newStack.getItem());
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
        tooltip.add(Component.translatable("tooltip.valkron." + weapon.id).withStyle(ChatFormatting.AQUA));
        tooltip.add(Component.translatable("tooltip.valkron.magazine", rounds(stack), weapon.magazine,
                Valkron.kit(weapon).ammo().get().getDescription()).withStyle(ChatFormatting.GRAY));
        tooltip.add(Component.translatable("tooltip.valkron.controls").withStyle(ChatFormatting.DARK_GRAY));
    }
}
