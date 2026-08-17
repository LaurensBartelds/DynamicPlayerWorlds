# 0003. Java 21 with Gradle, not Kotlin

Status: Accepted (2026-08-16)

## Context

The project is greenfield and both plugins target the JVM. Kotlin is common in
modern Minecraft plugins and has two advantages that are genuinely relevant
here:

- **Null safety against the Bukkit API.** `Bukkit.getWorld()` returns null, and
  FR-25b requires re-resolving worlds by name at every use, so null handling is
  not incidental — it is a rule the design depends on.
- **Async ergonomics.** Every database and storage call is off the main thread
  (NFR-2, NFR-7), and `withContext(IO) { … }` followed by a hop back to the main
  thread reads better than nested `CompletableFuture` chains.

Against that, the project's two stated goals are 24/7 operation and cheap
Minecraft upgrades, and both favour the fewest moving parts and the deepest
ecosystem alignment.

## Decision

Java 21, built with Gradle using the Kotlin DSL. Null safety is recovered with
JSpecify annotations and NullAway configured to fail the build. Concurrency uses
explicit bounded executors rather than a coroutine dispatcher.

## Consequences

What is given up is real: NullAway recovers most of Kotlin's null safety but not
all of it, and `CompletableFuture` chains are less pleasant to read than
suspending functions.

What is gained:

- No `kotlin-stdlib` to shade and relocate into two plugin jars that share a
  classloader with other people's plugins.
- No hand-written Bukkit coroutine dispatcher. Getting "which thread am I on"
  wrong is exactly the class of bug that corrupts region files, and it is
  *less* visible in coroutine code than in an explicit scheduler call.
- Every Paper and Velocity upgrade note, example and migration guide applies
  verbatim. When a new Minecraft version breaks something, nobody is
  translating.
- One fewer release treadmill running alongside the Minecraft one.

Two things blunt the Kotlin advantage enough to make this comfortable rather
than merely defensible. Concurrency here is tiny — five worlds and a few dozen
players per node — so the hard problems are ordering and fencing, which
coroutines do not help with. And Java 21 has records, sealed interfaces and
pattern matching, which cover most of the modelling win.

## Notes

Virtual threads are deliberately not used for database work. On Java 21
`synchronized` still pins carrier threads and the PostgreSQL driver
synchronizes, so JDBC stays on a small platform-thread pool. At this scale that
is not a compromise.

The Gradle toolchain version lives in one property, so moving to a newer JDK
when Paper requires it is a one-line change.
