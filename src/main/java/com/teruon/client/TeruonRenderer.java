package com.teruon.client;

import com.teruon.Teruon;
import com.teruon.TeruonEntity;
import net.minecraft.client.render.OverlayTexture;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.entity.EntityRenderer;
import net.minecraft.client.render.entity.EntityRendererFactory;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.MathHelper;

/** Рисует Teruon плоским спрайтом, всегда повёрнутым к камере. */
public class TeruonRenderer extends EntityRenderer<TeruonEntity> {
    private static final Identifier IDLE = Identifier.of(Teruon.MOD_ID, "textures/entity/teruon.png");
    private static final Identifier TALK = Identifier.of(Teruon.MOD_ID, "textures/entity/teruon_talk.png");

    public TeruonRenderer(EntityRendererFactory.Context ctx) {
        super(ctx);
        this.shadowRadius = 0.25f;
    }

    @Override
    public Identifier getTexture(TeruonEntity entity) {
        return entity.isTalking() ? TALK : IDLE;
    }

    @Override
    public void render(TeruonEntity e, float yaw, float tickDelta, MatrixStack m,
                       VertexConsumerProvider vcp, int light) {
        m.push();
        float bob = MathHelper.sin((e.age + tickDelta) * 0.1f) * 0.05f;
        m.translate(0.0, 0.4 + bob, 0.0);
        m.multiply(this.dispatcher.getRotation());
        m.scale(0.8f, 0.8f, 0.8f);

        MatrixStack.Entry entry = m.peek();
        VertexConsumer vc = vcp.getBuffer(RenderLayer.getEntityCutoutNoCull(getTexture(e)));
        vertex(vc, entry, -0.5f, -0.5f, 0f, 1f, light);
        vertex(vc, entry,  0.5f, -0.5f, 1f, 1f, light);
        vertex(vc, entry,  0.5f,  0.5f, 1f, 0f, light);
        vertex(vc, entry, -0.5f,  0.5f, 0f, 0f, light);
        m.pop();

        super.render(e, yaw, tickDelta, m, vcp, light); // имя над головой
    }

    private static void vertex(VertexConsumer vc, MatrixStack.Entry entry, float x, float y, float u, float v, int light) {
        vc.vertex(entry.getPositionMatrix(), x, y, 0f)
                .color(255, 255, 255, 255)
                .texture(u, v)
                .overlay(OverlayTexture.DEFAULT_UV)
                .light(light)
                .normal(entry, 0f, 1f, 0f);
    }
}
