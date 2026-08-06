package com.example.cyberdeck.client.gun;

import com.example.cyberdeck.weapon.GunItem;
import com.example.cyberdeck.weapon.ReloadState;
import com.example.cyberdeck.weapon.WeaponComponents;

import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

/**
 * Client-side animation state for the held gun in one hand. Chooses which Bedrock clip is playing
 * and how far into it, driven entirely by cyberdeck's own gun state (magazine component + reload
 * attachment) so it stays in sync with the real mechanics without touching them.
 *
 * <ul>
 *   <li>reload in progress -> {@code reload_empty} or {@code reload_tactical}, stretched over the
 *       reload duration</li>
 *   <li>magazine just dropped (a shot was fired) -> one-shot {@code shoot} recoil</li>
 *   <li>otherwise -> looping {@code static_idle}</li>
 * </ul>
 */
public final class GunAnimationController {
    private static final GunAnimationController INSTANCE = new GunAnimationController();

    private String currentGunId;
    private int lastMagazine = -1;

    private String activeClip = "static_idle";
    private double clipStartTime; // seconds (render clock)
    private double sampledClipTime = -1.0;
    private boolean clipLoop = true;

    private boolean wasReloading;
    /** Previous frame's swing state, so a melee rig starts a clip on the swing's rising edge. */
    private boolean wasSwinging;
    /** Rotates through the authored swing clips so repeated attacks do not look identical. */
    private int meleeSwingIndex;

    private static final String[] MELEE_SWING_CLIPS = {
            "melee_stock_1", "melee_stock_2", "melee_stock_3"
    };

    private GunAnimationController() {}

    public static GunAnimationController get() {
        return INSTANCE;
    }

    /** Time base in seconds for the render clock. */
    private static double now() {
        return System.nanoTime() / 1_000_000_000.0;
    }

    /**
     * Advance state for the given held gun and return the clip + local time to sample.
     *
     * @return {@code {clipName, localTimeSeconds}} packed, or clip name via {@link #clipName()} and
     *         {@link #clipTime()} after calling this.
     */
    public void update(Player player, ItemStack stack, GunItem gunItem, BedrockAnimationData animation) {
        sampledClipTime = -1.0;
        String gunId = gunItem.gun().id();
        boolean gunChanged = !gunId.equals(currentGunId);
        if (gunChanged) {
            currentGunId = gunId;
            lastMagazine = magazine(stack, gunItem);
            wasReloading = false;
            startClip("static_idle", animation);
        }

        int magazine = magazine(stack, gunItem);
        ReloadState reload = ReloadState.get(player);
        boolean reloading = reload.active();

        if (reloading) {
            double duration = Math.max(1.0, reload.endTick() - reload.startTick());
            double progress = Mth.clamp(
                    (player.level().getGameTime() - reload.startTick()) / duration, 0.0, 1.0);
            if (animation.clips.containsKey("reload_intro")) {
                sampleSegmentedReload(animation, magazine <= 0, progress);
            } else if (!wasReloading) {
                String clip = magazine <= 0 ? "reload_empty" : "reload_tactical";
                if (!animation.clips.containsKey(clip)) {
                    clip = firstAvailable(animation,
                            "reload_empty", "reload_intro_empty", "reload_intro", "reload_loop");
                }
                startClip(clip, animation);
            }
            if (!animation.clips.containsKey("reload_intro")) {
                BedrockAnimationData.Clip clip = animation.clips.get(activeClip);
                sampledClipTime = clip == null ? 0.0 : progress * clip.length;
            }
            wasReloading = true;
            lastMagazine = magazine;
            return;
        }
        if (wasReloading) {
            wasReloading = false;
            startClip("static_idle", animation);
        }

        // Detect a shot: magazine dropped since last frame.
        if (lastMagazine >= 0 && magazine < lastMagazine) {
            String attack = animation.clips.containsKey("shoot") ? "shoot"
                    : firstAvailable(animation, "melee_stock_1", "melee_stock_2", "melee_stock_3");
            startClip(attack, animation);
        }
        lastMagazine = magazine;

        // When a one-shot clip finishes, fall back to idle.
        if (!clipLoop) {
            BedrockAnimationData.Clip clip = animation.clips.get(activeClip);
            if (clip == null || (clip.length > 0 && clipTime() >= clip.length)) {
                startClip("static_idle", animation);
            }
        }
    }

    private void sampleSegmentedReload(BedrockAnimationData animation, boolean empty,
                                       double progress) {
        String intro = empty && animation.clips.containsKey("reload_intro_empty")
                ? "reload_intro_empty" : "reload_intro";
        String selected;
        double segmentProgress;
        if (progress < 0.35) {
            selected = intro;
            segmentProgress = progress / 0.35;
        } else if (progress < 0.80 && animation.clips.containsKey("reload_loop")) {
            selected = "reload_loop";
            segmentProgress = (progress - 0.35) / 0.45;
        } else if (animation.clips.containsKey("reload_end")) {
            selected = "reload_end";
            segmentProgress = (progress - 0.80) / 0.20;
        } else {
            selected = intro;
            segmentProgress = progress;
        }
        if (!selected.equals(activeClip)) {
            startClip(selected, animation);
        }
        BedrockAnimationData.Clip clip = animation.clips.get(selected);
        sampledClipTime = clip == null ? 0.0 : Mth.clamp(segmentProgress, 0.0, 1.0) * clip.length;
    }

    /**
     * Advance state for a held melee rig. A blade has no magazine and no reload, so the clip is
     * chosen from the player's own swing instead: each rising edge of the vanilla swing starts the
     * next authored slash, and the rig returns to idle when that slash finishes.
     */
    public void updateMelee(Player player, String itemId, BedrockAnimationData animation) {
        sampledClipTime = -1.0;
        if (!itemId.equals(currentGunId)) {
            currentGunId = itemId;
            lastMagazine = -1;
            wasReloading = false;
            wasSwinging = player.swinging;
            startClip(firstAvailable(animation, "draw", "static_idle"), animation);
        }

        boolean swinging = player.swinging;
        if (swinging && !wasSwinging) {
            String clip = firstAvailable(
                    animation, MELEE_SWING_CLIPS[meleeSwingIndex % MELEE_SWING_CLIPS.length]);
            meleeSwingIndex++;
            startClip(clip, animation);
        }
        wasSwinging = swinging;

        if (!clipLoop) {
            BedrockAnimationData.Clip clip = animation.clips.get(activeClip);
            if (clip == null || (clip.length > 0 && clipTime() >= clip.length)) {
                startClip("static_idle", animation);
            }
        }
    }

    private void startClip(String clip, BedrockAnimationData animation) {
        BedrockAnimationData.Clip c = animation.clips.get(clip);
        if (c == null) {
            // Fall back to idle if the requested clip is missing.
            clip = "static_idle";
            c = animation.clips.get(clip);
        }
        activeClip = clip;
        clipStartTime = now();
        clipLoop = c != null && c.loop;
    }

    private static String firstAvailable(BedrockAnimationData animation, String... names) {
        for (String name : names) {
            if (animation.clips.containsKey(name)) {
                return name;
            }
        }
        return "static_idle";
    }

    public String clipName() {
        return activeClip;
    }

    public double clipTime() {
        return sampledClipTime >= 0.0 ? sampledClipTime : now() - clipStartTime;
    }

    private static int magazine(ItemStack stack, GunItem gunItem) {
        Integer value = stack.get(WeaponComponents.MAGAZINE.get());
        return value == null ? gunItem.gun().magazineSize() : value;
    }
}
