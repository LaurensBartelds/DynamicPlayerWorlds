package nl.gzmn.playerworlds.core.control;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class EjectPayloadTest {

    @Test
    void encodesAndDecodesPayloadWithReason() {
        UUID uuid = UUID.randomUUID();
        String reason = "World deleted";
        String json = EjectPayload.format(uuid, reason);

        Optional<EjectPayload> parsed = EjectPayload.parse(json);
        assertTrue(parsed.isPresent());
        assertEquals(uuid, parsed.get().playerUuid());
        assertEquals(reason, parsed.get().reason());
    }

    @Test
    void encodesAndDecodesPayloadWithoutReason() {
        UUID uuid = UUID.randomUUID();
        String json = EjectPayload.format(uuid, null);

        Optional<EjectPayload> parsed = EjectPayload.parse(json);
        assertTrue(parsed.isPresent());
        assertEquals(uuid, parsed.get().playerUuid());
        assertNull(parsed.get().reason());
    }

    @Test
    void encodesAndDecodesPayloadWithSpecialCharactersInReason() {
        UUID uuid = UUID.randomUUID();
        String reason = "Kicked: \"Violated rules\"\nGoodbye";
        String json = EjectPayload.format(uuid, reason);

        Optional<EjectPayload> parsed = EjectPayload.parse(json);
        assertTrue(parsed.isPresent());
        assertEquals(uuid, parsed.get().playerUuid());
        assertEquals("Kicked: \"Violated rules\" Goodbye", parsed.get().reason());
    }

    @Test
    void rejectsInvalidJson() {
        assertTrue(EjectPayload.parse(null).isEmpty());
        assertTrue(EjectPayload.parse("").isEmpty());
        assertTrue(EjectPayload.parse("   ").isEmpty());
        assertTrue(EjectPayload.parse("{}").isEmpty());
        assertTrue(EjectPayload.parse("not json").isEmpty());
        assertTrue(EjectPayload.parse("{\"playerUuid\":\"invalid-uuid\"}").isEmpty());
        assertTrue(EjectPayload.parse("{\"reason\":\"only reason\"}").isEmpty());
    }

    @Test
    void constructorAndFormatNullChecks() {
        assertThrows(NullPointerException.class, () -> new EjectPayload(null, "reason"));
        assertThrows(NullPointerException.class, () -> EjectPayload.format(null, "reason"));
    }
}
