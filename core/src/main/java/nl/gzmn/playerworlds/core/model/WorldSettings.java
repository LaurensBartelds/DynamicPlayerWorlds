package nl.gzmn.playerworlds.core.model;

import org.jspecify.annotations.Nullable;

/**
 * Per-world settings configured by the owner (FR-9e, FR-9i).
 *
 * <p>Persisted in {@code player_world.settings} as JSONB. Applied to all three
 * dimensions on world load, and re-asserted on restore and cache invalidation.
 *
 * @param pvp whether PVP combat is enabled (default true)
 * @param visitorsMayOpenContainers whether visitors may open chests, barrels, etc. (default false)
 * @param visitorsMayInteract whether visitors may use doors, levers, buttons, etc. (default true)
 * @param mobGriefing whether mob griefing is enabled (default true)
 * @param keepInventory whether players keep their inventory on death (default false)
 * @param fallDamage whether players take fall damage (default true)
 * @param fireDamage whether players take fire damage (default true)
 * @param freezeDamage whether players take freeze damage (default true)
 * @param drowningDamage whether players take drowning damage (default true)
 * @param advanceTime whether the day/night cycle advances (default true)
 * @param advanceWeather whether the weather cycle advances (default true)
 * @param spawnPhantoms whether phantoms can spawn from insomnia (default true)
 * @param immediateRespawn whether players skip the death screen on respawn (default false)
 * @param naturalHealthRegeneration whether players regenerate health from hunger (default true)
 * @param playersSleepingPercentage percentage of players who must sleep to skip the night, 0-100 (default 100)
 * @param maxEntityCramming max entities that can occupy one block before taking cramming damage (default 24)
 * @param respawnRadius radius around world spawn in which a respawn may land (default 10)
 * @param maxSnowAccumulationHeight max layers of snow that can accumulate (default 1)
 */
public record WorldSettings(
        boolean pvp,
        boolean visitorsMayOpenContainers,
        boolean visitorsMayInteract,
        boolean mobGriefing,
        boolean keepInventory,
        boolean fallDamage,
        boolean fireDamage,
        boolean freezeDamage,
        boolean drowningDamage,
        boolean advanceTime,
        boolean advanceWeather,
        boolean spawnPhantoms,
        boolean immediateRespawn,
        boolean naturalHealthRegeneration,
        int playersSleepingPercentage,
        int maxEntityCramming,
        int respawnRadius,
        int maxSnowAccumulationHeight) {

    /**
     * PVP starts on; every other default is the conservative one.
     *
     * <p>FR-9e originally said PVP off. It is on because the worlds people
     * actually invite each other into are ones where they expect to be able to
     * fight, and a setting nobody knows to look for reads as a broken world
     * rather than as a safe one. The container and interact defaults are
     * unchanged: those protect blocks a visitor could take or break, which is
     * loss rather than surprise, and the asymmetry is the point.
     *
     * <p>A world stores {@code '{}'} until its owner touches a setting, so this
     * value is what every existing world reads back as -- changing it changes
     * them all, which is intended. The FR-9i additions all default to vanilla's
     * own defaults, so a world that never touches them behaves exactly as an
     * untouched vanilla world would.
     */
    public static final WorldSettings DEFAULTS = new WorldSettings(
            true, false, true, true, false, true, true, true, true, true, true, true, false, true, 100, 24, 10, 1);

    public static WorldSettings defaults() {
        return DEFAULTS;
    }

    public WorldSettings withPvp(boolean pvp) {
        return new WorldSettings(
                pvp,
                visitorsMayOpenContainers,
                visitorsMayInteract,
                mobGriefing,
                keepInventory,
                fallDamage,
                fireDamage,
                freezeDamage,
                drowningDamage,
                advanceTime,
                advanceWeather,
                spawnPhantoms,
                immediateRespawn,
                naturalHealthRegeneration,
                playersSleepingPercentage,
                maxEntityCramming,
                respawnRadius,
                maxSnowAccumulationHeight);
    }

    public WorldSettings withVisitorsMayOpenContainers(boolean visitorsMayOpenContainers) {
        return new WorldSettings(
                pvp,
                visitorsMayOpenContainers,
                visitorsMayInteract,
                mobGriefing,
                keepInventory,
                fallDamage,
                fireDamage,
                freezeDamage,
                drowningDamage,
                advanceTime,
                advanceWeather,
                spawnPhantoms,
                immediateRespawn,
                naturalHealthRegeneration,
                playersSleepingPercentage,
                maxEntityCramming,
                respawnRadius,
                maxSnowAccumulationHeight);
    }

    public WorldSettings withVisitorsMayInteract(boolean visitorsMayInteract) {
        return new WorldSettings(
                pvp,
                visitorsMayOpenContainers,
                visitorsMayInteract,
                mobGriefing,
                keepInventory,
                fallDamage,
                fireDamage,
                freezeDamage,
                drowningDamage,
                advanceTime,
                advanceWeather,
                spawnPhantoms,
                immediateRespawn,
                naturalHealthRegeneration,
                playersSleepingPercentage,
                maxEntityCramming,
                respawnRadius,
                maxSnowAccumulationHeight);
    }

    public WorldSettings withMobGriefing(boolean mobGriefing) {
        return new WorldSettings(
                pvp,
                visitorsMayOpenContainers,
                visitorsMayInteract,
                mobGriefing,
                keepInventory,
                fallDamage,
                fireDamage,
                freezeDamage,
                drowningDamage,
                advanceTime,
                advanceWeather,
                spawnPhantoms,
                immediateRespawn,
                naturalHealthRegeneration,
                playersSleepingPercentage,
                maxEntityCramming,
                respawnRadius,
                maxSnowAccumulationHeight);
    }

    public WorldSettings withKeepInventory(boolean keepInventory) {
        return new WorldSettings(
                pvp,
                visitorsMayOpenContainers,
                visitorsMayInteract,
                mobGriefing,
                keepInventory,
                fallDamage,
                fireDamage,
                freezeDamage,
                drowningDamage,
                advanceTime,
                advanceWeather,
                spawnPhantoms,
                immediateRespawn,
                naturalHealthRegeneration,
                playersSleepingPercentage,
                maxEntityCramming,
                respawnRadius,
                maxSnowAccumulationHeight);
    }

    public WorldSettings withFallDamage(boolean fallDamage) {
        return new WorldSettings(
                pvp,
                visitorsMayOpenContainers,
                visitorsMayInteract,
                mobGriefing,
                keepInventory,
                fallDamage,
                fireDamage,
                freezeDamage,
                drowningDamage,
                advanceTime,
                advanceWeather,
                spawnPhantoms,
                immediateRespawn,
                naturalHealthRegeneration,
                playersSleepingPercentage,
                maxEntityCramming,
                respawnRadius,
                maxSnowAccumulationHeight);
    }

    public WorldSettings withFireDamage(boolean fireDamage) {
        return new WorldSettings(
                pvp,
                visitorsMayOpenContainers,
                visitorsMayInteract,
                mobGriefing,
                keepInventory,
                fallDamage,
                fireDamage,
                freezeDamage,
                drowningDamage,
                advanceTime,
                advanceWeather,
                spawnPhantoms,
                immediateRespawn,
                naturalHealthRegeneration,
                playersSleepingPercentage,
                maxEntityCramming,
                respawnRadius,
                maxSnowAccumulationHeight);
    }

    public WorldSettings withFreezeDamage(boolean freezeDamage) {
        return new WorldSettings(
                pvp,
                visitorsMayOpenContainers,
                visitorsMayInteract,
                mobGriefing,
                keepInventory,
                fallDamage,
                fireDamage,
                freezeDamage,
                drowningDamage,
                advanceTime,
                advanceWeather,
                spawnPhantoms,
                immediateRespawn,
                naturalHealthRegeneration,
                playersSleepingPercentage,
                maxEntityCramming,
                respawnRadius,
                maxSnowAccumulationHeight);
    }

    public WorldSettings withDrowningDamage(boolean drowningDamage) {
        return new WorldSettings(
                pvp,
                visitorsMayOpenContainers,
                visitorsMayInteract,
                mobGriefing,
                keepInventory,
                fallDamage,
                fireDamage,
                freezeDamage,
                drowningDamage,
                advanceTime,
                advanceWeather,
                spawnPhantoms,
                immediateRespawn,
                naturalHealthRegeneration,
                playersSleepingPercentage,
                maxEntityCramming,
                respawnRadius,
                maxSnowAccumulationHeight);
    }

    public WorldSettings withAdvanceTime(boolean advanceTime) {
        return new WorldSettings(
                pvp,
                visitorsMayOpenContainers,
                visitorsMayInteract,
                mobGriefing,
                keepInventory,
                fallDamage,
                fireDamage,
                freezeDamage,
                drowningDamage,
                advanceTime,
                advanceWeather,
                spawnPhantoms,
                immediateRespawn,
                naturalHealthRegeneration,
                playersSleepingPercentage,
                maxEntityCramming,
                respawnRadius,
                maxSnowAccumulationHeight);
    }

    public WorldSettings withAdvanceWeather(boolean advanceWeather) {
        return new WorldSettings(
                pvp,
                visitorsMayOpenContainers,
                visitorsMayInteract,
                mobGriefing,
                keepInventory,
                fallDamage,
                fireDamage,
                freezeDamage,
                drowningDamage,
                advanceTime,
                advanceWeather,
                spawnPhantoms,
                immediateRespawn,
                naturalHealthRegeneration,
                playersSleepingPercentage,
                maxEntityCramming,
                respawnRadius,
                maxSnowAccumulationHeight);
    }

    public WorldSettings withSpawnPhantoms(boolean spawnPhantoms) {
        return new WorldSettings(
                pvp,
                visitorsMayOpenContainers,
                visitorsMayInteract,
                mobGriefing,
                keepInventory,
                fallDamage,
                fireDamage,
                freezeDamage,
                drowningDamage,
                advanceTime,
                advanceWeather,
                spawnPhantoms,
                immediateRespawn,
                naturalHealthRegeneration,
                playersSleepingPercentage,
                maxEntityCramming,
                respawnRadius,
                maxSnowAccumulationHeight);
    }

    public WorldSettings withImmediateRespawn(boolean immediateRespawn) {
        return new WorldSettings(
                pvp,
                visitorsMayOpenContainers,
                visitorsMayInteract,
                mobGriefing,
                keepInventory,
                fallDamage,
                fireDamage,
                freezeDamage,
                drowningDamage,
                advanceTime,
                advanceWeather,
                spawnPhantoms,
                immediateRespawn,
                naturalHealthRegeneration,
                playersSleepingPercentage,
                maxEntityCramming,
                respawnRadius,
                maxSnowAccumulationHeight);
    }

    public WorldSettings withNaturalHealthRegeneration(boolean naturalHealthRegeneration) {
        return new WorldSettings(
                pvp,
                visitorsMayOpenContainers,
                visitorsMayInteract,
                mobGriefing,
                keepInventory,
                fallDamage,
                fireDamage,
                freezeDamage,
                drowningDamage,
                advanceTime,
                advanceWeather,
                spawnPhantoms,
                immediateRespawn,
                naturalHealthRegeneration,
                playersSleepingPercentage,
                maxEntityCramming,
                respawnRadius,
                maxSnowAccumulationHeight);
    }

    public WorldSettings withPlayersSleepingPercentage(int playersSleepingPercentage) {
        return new WorldSettings(
                pvp,
                visitorsMayOpenContainers,
                visitorsMayInteract,
                mobGriefing,
                keepInventory,
                fallDamage,
                fireDamage,
                freezeDamage,
                drowningDamage,
                advanceTime,
                advanceWeather,
                spawnPhantoms,
                immediateRespawn,
                naturalHealthRegeneration,
                playersSleepingPercentage,
                maxEntityCramming,
                respawnRadius,
                maxSnowAccumulationHeight);
    }

    public WorldSettings withMaxEntityCramming(int maxEntityCramming) {
        return new WorldSettings(
                pvp,
                visitorsMayOpenContainers,
                visitorsMayInteract,
                mobGriefing,
                keepInventory,
                fallDamage,
                fireDamage,
                freezeDamage,
                drowningDamage,
                advanceTime,
                advanceWeather,
                spawnPhantoms,
                immediateRespawn,
                naturalHealthRegeneration,
                playersSleepingPercentage,
                maxEntityCramming,
                respawnRadius,
                maxSnowAccumulationHeight);
    }

    public WorldSettings withRespawnRadius(int respawnRadius) {
        return new WorldSettings(
                pvp,
                visitorsMayOpenContainers,
                visitorsMayInteract,
                mobGriefing,
                keepInventory,
                fallDamage,
                fireDamage,
                freezeDamage,
                drowningDamage,
                advanceTime,
                advanceWeather,
                spawnPhantoms,
                immediateRespawn,
                naturalHealthRegeneration,
                playersSleepingPercentage,
                maxEntityCramming,
                respawnRadius,
                maxSnowAccumulationHeight);
    }

    public WorldSettings withMaxSnowAccumulationHeight(int maxSnowAccumulationHeight) {
        return new WorldSettings(
                pvp,
                visitorsMayOpenContainers,
                visitorsMayInteract,
                mobGriefing,
                keepInventory,
                fallDamage,
                fireDamage,
                freezeDamage,
                drowningDamage,
                advanceTime,
                advanceWeather,
                spawnPhantoms,
                immediateRespawn,
                naturalHealthRegeneration,
                playersSleepingPercentage,
                maxEntityCramming,
                respawnRadius,
                maxSnowAccumulationHeight);
    }

    /** Encodes this instance to a compact JSON string. */
    public String toJson() {
        return "{"
                + "\"pvp\":" + pvp + ","
                + "\"visitorsMayOpenContainers\":" + visitorsMayOpenContainers + ","
                + "\"visitorsMayInteract\":" + visitorsMayInteract + ","
                + "\"mobGriefing\":" + mobGriefing + ","
                + "\"keepInventory\":" + keepInventory + ","
                + "\"fallDamage\":" + fallDamage + ","
                + "\"fireDamage\":" + fireDamage + ","
                + "\"freezeDamage\":" + freezeDamage + ","
                + "\"drowningDamage\":" + drowningDamage + ","
                + "\"advanceTime\":" + advanceTime + ","
                + "\"advanceWeather\":" + advanceWeather + ","
                + "\"spawnPhantoms\":" + spawnPhantoms + ","
                + "\"immediateRespawn\":" + immediateRespawn + ","
                + "\"naturalHealthRegeneration\":" + naturalHealthRegeneration + ","
                + "\"playersSleepingPercentage\":" + playersSleepingPercentage + ","
                + "\"maxEntityCramming\":" + maxEntityCramming + ","
                + "\"respawnRadius\":" + respawnRadius + ","
                + "\"maxSnowAccumulationHeight\":" + maxSnowAccumulationHeight
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
        boolean keepInventory = extractBoolean(json, "keepInventory", DEFAULTS.keepInventory());
        boolean fallDamage = extractBoolean(json, "fallDamage", DEFAULTS.fallDamage());
        boolean fireDamage = extractBoolean(json, "fireDamage", DEFAULTS.fireDamage());
        boolean freezeDamage = extractBoolean(json, "freezeDamage", DEFAULTS.freezeDamage());
        boolean drowningDamage = extractBoolean(json, "drowningDamage", DEFAULTS.drowningDamage());
        boolean advanceTime = extractBoolean(json, "advanceTime", DEFAULTS.advanceTime());
        boolean advanceWeather = extractBoolean(json, "advanceWeather", DEFAULTS.advanceWeather());
        boolean spawnPhantoms = extractBoolean(json, "spawnPhantoms", DEFAULTS.spawnPhantoms());
        boolean immediateRespawn = extractBoolean(json, "immediateRespawn", DEFAULTS.immediateRespawn());
        boolean naturalHealthRegeneration =
                extractBoolean(json, "naturalHealthRegeneration", DEFAULTS.naturalHealthRegeneration());
        int playersSleepingPercentage =
                extractInt(json, "playersSleepingPercentage", DEFAULTS.playersSleepingPercentage());
        int maxEntityCramming = extractInt(json, "maxEntityCramming", DEFAULTS.maxEntityCramming());
        int respawnRadius = extractInt(json, "respawnRadius", DEFAULTS.respawnRadius());
        int maxSnowAccumulationHeight =
                extractInt(json, "maxSnowAccumulationHeight", DEFAULTS.maxSnowAccumulationHeight());

        return new WorldSettings(
                pvp,
                containers,
                interact,
                mobGriefing,
                keepInventory,
                fallDamage,
                fireDamage,
                freezeDamage,
                drowningDamage,
                advanceTime,
                advanceWeather,
                spawnPhantoms,
                immediateRespawn,
                naturalHealthRegeneration,
                playersSleepingPercentage,
                maxEntityCramming,
                respawnRadius,
                maxSnowAccumulationHeight);
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

    private static int extractInt(String json, String key, int defaultValue) {
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
        int end = start;
        if (end < json.length() && json.charAt(end) == '-') {
            end++;
        }
        int digitsStart = end;
        while (end < json.length() && Character.isDigit(json.charAt(end))) {
            end++;
        }
        if (end == digitsStart) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(json.substring(start, end));
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }
}
