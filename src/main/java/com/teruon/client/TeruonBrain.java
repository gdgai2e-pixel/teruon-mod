package com.teruon.client;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/** Общение с OpenRouter: речь -> текст и текст -> ответ Teruon (+ действие). */
public final class TeruonBrain {
    private TeruonBrain() {}

    public record Reply(String say, String action, String item, int count) {}

    private static final Gson GSON = new Gson();
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();
    private static final URI URL = URI.create("https://openrouter.ai/api/v1/chat/completions");
    private static final int MAX_HISTORY = 20;
    private static final List<JsonObject> history = new ArrayList<>();

    private static final String SYSTEM_PROMPT = """
            Ты — Teruon, маленький жёлтый смайлик-спутник игрока в Minecraft. Ты следишь за игроком, любишь с ним болтать, \
            слегка забавный и дружелюбный. Отвечай коротко (1-2 предложения) на языке игрока.
            Ты умеешь два действия:
            - "teleport": ты телепортируешься к игроку (если он просит подойти / прилететь / ты далеко).
            - "give": ты выдаёшь игроку предмет (поле "item" — id вида minecraft:diamond, "count" — от 1 до 64).
            Если действие не нужно — "none".
            ОТВЕЧАЙ СТРОГО ОДНИМ JSON-ОБЪЕКТОМ без markdown и пояснений:
            {"say":"что ты говоришь","action":"none|teleport|give","item":"","count":1}
            Перед сообщением игрока в квадратных скобках дан контекст из игры — используй его, но не зачитывай целиком.""";

    /** Речь (WAV) -> текст. */
    public static String transcribe(byte[] wav) throws Exception {
        JsonObject text = new JsonObject();
        text.addProperty("type", "text");
        text.addProperty("text", "Дословно транскрибируй речь из аудио. Верни только текст речи, без пояснений и кавычек. "
                + "Если речи нет — верни пустую строку.");
        JsonObject audio = new JsonObject();
        audio.addProperty("data", Base64.getEncoder().encodeToString(wav));
        audio.addProperty("format", "wav");
        JsonObject audioPart = new JsonObject();
        audioPart.addProperty("type", "input_audio");
        audioPart.add("input_audio", audio);

        JsonArray content = new JsonArray();
        content.add(text);
        content.add(audioPart);
        JsonObject msg = new JsonObject();
        msg.addProperty("role", "user");
        msg.add("content", content);

        return call(TeruonConfig.get().sttModel, List.of(msg)).trim();
    }

    /** Текст игрока -> ответ Teruon. */
    public static Reply chat(String userText, String context) throws Exception {
        history.add(msg("user", "[" + context + "]\n" + userText));
        trim();

        List<JsonObject> all = new ArrayList<>();
        all.add(msg("system", SYSTEM_PROMPT));
        all.addAll(history);

        String raw = call(TeruonConfig.get().model, all);
        history.add(msg("assistant", raw));
        trim();
        return parse(raw);
    }

    private static void trim() {
        while (history.size() > MAX_HISTORY) history.remove(0);
    }

    private static JsonObject msg(String role, String content) {
        JsonObject o = new JsonObject();
        o.addProperty("role", role);
        o.addProperty("content", content);
        return o;
    }

    private static Reply parse(String raw) {
        String s = raw.trim();
        int a = s.indexOf('{'), b = s.lastIndexOf('}');
        if (a >= 0 && b > a) {
            try {
                JsonObject o = JsonParser.parseString(s.substring(a, b + 1)).getAsJsonObject();
                return new Reply(
                        str(o, "say", ""),
                        str(o, "action", "none").toLowerCase(),
                        str(o, "item", ""),
                        o.has("count") && o.get("count").isJsonPrimitive() ? o.get("count").getAsInt() : 1);
            } catch (Exception ignored) { }
        }
        return new Reply(s, "none", "", 1); // модель ответила не JSON-ом — просто говорим как есть
    }

    private static String str(JsonObject o, String k, String def) {
        JsonElement e = o.get(k);
        return (e == null || e.isJsonNull()) ? def : e.getAsString();
    }

    private static String call(String model, List<JsonObject> messages) throws Exception {
        TeruonConfig cfg = TeruonConfig.get();
        JsonObject body = new JsonObject();
        body.addProperty("model", model);
        JsonArray arr = new JsonArray();
        messages.forEach(arr::add);
        body.add("messages", arr);

        HttpRequest req = HttpRequest.newBuilder(URL)
                .timeout(Duration.ofSeconds(60))
                .header("Authorization", "Bearer " + cfg.apiKey.trim())
                .header("Content-Type", "application/json")
                .header("X-Title", "Teruon Minecraft Mod")
                .POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(body)))
                .build();
        HttpResponse<String> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() / 100 != 2) {
            String b = resp.body();
            throw new IOException("OpenRouter " + resp.statusCode() + ": " + (b.length() > 200 ? b.substring(0, 200) : b));
        }
        JsonObject root = JsonParser.parseString(resp.body()).getAsJsonObject();
        JsonElement c = root.getAsJsonArray("choices").get(0).getAsJsonObject()
                .getAsJsonObject("message").get("content");
        return (c == null || c.isJsonNull()) ? "" : c.getAsString();
    }
}
