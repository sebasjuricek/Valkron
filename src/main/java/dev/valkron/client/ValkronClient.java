package dev.valkron.client;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import dev.valkron.GeoGunItem;
import dev.valkron.GunItem;
import dev.valkron.GunNetwork;
import dev.valkron.Valkron;
import dev.valkron.Weapon;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.item.ItemProperties;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.event.RenderGuiEvent;
import net.neoforged.neoforge.client.event.RenderPlayerEvent;
import net.neoforged.neoforge.client.extensions.common.IClientItemExtensions;
import net.neoforged.neoforge.client.extensions.common.RegisterClientExtensionsEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import org.joml.Vector3f;
import org.lwjgl.glfw.GLFW;

import java.util.HashMap;
import java.util.Map;

@EventBusSubscriber(modid = Valkron.ID, value = Dist.CLIENT)
public final class ValkronClient {
    private static final KeyMapping RELOAD = new KeyMapping(
            "key.valkron.reload", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_R, "key.categories.valkron");
    private static final DustParticleOptions BEAM_CORE = new DustParticleOptions(new Vector3f(1, 0.86f, 0.89f), 0.43f);

    private record ObservedAction(Weapon weapon, GunNetwork.Phase phase, int startTick, int endTick) {}

    public record GunAnimationCue(GunNetwork.Phase phase, int startTick) {}

    private static final Map<Integer, ObservedAction> OBSERVED_ACTIONS = new HashMap<>();
    private static Player renderedPlayer;
    private static int clock;
    private static int nextTrigger;
    private static boolean triggerHeld;
    private static boolean triggerPressed;
    private static int heldSlot = -1;
    private static Weapon heldWeapon;
    private static Weapon effectWeapon;
    private static int firedAt = -100;
    private static int reloadAt = -100;
    private static int reloadEnds = -100;

    private ValkronClient() {}

    @SubscribeEvent
    public static void keys(RegisterKeyMappingsEvent event) {
        event.register(RELOAD);
    }

    @SubscribeEvent
    public static void renderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerEntityRenderer(Valkron.BOLT.get(), EnergyBoltRenderer::new);
    }

    @SubscribeEvent
    public static void setup(FMLClientSetupEvent event) {
        event.enqueueWork(() -> {
            for (Weapon weapon : Weapon.values()) {
                GunItem gun = Valkron.kit(weapon).gun().get();
                if (gun instanceof GeoGunItem) continue;
                ItemProperties.register(gun, Valkron.id("action"),
                        (stack, level, entity, seed) -> modelAction(entity, weapon));
            }
        });
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void beginPlayerRender(RenderPlayerEvent.Pre event) {
        renderedPlayer = event.getEntity();
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void endPlayerRender(RenderPlayerEvent.Post event) {
        renderedPlayer = null;
    }

    public static Player renderedGunHolder(ItemStack stack, ItemDisplayContext perspective) {
        if (stack == null || perspective == null) return null;
        Player holder = switch (perspective) {
            case FIRST_PERSON_LEFT_HAND, FIRST_PERSON_RIGHT_HAND -> Minecraft.getInstance().player;
            case THIRD_PERSON_LEFT_HAND, THIRD_PERSON_RIGHT_HAND -> renderedPlayer;
            default -> null;
        };
        if (holder == null || !holder.getMainHandItem().is(stack.getItem())) return null;
        boolean leftHand = perspective == ItemDisplayContext.FIRST_PERSON_LEFT_HAND
                || perspective == ItemDisplayContext.THIRD_PERSON_LEFT_HAND;
        return (holder.getMainArm() == HumanoidArm.LEFT) == leftHand ? holder : null;
    }

    public static GunAnimationCue gunAnimationCue(Player holder, Weapon weapon) {
        if (holder == null) return null;
        if (holder == Minecraft.getInstance().player) {
            if (weapon != effectWeapon) return null;
            if (isReloading(weapon)) return new GunAnimationCue(GunNetwork.Phase.RELOAD, reloadAt);
            return clock - firedAt < weapon.cooldown ? new GunAnimationCue(GunNetwork.Phase.FIRE, firedAt) : null;
        }
        ObservedAction action = OBSERVED_ACTIONS.get(holder.getId());
        return action != null && action.weapon() == weapon && clock < action.endTick()
                ? new GunAnimationCue(action.phase(), action.startTick()) : null;
    }

    @SubscribeEvent
    public static void clientExtensions(RegisterClientExtensionsEvent event) {
        IClientItemExtensions grip = new IClientItemExtensions() {
            @Override
            public HumanoidModel.ArmPose getArmPose(LivingEntity living, InteractionHand hand, ItemStack stack) {
                return hand == InteractionHand.MAIN_HAND ? HumanoidModel.ArmPose.CROSSBOW_HOLD : null;
            }

            @Override
            public boolean applyForgeHandTransform(PoseStack pose, LocalPlayer player, HumanoidArm arm,
                                                   ItemStack stack, float partialTick, float equipProgress,
                                                   float swingProgress) {
                float side = arm == HumanoidArm.RIGHT ? 1 : -1;
                if (player.getMainArm() == arm && stack.getItem() instanceof GunItem gun) {
                    animate(pose, gun.weapon(), side, partialTick);
                }
                pose.translate(side * 0.56, -0.52 - equipProgress * 0.6, -0.72);
                return true;
            }
        };
        for (Weapon weapon : Weapon.values()) event.registerItem(grip, Valkron.kit(weapon).gun().get());
    }

    @SubscribeEvent
    public static void use(InputEvent.InteractionKeyMappingTriggered event) {
        Minecraft client = Minecraft.getInstance();
        if (!event.isUseItem() || client.player == null || client.player.isSpectator()
                || !(client.player.getMainHandItem().getItem() instanceof GunItem gun)) return;
        event.setCanceled(true);
        event.setSwingHand(false);
        synchronizeHeld(client, gun.weapon());
        if (!triggerHeld) {
            triggerHeld = true;
            triggerPressed = true;
        }
    }

    @SubscribeEvent
    public static void tick(ClientTickEvent.Post event) {
        Minecraft client = Minecraft.getInstance();
        if (client.isPaused()) return;
        clock++;
        if (clock % 40 == 0) OBSERVED_ACTIONS.values().removeIf(action -> clock >= action.endTick());

        var player = client.player;
        if (player == null || client.screen != null || !player.isAlive() || player.isSpectator()
                || !(player.getMainHandItem().getItem() instanceof GunItem gun)) {
            triggerHeld = false;
            triggerPressed = false;
            while (RELOAD.consumeClick()) {}
            return;
        }
        synchronizeHeld(client, gun.weapon());
        if (RELOAD.consumeClick()) {
            PacketDistributor.sendToServer(new GunNetwork.Trigger(true));
            triggerPressed = false;
            while (RELOAD.consumeClick()) {}
            return;
        }
        if (triggerPressed || (triggerHeld && client.options.keyUse.isDown() && gun.weapon().automatic)) {
            requestShot(client, gun);
        }
        triggerPressed = false;
        if (!client.options.keyUse.isDown()) {
            triggerHeld = false;
        }
    }

    private static void synchronizeHeld(Minecraft client, Weapon weapon) {
        int slot = client.player.getInventory().selected;
        if (heldSlot != slot || heldWeapon != weapon) {
            heldSlot = slot;
            heldWeapon = weapon;
            triggerHeld = false;
            triggerPressed = false;
            effectWeapon = null;
            firedAt = -100;
            reloadEnds = -100;
        }
    }

    private static void requestShot(Minecraft client, GunItem gun) {
        Weapon weapon = gun.weapon();
        int rounds = gun.rounds(client.player.getMainHandItem());
        if (clock < nextTrigger || (isReloading(weapon) && (weapon != Weapon.ION_BLASTER || rounds == 0))) return;
        nextTrigger = clock + (rounds == 0 && !client.player.getAbilities().instabuild ? 7 : weapon.cooldown);
        PacketDistributor.sendToServer(new GunNetwork.Trigger(false));
    }

    @SubscribeEvent
    public static void logout(ClientPlayerNetworkEvent.LoggingOut event) {
        heldSlot = -1;
        heldWeapon = null;
        effectWeapon = null;
        nextTrigger = 0;
        firedAt = reloadEnds = -100;
        triggerHeld = false;
        triggerPressed = false;
        OBSERVED_ACTIONS.clear();
        renderedPlayer = null;
    }

    public static void accept(GunNetwork.State state) {
        Minecraft client = Minecraft.getInstance();
        if (client.player == null) return;
        if (state.actorId() != client.player.getId()) {
            switch (state.phase()) {
                case FIRE -> {
                    int duration = Valkron.kit(state.weapon()).gun().get() instanceof GeoGunItem
                            ? state.weapon().cooldown : 2;
                    OBSERVED_ACTIONS.put(state.actorId(),
                            new ObservedAction(state.weapon(), state.phase(), clock, clock + duration));
                }
                case RELOAD -> OBSERVED_ACTIONS.put(state.actorId(),
                        new ObservedAction(state.weapon(), state.phase(), clock, clock + state.weapon().reloadTicks));
                case READY -> OBSERVED_ACTIONS.remove(state.actorId());
            }
            return;
        }
        if (!(client.player.getMainHandItem().getItem() instanceof GunItem gun) || gun.weapon() != state.weapon()) return;
        synchronizeHeld(client, state.weapon());
        effectWeapon = state.weapon();
        switch (state.phase()) {
            case FIRE -> {
                firedAt = clock;
                reloadEnds = clock;
            }
            case RELOAD -> {
                reloadAt = clock;
                reloadEnds = clock + state.weapon().reloadTicks;
            }
            case READY -> reloadEnds = clock;
        }
    }

    private static boolean isReloading(Weapon weapon) {
        return effectWeapon == weapon && clock < reloadEnds;
    }

    private static float reloadProgress(float partialTick) {
        return Mth.clamp((clock + partialTick - reloadAt) / (reloadEnds - reloadAt), 0, 1);
    }

    private static float modelAction(LivingEntity entity, Weapon weapon) {
        Minecraft client = Minecraft.getInstance();
        if (entity == null) return 0;
        if (entity != client.player) {
            ObservedAction action = OBSERVED_ACTIONS.get(entity.getId());
            if (action == null || action.weapon() != weapon || clock >= action.endTick()) return 0;
            if (action.phase() == GunNetwork.Phase.FIRE) return 1;
            return openReceiver((clock - action.startTick()) / (float) weapon.reloadTicks);
        }
        if (weapon != effectWeapon) return 0;
        if (isReloading(weapon)) {
            return openReceiver(reloadProgress(0));
        }
        return clock - firedAt < 2 ? 1 : 0;
    }

    private static float openReceiver(float progress) {
        return progress > 0.23f && progress < 0.76f ? 0.5f : 0;
    }

    private static void animate(PoseStack pose, Weapon weapon, float side, float partialTick) {
        if (effectWeapon != weapon) return;
        if (isReloading(weapon)) {
            float progress = reloadProgress(partialTick);
            float tilt = Mth.sin(progress * Mth.PI);
            float seating = Mth.sin(Mth.clamp((progress - 0.62f) / 0.28f, 0, 1) * Mth.PI);
            float roll = switch (weapon) {
                case PULSE_RIFLE -> 24;
                case PLASMA_CANNON -> -13;
                case RAILGUN -> 34;
                case LASER_CARBINE -> -28;
                case ION_BLASTER -> 17;
            };
            pose.translate(side * 0.13f * tilt, -0.16f * tilt + 0.038f * seating, 0.14f * tilt);
            pose.mulPose(Axis.XP.rotationDegrees(20 * tilt - 5 * seating));
            pose.mulPose(Axis.ZP.rotationDegrees(side * roll * tilt));
        } else {
            float age = clock - firedAt + partialTick;
            float recovery = Math.min(weapon.cooldown, 7);
            if (age >= recovery) return;
            float spring = 1 - age / recovery;
            float kick = spring * spring * weapon.recoil;
            pose.translate(0, 0.028f * kick, 0.14f * kick);
            pose.mulPose(Axis.XN.rotationDegrees(6.5f * kick));
            pose.mulPose(Axis.ZP.rotationDegrees(side * 2.5f * kick));
        }
    }

    @SubscribeEvent
    public static void hud(RenderGuiEvent.Post event) {
        Minecraft client = Minecraft.getInstance();
        var player = client.player;
        if (player == null || client.screen != null || client.options.hideGui || player.isSpectator()
                || !(player.getMainHandItem().getItem() instanceof GunItem gun)) return;
        Weapon weapon = gun.weapon();
        var graphics = event.getGuiGraphics();
        int width = graphics.guiWidth();
        int height = graphics.guiHeight();
        int y = height - 39;
        int accent = weapon.color | 0xFF000000;

        boolean loading = isReloading(weapon);
        boolean creative = player.getAbilities().instabuild;
        int rounds = gun.rounds(player.getMainHandItem());
        int reserve = player.getInventory().countItem(Valkron.kit(weapon).ammo().get());
        Component name = Component.translatable("item.valkron." + weapon.id);
        Component counter = loading ? Component.translatable("hud.valkron.reloading")
                : creative ? Component.translatable("hud.valkron.unlimited")
                : Component.translatable("hud.valkron.count", rounds, weapon.magazine, reserve);
        int panelWidth = Math.max(104, Math.max(client.font.width(name), client.font.width(counter)) + 14);
        int x = width - panelWidth - 9;
        graphics.fill(x, y, x + panelWidth, y + 28, 0xD319202A);
        graphics.fill(x, y, x + 2, y + 28, accent);
        graphics.drawString(client.font, name, x + 7, y + 4, accent, false);
        graphics.drawString(client.font, counter, x + 7, y + 15, 0xFFE7EDF3, false);

        float progress = loading ? reloadProgress(event.getPartialTick().getGameTimeDeltaPartialTick(false))
                : creative ? 1 : rounds / (float) weapon.magazine;
        graphics.fill(x + 1, y + 28, x + panelWidth, y + 30, 0xA63F4854);
        graphics.fill(x + 1, y + 28, x + 1 + Math.round((panelWidth - 1) * progress), y + 30, accent);
    }

    public static void drawBeam(GunNetwork.Beam beam) {
        var level = Minecraft.getInstance().level;
        if (level == null) return;
        int steps = Mth.clamp((int) Math.ceil(beam.start().distanceTo(beam.end()) * 3), 1, 240);
        var halo = Weapon.LASER_CARBINE.dust(0.68f);
        for (int index = 0; index <= steps; index++) {
            Vec3 point = beam.start().lerp(beam.end(), (double) index / steps);
            level.addParticle(index % 3 == 0 ? BEAM_CORE : halo, point.x, point.y, point.z, 0, 0, 0);
            if (index % 15 == 0) {
                level.addParticle(ParticleTypes.ELECTRIC_SPARK, point.x, point.y, point.z, 0, 0.035, 0);
            }
        }
    }
}
