package com.example.cyberdeck.vehicle;

import com.example.cyberdeck.Cyberdeck;
import com.mojang.serialization.Codec;

import net.minecraft.network.codec.ByteBufCodecs;
import net.neoforged.neoforge.attachment.AttachmentType;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.NeoForgeRegistries;

import java.util.function.Supplier;

/** Durable, client-synced opt-in state for individually adapted car entities. */
public final class VehicleQuickhackAttachments {
    public static final DeferredRegister<AttachmentType<?>> ATTACHMENT_TYPES =
            DeferredRegister.create(NeoForgeRegistries.ATTACHMENT_TYPES, Cyberdeck.MODID);

    public static final Supplier<AttachmentType<Boolean>> COMPATIBLE_CAR =
            ATTACHMENT_TYPES.register("compatible_quickhack_car", () -> AttachmentType
                    .builder(() -> Boolean.FALSE)
                    .serialize(Codec.BOOL.fieldOf("compatible_car"))
                    .sync(ByteBufCodecs.BOOL)
                    .build());

    private VehicleQuickhackAttachments() {
    }
}
