package nl.gzmn.playerworlds.backend.platform;

import org.bukkit.Bukkit;

/**
 * What Minecraft version this node is, for the version gating in section 12.9.
 *
 * @param minecraftVersion display only, for example {@code 1.21.4}. Never
 *     compared — version strings do not order reliably (MN-27).
 * @param dataVersion the chunk {@code DataVersion}. This is the number every
 *     version decision is taken against, because it does order reliably and it
 *     moves in only one direction.
 */
public record ServerIdentity(String minecraftVersion, int dataVersion) {

    /**
     * Reads this node's identity from the running server.
     *
     * <p>{@code UnsafeValues} is the API-sanctioned route to the data version
     * and is deliberately not an internals access: it is part of the Bukkit API
     * surface, carries no NMS types, and there is no other way to obtain the
     * number. The deprecation on it warns that the interface is unstable, which
     * is why the call is confined to this class.
     */
    @SuppressWarnings("deprecation")
    public static ServerIdentity detect() {
        return new ServerIdentity(Bukkit.getMinecraftVersion(), Bukkit.getUnsafe().getDataVersion());
    }
}
