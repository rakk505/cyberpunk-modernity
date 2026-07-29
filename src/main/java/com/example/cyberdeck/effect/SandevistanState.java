package com.example.cyberdeck.effect;

import com.example.cyberdeck.cyberware.SandevistanProfile;

import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.neoforged.neoforge.common.util.ValueIOSerializable;

/** Persisted, server-owned charge and activation state for a player's Sandevistan. */
public final class SandevistanState implements ValueIOSerializable {
    private String variantId = "";
    private double chargeTicks;
    private boolean active;
    private long nextToggleTick;

    public boolean ensureVariant(SandevistanProfile profile) {
        if (profile.cyberware().id().equals(variantId)) {
            chargeTicks = Math.min(chargeTicks, profile.durationTicks());
            return false;
        }
        variantId = profile.cyberware().id();
        chargeTicks = profile.durationTicks();
        active = false;
        nextToggleTick = 0L;
        return true;
    }

    public void clear() {
        variantId = "";
        chargeTicks = 0.0;
        active = false;
        nextToggleTick = 0L;
    }

    public boolean active() {
        return active;
    }

    public double chargeTicks() {
        return chargeTicks;
    }

    public boolean canToggle(long gameTime) {
        return gameTime >= nextToggleTick;
    }

    public void markToggled(long gameTime) {
        nextToggleTick = gameTime + 2L;
    }

    public boolean canActivate(SandevistanProfile profile) {
        return profile.partialActivation()
                ? chargeTicks >= 1.0
                : chargeTicks >= profile.durationTicks() - 1.0E-6;
    }

    public void activate() {
        active = true;
    }

    public void deactivate() {
        active = false;
    }

    public void tick(SandevistanProfile profile) {
        ensureVariant(profile);
        if (active) {
            chargeTicks = Math.max(0.0, chargeTicks - 1.0);
            if (chargeTicks <= 0.0) {
                active = false;
            }
        } else {
            double rechargePerTick = (double) profile.durationTicks() / profile.cooldownTicks();
            chargeTicks = Math.min(profile.durationTicks(), chargeTicks + rechargePerTick);
        }
    }

    public void addCharge(SandevistanProfile profile, double ticks) {
        chargeTicks = Math.min(profile.durationTicks(), chargeTicks + Math.max(0.0, ticks));
    }

    @Override
    public void serialize(ValueOutput output) {
        output.putString("Variant", variantId);
        output.putDouble("ChargeTicks", chargeTicks);
        output.putBoolean("Active", active);
        output.putLong("NextToggleTick", nextToggleTick);
    }

    @Override
    public void deserialize(ValueInput input) {
        variantId = input.getStringOr("Variant", "");
        chargeTicks = Math.max(0.0, input.getDoubleOr("ChargeTicks", 0.0));
        active = input.getBooleanOr("Active", false);
        nextToggleTick = Math.max(0L, input.getLongOr("NextToggleTick", 0L));
    }
}
