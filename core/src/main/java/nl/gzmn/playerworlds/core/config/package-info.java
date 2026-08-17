/**
 * Typed configuration and the startup validations that refuse to enable on a
 * bad one.
 *
 * <p>Two places hold configuration, and mixing them is how nodes silently
 * disagree (plan section 8.1, OQ-16):
 *
 * <ul>
 *   <li><b>Node-local files</b> — {@link nl.gzmn.playerworlds.core.config.NodeConfig}
 *       and {@link nl.gzmn.playerworlds.core.config.ProxyConfig}. Identity, paths,
 *       credentials and pool size. Readable before the database is.
 *   <li><b>Network policy</b> — {@link nl.gzmn.playerworlds.core.config.NetworkPolicy},
 *       loaded from the {@code network_setting} table. Caps, expiries, retention
 *       counts and defaults. One value, every component, changeable without a
 *       restart.
 * </ul>
 *
 * <p>Invalid configuration disables the plugin loudly rather than running with a
 * default that silently violates a safety property. See {@link
 * nl.gzmn.playerworlds.core.config.ConfigValidator}.
 */
@NullMarked
package nl.gzmn.playerworlds.core.config;

import org.jspecify.annotations.NullMarked;
