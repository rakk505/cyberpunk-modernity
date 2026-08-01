package com.example.cyberdeck.mixin;

import com.example.cyberdeck.weapon.AmmoItem;
import net.minecraft.world.Container;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;

/** Raises the container ceiling while item-specific limits continue to cap ordinary stacks. */
@Mixin(Container.class)
public interface ContainerMixin {
    /**
     * @author Cyberdeck
     * @reason Minecraft 26.2 hard-caps containers at 99, below the ammo item's 500 limit.
     */
    @Overwrite
    default int getMaxStackSize() {
        return AmmoItem.MAX_STACK_SIZE;
    }
}
