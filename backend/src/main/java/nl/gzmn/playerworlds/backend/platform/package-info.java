/**
 * The only package permitted to know Minecraft specifics.
 *
 * <p>Everything version-sensitive lives behind an interface here, so the code
 * that has to change when Paper ships a new Minecraft version is small and
 * findable. Section 5.2 of the foundation plan lists the surfaces this covers:
 * world folder layout, item serialisation, world runtime settings, portal
 * routing and server identity.
 *
 * <p>Enforced by {@code ArchitectureTest}: no other package may reference the
 * version-sensitive surfaces this one wraps.
 */
@NullMarked
package nl.gzmn.playerworlds.backend.platform;

import org.jspecify.annotations.NullMarked;
