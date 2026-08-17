package nl.gzmn.playerworlds.core.obs;

import java.util.Objects;
import java.util.UUID;
import nl.gzmn.playerworlds.core.model.WorldId;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.spi.LoggingEventBuilder;

/**
 * Emits NFR-6 structured events through SLF4J.
 *
 * <p>The event name always goes in MDC under {@link MdcKeys#EVENT} and as the
 * SLF4J key-value {@code event}, so a JSON encoder (Logback LogstashEncoder) and
 * a plain pattern layout both surface it. Callers still set world/player/node
 * keys on {@link MdcContext} around broader work; this class only guarantees the
 * event identity cannot be misspelled (it is a {@link LogEvent}).
 */
public final class EventLogger {

    private final Logger log;

    public EventLogger(Logger log) {
        this.log = Objects.requireNonNull(log, "log");
    }

    public static EventLogger create(Class<?> owner) {
        return new EventLogger(org.slf4j.LoggerFactory.getLogger(owner));
    }

    public void info(LogEvent event, String message) {
        emit(log.atInfo(), event, message, null, null, null, null);
    }

    public void info(LogEvent event, String message, @Nullable WorldId worldId) {
        emit(log.atInfo(), event, message, worldId, null, null, null);
    }

    public void info(
            LogEvent event,
            String message,
            @Nullable WorldId worldId,
            @Nullable Long generation,
            @Nullable UUID playerUuid) {
        emit(log.atInfo(), event, message, worldId, generation, playerUuid, null);
    }

    public void warn(LogEvent event, String message) {
        emit(log.atWarn(), event, message, null, null, null, null);
    }

    public void warn(LogEvent event, String message, @Nullable WorldId worldId) {
        emit(log.atWarn(), event, message, worldId, null, null, null);
    }

    public void error(LogEvent event, String message) {
        emit(log.atError(), event, message, null, null, null, null);
    }

    public void error(LogEvent event, String message, @Nullable Throwable cause) {
        emit(log.atError(), event, message, null, null, null, cause);
    }

    private static void emit(
            LoggingEventBuilder builder,
            LogEvent event,
            String message,
            @Nullable WorldId worldId,
            @Nullable Long generation,
            @Nullable UUID playerUuid,
            @Nullable Throwable cause) {
        Objects.requireNonNull(event, "event");
        Objects.requireNonNull(message, "message");

        MdcContext mdc = MdcContext.open()
                .event(event)
                .worldId(worldId)
                .generation(generation)
                .playerUuid(playerUuid);
        try {
            LoggingEventBuilder line = builder.addKeyValue(MdcKeys.EVENT, event.key());
            if (worldId != null) {
                line = line.addKeyValue(MdcKeys.WORLD_ID, worldId.value().toString());
            }
            if (generation != null) {
                line = line.addKeyValue(MdcKeys.GENERATION, generation);
            }
            if (playerUuid != null) {
                line = line.addKeyValue(MdcKeys.PLAYER_UUID, playerUuid.toString());
            }
            if (cause != null) {
                line = line.setCause(cause);
            }
            // Keep the event key in the message body too: some platform log
            // bridges drop MDC and key-value pairs, and NFR-6 still has to be
            // greppable from a plain Paper/Velocity log file.
            line.log("{} | {}", event.key(), message);
        } finally {
            mdc.close();
        }
    }
}
