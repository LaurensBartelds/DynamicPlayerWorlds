package nl.gzmn.playerworlds.core.menu;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.Objects;
import java.util.UUID;
import nl.gzmn.playerworlds.core.model.Visibility;
import nl.gzmn.playerworlds.core.model.WorldId;
import org.jspecify.annotations.Nullable;

/**
 * Hand-rolled binary codec for the menu plugin messaging protocol (FR-27, FR-30a).
 *
 * <p>Encodes and decodes {@link OpenMenu}, {@link IntentEnvelope}, and {@link MenuResult} messages
 * with deterministic binary serialization.
 */
public final class MenuCodec {

    public static final byte MSG_OPEN_MENU = 1;
    public static final byte MSG_INTENT = 2;
    public static final byte MSG_RESULT_OK = 3;
    public static final byte MSG_RESULT_FAILED = 4;

    public static final byte INTENT_JOIN_WORLD = 1;
    public static final byte INTENT_CREATE_WORLD = 2;
    public static final byte INTENT_ARCHIVE_WORLD = 3;
    public static final byte INTENT_RESTORE_WORLD = 4;
    public static final byte INTENT_INVITE_MEMBER = 5;
    public static final byte INTENT_KICK_MEMBER = 6;
    public static final byte INTENT_PROMOTE_MEMBER = 7;
    public static final byte INTENT_SET_VISIBILITY = 8;
    public static final byte INTENT_SET_SETTING = 9;
    public static final byte INTENT_BAN_PLAYER = 10;
    public static final byte INTENT_UNBAN_PLAYER = 11;
    public static final byte INTENT_REQUEST_TRANSFER = 12;
    public static final byte INTENT_ACCEPT_TRANSFER = 13;
    public static final byte INTENT_DECLINE_TRANSFER = 14;
    public static final byte INTENT_ACCEPT_INVITE = 15;

    private MenuCodec() {}

    /**
     * Encodes an {@link OpenMenu} message to bytes.
     *
     * @param msg the OpenMenu message
     * @return encoded byte array
     */
    public static byte[] encodeOpenMenu(OpenMenu msg) {
        Objects.requireNonNull(msg, "msg");
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(baos)) {
            out.writeByte(MSG_OPEN_MENU);
            out.writeLong(msg.correlationId());
        } catch (IOException e) {
            throw new AssertionError("ByteArrayOutputStream should not throw IOException", e);
        }
        return baos.toByteArray();
    }

    /**
     * Decodes an {@link OpenMenu} message from bytes.
     *
     * @param data the byte array
     * @return decoded OpenMenu
     * @throws MenuCodecException if payload is invalid or truncated
     */
    public static OpenMenu decodeOpenMenu(byte[] data) {
        Objects.requireNonNull(data, "data");
        if (data.length == 0) {
            throw new MenuCodecException("Empty payload for OpenMenu");
        }
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(data))) {
            byte type = in.readByte();
            if (type != MSG_OPEN_MENU) {
                throw new MenuCodecException(
                        "Expected message type OPEN_MENU (" + MSG_OPEN_MENU + ") but got: " + type);
            }
            long correlationId = in.readLong();
            if (in.available() > 0) {
                throw new MenuCodecException("Unexpected trailing bytes in OpenMenu payload");
            }
            return new OpenMenu(correlationId);
        } catch (IOException e) {
            throw new MenuCodecException("Failed to decode OpenMenu payload", e);
        }
    }

    /**
     * Encodes a {@link MenuIntent} with its correlation id into bytes.
     *
     * @param correlationId unique correlation id
     * @param intent the menu intent to encode
     * @return encoded byte array
     */
    public static byte[] encodeIntent(long correlationId, MenuIntent intent) {
        Objects.requireNonNull(intent, "intent");
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(baos)) {
            out.writeByte(MSG_INTENT);
            out.writeLong(correlationId);
            encodeIntentPayload(out, intent);
        } catch (IOException e) {
            throw new AssertionError("ByteArrayOutputStream should not throw IOException", e);
        }
        return baos.toByteArray();
    }

    /**
     * Decodes an {@link IntentEnvelope} containing correlation id and {@link MenuIntent} from bytes.
     *
     * @param data the byte array
     * @return decoded IntentEnvelope
     * @throws MenuCodecException if payload is invalid or truncated
     */
    public static IntentEnvelope decodeIntent(byte[] data) {
        Objects.requireNonNull(data, "data");
        if (data.length == 0) {
            throw new MenuCodecException("Empty payload for MenuIntent");
        }
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(data))) {
            byte type = in.readByte();
            if (type != MSG_INTENT) {
                throw new MenuCodecException("Expected message type INTENT (" + MSG_INTENT + ") but got: " + type);
            }
            long correlationId = in.readLong();
            MenuIntent intent = decodeIntentPayload(in);
            if (in.available() > 0) {
                throw new MenuCodecException("Unexpected trailing bytes in MenuIntent payload");
            }
            return new IntentEnvelope(correlationId, intent);
        } catch (IOException e) {
            throw new MenuCodecException("Failed to decode MenuIntent payload", e);
        }
    }

    /**
     * Encodes a {@link MenuResult} to bytes.
     *
     * @param result the menu result (Ok or Failed)
     * @return encoded byte array
     */
    public static byte[] encodeResult(MenuResult result) {
        Objects.requireNonNull(result, "result");
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(baos)) {
            if (result instanceof MenuResult.Ok ok) {
                out.writeByte(MSG_RESULT_OK);
                out.writeLong(ok.correlationId());
                out.writeUTF(ok.message());
            } else if (result instanceof MenuResult.Failed failed) {
                out.writeByte(MSG_RESULT_FAILED);
                out.writeLong(failed.correlationId());
                out.writeUTF(failed.code().name());
                out.writeUTF(failed.message());
            } else {
                throw new IllegalArgumentException(
                        "Unknown MenuResult type: " + result.getClass().getName());
            }
        } catch (IOException e) {
            throw new AssertionError("ByteArrayOutputStream should not throw IOException", e);
        }
        return baos.toByteArray();
    }

    /**
     * Decodes a {@link MenuResult} from bytes.
     *
     * @param data the byte array
     * @return decoded MenuResult
     * @throws MenuCodecException if payload is invalid or truncated
     */
    public static MenuResult decodeResult(byte[] data) {
        Objects.requireNonNull(data, "data");
        if (data.length == 0) {
            throw new MenuCodecException("Empty payload for MenuResult");
        }
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(data))) {
            byte type = in.readByte();
            if (type == MSG_RESULT_OK) {
                long correlationId = in.readLong();
                String message = in.readUTF();
                if (in.available() > 0) {
                    throw new MenuCodecException("Unexpected trailing bytes in MenuResult.Ok payload");
                }
                return new MenuResult.Ok(correlationId, message);
            } else if (type == MSG_RESULT_FAILED) {
                long correlationId = in.readLong();
                String codeName = in.readUTF();
                FailureCode code;
                try {
                    code = FailureCode.fromName(codeName);
                } catch (IllegalArgumentException e) {
                    throw new MenuCodecException("Unknown FailureCode: " + codeName, e);
                }
                String message = in.readUTF();
                if (in.available() > 0) {
                    throw new MenuCodecException("Unexpected trailing bytes in MenuResult.Failed payload");
                }
                return new MenuResult.Failed(correlationId, code, message);
            } else {
                throw new MenuCodecException(
                        "Expected result type (" + MSG_RESULT_OK + " or " + MSG_RESULT_FAILED + ") but got: " + type);
            }
        } catch (IOException e) {
            throw new MenuCodecException("Failed to decode MenuResult payload", e);
        }
    }

    /**
     * Dispatches decoding according to the top-level message type discriminator.
     *
     * @param data raw message bytes
     * @return decoded object ({@link OpenMenu}, {@link IntentEnvelope}, or {@link MenuResult})
     * @throws MenuCodecException if payload is invalid or unknown
     */
    public static Object decode(byte[] data) {
        Objects.requireNonNull(data, "data");
        if (data.length == 0) {
            throw new MenuCodecException("Empty data payload");
        }
        byte type = data[0];
        return switch (type) {
            case MSG_OPEN_MENU -> decodeOpenMenu(data);
            case MSG_INTENT -> decodeIntent(data);
            case MSG_RESULT_OK, MSG_RESULT_FAILED -> decodeResult(data);
            default -> throw new MenuCodecException("Unknown message type: " + type);
        };
    }

    private static void encodeIntentPayload(DataOutputStream out, MenuIntent intent) throws IOException {
        switch (intent) {
            case MenuIntent.JoinWorld joinWorld -> {
                out.writeByte(INTENT_JOIN_WORLD);
                writeWorldId(out, joinWorld.worldId());
            }
            case MenuIntent.CreateWorld createWorld -> {
                out.writeByte(INTENT_CREATE_WORLD);
                out.writeUTF(createWorld.name());
                writeNullableString(out, createWorld.seed());
            }
            case MenuIntent.ArchiveWorld archiveWorld -> {
                out.writeByte(INTENT_ARCHIVE_WORLD);
                out.writeUTF(archiveWorld.worldName());
            }
            case MenuIntent.RestoreWorld restoreWorld -> {
                out.writeByte(INTENT_RESTORE_WORLD);
                out.writeUTF(restoreWorld.worldName());
            }
            case MenuIntent.InviteMember inviteMember -> {
                out.writeByte(INTENT_INVITE_MEMBER);
                out.writeUTF(inviteMember.targetName());
                writeNullableWorldId(out, inviteMember.worldId());
            }
            case MenuIntent.KickMember kickMember -> {
                out.writeByte(INTENT_KICK_MEMBER);
                out.writeUTF(kickMember.targetName());
                writeNullableWorldId(out, kickMember.worldId());
            }
            case MenuIntent.PromoteMember promoteMember -> {
                out.writeByte(INTENT_PROMOTE_MEMBER);
                out.writeUTF(promoteMember.targetName());
                writeNullableWorldId(out, promoteMember.worldId());
            }
            case MenuIntent.SetVisibility setVisibility -> {
                out.writeByte(INTENT_SET_VISIBILITY);
                writeWorldId(out, setVisibility.worldId());
                out.writeUTF(setVisibility.visibility().name());
            }
            case MenuIntent.SetSetting setSetting -> {
                out.writeByte(INTENT_SET_SETTING);
                writeWorldId(out, setSetting.worldId());
                out.writeUTF(setSetting.settingKey());
                out.writeUTF(setSetting.value());
            }
            case MenuIntent.BanPlayer banPlayer -> {
                out.writeByte(INTENT_BAN_PLAYER);
                out.writeUTF(banPlayer.targetName());
                writeNullableWorldId(out, banPlayer.worldId());
                writeNullableString(out, banPlayer.reason());
            }
            case MenuIntent.UnbanPlayer unbanPlayer -> {
                out.writeByte(INTENT_UNBAN_PLAYER);
                out.writeUTF(unbanPlayer.targetName());
                writeNullableWorldId(out, unbanPlayer.worldId());
            }
            case MenuIntent.RequestTransfer requestTransfer -> {
                out.writeByte(INTENT_REQUEST_TRANSFER);
                out.writeUTF(requestTransfer.targetName());
                writeNullableWorldId(out, requestTransfer.worldId());
            }
            case MenuIntent.AcceptTransfer acceptTransfer -> {
                out.writeByte(INTENT_ACCEPT_TRANSFER);
                out.writeUTF(acceptTransfer.ownerName());
            }
            case MenuIntent.DeclineTransfer declineTransfer -> {
                out.writeByte(INTENT_DECLINE_TRANSFER);
                out.writeUTF(declineTransfer.ownerName());
            }
            case MenuIntent.AcceptInvite acceptInvite -> {
                out.writeByte(INTENT_ACCEPT_INVITE);
                out.writeUTF(acceptInvite.ownerName());
            }
        }
    }

    private static MenuIntent decodeIntentPayload(DataInputStream in) throws IOException {
        byte intentType = in.readByte();
        return switch (intentType) {
            case INTENT_JOIN_WORLD -> new MenuIntent.JoinWorld(readWorldId(in));
            case INTENT_CREATE_WORLD -> new MenuIntent.CreateWorld(in.readUTF(), readNullableString(in));
            case INTENT_ARCHIVE_WORLD -> new MenuIntent.ArchiveWorld(in.readUTF());
            case INTENT_RESTORE_WORLD -> new MenuIntent.RestoreWorld(in.readUTF());
            case INTENT_INVITE_MEMBER -> new MenuIntent.InviteMember(in.readUTF(), readNullableWorldId(in));
            case INTENT_KICK_MEMBER -> new MenuIntent.KickMember(in.readUTF(), readNullableWorldId(in));
            case INTENT_PROMOTE_MEMBER -> new MenuIntent.PromoteMember(in.readUTF(), readNullableWorldId(in));
            case INTENT_SET_VISIBILITY -> {
                WorldId worldId = readWorldId(in);
                String visName = in.readUTF();
                try {
                    yield new MenuIntent.SetVisibility(worldId, Visibility.fromWire(visName));
                } catch (IllegalArgumentException e) {
                    throw new MenuCodecException("Unknown Visibility: " + visName, e);
                }
            }
            case INTENT_SET_SETTING -> new MenuIntent.SetSetting(readWorldId(in), in.readUTF(), in.readUTF());
            case INTENT_BAN_PLAYER ->
                new MenuIntent.BanPlayer(in.readUTF(), readNullableWorldId(in), readNullableString(in));
            case INTENT_UNBAN_PLAYER -> new MenuIntent.UnbanPlayer(in.readUTF(), readNullableWorldId(in));
            case INTENT_REQUEST_TRANSFER -> new MenuIntent.RequestTransfer(in.readUTF(), readNullableWorldId(in));
            case INTENT_ACCEPT_TRANSFER -> new MenuIntent.AcceptTransfer(in.readUTF());
            case INTENT_DECLINE_TRANSFER -> new MenuIntent.DeclineTransfer(in.readUTF());
            case INTENT_ACCEPT_INVITE -> new MenuIntent.AcceptInvite(in.readUTF());
            default -> throw new MenuCodecException("Unknown intent type: " + intentType);
        };
    }

    private static void writeWorldId(DataOutputStream out, WorldId worldId) throws IOException {
        out.writeLong(worldId.value().getMostSignificantBits());
        out.writeLong(worldId.value().getLeastSignificantBits());
    }

    private static WorldId readWorldId(DataInputStream in) throws IOException {
        long most = in.readLong();
        long least = in.readLong();
        return new WorldId(new UUID(most, least));
    }

    private static void writeNullableWorldId(DataOutputStream out, @Nullable WorldId worldId) throws IOException {
        if (worldId == null) {
            out.writeBoolean(false);
        } else {
            out.writeBoolean(true);
            writeWorldId(out, worldId);
        }
    }

    private static @Nullable WorldId readNullableWorldId(DataInputStream in) throws IOException {
        boolean present = in.readBoolean();
        return present ? readWorldId(in) : null;
    }

    private static void writeNullableString(DataOutputStream out, @Nullable String str) throws IOException {
        if (str == null) {
            out.writeBoolean(false);
        } else {
            out.writeBoolean(true);
            out.writeUTF(str);
        }
    }

    private static @Nullable String readNullableString(DataInputStream in) throws IOException {
        boolean present = in.readBoolean();
        return present ? in.readUTF() : null;
    }
}
