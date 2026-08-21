package nl.gzmn.playerworlds.core.menu;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.UUID;
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
    @DisplayName("RenderMenuPayload codec")
    class RenderMenuPayloadTests {

        @Test
        @DisplayName("round-trips RenderMenuPayload with items, lore, and skullOwner")
        void roundTripsRenderMenuPayload() {
            UUID skullId = UUID.randomUUID();
            List<MenuItemDescriptor> items = List.of(
                    new MenuItemDescriptor(
                            0,
                            "GRASS_BLOCK",
                            1,
                            "§aWorld 1",
                            List.of("§7Lore line 1", "§7Lore line 2"),
                            null,
                            "ACTION:JOIN:test-id"),
                    new MenuItemDescriptor(
                            4, "PLAYER_HEAD", 1, "§eProfile", List.of("§7Player info"), skullId, "NAV:PROFILE"),
                    new MenuItemDescriptor(8, "BARRIER", 64, "§cClose", List.of(), null, "ACTION:CLOSE"));
            RenderMenuPayload payload = new RenderMenuPayload(1001L, "MAIN", "§8Main Menu", 54, items);

            byte[] encoded = MenuCodec.encodeRenderMenu(payload);
            RenderMenuPayload decoded = MenuCodec.decodeRenderMenu(encoded);

            assertThat(decoded).isEqualTo(payload);
            assertThat(decoded.correlationId()).isEqualTo(1001L);
            assertThat(decoded.screenType()).isEqualTo("MAIN");
            assertThat(decoded.title()).isEqualTo("§8Main Menu");
            assertThat(decoded.size()).isEqualTo(54);
            assertThat(decoded.items()).hasSize(3);

            MenuItemDescriptor item0 = decoded.items().get(0);
            assertThat(item0.slot()).isEqualTo(0);
            assertThat(item0.materialName()).isEqualTo("GRASS_BLOCK");
            assertThat(item0.amount()).isEqualTo(1);
            assertThat(item0.displayName()).isEqualTo("§aWorld 1");
            assertThat(item0.lore()).containsExactly("§7Lore line 1", "§7Lore line 2");
            assertThat(item0.skullOwner()).isNull();
            assertThat(item0.actionTag()).isEqualTo("ACTION:JOIN:test-id");

            MenuItemDescriptor item1 = decoded.items().get(1);
            assertThat(item1.slot()).isEqualTo(4);
            assertThat(item1.materialName()).isEqualTo("PLAYER_HEAD");
            assertThat(item1.amount()).isEqualTo(1);
            assertThat(item1.displayName()).isEqualTo("§eProfile");
            assertThat(item1.lore()).containsExactly("§7Player info");
            assertThat(item1.skullOwner()).isEqualTo(skullId);
            assertThat(item1.actionTag()).isEqualTo("NAV:PROFILE");

            MenuItemDescriptor item2 = decoded.items().get(2);
            assertThat(item2.slot()).isEqualTo(8);
            assertThat(item2.materialName()).isEqualTo("BARRIER");
            assertThat(item2.amount()).isEqualTo(64);
            assertThat(item2.displayName()).isEqualTo("§cClose");
            assertThat(item2.lore()).isEmpty();
            assertThat(item2.skullOwner()).isNull();
            assertThat(item2.actionTag()).isEqualTo("ACTION:CLOSE");
        }

        @Test
        @DisplayName("round-trips RenderMenuPayload with empty items list")
        void roundTripsEmptyItems() {
            RenderMenuPayload payload = new RenderMenuPayload(1002L, "EMPTY", "Empty Menu", 9, List.of());
            byte[] encoded = MenuCodec.encodeRenderMenu(payload);
            RenderMenuPayload decoded = MenuCodec.decodeRenderMenu(encoded);

            assertThat(decoded).isEqualTo(payload);
            assertThat(decoded.items()).isEmpty();
        }

        @Test
        @DisplayName("decodeRenderMenu rejects mismatched message type")
        void rejectsMismatchedType() {
            byte[] encodedOpen = MenuCodec.encodeOpenMenu(new OpenMenu(1L));
            assertThatThrownBy(() -> MenuCodec.decodeRenderMenu(encodedOpen)).isInstanceOf(MenuCodecException.class);
        }

        @Test
        @DisplayName("decodeRenderMenu rejects trailing bytes")
        void rejectsTrailingBytes() {
            RenderMenuPayload payload = new RenderMenuPayload(1003L, "MAIN", "Title", 27, List.of());
            byte[] valid = MenuCodec.encodeRenderMenu(payload);
            byte[] withTrailing = new byte[valid.length + 1];
            System.arraycopy(valid, 0, withTrailing, 0, valid.length);
            withTrailing[valid.length] = (byte) 0x01;

            assertThatThrownBy(() -> MenuCodec.decodeRenderMenu(withTrailing)).isInstanceOf(MenuCodecException.class);
        }
    }

    @Nested
    @DisplayName("MenuClickIntent codec")
    class MenuClickIntentTests {

        @Test
        @DisplayName("round-trips MenuClickIntent")
        void roundTripsMenuClickIntent() {
            MenuClickIntent intent = new MenuClickIntent(2002L, "ACTION:JOIN:test-id", 5);
            byte[] encoded = MenuCodec.encodeClickIntent(intent);
            MenuClickIntent decoded = MenuCodec.decodeClickIntent(encoded);

            assertThat(decoded).isEqualTo(intent);
            assertThat(decoded.correlationId()).isEqualTo(2002L);
            assertThat(decoded.actionTag()).isEqualTo("ACTION:JOIN:test-id");
            assertThat(decoded.screenSequence()).isEqualTo(5);
        }

        @Test
        @DisplayName("decodeClickIntent rejects mismatched message type")
        void rejectsMismatchedType() {
            byte[] encodedOpen = MenuCodec.encodeOpenMenu(new OpenMenu(1L));
            assertThatThrownBy(() -> MenuCodec.decodeClickIntent(encodedOpen)).isInstanceOf(MenuCodecException.class);
        }

        @Test
        @DisplayName("decodeClickIntent rejects trailing bytes")
        void rejectsTrailingBytes() {
            MenuClickIntent intent = new MenuClickIntent(2002L, "NAV:MY_WORLDS", 1);
            byte[] valid = MenuCodec.encodeClickIntent(intent);
            byte[] withTrailing = new byte[valid.length + 1];
            System.arraycopy(valid, 0, withTrailing, 0, valid.length);

            assertThatThrownBy(() -> MenuCodec.decodeClickIntent(withTrailing)).isInstanceOf(MenuCodecException.class);
        }
    }

    @Nested
    @DisplayName("CloseMenuMessage codec")
    class CloseMenuMessageTests {

        @Test
        @DisplayName("round-trips CloseMenuMessage")
        void roundTripsCloseMenuMessage() {
            CloseMenuMessage msg = new CloseMenuMessage(3003L);
            byte[] encoded = MenuCodec.encodeCloseMenu(msg);
            CloseMenuMessage decoded = MenuCodec.decodeCloseMenu(encoded);

            assertThat(decoded).isEqualTo(msg);
            assertThat(decoded.correlationId()).isEqualTo(3003L);
        }

        @Test
        @DisplayName("decodeCloseMenu rejects mismatched message type")
        void rejectsMismatchedType() {
            byte[] encodedOpen = MenuCodec.encodeOpenMenu(new OpenMenu(1L));
            assertThatThrownBy(() -> MenuCodec.decodeCloseMenu(encodedOpen)).isInstanceOf(MenuCodecException.class);
        }

        @Test
        @DisplayName("decodeCloseMenu rejects trailing bytes")
        void rejectsTrailingBytes() {
            CloseMenuMessage msg = new CloseMenuMessage(3003L);
            byte[] valid = MenuCodec.encodeCloseMenu(msg);
            byte[] withTrailing = new byte[valid.length + 1];
            System.arraycopy(valid, 0, withTrailing, 0, valid.length);

            assertThatThrownBy(() -> MenuCodec.decodeCloseMenu(withTrailing)).isInstanceOf(MenuCodecException.class);
        }
    }

    @Nested
    @DisplayName("MenuClosedNotice codec")
    class MenuClosedNoticeTests {

        @Test
        @DisplayName("round-trips MenuClosedNotice")
        void roundTripsMenuClosedNotice() {
            MenuClosedNotice notice = new MenuClosedNotice(4004L);
            byte[] encoded = MenuCodec.encodeClosedNotice(notice);
            MenuClosedNotice decoded = MenuCodec.decodeClosedNotice(encoded);

            assertThat(decoded).isEqualTo(notice);
            assertThat(decoded.correlationId()).isEqualTo(4004L);
        }

        @Test
        @DisplayName("decodeClosedNotice rejects mismatched message type")
        void rejectsMismatchedType() {
            byte[] encodedOpen = MenuCodec.encodeOpenMenu(new OpenMenu(1L));
            assertThatThrownBy(() -> MenuCodec.decodeClosedNotice(encodedOpen)).isInstanceOf(MenuCodecException.class);
        }

        @Test
        @DisplayName("decodeClosedNotice rejects trailing bytes")
        void rejectsTrailingBytes() {
            MenuClosedNotice notice = new MenuClosedNotice(4004L);
            byte[] valid = MenuCodec.encodeClosedNotice(notice);
            byte[] withTrailing = new byte[valid.length + 1];
            System.arraycopy(valid, 0, withTrailing, 0, valid.length);

            assertThatThrownBy(() -> MenuCodec.decodeClosedNotice(withTrailing)).isInstanceOf(MenuCodecException.class);
        }
    }

    @Nested
    @DisplayName("Generic decode method")
    class GenericDecodeTests {

        @Test
        @DisplayName("decode dispatches all supported message types")
        void dispatchesAllTypes() {
            OpenMenu openMenu = new OpenMenu(10L);
            IntentEnvelope envelope = new IntentEnvelope(20L, new MenuIntent.RestoreWorld("demo"));
            MenuResult ok = new MenuResult.Ok(30L, "done");
            MenuResult failed = new MenuResult.Failed(40L, FailureCode.PERMISSION_DENIED, "no");
            RenderMenuPayload render = new RenderMenuPayload(50L, "MAIN", "Title", 27, List.of());
            CloseMenuMessage closeMsg = new CloseMenuMessage(60L);
            MenuClickIntent click = new MenuClickIntent(70L, "ACTION:JOIN:world-1", 2);
            MenuClosedNotice closed = new MenuClosedNotice(80L);
            WorldPresenceNotice presence = new WorldPresenceNotice(WorldId.random());

            assertThat(MenuCodec.decode(MenuCodec.encodeOpenMenu(openMenu))).isEqualTo(openMenu);
            assertThat(MenuCodec.decode(MenuCodec.encodeIntent(envelope.correlationId(), envelope.intent())))
                    .isEqualTo(envelope);
            assertThat(MenuCodec.decode(MenuCodec.encodeResult(ok))).isEqualTo(ok);
            assertThat(MenuCodec.decode(MenuCodec.encodeResult(failed))).isEqualTo(failed);
            assertThat(MenuCodec.decode(MenuCodec.encodeRenderMenu(render))).isEqualTo(render);
            assertThat(MenuCodec.decode(MenuCodec.encodeCloseMenu(closeMsg))).isEqualTo(closeMsg);
            assertThat(MenuCodec.decode(MenuCodec.encodeClickIntent(click))).isEqualTo(click);
            assertThat(MenuCodec.decode(MenuCodec.encodeClosedNotice(closed))).isEqualTo(closed);
            assertThat(MenuCodec.decode(MenuCodec.encodePresence(presence))).isEqualTo(presence);
        }
    }

    @Nested
    @DisplayName("WorldPresenceNotice")
    class WorldPresenceNoticeTests {

        @Test
        @DisplayName("round trips a world")
        void roundTripsAWorld() {
            WorldPresenceNotice notice = new WorldPresenceNotice(WorldId.random());
            assertThat(MenuCodec.decodePresence(MenuCodec.encodePresence(notice)))
                    .isEqualTo(notice);
        }

        @Test
        @DisplayName("round trips no world, which is how a node says the lobby or its own level")
        void roundTripsNoWorld() {
            WorldPresenceNotice notice = new WorldPresenceNotice(null);
            assertThat(MenuCodec.decodePresence(MenuCodec.encodePresence(notice))
                            .worldId())
                    .isNull();
        }

        @Test
        @DisplayName("rejects trailing bytes")
        void rejectsTrailingBytes() {
            byte[] encoded = MenuCodec.encodePresence(new WorldPresenceNotice(WorldId.random()));
            byte[] withTrailing = new byte[encoded.length + 1];
            System.arraycopy(encoded, 0, withTrailing, 0, encoded.length);
            assertThatThrownBy(() -> MenuCodec.decodePresence(withTrailing)).isInstanceOf(MenuCodecException.class);
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
            assertThatThrownBy(() -> MenuCodec.decodeRenderMenu(new byte[0])).isInstanceOf(MenuCodecException.class);
            assertThatThrownBy(() -> MenuCodec.decodeCloseMenu(new byte[0])).isInstanceOf(MenuCodecException.class);
            assertThatThrownBy(() -> MenuCodec.decodeClickIntent(new byte[0])).isInstanceOf(MenuCodecException.class);
            assertThatThrownBy(() -> MenuCodec.decodeClosedNotice(new byte[0])).isInstanceOf(MenuCodecException.class);
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
            assertThatThrownBy(() -> MenuCodec.encodeRenderMenu(null)).isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> MenuCodec.encodeCloseMenu(null)).isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> MenuCodec.encodeClickIntent(null)).isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> MenuCodec.encodeClosedNotice(null)).isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> MenuCodec.decode(null)).isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> MenuCodec.decodeOpenMenu(null)).isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> MenuCodec.decodeIntent(null)).isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> MenuCodec.decodeResult(null)).isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> MenuCodec.decodeRenderMenu(null)).isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> MenuCodec.decodeCloseMenu(null)).isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> MenuCodec.decodeClickIntent(null)).isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> MenuCodec.decodeClosedNotice(null)).isInstanceOf(NullPointerException.class);
        }
    }
}
