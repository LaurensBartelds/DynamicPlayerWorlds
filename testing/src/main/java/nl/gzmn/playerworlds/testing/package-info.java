/**
 * Shared test harness for modules that may depend on {@code :core} (plan section
 * 11, foundation task F9).
 *
 * <p>{@link nl.gzmn.playerworlds.testing.TestDatabase} and
 * {@link nl.gzmn.playerworlds.testing.TestObjectStore} are the Testcontainers
 * factories for PostgreSQL and MinIO. {@link
 * nl.gzmn.playerworlds.testing.WorldFixture} builds synthetic Anvil-shaped
 * folders for storage-layer tests without booting a Minecraft server.
 *
 * <p>{@code :core} does not depend on this module — that would be a cycle,
 * because this module depends on {@code :core}. Core keeps its own
 * {@code TestPostgres} for database tests; the factories here serve
 * {@code :backend}, {@code :proxy} and the e2e harness.
 *
 * <p>One smoke test per CI layer lives under {@code src/test}: unit
 * ({@code WorldFixture}), database, object storage. Architecture smokes stay in
 * the modules that own the packages under test. The MockBukkit plugin-surface
 * smoke lives in {@code :backend}.
 */
@NullMarked
package nl.gzmn.playerworlds.testing;

import org.jspecify.annotations.NullMarked;
