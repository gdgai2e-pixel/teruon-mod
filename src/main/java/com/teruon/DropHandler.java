package com.teruon;

import java.util.ArrayList;
import java.util.List;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.Vec3d;

/** Выкинули предмет Teruon (Q) — он тут же оживает и летает рядом. */
public final class DropHandler {
    private DropHandler() {}

    private static final List<ItemEntity> PENDING = new ArrayList<>();

    public static void register() {
        ServerEntityEvents.ENTITY_LOAD.register((entity, world) -> {
            if (entity instanceof ItemEntity ie && ie.getStack().isOf(Teruon.TERUON_ITEM)) {
                PENDING.add(ie);
            }
        });

        // превращаем в конце тика, когда у выброшенного предмета уже всё настроено
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            if (PENDING.isEmpty()) return;
            List<ItemEntity> batch = new ArrayList<>(PENDING);
            PENDING.clear();
            for (ItemEntity ie : batch) {
                if (ie.isRemoved() || !ie.getStack().isOf(Teruon.TERUON_ITEM)) continue;
                if (!(ie.getWorld() instanceof ServerWorld sw)) continue;
                PlayerEntity near = sw.getClosestPlayer(ie, 10.0);
                TeruonEntity.spawnAt(sw, near != null ? near.getUuid() : null,
                        ie.getPos().add(0.0, 0.3, 0.0), ie.getYaw());
                ie.discard();
            }
        });
    }
}
