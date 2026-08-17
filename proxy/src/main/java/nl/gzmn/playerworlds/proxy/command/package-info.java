/**
 * The {@code /world} command tree (specification section 6).
 *
 * <p>Registering the root claims the whole namespace, so the backend entries it
 * keeps — {@code /world leave} and {@code /world report} — are reachable only
 * through the forwarding list in {@code WorldCommand}. See OQ-15.
 */
@NullMarked
package nl.gzmn.playerworlds.proxy.command;

import org.jspecify.annotations.NullMarked;
