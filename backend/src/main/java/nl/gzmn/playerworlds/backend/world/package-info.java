/**
 * World lifecycle on a node: create, load, portal transit, idle unload.
 *
 * <p>This package orchestrates; it does not know Minecraft versions. Anything
 * version-sensitive goes through {@code backend.platform} — {@code WorldLayout}
 * for folder shapes, {@code WorldLifecycle} for creating and unloading,
 * {@code WorldRuntime} for borders and gamerules, {@code PortalRouting} for
 * destination maths.
 *
 * <p>Two rules run through everything here. No field holds a {@code World}
 * (FR-25b) — worlds are resolved by name at use time, every time. And no method
 * that touches the database runs on the main thread (NFR-2), which is why
 * {@link nl.gzmn.playerworlds.backend.world.LoadedWorld} caches the handful of
 * row values the tick-thread paths need.
 */
@NullMarked
package nl.gzmn.playerworlds.backend.world;

import org.jspecify.annotations.NullMarked;
