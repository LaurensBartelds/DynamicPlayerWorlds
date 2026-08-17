package nl.gzmn.playerworlds.core.profile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Arrays;
import java.util.List;
import nl.gzmn.playerworlds.core.profile.ProfileEnvelope.StoredLocation;
import nl.gzmn.playerworlds.core.profile.ProfileEnvelope.StoredPotionEffect;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The FR-17 envelope, and FR-16's refusal to read a broken one. */
class ProfileCodecTest {

    private static ProfileEnvelope sample() {
        return new ProfileEnvelope(
                new byte[] {1, 2, 3, 0, -1, 127},
                new byte[] {9, 0, 9},
                30,
                0.75f,
                1395,
                18.5,
                17,
                4.25f,
                List.of(
                        new StoredPotionEffect("minecraft:speed", 600, 1, false, true, true),
                        new StoredPotionEffect("minecraft:night_vision", 200, 0, true, false, false)),
                new StoredLocation("pw_abc_nether", 1.5, 64.0, -3.25, 90.0f, -12.5f));
    }

    @Test
    @DisplayName("a full profile round-trips exactly")
    void roundTrip() {
        ProfileEnvelope original = sample();

        ProfileEnvelope decoded = ProfileCodec.decode(ProfileCodec.encode(original), ProfileCodec.FORMAT_VERSION);

        assertThat(decoded).isEqualTo(original);
    }

    @Test
    @DisplayName("item blobs survive null bytes, which is why this is BYTEA and not JSONB (FR-17a)")
    void nullBytesSurvive() {
        // PostgreSQL rejects U+0000 inside jsonb strings, and serialised item NBT
        // will contain null bytes. The payload has to be able to carry them.
        byte[] withNulls = new byte[] {0, 0, 65, 0, -128, 0};
        ProfileEnvelope original = new ProfileEnvelope(withNulls, new byte[0], 0, 0f, 0, 20.0, 20, 5f, List.of(), null);

        ProfileEnvelope decoded = ProfileCodec.decode(ProfileCodec.encode(original), ProfileCodec.FORMAT_VERSION);

        assertThat(decoded.inventory()).containsExactly(0, 0, 65, 0, -128, 0);
    }

    @Test
    @DisplayName("a profile that has never been anywhere has no location")
    void locationIsOptional() {
        ProfileEnvelope original =
                new ProfileEnvelope(new byte[0], new byte[0], 0, 0f, 0, 20.0, 20, 5f, List.of(), null);

        assertThat(ProfileCodec.decode(ProfileCodec.encode(original), ProfileCodec.FORMAT_VERSION)
                        .lastLocation())
                .isNull();
    }

    @Test
    @DisplayName("an unknown format version is refused, not guessed at (FR-16, FR-17)")
    void unknownVersionIsRefused() {
        byte[] payload = ProfileCodec.encode(sample());

        assertThatThrownBy(() -> ProfileCodec.decode(payload, ProfileCodec.FORMAT_VERSION + 1))
                .isInstanceOf(ProfileFormatException.class)
                .hasMessageContaining("FR-16a");
    }

    @Test
    @DisplayName("a truncated payload throws rather than decoding a smaller inventory (FR-16)")
    void truncatedPayloadIsRefused() {
        // The dangerous failure is a short read that succeeds: it would look
        // exactly like items having been stolen, and the player would notice
        // before anyone else did.
        byte[] payload = ProfileCodec.encode(sample());
        for (int cut = 1; cut < payload.length; cut += 7) {
            byte[] truncated = Arrays.copyOf(payload, payload.length - cut);

            assertThatThrownBy(() -> ProfileCodec.decode(truncated, ProfileCodec.FORMAT_VERSION))
                    .as("payload truncated by %d bytes", cut)
                    .isInstanceOf(ProfileFormatException.class);
        }
    }

    @Test
    @DisplayName("trailing bytes are refused, because they mean this is not what we wrote")
    void trailingBytesAreRefused() {
        byte[] payload = ProfileCodec.encode(sample());
        byte[] extended = Arrays.copyOf(payload, payload.length + 1);

        assertThatThrownBy(() -> ProfileCodec.decode(extended, ProfileCodec.FORMAT_VERSION))
                .isInstanceOf(ProfileFormatException.class)
                .hasMessageContaining("trailing");
    }

    @Test
    @DisplayName("a corrupt length field cannot ask for a huge allocation")
    void implausibleLengthIsRefused() {
        byte[] payload = ProfileCodec.encode(sample());
        // Overwrite the first blob's length with something enormous.
        payload[0] = 0x7f;
        payload[1] = 0x7f;
        payload[2] = 0x7f;
        payload[3] = 0x7f;

        assertThatThrownBy(() -> ProfileCodec.decode(payload, ProfileCodec.FORMAT_VERSION))
                .isInstanceOf(ProfileFormatException.class)
                .hasMessageContaining("implausible");
    }

    @Test
    @DisplayName("empty is a valid profile, and is not the same as a broken one")
    void emptyIsValid() {
        ProfileEnvelope empty = new ProfileEnvelope(new byte[0], new byte[0], 0, 0f, 0, 20.0, 20, 5f, List.of(), null);

        assertThat(ProfileCodec.decode(ProfileCodec.encode(empty), ProfileCodec.FORMAT_VERSION))
                .isEqualTo(empty);
    }
}
