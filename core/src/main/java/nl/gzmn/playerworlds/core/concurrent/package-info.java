/**
 * Executor topology and main-thread guards (plan section 9).
 *
 * <p>NFR-2 and NFR-7 are structural here rather than a code-review note:
 * {@link nl.gzmn.playerworlds.core.concurrent.MainThread} is asserted at every
 * boundary, {@link nl.gzmn.playerworlds.core.db.Database} refuses the main
 * thread, and every long operation goes through the bounded pools in
 * {@link nl.gzmn.playerworlds.core.concurrent.PluginExecutors}.
 */
@NullMarked
package nl.gzmn.playerworlds.core.concurrent;

import org.jspecify.annotations.NullMarked;
