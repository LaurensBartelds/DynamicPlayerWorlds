package nl.gzmn.playerworlds.core.model;

import org.jspecify.annotations.Nullable;

/**
 * Per-world settings configured by the owner (FR-9e).
 *
 * <p>Persisted in {@code player_world.settings} as JSONB. Applied to all three
 * dimensions on world load, and re-asserted on restore and cache invalidation.
 *
 * @param pvp whether PVP combat is enabled (default false)
 * @param visitorsMayOpenContainers whether visitors may open chests, barrels, etc. (default false)
 * @param visitorsMayInteract whether visitors may use doors, levers, buttons, etc. (default true)
 * @param mobGriefing whether mob griefing is enabled (default true)
 */
public record WorldSettings(
        boolean pvp, boolean visitorsMayOpenContainers, boolean visitorsMayInteract, boolean mobGriefing) {

    public static final WorldSettings DEFAULTS = new WorldSettings(false, false, true, true);

    public static WorldSettings defaults() {
        return DEFAULTS;
    }

    public WorldSettings withPvp(boolean pvp) {
        return new WorldSettings(pvp, visitorsMayOpenContainers, visitorsMayInteract, mobGriefing);
    }

    public WorldSettings withVisitorsMayOpenContainers(boolean visitorsMayOpenContainers) {
        return new WorldSettings(pvp, visitorsMayOpenContainers, visitorsMayInteract, mobGriefing);
    }

    public WorldSettings withVisitorsMayInteract(boolean visitorsMayInteract) {
        return new WorldSettings(pvp, visitorsMayOpenContainers, visitorsMayInteract, mobGriefing);
    }

    public WorldSettings withMobGriefing(boolean mobGriefing) {
        return new WorldSettings(pvp, visitorsMayOpenContainers, visitorsMayInteract, mobGriefing);
    }

    /** Encodes this instance to a compact JSON string. */
    public String toJson() {
        return "{"
                + "\"pvp\":" + pvp + ","
                + "\"visitorsMayOpenContainers\":" + visitorsMayOpenContainers + ","
                + "\"visitorsMayInteract\":" + visitorsMayInteract + ","
                + "\"mobGriefing\":" + mobGriefing
                + "}";
    }

    /**
     * Decodes a JSON string into {@link WorldSettings}. Missing or unrecognised fields
     * fall back to their default values.
     */
    public static WorldSettings fromJson(@Nullable String json) {
        if (json == null || json.isBlank() || json.equals("{}")) {
            return defaults();
        }

        boolean pvp = extractBoolean(json, "pvp", DEFAULTS.pvp());
        boolean containers = extractBoolean(json, "visitorsMayOpenContainers", DEFAULTS.visitorsMayOpenContainers());
        boolean interact = extractBoolean(json, "visitorsMayInteract", DEFAULTS.visitorsMayInteract());
        boolean mobGriefing = extractBoolean(json, "mobGriefing", DEFAULTS.mobGriefing());

        return new WorldSettings(pvp, containers, interact, mobGriefing);
    }

    private static boolean extractBoolean(String json, String key, boolean defaultValue) {
        String search = "\"" + key + "\"";
        int keyIndex = json.indexOf(search);
        if (keyIndex == -1) {
            return defaultValue;
        }
        int colonIndex = json.indexOf(':', keyIndex + search.length());
        if (colonIndex == -1) {
            return defaultValue;
        }
        int start = colonIndex + 1;
        while (start < json.length() && Character.isWhitespace(json.charAt(start))) {
            start++;
        }
        if (json.startsWith("true", start)) {
            return true;
        }
        if (json.startsWith("false", start)) {
            return false;
        }
        return defaultValue;
    }
}
