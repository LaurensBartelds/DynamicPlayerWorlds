/**
 * The catalog of admin-configurable, MiniMessage-formatted player-facing text (NFR-5).
 *
 * <p>Message templates are grouped into one small file per area (a command surface or a GUI
 * screen) so independent areas can be authored and reviewed without touching a shared file;
 * {@link nl.gzmn.playerworlds.core.config.messages.MessageRegistry} is the only place that
 * aggregates them. This package holds only key names and default MiniMessage template text —
 * never {@code Component} or any Adventure/MiniMessage type, since it is shaded into both the
 * Paper and Velocity plugins and must not carry platform-specific classes (see {@code
 * ArchitectureTest}). Parsing a template into a {@code Component} happens per-platform, backed by
 * {@link nl.gzmn.playerworlds.core.db.NetworkSettings#messages()}.
 */
@NullMarked
package nl.gzmn.playerworlds.core.config.messages;

import org.jspecify.annotations.NullMarked;
