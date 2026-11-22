package com.teruon.client;

import com.mojang.brigadier.Command;
import com.teruon.Teruon;
import com.teruon.TeruonEntity;
import com.teruon.net.ActionPayload;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.client.util.InputUtil;
import net.minecraft.entity.Entity;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.MathHelper;
import org.lwjgl.glfw.GLFW;

public class TeruonClient implements ClientModInitializer {
    private enum State { IDLE, LISTENING, THINKING }

    private static final Identifier TEX_IDLE = Identifier.of(Teruon.MOD_ID, "textures/entity/teruon.png");
    private static final Identifier TEX_TALK = Identifier.of(Teruon.MOD_ID, "textures/entity/teruon_talk.png");

    private static volatile State state = State.IDLE;
    private static volatile long talkUntilMs = 0;
    private static KeyBinding talkKey;
    private static MicRecorder recorder;

    @Override
    public void onInitializeClient() {
        EntityRendererRegistry.register(Teruon.TERUON_ENTITY, TeruonRenderer::new);

        talkKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.teruon.talk", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_M, "category.teruon"));

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (talkKey.wasPressed()) onTalkKey(client);
        });

        HudRenderCallback.EVENT.register(TeruonClient::renderHud);

        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) ->
                dispatcher.register(ClientCommandManager.literal("teruon")
                        .then(ClientCommandManager.literal("config").executes(ctx -> {
                            MinecraftClient mc = ctx.getSource().getClient();
                            mc.send(() -> mc.setScreen(new TeruonConfigScreen(mc.currentScreen)));
                            return Command.SINGLE_SUCCESS;
                        }))));
    }

    // ------------------------------------------------------------ клавиша M
    private static void onTalkKey(MinecraftClient mc) {
        if (mc.player == null) return;
        if (state == State.LISTENING) { recorder.requestStop(); return; }
        if (state == State.THINKING) return;

        if (!canTalk(mc)) {
            info(mc, "Возьми Teruon в руку (ПКМ по нему пустой рукой) или позови его поближе.");
            return;
        }
        if (TeruonConfig.get().apiKey.isBlank()) {
            info(mc, "Сначала впиши ключ OpenRouter: /teruon config");
            return;
        }

        state = State.LISTENING; // HUD покажет "Говори!"
        recorder = new MicRecorder(new MicRecorder.Callback() {
            @Override public void onDone(byte[] wav) { process(mc, wav); }
            @Override public void onError(String message) {
                state = State.IDLE;
                mc.execute(() -> info(mc, message));
            }
        });
        recorder.start();
    }

    private static boolean canTalk(MinecraftClient mc) {
        if (mc.player.getMainHandStack().isOf(Teruon.TERUON_ITEM)
                || mc.player.getOffHandStack().isOf(Teruon.TERUON_ITEM)) return true;
        if (mc.world == null) return false;
        for (Entity e : mc.world.getEntities()) if (e instanceof TeruonEntity) return true;
        return false;
    }

    // ------------------------------------------------------------ речь -> ИИ -> ответ (фоновый поток)
    private static void process(MinecraftClient mc, byte[] wav) {
        state = State.THINKING;
        try {
            String text = TeruonBrain.transcribe(wav);
            if (text.isBlank()) {
                mc.execute(() -> info(mc, "Не разобрал, что ты сказал."));
                return;
            }
            String ctx = mc.submit(() -> buildContext(mc)).get();

            mc.execute(() -> {
                if (TeruonConfig.get().sendToPublicChat) {
                    mc.player.networkHandler.sendChatMessage(text);
                } else {
                    mc.inGameHud.getChatHud().addMessage(
                            Text.literal("<Ты> ").formatted(Formatting.GRAY).append(Text.literal(text).formatted(Formatting.WHITE)));
                }
            });

            TeruonBrain.Reply r = TeruonBrain.chat(text, ctx);
            int talkTicks = MathHelper.clamp(20 + (int) (r.say().length() * 1.6), 30, 300);

            mc.execute(() -> {
                mc.inGameHud.getChatHud().addMessage(
                        Text.literal("<Teruon> ").formatted(Formatting.YELLOW).append(Text.literal(r.say()).formatted(Formatting.WHITE)));
                talkUntilMs = System.currentTimeMillis() + talkTicks * 50L;
                try {
                    ClientPlayNetworking.send(new ActionPayload(r.action(), r.item(), r.count(), talkTicks));
                } catch (Exception ignored) { }
            });
        } catch (Exception e) {
            mc.execute(() -> info(mc, "§cОшибка Teruon: " + e.getMessage()));
        } finally {
            state = State.IDLE;
        }
    }

    private static String buildContext(MinecraftClient mc) {
        var p = mc.player;
        var w = mc.world;
        String biome = w.getBiome(p.getBlockPos()).getKey().map(k -> k.getValue().getPath()).orElse("?");
        boolean holding = p.getMainHandStack().isOf(Teruon.TERUON_ITEM) || p.getOffHandStack().isOf(Teruon.TERUON_ITEM);
        return "Игрок: " + p.getName().getString()
                + ", здоровье " + (int) p.getHealth() + "/20, сытость " + p.getHungerManager().getFoodLevel() + "/20"
                + ", измерение " + w.getRegistryKey().getValue()
                + ", биом " + biome
                + ", время суток " + (w.getTimeOfDay() % 24000)
                + ", в руке у игрока: " + p.getMainHandStack().getName().getString()
                + ", Teruon сейчас " + (holding ? "у игрока в руке" : "летает рядом/в мире");
    }

    private static void info(MinecraftClient mc, String msg) {
        if (mc.player != null) mc.player.sendMessage(Text.literal(msg), true);
    }

    // ------------------------------------------------------------ HUD: "Говори!"
    private static void renderHud(DrawContext ctx, RenderTickCounter tick) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null || mc.options.hudHidden) return;
        State s = state;
        boolean talking = System.currentTimeMillis() < talkUntilMs;
        if (s == State.IDLE && !talking) return;

        Identifier tex = (s == State.LISTENING || talking) ? TEX_TALK : TEX_IDLE;
        String label = s == State.LISTENING ? "Говори!" : s == State.THINKING ? "Teruon думает..." : "";

        int w = ctx.getScaledWindowWidth(), h = ctx.getScaledWindowHeight();
        int size = 32, x = w / 2 - size / 2, y = h - 110;
        ctx.drawTexture(tex, x, y, size, size, 0f, 0f, 48, 48, 48, 48);
        if (!label.isEmpty()) {
            ctx.drawCenteredTextWithShadow(mc.textRenderer, label, w / 2, y + size + 3, 0xFFFF55);
        }
    }
}
