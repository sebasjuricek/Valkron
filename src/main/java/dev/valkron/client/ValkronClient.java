package dev.valkron.client;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import dev.valkron.GunItem;
import dev.valkron.GunNetwork;
import dev.valkron.Valkron;
import dev.valkron.Weapon;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.player.LocalPlayer;
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

import software.bernie.geckolib.animatable.GeoItem;

import java.util.IdentityHashMap;
import java.util.Map;

@EventBusSubscriber(modid = Valkron.ID, value = Dist.CLIENT)
public final class ValkronClient {
    private static final KeyMapping RELOAD = new KeyMapping(
            "key.valkron.reload", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_R, "key.categories.valkron");
    private static final KeyMapping INSPECT = new KeyMapping(
            "key.valkron.inspect", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_H, "key.categories.valkron");
    private static final DustParticleOptions BEAM_CORE = new DustParticleOptions(new Vector3f(1, 0.86f, 0.89f), 0.43f);

    private static final GunAnimationState LOCAL_ANIMATION = new GunAnimationState();
    private static final Map<Player, GunAnimationState> OBSERVED_ACTIONS = new IdentityHashMap<>();
    private static Player renderedPlayer;
    private static Player heldPlayer;
    private static int clock;
    private static int nextTrigger;
    private static boolean triggerHeld;
    private static boolean triggerPressed;
    private static int heldSlot = -1;
    private static Weapon heldWeapon;

    private ValkronClient() {}

    @SubscribeEvent
    public static void keys(RegisterKeyMappingsEvent event) {
        event.register(RELOAD);
        event.register(INSPECT);
    }

    @SubscribeEvent
    public static void renderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerEntityRenderer(Valkron.BOLT.get(), EnergyBoltRenderer::new);
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
        if (holder == null || !holder.isAlive() || holder.isSpectator()) return null;
        ItemStack held = holder.getMainHandItem();
        if (stack != held) {
            long id = GeoItem.getId(stack);
            if (!held.is(stack.getItem()) || id == Long.MAX_VALUE || id != GeoItem.getId(held)) return null;
        }
        boolean leftHand = perspective == ItemDisplayContext.FIRST_PERSON_LEFT_HAND
                || perspective == ItemDisplayContext.THIRD_PERSON_LEFT_HAND;
        return (holder.getMainArm() == HumanoidArm.LEFT) == leftHand ? holder : null;
    }

    static GunAnimationState.Cue gunAnimationCue(Player holder, Weapon weapon) {
        if (holder == null || !holder.isAlive() || holder.isSpectator()
                || !(holder.getMainHandItem().getItem() instanceof GunItem gun) || gun.weapon() != weapon) {
            return GunAnimationState.Cue.IDLE;
        }
        if (holder == Minecraft.getInstance().player) return localCue(holder, weapon);
        GunAnimationState observed = OBSERVED_ACTIONS.get(holder);
        return observed == null ? GunAnimationState.Cue.IDLE : observed.at(holder.getMainHandItem(), -1, clock);
    }

    private static GunAnimationState.Cue localCue(Player player, Weapon weapon) {
        if (player == null || player != heldPlayer || weapon != heldWeapon || player.getInventory().selected != heldSlot) {
            return GunAnimationState.Cue.IDLE;
        }
        return LOCAL_ANIMATION.at(player.getMainHandItem(), heldSlot, clock);
    }

    static float gunAnimationAge(GunAnimationState.Cue cue, float partialTick) {
        return Mth.clamp(clock - cue.startTick() + partialTick, 0, cue.endTick() - cue.startTick());
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
                    var cue = gunAnimationCue(renderedGunHolder(stack, arm == HumanoidArm.RIGHT
                            ? ItemDisplayContext.FIRST_PERSON_RIGHT_HAND : ItemDisplayContext.FIRST_PERSON_LEFT_HAND),
                            gun.weapon());
                    applyRecoil(pose, gun.weapon(), cue, side, partialTick);
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
        boolean inspectRequested = consumePress(INSPECT);
        if (client.screen != null) LOCAL_ANIMATION.interruptInspect();
        if (client.isPaused()) return;

        clock++;
        OBSERVED_ACTIONS.entrySet().removeIf(entry -> {
            Player holder = entry.getKey();
            if (holder.isRemoved() || holder.level() != client.level || !holder.isAlive() || holder.isSpectator()) return true;
            GunAnimationState animation = entry.getValue();
            animation.at(holder.getMainHandItem(), -1, clock);
            return animation.isIdle();
        });

        var player = client.player;
        ItemStack held = player == null ? ItemStack.EMPTY : player.getMainHandItem();
        GunItem gun = held.getItem() instanceof GunItem item ? item : null;
        synchronizeHeld(client, gun == null ? null : gun.weapon());
        LOCAL_ANIMATION.at(held, heldSlot, clock);

        boolean reloadRequested = consumePress(RELOAD);
        if (player == null || !player.isAlive() || player.isSpectator()) LOCAL_ANIMATION.reset();
        if (player == null || client.screen != null || !player.isAlive() || player.isSpectator() || gun == null) {
            LOCAL_ANIMATION.interruptInspect();
            triggerHeld = false;
            triggerPressed = false;
            return;
        }
        if (reloadRequested) {
            LOCAL_ANIMATION.interruptInspect();
            PacketDistributor.sendToServer(new GunNetwork.Trigger(true));
            triggerPressed = false;
            return;
        }
        if (triggerPressed || (triggerHeld && client.options.keyUse.isDown() && gun.weapon().automatic)) {
            LOCAL_ANIMATION.interruptInspect();
            requestShot(client, gun);
        } else if (inspectRequested && !client.options.keyUse.isDown()) {
            LOCAL_ANIMATION.inspect(gun.weapon(), held, heldSlot, clock);
        }
        triggerPressed = false;
        if (!client.options.keyUse.isDown()) triggerHeld = false;
    }

    private static boolean consumePress(KeyMapping key) {
        boolean pressed = key.consumeClick();
        while (key.consumeClick()) {}
        return pressed;
    }

    private static void synchronizeHeld(Minecraft client, Weapon weapon) {
        int slot = client.player == null ? -1 : client.player.getInventory().selected;
        if (heldPlayer != client.player || heldSlot != slot || heldWeapon != weapon) {
            heldPlayer = client.player;
            heldSlot = slot;
            heldWeapon = weapon;
            triggerHeld = false;
            triggerPressed = false;
            LOCAL_ANIMATION.reset();
        }
    }

    private static void requestShot(Minecraft client, GunItem gun) {
        Weapon weapon = gun.weapon();
        int rounds = gun.rounds(client.player.getMainHandItem());
        boolean reloading = localCue(client.player, weapon).phase() == GunAnimationState.Phase.RELOAD;
        if (clock < nextTrigger || (reloading && (weapon != Weapon.ION_BLASTER || rounds == 0))) return;
        nextTrigger = clock + (rounds == 0 && !client.player.getAbilities().instabuild ? 7 : weapon.cooldown);
        PacketDistributor.sendToServer(new GunNetwork.Trigger(false));
    }

    @SubscribeEvent
    public static void logout(ClientPlayerNetworkEvent.LoggingOut event) {
        heldPlayer = null;
        heldSlot = -1;
        heldWeapon = null;
        nextTrigger = 0;
        triggerHeld = false;
        triggerPressed = false;
        OBSERVED_ACTIONS.clear();
        renderedPlayer = null;
        LOCAL_ANIMATION.reset();
    }

    public static void accept(GunNetwork.State state) {
        Minecraft client = Minecraft.getInstance();
        if (client.player == null || client.level == null) return;
        if (state.actorId() == client.player.getId()) {
            var player = client.player;
            var stack = player.getMainHandItem();
            Weapon weapon = stack.getItem() instanceof GunItem gun ? gun.weapon() : null;
            synchronizeHeld(client, weapon);
            if (heldSlot == state.slot() && weapon == state.weapon() && player.isAlive() && !player.isSpectator()) {
                LOCAL_ANIMATION.confirm(state, stack, heldSlot, clock);
            }
            return;
        }
        if (client.level.getEntity(state.actorId()) instanceof Player holder && holder.isAlive() && !holder.isSpectator()) {
            GunAnimationState observed = state.phase() == GunNetwork.Phase.READY ? OBSERVED_ACTIONS.get(holder)
                    : OBSERVED_ACTIONS.computeIfAbsent(holder, ignored -> new GunAnimationState());
            if (observed != null) observed.confirm(state, holder.getMainHandItem(), -1, clock);
        }
    }

    private static void applyRecoil(PoseStack pose, Weapon weapon, GunAnimationState.Cue cue, float side, float partialTick) {
        if (cue.phase() != GunAnimationState.Phase.FIRE) return;
        float recovery = Math.min(weapon.cooldown, 7);
        float age = gunAnimationAge(cue, partialTick);
        if (age >= recovery) return;
        float spring = 1 - age / recovery;
        float kick = spring * spring * weapon.recoil;
        pose.translate(0, 0.028f * kick, 0.14f * kick);
        pose.mulPose(Axis.XN.rotationDegrees(6.5f * kick));
        pose.mulPose(Axis.ZP.rotationDegrees(side * 2.5f * kick));
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

        var cue = localCue(player, weapon);
        boolean loading = cue.phase() == GunAnimationState.Phase.RELOAD;
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

        float progress = loading ? gunAnimationAge(cue, event.getPartialTick().getGameTimeDeltaPartialTick(false))
                / (cue.endTick() - cue.startTick())
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
