package com.teruon.client;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import net.fabricmc.loader.api.FabricLoader;

/** Настройки мода: config/teruon.json */
public class TeruonConfig {
    /** Ключ OpenRouter (sk-or-...). Без него Teruon молчит. */
    public String apiKey = "";
    /** Модель для ответов Teruon. */
    public String model = "google/gemini-2.5-flash";
    /** Модель, которая переводит речь в текст (должна принимать аудио). */
    public String sttModel = "google/gemini-2.5-flash";
    /** true — твои слова уходят в общий чат сервера, false — видишь только ты. */
    public boolean sendToPublicChat = false;

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path FILE = FabricLoader.getInstance().getConfigDir().resolve("teruon.json");
    private static TeruonConfig instance;

    public static synchronized TeruonConfig get() {
        if (instance == null) {
            try {
                if (Files.exists(FILE)) {
                    instance = GSON.fromJson(Files.readString(FILE, StandardCharsets.UTF_8), TeruonConfig.class);
                }
            } catch (Exception ignored) { }
            if (instance == null) instance = new TeruonConfig();
        }
        return instance;
    }

    public synchronized void save() {
        try {
            Files.writeString(FILE, GSON.toJson(this), StandardCharsets.UTF_8);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}
