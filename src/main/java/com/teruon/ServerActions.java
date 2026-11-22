package com.teruon;

import com.teruon.net.ActionPayload;
import java.util.Set;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.MathHelper;
import org.jetbrains.annotations.Nullable;

/** Серверная часть: выполняет то, что "решил" Teruon. */
public final class ServerActions {
    private ServerActions() {}

    /** Предметы, которые Teruon никогда не выдаст. Дополни по вкусу. */
    private static final Set<String> BLOCKED = Set.of(
            "minecraft:command_block", "minecraft:chain_command_block", "minecraft:repeating_command_block",
            "minecraft:command_block_minecart", "minecraft:barrier", "minecraft:light",
            "minecraft:structure_block", "minecraft:structure_void", "minecraft:jigsaw",
            "minecraft:debug_stick", "minecraft:bedrock", "minecraft:spawner", "minecraft:trial_spawner");

    public static boolean isHolding(PlayerEntity p) {
        return p.getMainHandStack().isOf(Teruon.TERUON_ITEM) || p.getOffHandStack().isOf(Teruon.TERUON_ITEM);
    }

    @Nullable
    private static TeruonEntity findOwned(ServerPlayNetworking.Context ctx, ServerPlayerEntity player) {
        for (ServerWorld w : ctx.server().getWorlds()) {
            for (Entity e : w.iterateEntities()) {
                if (e instanceof TeruonEntity t && player.getUuid().equals(t.getOwnerUuid())) return t;
            }
        }
        return null;
    }

    public static void handle(ActionPayload p, ServerPlayNetworking.Context ctx) {
        ServerPlayerEntity player = ctx.player();
        TeruonEntity ent = findOwned(ctx, player);
        boolean holding = isHolding(player);
        if (ent == null && !holding) {
            if (!"none".equals(p.action())) {
                player.sendMessage(Text.literal("§7Teruon слишком далеко, чтобы помочь делом."), true);
            }
            return;
        }

        if (ent != null) ent.startTalking(p.talkTicks());

        switch (p.action()) {
            case "teleport" -> teleport(player, ent);
            case "give" -> give(player, p.item(), p.count());
            default -> { }
        }
    }

    private static void teleport(ServerPlayerEntity player, @Nullable TeruonEntity ent) {
        if (ent == null) {
            player.sendMessage(Text.literal("§7Teruon у тебя в руке — он и так рядом."), true);
            return;
        }
        if (ent.getWorld() == player.getServerWorld()) {
            ent.teleportNear(player);
        } else {
            // другое измерение: пересоздаём рядом с игроком
            ent.discard();
            TeruonEntity n = TeruonEntity.spawnFor(player.getServerWorld(), player, player.getPos());
            if (n != null) n.teleportNear(player);
        }
    }

    private static void give(ServerPlayerEntity player, String itemId, int count) {
        Identifier id = Identifier.tryParse(itemId == null ? "" : itemId.trim());
        if (id == null || !Registries.ITEM.containsId(id) || BLOCKED.contains(id.toString())) {
            player.sendMessage(Text.literal("§7Teruon не нашёл такой предмет."), true);
            return;
        }
        Item item = Registries.ITEM.get(id);
        if (item == Items.AIR) return;
        int n = MathHelper.clamp(count, 1, Math.min(64, item.getMaxCount()));
        ItemStack stack = new ItemStack(item, n);
        Text name = stack.getName();
        if (!player.getInventory().insertStack(stack)) {
            player.dropItem(stack, false);
        }
        player.sendMessage(Text.literal("§eTeruon выдал: ").append(name).append(" x" + n), true);
    }
}
