/**
 * Reading {@code config.yml} into the typed records {@code :core} defines.
 *
 * <p>Node-local facts only — id, address, paths, credentials. Network-wide policy
 * lives in {@code network_setting} and is read through
 * {@code core.config.NetworkPolicy}, so a cap changed once applies to the proxy
 * and every node without a restart (ADR 0007).
 */
@NullMarked
package nl.gzmn.playerworlds.backend.config;

import org.jspecify.annotations.NullMarked;
