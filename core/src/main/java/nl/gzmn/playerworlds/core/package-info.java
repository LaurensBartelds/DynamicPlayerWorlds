/**
 * Platform-independent core. Nothing in this package tree may reference Paper,
 * Bukkit or Velocity; see {@code ArchitectureTest} and ADR 0004.
 *
 * <p>The whole tree is {@link org.jspecify.annotations.NullMarked}, so a
 * reference type is non-null unless explicitly annotated otherwise and NullAway
 * fails the build on a missing check.
 */
@NullMarked
package nl.gzmn.playerworlds.core;

import org.jspecify.annotations.NullMarked;
