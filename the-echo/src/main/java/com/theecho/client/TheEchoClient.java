package com.theecho.client;

import com.theecho.ModBlocks;
import com.theecho.ModEntities;
import com.theecho.net.DreadPayload;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.blockrenderlayer.v1.BlockRenderLayerMap;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.sound.PositionedSoundInstance;
import net.minecraft.sound.SoundEvents;

import java.util.Random;

public class TheEchoClient implements ClientModInitializer {

    private static final Random R = new Random();
    private static int targetDread = 0;
    private static float shown = 0f;
    private static int flash = 0, flashMax = 1;
    private static int heartbeat = 0;
    private static int eyes = 0;
    private static float eyeX = 0.5f, eyeY = 0.4f;

    @Override
    public void onInitializeClient() {
        EntityRendererRegistry.register(ModEntities.ECHO, EchoEntityRenderer::new);
        // El espejo tiene transparencia: sin esto se ve con fondo negro
        BlockRenderLayerMap.INSTANCE.putBlock(ModBlocks.ECHO_MIRROR, RenderLayer.getTranslucent());

        ClientPlayNetworking.registerGlobalReceiver(DreadPayload.ID, (payload, context) -> {
            if (payload.dread() >= 0) targetDread = Math.min(100, payload.dread());
            if (payload.scare() > 0) {
                flashMax = payload.scare() >= 2 ? 26 : 12;
                flash = flashMax;
            }
        });

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            ClientPlayerEntity pl = client.player;
            if (pl == null) { targetDread = 0; shown = 0f; flash = 0; return; }
            if (client.isPaused()) return;
            shown += (targetDread - shown) * 0.04f;
            if (flash > 0) flash--;

            // La cámara tiembla un poco cuando el miedo es alto
            if (shown > 35f && R.nextInt(1000) < shown / 4f) {
                float pitch = pl.getPitch() + (R.nextFloat() - 0.5f) * shown * 0.03f;
                pl.setPitch(Math.max(-90f, Math.min(90f, pitch)));
                pl.setYaw(pl.getYaw() + (R.nextFloat() - 0.5f) * shown * 0.05f);
            }
            // Latido
            if (shown > 40f) {
                if (--heartbeat <= 0) {
                    float vol = 0.5f + shown / 200f;
                    client.getSoundManager().play(PositionedSoundInstance.master(SoundEvents.ENTITY_WARDEN_HEARTBEAT, 1.0f, vol));
                    heartbeat = (int) (80 - shown * 0.55f);
                }
            } else {
                heartbeat = 0;
            }
            // Ojos que parpadean en la oscuridad del borde de la pantalla
            if (eyes > 0) {
                eyes--;
            } else if (shown > 45f && R.nextInt(600) < shown / 10f) {
                eyes = 6 + R.nextInt(6);
                eyeX = 0.1f + R.nextFloat() * 0.8f;
                eyeY = 0.15f + R.nextFloat() * 0.6f;
            }
        });

        HudRenderCallback.EVENT.register((ctx, tickCounter) -> {
            int w = ctx.getScaledWindowWidth(), h = ctx.getScaledWindowHeight();
            float s = shown;
            if (s > 8f) {
                // Viñeta negra que se cierra con el miedo
                float a = Math.min(0.85f, s / 100f * 0.85f);
                int steps = 10, tw = Math.max(1, w / 60), th = Math.max(1, h / 40);
                for (int i = 0; i < steps; i++) {
                    float k = 1f - i / (float) steps;
                    int col = ((int) (255 * a * k * k)) << 24;
                    ctx.fill(i * tw, 0, (i + 1) * tw, h, col);
                    ctx.fill(w - (i + 1) * tw, 0, w - i * tw, h, col);
                    ctx.fill(0, i * th, w, (i + 1) * th, col);
                    ctx.fill(0, h - (i + 1) * th, w, h - i * th, col);
                }
                // Pulso rojo al ritmo del corazón
                double period = Math.max(250.0, 800.0 - s * 5.0);
                float pulse = (float) (0.5 + 0.5 * Math.sin(System.currentTimeMillis() / period * Math.PI));
                int ra = (int) (255 * 0.16f * (s / 100f) * pulse);
                ctx.fill(0, 0, w, h, (ra << 24) | 0x00600000);
            }
            if (eyes > 0) {
                int cx = (int) (w * eyeX), cy = (int) (h * eyeY);
                ctx.fill(cx - 14, cy, cx - 6, cy + 3, 0xFFFFFFFF);
                ctx.fill(cx + 6, cy, cx + 14, cy + 3, 0xFFFFFFFF);
            }
            if (flash > 0) {
                float f = flash / (float) flashMax;
                if (f > 0.8f) {
                    ctx.fill(0, 0, w, h, 0xE6FFFFFF);
                } else {
                    int al = (int) (255 * Math.min(1f, f * 1.4f));
                    ctx.fill(0, 0, w, h, al << 24);
                    int ew = w / 9, eh = h / 16;
                    ctx.fill(w * 36 / 100, h * 42 / 100, w * 36 / 100 + ew, h * 42 / 100 + eh, 0xFFFFFFFF);
                    ctx.fill(w * 64 / 100 - ew, h * 42 / 100, w * 64 / 100, h * 42 / 100 + eh, 0xFFFFFFFF);
                }
            }
        });
    }
}
