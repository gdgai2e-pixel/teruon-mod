package com.teruon;

import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.util.Hand;
import net.minecraft.util.TypedActionResult;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

/** Teruon "в руке". ПКМ — поставить обратно в мир. */
public class TeruonItem extends Item {
    public TeruonItem(Settings settings) {
        super(settings);
    }

    @Override
    public TypedActionResult<ItemStack> use(World world, PlayerEntity user, Hand hand) {
        ItemStack stack = user.getStackInHand(hand);
        if (!world.isClient) {
            Vec3d pos = user.getEyePos().add(user.getRotationVector().multiply(1.5));
            TeruonEntity e = TeruonEntity.spawnFor(world, user, pos);
            if (e != null) {
                stack.decrement(1);
            }
        }
        return TypedActionResult.success(stack, world.isClient);
    }
}
