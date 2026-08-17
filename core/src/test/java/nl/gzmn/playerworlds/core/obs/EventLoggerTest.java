package nl.gzmn.playerworlds.core.obs;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.util.UUID;
import nl.gzmn.playerworlds.core.model.WorldId;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

class EventLoggerTest {

    private Logger logger;
    private ListAppender<ILoggingEvent> appender;

    @BeforeEach
    void attachAppender() {
        logger = (Logger) LoggerFactory.getLogger("test.EventLogger");
        logger.setLevel(Level.INFO);
        appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
    }

    @AfterEach
    void detach() {
        logger.detachAppender(appender);
        MDC.clear();
    }

    @Test
    @DisplayName("info event carries LogEvent key in message and MDC snapshot")
    void eventKeyIsInMessage() {
        EventLogger events = new EventLogger(logger);
        WorldId world = new WorldId(UUID.fromString("33333333-3333-3333-3333-333333333333"));

        events.info(LogEvent.LEASE_ACQUIRE, "claimed generation 3", world, 3L, null);

        assertThat(appender.list).hasSize(1);
        ILoggingEvent line = appender.list.getFirst();
        assertThat(line.getFormattedMessage()).contains("lease.acquire").contains("claimed generation 3");
        assertThat(line.getMDCPropertyMap()).containsEntry(MdcKeys.EVENT, "lease.acquire");
        assertThat(line.getMDCPropertyMap())
                .containsEntry(MdcKeys.WORLD_ID, world.value().toString());
        assertThat(line.getMDCPropertyMap()).containsEntry(MdcKeys.GENERATION, "3");
        // Must not leak after the call.
        assertThat(MDC.get(MdcKeys.EVENT)).isNull();
    }
}
