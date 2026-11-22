package com.teruon.net;

import com.teruon.Teruon;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

/**
 * Клиент -> сервер: "Teruon что-то сказал: выполни действие и открой ему рот".
 * action: "none" | "teleport" | "give"
 */
public record ActionPayload(String action, String item, int count, int talkTicks) implements CustomPayload {
    public static final Id<ActionPayload> ID = new Id<>(Identifier.of(Teruon.MOD_ID, "action"));

    public static final PacketCodec<RegistryByteBuf, ActionPayload> CODEC = PacketCodec.tuple(
            PacketCodecs.STRING, ActionPayload::action,
            PacketCodecs.STRING, ActionPayload::item,
            PacketCodecs.VAR_INT, ActionPayload::count,
            PacketCodecs.VAR_INT, ActionPayload::talkTicks,
            ActionPayload::new);

    @Override
    public Id<? extends CustomPayload> getId() {
        return ID;
    }
}
