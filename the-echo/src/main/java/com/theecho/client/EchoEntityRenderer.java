package com.theecho.client;

import com.theecho.entity.EchoEntity;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.PlayerListEntry;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.entity.EntityRendererFactory;
import net.minecraft.client.render.entity.LivingEntityRenderer;
import net.minecraft.client.render.entity.feature.ArmorFeatureRenderer;
import net.minecraft.client.render.entity.feature.HeldItemFeatureRenderer;
import net.minecraft.client.render.entity.model.BipedEntityModel;
import net.minecraft.client.render.entity.model.EntityModelLayers;
import net.minecraft.client.render.entity.model.PlayerEntityModel;
import net.minecraft.client.util.DefaultSkinHelper;
import net.minecraft.client.util.SkinTextures;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.Identifier;

import java.util.UUID;

/** Renderiza al Eco como un jugador con la skin (y el modelo wide/slim) del jugador al que imita. */
public class EchoEntityRenderer extends LivingEntityRenderer<EchoEntity, PlayerEntityModel<EchoEntity>> {

    private final PlayerEntityModel<EchoEntity> wideModel;
    private final PlayerEntityModel<EchoEntity> slimModel;

    public EchoEntityRenderer(EntityRendererFactory.Context ctx) {
        super(ctx, new PlayerEntityModel<>(ctx.getPart(EntityModelLayers.PLAYER), false), 0.5f);
        this.wideModel = this.model;
        this.slimModel = new PlayerEntityModel<>(ctx.getPart(EntityModelLayers.PLAYER_SLIM), true);
        this.addFeature(new ArmorFeatureRenderer<>(this,
                new BipedEntityModel<>(ctx.getPart(EntityModelLayers.PLAYER_INNER_ARMOR)),
                new BipedEntityModel<>(ctx.getPart(EntityModelLayers.PLAYER_OUTER_ARMOR)),
                ctx.getModelManager()));
        this.addFeature(new HeldItemFeatureRenderer<>(this, ctx.getHeldItemRenderer()));
    }

    private static SkinTextures skinOf(EchoEntity entity) {
        UUID id = entity.getOwnerUuid();
        if (id == null) return DefaultSkinHelper.getSkinTextures(entity.getUuid());
        if (MinecraftClient.getInstance().getNetworkHandler() != null) {
            PlayerListEntry e = MinecraftClient.getInstance().getNetworkHandler().getPlayerListEntry(id);
            if (e != null) return e.getSkinTextures();
        }
        return DefaultSkinHelper.getSkinTextures(id);
    }

    @Override
    public void render(EchoEntity entity, float yaw, float tickDelta, MatrixStack matrices,
                       VertexConsumerProvider vertexConsumers, int light) {
        // Elegir el modelo de brazos correcto (Steve = wide, Alex = slim) antes de dibujar
        this.model = skinOf(entity).model() == SkinTextures.Model.SLIM ? slimModel : wideModel;
        super.render(entity, yaw, tickDelta, matrices, vertexConsumers, light);
    }

    @Override
    public Identifier getTexture(EchoEntity entity) {
        return skinOf(entity).texture();
    }

    @Override
    protected void scale(EchoEntity entity, MatrixStack matrices, float amount) {
        matrices.scale(0.9375f, 0.9375f, 0.9375f);
    }

    /** El nombre solo se ve de cerca: de lejos es solo "otro jugador". */
    @Override
    protected boolean hasLabel(EchoEntity entity) {
        return super.hasLabel(entity) && this.dispatcher.getSquaredDistanceToCamera(entity) < 18 * 18;
    }
}
