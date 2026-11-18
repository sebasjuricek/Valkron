package dev.valkron;

import com.mojang.serialization.Codec;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.registries.DeferredRegister;

import java.util.EnumMap;
import java.util.function.Supplier;

@Mod(Valkron.ID)
public final class Valkron {
    public static final String ID = "valkron";
    private static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(ID);
    private static final DeferredRegister.DataComponents COMPONENTS =
            DeferredRegister.createDataComponents(Registries.DATA_COMPONENT_TYPE, ID);
    private static final DeferredRegister<EntityType<?>> ENTITIES = DeferredRegister.create(Registries.ENTITY_TYPE, ID);
    private static final DeferredRegister<SoundEvent> SOUNDS = DeferredRegister.create(Registries.SOUND_EVENT, ID);
    private static final DeferredRegister<CreativeModeTab> TABS = DeferredRegister.create(Registries.CREATIVE_MODE_TAB, ID);

    public static final ResourceKey<DamageType> ENERGY = ResourceKey.create(Registries.DAMAGE_TYPE, id("energy"));
    public static final Supplier<DataComponentType<Integer>> ROUNDS = COMPONENTS.registerComponentType(
            "rounds", builder -> builder.persistent(Codec.intRange(0, 64)).networkSynchronized(ByteBufCodecs.VAR_INT));
    public static final Supplier<EntityType<EnergyBolt>> BOLT = ENTITIES.register("energy_bolt",
            () -> EntityType.Builder.<EnergyBolt>of(EnergyBolt::new, MobCategory.MISC)
                    .sized(0.16f, 0.16f).clientTrackingRange(8).updateInterval(1)
                    .noSummon().build(id("energy_bolt").toString()));
    public static final Supplier<SoundEvent> DRY = sound("dry");

    public record Kit(Supplier<GunItem> gun, Supplier<Item> ammo, Supplier<SoundEvent> fire,
                      Supplier<SoundEvent> reload, Supplier<SoundEvent> impact) {}

    private static final EnumMap<Weapon, Kit> KITS = new EnumMap<>(Weapon.class);

    static {
        for (Weapon weapon : Weapon.values()) {
            KITS.put(weapon, new Kit(
                    ITEMS.register(weapon.id, () -> weapon == Weapon.PULSE_RIFLE
                            ? new GeoGunItem(weapon) : new GunItem(weapon)),
                    ITEMS.registerSimpleItem(weapon.ammoId),
                    sound(weapon.id + ".fire"),
                    sound(weapon.id + ".reload"),
                    sound(weapon.id + ".impact")));
        }
        TABS.register("equipment", () -> CreativeModeTab.builder()
                .title(Component.translatable("itemGroup.valkron.equipment"))
                .icon(() -> new ItemStack(kit(Weapon.PULSE_RIFLE).gun().get()))
                .displayItems((parameters, output) -> {
                    for (Weapon weapon : Weapon.values()) {
                        output.accept(kit(weapon).gun().get());
                        output.accept(kit(weapon).ammo().get());
                    }
                }).build());
    }

    public Valkron(IEventBus modBus) {
        ITEMS.register(modBus);
        COMPONENTS.register(modBus);
        ENTITIES.register(modBus);
        SOUNDS.register(modBus);
        TABS.register(modBus);
        modBus.addListener(GunNetwork::register);
        NeoForge.EVENT_BUS.addListener(GunActions::tickPlayer);
    }

    public static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(ID, path);
    }

    public static Kit kit(Weapon weapon) {
        return KITS.get(weapon);
    }

    private static Supplier<SoundEvent> sound(String path) {
        ResourceLocation location = id(path);
        return SOUNDS.register(path, () -> SoundEvent.createVariableRangeEvent(location));
    }
}
