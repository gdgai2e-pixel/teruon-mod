package com.teruon.client;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.teruon.Teruon;
import com.teruon.TeruonEntity;
import com.teruon.net.ActionPayload;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.message.v1.ClientSendMessageEvents;
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
    private static volatile boolean bypass; // true — наше собственное сообщение, не перехватывать

    @Override
    public void onInitializeClient() {
        EntityRendererRegistry.register(Teruon.TERUON_ENTITY, TeruonRenderer::new);

        talkKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.teruon.talk", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_V, "category.teruon"));

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (talkKey.wasPressed()) onTalkKey(client);
            // отпустил V -> запись закончилась, отправляем
            if (state == State.LISTENING && recorder != null && !talkKey.isPressed()) {
                recorder.requestStop();
            }
        });

        HudRenderCallback.EVENT.register(TeruonClient::renderHud);

        // Обычное сообщение в чате -> Teruon (если он в руке или рядом). "!текст" — написать в обычный чат.
        ClientSendMessageEvents.ALLOW_CHAT.register(message -> {
            if (bypass) return true;
            MinecraftClient mc = MinecraftClient.getInstance();
            if (mc.player == null || !TeruonConfig.get().chatWithTeruon) return true;
            // сообщение со словом "Теруон" уходит ему всегда; остальные — только если он в руке или рядом
            if (!mentionsTeruon(message) && !canTalk(mc)) return true;
            if (message.startsWith("!")) {
                String rest = message.substring(1).trim();
                if (rest.isEmpty()) return true;
                sendPublic(mc, rest);
                return false;
            }
            sayText(mc, message);
            return false;
        });

        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
            Command<FabricClientCommandSource> say = ctx -> {
                String text = StringArgumentType.getString(ctx, "text");
                MinecraftClient mc = ctx.getSource().getClient();
                mc.execute(() -> sayText(mc, text));
                return Command.SINGLE_SUCCESS;
            };
            dispatcher.register(ClientCommandManager.literal("teruon")
                    .then(ClientCommandManager.literal("config").executes(ctx -> {
                        MinecraftClient mc = ctx.getSource().getClient();
                        mc.send(() -> mc.setScreen(new TeruonConfigScreen(mc.currentScreen)));
                        return Command.SINGLE_SUCCESS;
                    }))
                    .then(ClientCommandManager.literal("mic").executes(ctx -> {
                        MinecraftClient mc = ctx.getSource().getClient();
                        info(mc, "Тест микрофона: говори 3 секунды...");
                        Thread t = new Thread(() -> {
                            for (String line : MicRecorder.diagnose()) mc.execute(() -> info(mc, line));
                        }, "Teruon-MicTest");
                        t.setDaemon(true);
                        t.start();
                        return Command.SINGLE_SUCCESS;
                    }))
                    .then(ClientCommandManager.literal("say")
                            .then(ClientCommandManager.argument("text", StringArgumentType.greedyString()).executes(say))));
            dispatcher.register(ClientCommandManager.literal("t")
                    .then(ClientCommandManager.argument("text", StringArgumentType.greedyString()).executes(say)));
        });
    }

    // ------------------------------------------------------------ клавиша M
    private static void onTalkKey(MinecraftClient mc) {
        if (mc.player == null) return;
        if (state != State.IDLE) return;

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

    /** Есть ли в сообщении имя: "привет теруон", "Teruon, дай алмазы", "теруона" и т.п. */
    private static boolean mentionsTeruon(String message) {
        String m = message.toLowerCase();
        return m.contains("теруон") || m.contains("тэруон") || m.contains("teruon");
    }

    private static boolean canTalk(MinecraftClient mc) {
        if (mc.player.getMainHandStack().isOf(Teruon.TERUON_ITEM)
                || mc.player.getOffHandStack().isOf(Teruon.TERUON_ITEM)) return true;
        if (mc.world == null) return false;
        for (Entity e : mc.world.getEntities()) if (e instanceof TeruonEntity) return true;
        return false;
    }

    /** Общая часть для голоса и текста: показать слова, спросить ИИ, показать ответ, отправить действие. */
    private static void converse(MinecraftClient mc, String text) throws Exception {
        String ctx = mc.submit(() -> buildContext(mc)).get();

        mc.execute(() -> {
            if (TeruonConfig.get().sendToPublicChat) {
                sendPublic(mc, text);
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
    }

    /** Написать Teruon текстом: /t привет  или  /teruon say привет */
    private static void sayText(MinecraftClient mc, String text) {
        if (mc.player == null || text.isBlank()) return;
        if (state != State.IDLE) { info(mc, "Teruon ещё занят, подожди секунду."); return; }
        if (TeruonConfig.get().apiKey.isBlank()) {
            info(mc, "Сначала впиши ключ OpenRouter: /teruon config");
            return;
        }
        state = State.THINKING;
        Thread t = new Thread(() -> {
            try {
                converse(mc, text.trim());
            } catch (Throwable e) {
                mc.execute(() -> info(mc, "§cОшибка Teruon: " + describe(e)));
            } finally {
                state = State.IDLE;
            }
        }, "Teruon-Text");
        t.setDaemon(true);
        t.start();
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
            converse(mc, text);
        } catch (Throwable e) {
            mc.execute(() -> info(mc, "§cОшибка Teruon: " + describe(e)));
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

    /** Сообщение от мода — в чат (в полоске над хотбаром его легко не заметить). */
    private static void info(MinecraftClient mc, String msg) {
        if (mc.player != null) {
            mc.inGameHud.getChatHud().addMessage(Text.literal("[Teruon] " + msg).formatted(Formatting.GRAY));
        }
    }

    private static String describe(Throwable e) {
        return e.getMessage() != null ? e.getMessage() : e.toString();
    }

    private static void sendPublic(MinecraftClient mc, String text) {
        bypass = true;
        try {
            mc.player.networkHandler.sendChatMessage(text);
        } finally {
            bypass = false;
        }
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
