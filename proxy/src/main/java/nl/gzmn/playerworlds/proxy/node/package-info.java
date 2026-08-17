/**
 * Node discovery and routing on the proxy (MN-14, MN-17).
 *
 * <p>Nodes register themselves through the heartbeat table and this package
 * mirrors that into Velocity's server list. MN-17 is explicit that
 * {@code velocity.toml} is not edited to add capacity.
 */
@NullMarked
package nl.gzmn.playerworlds.proxy.node;

import org.jspecify.annotations.NullMarked;
