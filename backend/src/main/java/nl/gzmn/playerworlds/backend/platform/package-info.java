/**
 * The only package permitted to know Minecraft specifics.
 *
 * <p>Everything version-sensitive lives behind an interface here, so the code
 * that has to change when Paper ships a new Minecraft version is small and
 * findable. Section 5.2 of the foundation plan lists the surfaces this covers:
 *
 * <ul>
 *   <li>{@link nl.gzmn.playerworlds.backend.platform.WorldLayout} — MN-2a paths
 *   <li>{@link nl.gzmn.playerworlds.backend.platform.ItemCodec} — FR-14 / FR-17
 *   <li>{@link nl.gzmn.playerworlds.backend.platform.WorldRuntime} — border, save, gamerules, dragon
 *   <li>{@link nl.gzmn.playerworlds.backend.platform.PortalRouting} — FR-3a
 *   <li>{@link nl.gzmn.playerworlds.backend.platform.ServerIdentity} — D1 / MN-26
 * </ul>
 *
 * <p>{@link nl.gzmn.playerworlds.backend.platform.Platform} selects implementations
 * by chunk data version at enable. Enforced by {@code ArchitectureTest}: no
 * other package may reference the unstable surfaces this one wraps.
 */
@NullMarked
package nl.gzmn.playerworlds.backend.platform;

import org.jspecify.annotations.NullMarked;
