/**
 * Control plane protocol (specification section 13, ADR 0002).
 *
 * <p>Directed commands travel as durable {@code node_command} rows. {@code
 * NOTIFY} is only a latency optimisation; polling is the contract. Handlers are
 * registered by later milestones — this package delivers the wire shape, claim
 * rules and the listener that never double-executes a concurrent claim.
 *
 * <p>JDBC stays in {@code core.db}. This package owns kinds, outcomes and the
 * orchestration that turns a claimed row into a handler call.
 */
@NullMarked
package nl.gzmn.playerworlds.core.control;

import org.jspecify.annotations.NullMarked;
