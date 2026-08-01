package com.example.cyberdeck.mixin;

import com.example.cyberdeck.weapon.AmmoItem;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyConstant;

/** Allows high-capacity ammo stacks to survive inventory NBT save/load round trips. */
@Mixin(ItemStack.class)
public abstract class ItemStackCodecMixin {
    @ModifyConstant(method = "lambda$static$1", constant = @Constant(intValue = 99))
    private static int cyberdeck$raiseSerializedStackLimit(int originalLimit) {
        return AmmoItem.MAX_STACK_SIZE;
    }
}
