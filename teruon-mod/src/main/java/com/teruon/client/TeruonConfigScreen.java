package com.teruon.client;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;

/** Экран настроек: /teruon config */
public class TeruonConfigScreen extends Screen {
    private final Screen parent;
    private TextFieldWidget keyField, modelField, sttField;
    private ButtonWidget chatButton;
    private boolean publicChat;

    public TeruonConfigScreen(Screen parent) {
        super(Text.literal("Teruon — настройки"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        TeruonConfig cfg = TeruonConfig.get();
        publicChat = cfg.sendToPublicChat;
        int cx = width / 2, w = 300, x = cx - w / 2;

        keyField = new TextFieldWidget(textRenderer, x, 50, w, 20, Text.literal("API key"));
        keyField.setMaxLength(256);
        keyField.setText(cfg.apiKey);
        addDrawableChild(keyField);

        modelField = new TextFieldWidget(textRenderer, x, 90, w, 20, Text.literal("model"));
        modelField.setMaxLength(128);
        modelField.setText(cfg.model);
        addDrawableChild(modelField);

        sttField = new TextFieldWidget(textRenderer, x, 130, w, 20, Text.literal("stt"));
        sttField.setMaxLength(128);
        sttField.setText(cfg.sttModel);
        addDrawableChild(sttField);

        chatButton = ButtonWidget.builder(chatLabel(), b -> {
            publicChat = !publicChat;
            b.setMessage(chatLabel());
        }).dimensions(x, 160, w, 20).build();
        addDrawableChild(chatButton);

        addDrawableChild(ButtonWidget.builder(Text.literal("Сохранить"), b -> {
            cfg.apiKey = keyField.getText().trim();
            cfg.model = modelField.getText().trim();
            cfg.sttModel = sttField.getText().trim();
            cfg.sendToPublicChat = publicChat;
            cfg.save();
            close();
        }).dimensions(x, 195, 145, 20).build());

        addDrawableChild(ButtonWidget.builder(Text.literal("Отмена"), b -> close())
                .dimensions(x + 155, 195, 145, 20).build());
    }

    private Text chatLabel() {
        return Text.literal("Мои слова в чате: " + (publicChat ? "видят все" : "вижу только я"));
    }

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        super.render(ctx, mouseX, mouseY, delta);
        int x = width / 2 - 150;
        ctx.drawCenteredTextWithShadow(textRenderer, title, width / 2, 20, 0xFFFF55);
        ctx.drawTextWithShadow(textRenderer, "Ключ OpenRouter (API key, sk-or-...):", x, 38, 0xA0A0A0);
        ctx.drawTextWithShadow(textRenderer, "Модель для ответов:", x, 78, 0xA0A0A0);
        ctx.drawTextWithShadow(textRenderer, "Модель для распознавания речи (с аудио):", x, 118, 0xA0A0A0);
    }

    @Override
    public void close() {
        client.setScreen(parent);
    }
}
