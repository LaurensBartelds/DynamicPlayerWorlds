package nl.gzmn.playerworlds.core.profile;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import nl.gzmn.playerworlds.core.profile.ProfileEnvelope.StoredLocation;
import nl.gzmn.playerworlds.core.profile.ProfileEnvelope.StoredPotionEffect;

/**
 * Reads and writes {@link ProfileEnvelope} as {@code BYTEA} (FR-17, FR-17a).
 *
 * <p>Binary rather than JSON, and stored as {@code BYTEA} rather than
 * {@code JSONB}, because item NBT is arbitrary binary: a JSON encoding needs
 * base64 anyway and buys no queryability, and PostgreSQL rejects the null byte
 * inside {@code jsonb} strings with {@code unsupported Unicode escape sequence}
 * — which serialised item NBT will certainly contain (FR-17a).
 *
 * <p>The version tag lives in the {@code format_version} column, not in this
 * payload. FR-17 is explicit about why: a payload that cannot be parsed at all
 * can still be identified and migrated if its version is outside it. {@link
 * #FORMAT_VERSION} is what a writer stamps into that column.
 *
 * <p>Anything unreadable throws. FR-16 requires the plugin refuse a profile it
 * cannot deserialise and send the player to lobby with an error, rather than
 * granting an empty inventory — which would look like a wipe and be
 * indistinguishable from one.
 */
public final class ProfileCodec {

    /**
     * The envelope version this build writes.
     *
     * <p>Bump when the field layout below changes, and keep a decoder for every
     * version still retained under {@code storage.manifest-retention-count},
     * because FR-16a's rollback restores an older snapshot whose payload was
     * written by an older build.
     */
    public static final int FORMAT_VERSION = 1;

    /**
     * A ceiling on any single container blob, so a corrupt length field cannot
     * ask for a multi-gigabyte allocation before the payload is rejected. A full
     * inventory of the most NBT-heavy items is orders of magnitude below this.
     */
    private static final int MAX_BLOB_BYTES = 32 * 1024 * 1024;

    private ProfileCodec() {}

    /** Encodes an envelope. The caller stores {@link #FORMAT_VERSION} alongside. */
    public static byte[] encode(ProfileEnvelope profile) {
        Objects.requireNonNull(profile, "profile");
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(1024);
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            writeBlob(out, profile.inventory());
            writeBlob(out, profile.enderChest());
            out.writeInt(profile.xpLevel());
            out.writeFloat(profile.xpProgress());
            out.writeInt(profile.totalExperience());
            out.writeDouble(profile.health());
            out.writeInt(profile.foodLevel());
            out.writeFloat(profile.saturation());

            List<StoredPotionEffect> effects = profile.potionEffects();
            out.writeInt(effects.size());
            for (StoredPotionEffect effect : effects) {
                out.writeUTF(effect.type());
                out.writeInt(effect.duration());
                out.writeInt(effect.amplifier());
                out.writeBoolean(effect.ambient());
                out.writeBoolean(effect.particles());
                out.writeBoolean(effect.icon());
            }

            StoredLocation location = profile.lastLocation();
            out.writeBoolean(location != null);
            if (location != null) {
                out.writeUTF(location.dimension());
                out.writeDouble(location.x());
                out.writeDouble(location.y());
                out.writeDouble(location.z());
                out.writeFloat(location.yaw());
                out.writeFloat(location.pitch());
            }
        } catch (IOException e) {
            // ByteArrayOutputStream does not do IO, so this cannot happen; if it
            // ever does, it is not something a caller can act on.
            throw new IllegalStateException("encoding a profile to memory failed", e);
        }
        return bytes.toByteArray();
    }

    /**
     * Decodes a payload written by {@link #encode}.
     *
     * @param formatVersion the value from the {@code format_version} column
     * @throws ProfileFormatException if the version is unknown or the payload is
     *     malformed. FR-16: refuse rather than hand back a fresh inventory.
     */
    public static ProfileEnvelope decode(byte[] payload, int formatVersion) {
        Objects.requireNonNull(payload, "payload");
        if (formatVersion != FORMAT_VERSION) {
            throw new ProfileFormatException("profile format version " + formatVersion
                    + " is not supported by this build (writes and reads v" + FORMAT_VERSION
                    + "); an admin rollback under FR-16a may be needed");
        }
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(payload))) {
            byte[] inventory = readBlob(in);
            byte[] enderChest = readBlob(in);
            int xpLevel = in.readInt();
            float xpProgress = in.readFloat();
            int totalExperience = in.readInt();
            double health = in.readDouble();
            int foodLevel = in.readInt();
            float saturation = in.readFloat();

            int effectCount = in.readInt();
            if (effectCount < 0 || effectCount > 1024) {
                throw new ProfileFormatException("implausible potion effect count: " + effectCount);
            }
            List<StoredPotionEffect> effects = new ArrayList<>(effectCount);
            for (int i = 0; i < effectCount; i++) {
                effects.add(new StoredPotionEffect(
                        in.readUTF(),
                        in.readInt(),
                        in.readInt(),
                        in.readBoolean(),
                        in.readBoolean(),
                        in.readBoolean()));
            }

            StoredLocation location = null;
            if (in.readBoolean()) {
                location = new StoredLocation(
                        in.readUTF(),
                        in.readDouble(),
                        in.readDouble(),
                        in.readDouble(),
                        in.readFloat(),
                        in.readFloat());
            }
            if (in.read() != -1) {
                throw new ProfileFormatException("trailing bytes after a complete profile payload");
            }
            return new ProfileEnvelope(
                    inventory,
                    enderChest,
                    xpLevel,
                    xpProgress,
                    totalExperience,
                    health,
                    foodLevel,
                    saturation,
                    effects,
                    location);
        } catch (EOFException e) {
            throw new ProfileFormatException("profile payload ended early; it is truncated", e);
        } catch (IOException | IllegalArgumentException e) {
            throw new ProfileFormatException("profile payload is malformed: " + e.getMessage(), e);
        }
    }

    private static void writeBlob(DataOutputStream out, byte[] blob) throws IOException {
        out.writeInt(blob.length);
        out.write(blob);
    }

    private static byte[] readBlob(DataInputStream in) throws IOException {
        int length = in.readInt();
        if (length < 0 || length > MAX_BLOB_BYTES) {
            throw new ProfileFormatException("implausible blob length: " + length);
        }
        byte[] blob = in.readNBytes(length);
        if (blob.length != length) {
            // readNBytes returns short rather than throwing at end of stream, so
            // a truncated payload would otherwise decode into a silently smaller
            // inventory — which looks exactly like items having been stolen.
            throw new ProfileFormatException("blob truncated: declared " + length + " bytes, found " + blob.length);
        }
        return blob;
    }
}
