package dev.valkron.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.valkron.EnergyBolt;
import dev.valkron.Valkron;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;

final class EnergyBoltRenderer extends EntityRenderer<EnergyBolt> {
    private static final ResourceLocation TEXTURE = Valkron.id("textures/entity/bolt.png");

    EnergyBoltRenderer(EntityRendererProvider.Context context) {
        super(context);
    }

    @Override
    public void render(EnergyBolt bolt, float yaw, float partialTick, PoseStack pose,
                       MultiBufferSource buffers, int packedLight) {
        float size = switch (bolt.weapon()) {
            case PLASMA_CANNON -> 0.48f;
            case RAILGUN -> 0.24f;
            case ION_BLASTER -> 0.105f;
            default -> 0.17f;
        };
        pose.pushPose();
        pose.mulPose(entityRenderDispatcher.cameraOrientation());
        var vertices = buffers.getBuffer(RenderType.entityTranslucent(TEXTURE));
        var frame = pose.last();
        quad(vertices, frame, size, bolt.weapon().color, 223);
        quad(vertices, frame, size * 0.52f, 0xFFFFFF, 255);
        pose.popPose();
    }

    private static void quad(VertexConsumer vertices, PoseStack.Pose pose, float scale, int color, int alpha) {
        vertex(vertices, pose, -scale, -scale, 0, 1, color, alpha);
        vertex(vertices, pose, scale, -scale, 1, 1, color, alpha);
        vertex(vertices, pose, scale, scale, 1, 0, color, alpha);
        vertex(vertices, pose, -scale, scale, 0, 0, color, alpha);
    }

    private static void vertex(VertexConsumer vertices, PoseStack.Pose pose, float x, float y,
                               float u, float v, int color, int alpha) {
        vertices.addVertex(pose, x, y, 0).setColor(color >> 16 & 255, color >> 8 & 255, color & 255, alpha)
                .setUv(u, v).setOverlay(OverlayTexture.NO_OVERLAY).setLight(LightTexture.FULL_BRIGHT)
                .setNormal(pose, 0, 0, 1);
    }

    @Override
    public ResourceLocation getTextureLocation(EnergyBolt bolt) {
        return TEXTURE;
    }
}
