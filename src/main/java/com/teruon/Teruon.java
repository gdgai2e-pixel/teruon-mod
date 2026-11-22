package com.teruon;

import com.teruon.net.ActionPayload;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.itemgroup.v1.ItemGroupEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.fabricmc.fabric.api.object.builder.v1.entity.FabricDefaultAttributeRegistry;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.SpawnGroup;
import net.minecraft.item.Item;
import net.minecraft.item.ItemGroups;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.util.Identifier;

public class Teruon implements ModInitializer {
    public static final String MOD_ID = "teruon";

    public static final EntityType<TeruonEntity> TERUON_ENTITY = Registry.register(
            Registries.ENTITY_TYPE,
            Identifier.of(MOD_ID, "teruon"),
            EntityType.Builder.create(TeruonEntity::new, SpawnGroup.CREATURE)
                    .dimensions(0.75f, 0.75f)
                    .maxTrackingRange(10)
                    .build("teruon"));

    public static final Item TERUON_ITEM = Registry.register(
            Registries.ITEM,
            Identifier.of(MOD_ID, "teruon"),
            new TeruonItem(new Item.Settings().maxCount(1)));

    @Override
    public void onInitialize() {
        FabricDefaultAttributeRegistry.register(TERUON_ENTITY, TeruonEntity.createTeruonAttributes());
        ItemGroupEvents.modifyEntriesEvent(ItemGroups.TOOLS).register(entries -> entries.add(TERUON_ITEM));

        PayloadTypeRegistry.playC2S().register(ActionPayload.ID, ActionPayload.CODEC);
        ServerPlayNetworking.registerGlobalReceiver(ActionPayload.ID, ServerActions::handle);
    }
}
