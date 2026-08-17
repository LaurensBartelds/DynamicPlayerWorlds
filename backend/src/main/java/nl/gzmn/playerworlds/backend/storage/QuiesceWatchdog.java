package nl.gzmn.playerworlds.backend.storage;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import nl.gzmn.playerworlds.backend.platform.WorldRuntime;
import org.bukkit.World;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Background watchdog ensuring that Minecraft world auto-save is safely restored if a quiesced
 * snapshot operation times out or encounters an unexpected failure.
 */
public final class QuiesceWatchdog {

    private static final Logger log = LoggerFactory.getLogger(QuiesceWatchdog.class);

    private QuiesceWatchdog() {}

    /**
     * Arms a watchdog task to restore auto-save if it remains disabled after {@code timeout}.
     *
     * @param sched scheduled executor service
     * @param runtime world runtime abstraction
     * @param world world dimension to monitor
     * @param timeout duration after which auto-save should be forcefully re-enabled
     * @return scheduled future representing the watchdog task
     */
    public static ScheduledFuture<?> arm(
            ScheduledExecutorService sched, WorldRuntime runtime, World world, Duration timeout) {
        Objects.requireNonNull(sched, "sched");
        Objects.requireNonNull(runtime, "runtime");
        Objects.requireNonNull(world, "world");
        Objects.requireNonNull(timeout, "timeout");

        return sched.schedule(
                () -> {
                    try {
                        if (!runtime.isAutoSave(world)) {
                            log.warn(
                                    "QuiesceWatchdog triggered: auto-save was still disabled for world {}; restoring auto-save",
                                    world.getName());
                            runtime.setAutoSave(world, true);
                        }
                    } catch (Exception e) {
                        log.error(
                                "QuiesceWatchdog failed to check or restore auto-save for world {}",
                                world.getName(),
                                e);
                    }
                },
                timeout.toMillis(),
                TimeUnit.MILLISECONDS);
    }
}
