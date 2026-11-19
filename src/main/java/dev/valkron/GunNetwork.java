package dev.valkron;

import dev.valkron.client.ValkronClient;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import software.bernie.geckolib.animatable.GeoItem;

public final class GunNetwork {
    public enum Phase { FIRE, RELOAD, READY }

    private GunNetwork() {}

    static void register(RegisterPayloadHandlersEvent event) {
        var registrar = event.registrar("2");
        registrar.playToServer(Trigger.TYPE, Trigger.CODEC, (payload, context) -> {
            if (context.player() instanceof ServerPlayer player) GunActions.trigger(player, payload.reload());
        });
        registrar.playToClient(State.TYPE, State.CODEC, (payload, context) -> ValkronClient.accept(payload));
        registrar.playToClient(Beam.TYPE, Beam.CODEC, (payload, context) -> ValkronClient.drawBeam(payload));
    }

    static void sendState(ServerPlayer player, Phase phase, Weapon weapon, ItemStack stack, int slot) {
        long stackId = GeoItem.getOrAssignId(stack, player.serverLevel());
        PacketDistributor.sendToPlayersTrackingEntityAndSelf(player, new State(player.getId(), phase, weapon, slot, stackId));
    }

    static void sendBeam(ServerLevel level, Vec3 start, Vec3 end) {
        Vec3 center = start.lerp(end, 0.5);
        PacketDistributor.sendToPlayersNear(level, null, center.x, center.y, center.z,
                70 + start.distanceTo(end) * 0.5, new Beam(start, end));
    }

    public record Trigger(boolean reload) implements CustomPacketPayload {
        public static final Type<Trigger> TYPE = new Type<>(Valkron.id("trigger"));
        static final StreamCodec<FriendlyByteBuf, Trigger> CODEC = StreamCodec.composite(
                ByteBufCodecs.BOOL, Trigger::reload, Trigger::new);

        @Override
        public Type<Trigger> type() {
            return TYPE;
        }
    }

    public record State(int actorId, Phase phase, Weapon weapon, int slot, long stackId) implements CustomPacketPayload {
        static final Type<State> TYPE = new Type<>(Valkron.id("gun_state"));
        static final StreamCodec<FriendlyByteBuf, State> CODEC = StreamCodec.of(
                (buffer, payload) -> {
                    buffer.writeVarInt(payload.actorId);
                    buffer.writeEnum(payload.phase);
                    buffer.writeEnum(payload.weapon);
                    buffer.writeVarInt(payload.slot);
                    buffer.writeVarLong(payload.stackId);
                },
                buffer -> new State(buffer.readVarInt(), buffer.readEnum(Phase.class), buffer.readEnum(Weapon.class),
                        buffer.readVarInt(), buffer.readVarLong()));

        @Override
        public Type<State> type() {
            return TYPE;
        }
    }

    public record Beam(Vec3 start, Vec3 end) implements CustomPacketPayload {
        static final Type<Beam> TYPE = new Type<>(Valkron.id("beam"));
        static final StreamCodec<FriendlyByteBuf, Beam> CODEC = StreamCodec.of(
                (buffer, payload) -> {
                    writePosition(buffer, payload.start);
                    writePosition(buffer, payload.end);
                }, buffer -> new Beam(readPosition(buffer), readPosition(buffer)));

        private static void writePosition(FriendlyByteBuf buffer, Vec3 position) {
            buffer.writeDouble(position.x);
            buffer.writeDouble(position.y);
            buffer.writeDouble(position.z);
        }

        private static Vec3 readPosition(FriendlyByteBuf buffer) {
            return new Vec3(buffer.readDouble(), buffer.readDouble(), buffer.readDouble());
        }

        @Override
        public Type<Beam> type() {
            return TYPE;
        }
    }
}
