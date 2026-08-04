package com.example.cyberdeck.weapon;

import com.example.cyberdeck.Cyberdeck;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/** Registered positional firearm reports backed by randomized real-shot recordings. */
public final class WeaponSounds {
    public static final DeferredRegister<SoundEvent> SOUND_EVENTS =
            DeferredRegister.create(Registries.SOUND_EVENT, Cyberdeck.MODID);

    public static final DeferredHolder<SoundEvent, SoundEvent> PISTOL_FIRE =
            register("weapon.pistol.fire", 80.0f);
    public static final DeferredHolder<SoundEvent, SoundEvent> HEAVY_PISTOL_FIRE =
            register("weapon.heavy_pistol.fire", 96.0f);
    public static final DeferredHolder<SoundEvent, SoundEvent> SMG_FIRE =
            register("weapon.smg.fire", 96.0f);
    public static final DeferredHolder<SoundEvent, SoundEvent> RIFLE_FIRE =
            register("weapon.rifle.fire", 128.0f);
    public static final DeferredHolder<SoundEvent, SoundEvent> SHOTGUN_FIRE =
            register("weapon.shotgun.fire", 112.0f);
    public static final DeferredHolder<SoundEvent, SoundEvent> SNIPER_FIRE =
            register("weapon.sniper.fire", 160.0f);

    private WeaponSounds() {
    }

    public static SoundEvent fireSound(GunType gun) {
        return switch (gun.baseGun()) {
            case PISTOL, UNITY, YUKIMURA -> PISTOL_FIRE.get();
            case OVERTURE, THREE_FIVE_ONE_SIX -> HEAVY_PISTOL_FIRE.get();
            case SMG, SARATOGA, G58_DIAN -> SMG_FIRE.get();
            case ASSAULT_RIFLE, AJAX, COPPERHEAD -> RIFLE_FIRE.get();
            case SHOTGUN, M2038, CARNAGE -> SHOTGUN_FIRE.get();
            case SNIPER, GRAD -> SNIPER_FIRE.get();
            case MANTIS_BLADE -> SoundEvents.PLAYER_ATTACK_SWEEP;
            default -> throw new IllegalStateException("Unhandled firearm profile: " + gun);
        };
    }

    public static float volume(GunType gun) {
        return switch (gun.baseGun()) {
            case PISTOL, UNITY, YUKIMURA -> 1.0f;
            case OVERTURE, THREE_FIVE_ONE_SIX -> 1.15f;
            case SMG, SARATOGA, G58_DIAN -> 0.82f;
            case ASSAULT_RIFLE, AJAX, COPPERHEAD -> 0.95f;
            case SHOTGUN, M2038, CARNAGE -> 1.20f;
            case SNIPER, GRAD -> 1.30f;
            case MANTIS_BLADE -> 0.9f;
            default -> 1.0f;
        };
    }

    public static float basePitch(GunType gun) {
        float pitch = switch (gun.baseGun()) {
            case PISTOL, UNITY, YUKIMURA -> 1.0f;
            case OVERTURE, THREE_FIVE_ONE_SIX -> 0.96f;
            case SMG, SARATOGA, G58_DIAN -> 1.03f;
            case ASSAULT_RIFLE, AJAX, COPPERHEAD -> 1.0f;
            case SHOTGUN, M2038, CARNAGE -> 0.95f;
            case SNIPER, GRAD -> 0.90f;
            case MANTIS_BLADE -> 1.0f;
            default -> 1.0f;
        };
        return gun.isTech() ? pitch * 1.04f : pitch;
    }

    private static DeferredHolder<SoundEvent, SoundEvent> register(String name, float range) {
        Identifier id = Identifier.fromNamespaceAndPath(Cyberdeck.MODID, name);
        return SOUND_EVENTS.register(name, () -> SoundEvent.createFixedRangeEvent(id, range));
    }
}
