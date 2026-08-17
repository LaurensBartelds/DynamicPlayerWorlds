/**
 * Durability primitives for snapshot copy and region validation (plan F12, MN-5a,
 * MN-5c, ADR 0006).
 *
 * <p>Steps 4–5 and 7 of the quiesce → snapshot → verify procedure live here so
 * the highest-consequence correctness code is unit-testable without a Minecraft
 * server. Main-thread save / auto-save toggle stays in {@code backend.platform}.
 *
 * <ul>
 *   <li>{@link nl.gzmn.playerworlds.core.storage.SnapshotCopier} — reflink copy
 *       with plain fallback, post-copy re-stat and bounded retry;
 *   <li>{@link nl.gzmn.playerworlds.core.storage.RegionStructure} — Anvil
 *       {@code .mca} structural checks (MN-5c);
 *   <li>{@link nl.gzmn.playerworlds.core.storage.ContentHasher} — SHA-256 with
 *       optional region validation on the same read (plan §9.1 step 7).
 * </ul>
 */
@NullMarked
package nl.gzmn.playerworlds.core.storage;

import org.jspecify.annotations.NullMarked;
