/**
 * Node selection for a world (MN-14 to MN-16, MN-28).
 *
 * <p>Deliberately in {@code :core} and deliberately free of Velocity, Paper and
 * JDBC. Placement is the one decision in this system that both components have
 * to agree on — the proxy makes it at {@code /world join}, and a node's own load
 * path re-derives the same answer when it decides whether to accept a world — so
 * it is a pure function of values the caller has already read, and it is tested
 * without a database or a server.
 */
@NullMarked
package nl.gzmn.playerworlds.core.placement;

import org.jspecify.annotations.NullMarked;
