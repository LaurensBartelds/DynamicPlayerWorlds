package nl.gzmn.playerworlds.core.menu;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import nl.gzmn.playerworlds.core.model.Visibility;
import nl.gzmn.playerworlds.core.model.WorldId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class MenuCodecTest {

    @Nested
    @DisplayName("OpenMenu codec")
    class OpenMenuTests {

        @Test
        @DisplayName("round-trips OpenMenu message")
        void roundTripsOpenMenu() {
            OpenMenu original = new OpenMenu(42424242L);
            byte[] encoded = MenuCodec.encodeOpenMenu(original);
            OpenMenu decoded = MenuCodec.decodeOpenMenu(encoded);

            assertThat(decoded).isEqualTo(original);
            assertThat(decoded.correlationId()).isEqualTo(42424242L);
        }

        @Test
        @DisplayName("decodeOpenMenu rejects mismatched message type")
        void rejectsMismatchedType() {
            byte[] encodedResult = MenuCodec.encodeResult(new MenuResult.Ok(1L, "ok"));
            assertThatThrownBy(() -> MenuCodec.decodeOpenMenu(encodedResult)).isInstanceOf(MenuCodecException.class);
        }
    }

    @Nested
    @DisplayName("MenuResult codec")
    class MenuResultTests {

        @Test
        @DisplayName("round-trips MenuResult.Ok")
        void roundTripsOk() {
            MenuResult.Ok original = new MenuResult.Ok(999L, "World created successfully");
            byte[] encoded = MenuCodec.encodeResult(original);
            MenuResult decoded = MenuCodec.decodeResult(encoded);

            assertThat(decoded).isInstanceOf(MenuResult.Ok.class);
            MenuResult.Ok ok = (MenuResult.Ok) decoded;
            assertThat(ok.correlationId()).isEqualTo(999L);
            assertThat(ok.message()).isEqualTo("World created successfully");
        }

        @ParameterizedTest
        @EnumSource(FailureCode.class)
        @DisplayName("round-trips MenuResult.Failed for all FailureCode values")
        void roundTripsFailedAllCodes(FailureCode code) {
            MenuResult.Failed original = new MenuResult.Failed(12345L, code, "Failed due to: " + code.name());
            byte[] encoded = MenuCodec.encodeResult(original);
            MenuResult decoded = MenuCodec.decodeResult(encoded);

            assertThat(decoded).isInstanceOf(MenuResult.Failed.class);
            MenuResult.Failed failed = (MenuResult.Failed) decoded;
            assertThat(failed.correlationId()).isEqualTo(12345L);
            assertThat(failed.code()).isEqualTo(code);
            assertThat(failed.message()).isEqualTo("Failed due to: " + code.name());
        }

        @Test
        @DisplayName("decodeResult rejects mismatched message type")
        void rejectsMismatchedType() {
            byte[] encodedOpen = MenuCodec.encodeOpenMenu(new OpenMenu(1L));
            assertThatThrownBy(() -> MenuCodec.decodeResult(encodedOpen)).isInstanceOf(MenuCodecException.class);
        }
    }

    @Nested
    @DisplayName("MenuIntent codec")
    class MenuIntentTests {

        private final long correlationId = 777L;

        @Test
        @DisplayName("round-trips JoinWorld")
        void roundTripsJoinWorld() {
            WorldId worldId = WorldId.random();
            MenuIntent.JoinWorld intent = new MenuIntent.JoinWorld(worldId);

            byte[] encoded = MenuCodec.encodeIntent(correlationId, intent);
            IntentEnvelope envelope = MenuCodec.decodeIntent(encoded);

            assertThat(envelope.correlationId()).isEqualTo(correlationId);
            assertThat(envelope.intent()).isEqualTo(intent);
        }

        @Test
        @DisplayName("round-trips CreateWorld with seed")
        void roundTripsCreateWorldWithSeed() {
            MenuIntent.CreateWorld intent = new MenuIntent.CreateWorld("my-survival", "123456789");

            byte[] encoded = MenuCodec.encodeIntent(correlationId, intent);
            IntentEnvelope envelope = MenuCodec.decodeIntent(encoded);

            assertThat(envelope.correlationId()).isEqualTo(correlationId);
            assertThat(envelope.intent()).isEqualTo(intent);
        }

        @Test
        @DisplayName("round-trips CreateWorld with null seed")
        void roundTripsCreateWorldNullSeed() {
            MenuIntent.CreateWorld intent = new MenuIntent.CreateWorld("my-creative", null);

            byte[] encoded = MenuCodec.encodeIntent(correlationId, intent);
            IntentEnvelope envelope = MenuCodec.decodeIntent(encoded);

            assertThat(envelope.correlationId()).isEqualTo(correlationId);
            assertThat(envelope.intent()).isEqualTo(intent);
        }

        @Test
        @DisplayName("round-trips ArchiveWorld")
        void roundTripsArchiveWorld() {
            MenuIntent.ArchiveWorld intent = new MenuIntent.ArchiveWorld("old-world");

            byte[] encoded = MenuCodec.encodeIntent(correlationId, intent);
            IntentEnvelope envelope = MenuCodec.decodeIntent(encoded);

            assertThat(envelope.correlationId()).isEqualTo(correlationId);
            assertThat(envelope.intent()).isEqualTo(intent);
        }

        @Test
        @DisplayName("round-trips RestoreWorld")
        void roundTripsRestoreWorld() {
            MenuIntent.RestoreWorld intent = new MenuIntent.RestoreWorld("archived-world");

            byte[] encoded = MenuCodec.encodeIntent(correlationId, intent);
            IntentEnvelope envelope = MenuCodec.decodeIntent(encoded);

            assertThat(envelope.correlationId()).isEqualTo(correlationId);
            assertThat(envelope.intent()).isEqualTo(intent);
        }

        @Test
        @DisplayName("round-trips InviteMember with worldId and null worldId")
        void roundTripsInviteMember() {
            WorldId worldId = WorldId.random();
            MenuIntent.InviteMember withWorld = new MenuIntent.InviteMember("Bob", worldId);
            MenuIntent.InviteMember withoutWorld = new MenuIntent.InviteMember("Alice", null);

            byte[] encoded1 = MenuCodec.encodeIntent(1L, withWorld);
            byte[] encoded2 = MenuCodec.encodeIntent(2L, withoutWorld);

            assertThat(MenuCodec.decodeIntent(encoded1).intent()).isEqualTo(withWorld);
            assertThat(MenuCodec.decodeIntent(encoded2).intent()).isEqualTo(withoutWorld);
        }

        @Test
        @DisplayName("round-trips KickMember with worldId and null worldId")
        void roundTripsKickMember() {
            WorldId worldId = WorldId.random();
            MenuIntent.KickMember withWorld = new MenuIntent.KickMember("Griefer", worldId);
            MenuIntent.KickMember withoutWorld = new MenuIntent.KickMember("Troll", null);

            byte[] encoded1 = MenuCodec.encodeIntent(1L, withWorld);
            byte[] encoded2 = MenuCodec.encodeIntent(2L, withoutWorld);

            assertThat(MenuCodec.decodeIntent(encoded1).intent()).isEqualTo(withWorld);
            assertThat(MenuCodec.decodeIntent(encoded2).intent()).isEqualTo(withoutWorld);
        }

        @Test
        @DisplayName("round-trips PromoteMember with worldId and null worldId")
        void roundTripsPromoteMember() {
            WorldId worldId = WorldId.random();
            MenuIntent.PromoteMember withWorld = new MenuIntent.PromoteMember("Trusted", worldId);
            MenuIntent.PromoteMember withoutWorld = new MenuIntent.PromoteMember("Friend", null);

            byte[] encoded1 = MenuCodec.encodeIntent(1L, withWorld);
            byte[] encoded2 = MenuCodec.encodeIntent(2L, withoutWorld);

            assertThat(MenuCodec.decodeIntent(encoded1).intent()).isEqualTo(withWorld);
            assertThat(MenuCodec.decodeIntent(encoded2).intent()).isEqualTo(withoutWorld);
        }

        @Test
        @DisplayName("round-trips SetVisibility with PRIVATE and PUBLIC")
        void roundTripsSetVisibility() {
            WorldId worldId = WorldId.random();
            MenuIntent.SetVisibility priv = new MenuIntent.SetVisibility(worldId, Visibility.PRIVATE);
            MenuIntent.SetVisibility pub = new MenuIntent.SetVisibility(worldId, Visibility.PUBLIC);

            byte[] encPriv = MenuCodec.encodeIntent(1L, priv);
            byte[] encPub = MenuCodec.encodeIntent(2L, pub);

            assertThat(MenuCodec.decodeIntent(encPriv).intent()).isEqualTo(priv);
            assertThat(MenuCodec.decodeIntent(encPub).intent()).isEqualTo(pub);
        }

        @Test
        @DisplayName("round-trips SetSetting")
        void roundTripsSetSetting() {
            WorldId worldId = WorldId.random();
            MenuIntent.SetSetting intent = new MenuIntent.SetSetting(worldId, "pvp", "true");

            byte[] encoded = MenuCodec.encodeIntent(correlationId, intent);
            IntentEnvelope envelope = MenuCodec.decodeIntent(encoded);

            assertThat(envelope.correlationId()).isEqualTo(correlationId);
            assertThat(envelope.intent()).isEqualTo(intent);
        }

        @Test
        @DisplayName("round-trips BanPlayer with all combinations of nulls")
        void roundTripsBanPlayer() {
            WorldId worldId = WorldId.random();
            MenuIntent.BanPlayer all = new MenuIntent.BanPlayer("BadActor", worldId, "Spamming");
            MenuIntent.BanPlayer noReason = new MenuIntent.BanPlayer("BadActor2", worldId, null);
            MenuIntent.BanPlayer noWorld = new MenuIntent.BanPlayer("BadActor3", null, "Grief");
            MenuIntent.BanPlayer neither = new MenuIntent.BanPlayer("BadActor4", null, null);

            assertThat(MenuCodec.decodeIntent(MenuCodec.encodeIntent(1L, all)).intent())
                    .isEqualTo(all);
            assertThat(MenuCodec.decodeIntent(MenuCodec.encodeIntent(2L, noReason))
                            .intent())
                    .isEqualTo(noReason);
            assertThat(MenuCodec.decodeIntent(MenuCodec.encodeIntent(3L, noWorld))
                            .intent())
                    .isEqualTo(noWorld);
            assertThat(MenuCodec.decodeIntent(MenuCodec.encodeIntent(4L, neither))
                            .intent())
                    .isEqualTo(neither);
        }

        @Test
        @DisplayName("round-trips UnbanPlayer with worldId and null worldId")
        void roundTripsUnbanPlayer() {
            WorldId worldId = WorldId.random();
            MenuIntent.UnbanPlayer withWorld = new MenuIntent.UnbanPlayer("Reformed", worldId);
            MenuIntent.UnbanPlayer withoutWorld = new MenuIntent.UnbanPlayer("Forgiven", null);

            assertThat(MenuCodec.decodeIntent(MenuCodec.encodeIntent(1L, withWorld))
                            .intent())
                    .isEqualTo(withWorld);
            assertThat(MenuCodec.decodeIntent(MenuCodec.encodeIntent(2L, withoutWorld))
                            .intent())
                    .isEqualTo(withoutWorld);
        }

        @Test
        @DisplayName("round-trips RequestTransfer with worldId and null worldId")
        void roundTripsRequestTransfer() {
            WorldId worldId = WorldId.random();
            MenuIntent.RequestTransfer withWorld = new MenuIntent.RequestTransfer("NewOwner", worldId);
            MenuIntent.RequestTransfer withoutWorld = new MenuIntent.RequestTransfer("NewOwner2", null);

            assertThat(MenuCodec.decodeIntent(MenuCodec.encodeIntent(1L, withWorld))
                            .intent())
                    .isEqualTo(withWorld);
            assertThat(MenuCodec.decodeIntent(MenuCodec.encodeIntent(2L, withoutWorld))
                            .intent())
                    .isEqualTo(withoutWorld);
        }

        @Test
        @DisplayName("round-trips AcceptTransfer")
        void roundTripsAcceptTransfer() {
            MenuIntent.AcceptTransfer intent = new MenuIntent.AcceptTransfer("PreviousOwner");
            byte[] encoded = MenuCodec.encodeIntent(correlationId, intent);
            assertThat(MenuCodec.decodeIntent(encoded).intent()).isEqualTo(intent);
        }

        @Test
        @DisplayName("round-trips DeclineTransfer")
        void roundTripsDeclineTransfer() {
            MenuIntent.DeclineTransfer intent = new MenuIntent.DeclineTransfer("UnwantedGiver");
            byte[] encoded = MenuCodec.encodeIntent(correlationId, intent);
            assertThat(MenuCodec.decodeIntent(encoded).intent()).isEqualTo(intent);
        }

        @Test
        @DisplayName("round-trips AcceptInvite")
        void roundTripsAcceptInvite() {
            MenuIntent.AcceptInvite intent = new MenuIntent.AcceptInvite("InviterPerson");
            byte[] encoded = MenuCodec.encodeIntent(correlationId, intent);
            assertThat(MenuCodec.decodeIntent(encoded).intent()).isEqualTo(intent);
        }

        @Test
        @DisplayName("round-trips HardDeleteWorld")
        void roundTripsHardDeleteWorld() {
            WorldId worldId = WorldId.random();
            MenuIntent.HardDeleteWorld intent = new MenuIntent.HardDeleteWorld(worldId);
            byte[] encoded = MenuCodec.encodeIntent(correlationId, intent);
            IntentEnvelope envelope = MenuCodec.decodeIntent(encoded);
            assertThat(envelope.correlationId()).isEqualTo(correlationId);
            assertThat(envelope.intent()).isEqualTo(intent);
        }
    }

    @Nested
    @DisplayName("Generic decode method")
    class GenericDecodeTests {

        @Test
        @DisplayName("decode dispatches OpenMenu, IntentEnvelope, and MenuResult")
        void dispatchesAllTypes() {
            OpenMenu openMenu = new OpenMenu(10L);
            IntentEnvelope envelope = new IntentEnvelope(20L, new MenuIntent.RestoreWorld("demo"));
            MenuResult ok = new MenuResult.Ok(30L, "done");
            MenuResult failed = new MenuResult.Failed(40L, FailureCode.PERMISSION_DENIED, "no");

            assertThat(MenuCodec.decode(MenuCodec.encodeOpenMenu(openMenu))).isEqualTo(openMenu);
            assertThat(MenuCodec.decode(MenuCodec.encodeIntent(envelope.correlationId(), envelope.intent())))
                    .isEqualTo(envelope);
            assertThat(MenuCodec.decode(MenuCodec.encodeResult(ok))).isEqualTo(ok);
            assertThat(MenuCodec.decode(MenuCodec.encodeResult(failed))).isEqualTo(failed);
        }
    }

    @Nested
    @DisplayName("Negative and corruption edge cases")
    class ErrorHandlingTests {

        @Test
        @DisplayName("throws on empty byte array")
        void throwsOnEmptyArray() {
            assertThatThrownBy(() -> MenuCodec.decode(new byte[0])).isInstanceOf(MenuCodecException.class);
            assertThatThrownBy(() -> MenuCodec.decodeOpenMenu(new byte[0])).isInstanceOf(MenuCodecException.class);
            assertThatThrownBy(() -> MenuCodec.decodeIntent(new byte[0])).isInstanceOf(MenuCodecException.class);
            assertThatThrownBy(() -> MenuCodec.decodeResult(new byte[0])).isInstanceOf(MenuCodecException.class);
        }

        @Test
        @DisplayName("throws on truncated payload")
        void throwsOnTruncated() {
            byte[] valid = MenuCodec.encodeIntent(1L, new MenuIntent.JoinWorld(WorldId.random()));
            byte[] truncated = new byte[valid.length - 4];
            System.arraycopy(valid, 0, truncated, 0, truncated.length);

            assertThatThrownBy(() -> MenuCodec.decodeIntent(truncated)).isInstanceOf(MenuCodecException.class);
        }

        @Test
        @DisplayName("throws on unexpected trailing bytes")
        void throwsOnTrailingBytes() {
            byte[] valid = MenuCodec.encodeOpenMenu(new OpenMenu(1L));
            byte[] withTrailing = new byte[valid.length + 2];
            System.arraycopy(valid, 0, withTrailing, 0, valid.length);
            withTrailing[valid.length] = (byte) 0xFF;
            withTrailing[valid.length + 1] = (byte) 0xEE;

            assertThatThrownBy(() -> MenuCodec.decodeOpenMenu(withTrailing)).isInstanceOf(MenuCodecException.class);
        }

        @Test
        @DisplayName("throws on unknown top-level message type")
        void throwsOnUnknownMessageType() {
            byte[] unknown = new byte[] {99, 0, 0, 0, 1};
            assertThatThrownBy(() -> MenuCodec.decode(unknown)).isInstanceOf(MenuCodecException.class);
        }

        @Test
        @DisplayName("throws on unknown intent type")
        void throwsOnUnknownIntentType() {
            byte[] unknownIntent = new byte[] {
                MenuCodec.MSG_INTENT, // top-level message type
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                1, // correlationId
                99 // invalid intent type
            };
            assertThatThrownBy(() -> MenuCodec.decodeIntent(unknownIntent)).isInstanceOf(MenuCodecException.class);
        }

        @Test
        @DisplayName("throws on null arguments")
        void throwsOnNullArgs() {
            assertThatThrownBy(() -> MenuCodec.encodeOpenMenu(null)).isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> MenuCodec.encodeIntent(1L, null)).isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> MenuCodec.encodeResult(null)).isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> MenuCodec.decode(null)).isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> MenuCodec.decodeOpenMenu(null)).isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> MenuCodec.decodeIntent(null)).isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> MenuCodec.decodeResult(null)).isInstanceOf(NullPointerException.class);
        }
    }
}
