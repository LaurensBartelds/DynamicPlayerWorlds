/**
 * Backend commands.
 *
 * <p>Only {@code /pworld}, the operator surface. The player-facing {@code /world}
 * tree is registered on the proxy (specification section 6), and the two backend
 * entries it keeps — {@code /world leave} and {@code /world report} — wait for
 * OQ-15 to settle how the proxy forwards them.
 */
@NullMarked
package nl.gzmn.playerworlds.backend.command;

import org.jspecify.annotations.NullMarked;
