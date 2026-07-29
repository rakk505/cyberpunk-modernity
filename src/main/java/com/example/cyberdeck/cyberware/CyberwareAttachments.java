package com.example.cyberdeck.cyberware;

import com.example.cyberdeck.Cyberdeck;

import net.minecraft.world.entity.player.Player;
import net.neoforged.neoforge.attachment.AttachmentType;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.NeoForgeRegistries;

import java.util.function.Supplier;

/**
 * Registers the {@link CyberwareData} attachment used to store a player's installed cyberware.
 * The attachment is persisted to disk, copied across death/dimension change, and synced to the
 * owning client so the HUD/screen and client-side effects (visuals, input) stay in sync.
 */
public final class CyberwareAttachments {
    public static final DeferredRegister<AttachmentType<?>> ATTACHMENT_TYPES =
            DeferredRegister.create(NeoForgeRegistries.ATTACHMENT_TYPES, Cyberdeck.MODID);

    public static final Supplier<AttachmentType<CyberwareData>> CYBERWARE =
            ATTACHMENT_TYPES.register("cyberware", () -> AttachmentType
                    .builder(CyberwareData::new)
                    .serialize(CyberwareData.MAP_CODEC)
                    .sync(CyberwareData.STREAM_CODEC)
                    .copyOnDeath()
                    .build());

    private CyberwareAttachments() {
    }

    /** Convenience accessor for a player's cyberware data (creating a default if absent). */
    public static CyberwareData get(Player player) {
        return player.getData(CYBERWARE.get());
    }
}
