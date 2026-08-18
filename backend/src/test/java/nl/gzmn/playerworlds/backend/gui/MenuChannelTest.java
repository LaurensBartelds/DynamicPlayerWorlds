package nl.gzmn.playerworlds.backend.gui;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import nl.gzmn.playerworlds.core.concurrent.PluginExecutors;
import nl.gzmn.playerworlds.core.menu.FailureCode;
import nl.gzmn.playerworlds.core.menu.IntentEnvelope;
import nl.gzmn.playerworlds.core.menu.MenuChannels;
import nl.gzmn.playerworlds.core.menu.MenuCodec;
import nl.gzmn.playerworlds.core.menu.MenuIntent;
import nl.gzmn.playerworlds.core.menu.MenuResult;
import nl.gzmn.playerworlds.core.menu.OpenMenu;
import nl.gzmn.playerworlds.core.model.WorldId;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

class MenuChannelTest {

    private ServerMock server;
    private Plugin plugin;
    private PluginExecutors executors;
    private MenuChannel menuChannel;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        plugin = MockBukkit.createMockPlugin();
        executors = PluginExecutors.create(2, 2, Runnable::run);
        menuChannel = new MenuChannel(plugin, executors, null, Duration.ofSeconds(5));
        menuChannel.register();
    }

    @AfterEach
    void tearDown() {
        menuChannel.unregister();
        executors.shutdown(Duration.ofSeconds(2));
        MockBukkit.unmock();
    }

    @Test
    @DisplayName("sendIntent generates monotonic correlation IDs and sends plugin message")
    void sendIntentSendsPluginMessageWithMonotonicCorrelationId() {
        RecordingPlayerMock player = createRecordingPlayer("Alice");
        WorldId worldId = WorldId.random();

        CompletableFuture<MenuResult> f1 = menuChannel.sendIntent(player, new MenuIntent.JoinWorld(worldId));
        CompletableFuture<MenuResult> f2 =
                menuChannel.sendIntent(player, new MenuIntent.CreateWorld("testworld", null));

        assertThat(f1).isNotDone();
        assertThat(f2).isNotDone();

        byte[] msg1 = player.nextSentMessage();
        byte[] msg2 = player.nextSentMessage();

        assertThat(msg1).isNotNull();
        assertThat(msg2).isNotNull();

        IntentEnvelope env1 = (IntentEnvelope) MenuCodec.decode(msg1);
        IntentEnvelope env2 = (IntentEnvelope) MenuCodec.decode(msg2);

        assertThat(env1.correlationId()).isEqualTo(1L);
        assertThat(env1.intent()).isEqualTo(new MenuIntent.JoinWorld(worldId));

        assertThat(env2.correlationId()).isEqualTo(2L);
        assertThat(env2.intent()).isEqualTo(new MenuIntent.CreateWorld("testworld", null));
    }

    @Test
    @DisplayName("onPluginMessageReceived completes pending future on MenuResult.Ok")
    void receivesResultOk() throws Exception {
        RecordingPlayerMock player = createRecordingPlayer("Alice");
        CompletableFuture<MenuResult> future = menuChannel.sendIntent(player, new MenuIntent.CreateWorld("test", null));
        player.nextSentMessage(); // discard sent bytes

        byte[] okBytes = MenuCodec.encodeResult(new MenuResult.Ok(1L, "World created successfully"));
        menuChannel.onPluginMessageReceived(MenuChannels.CHANNEL_NAME, player, okBytes);

        assertThat(future).isCompleted();
        MenuResult result = future.get(1, TimeUnit.SECONDS);
        assertThat(result).isInstanceOf(MenuResult.Ok.class);
        MenuResult.Ok ok = (MenuResult.Ok) result;
        assertThat(ok.correlationId()).isEqualTo(1L);
        assertThat(ok.message()).isEqualTo("World created successfully");
    }

    @Test
    @DisplayName("onPluginMessageReceived completes pending future on MenuResult.Failed")
    void receivesResultFailed() throws Exception {
        RecordingPlayerMock player = createRecordingPlayer("Alice");
        CompletableFuture<MenuResult> future = menuChannel.sendIntent(player, new MenuIntent.CreateWorld("test", null));
        player.nextSentMessage();

        byte[] failedBytes =
                MenuCodec.encodeResult(new MenuResult.Failed(1L, FailureCode.QUOTA_EXCEEDED, "World quota reached"));
        menuChannel.onPluginMessageReceived(MenuChannels.CHANNEL_NAME, player, failedBytes);

        assertThat(future).isCompleted();
        MenuResult result = future.get(1, TimeUnit.SECONDS);
        assertThat(result).isInstanceOf(MenuResult.Failed.class);
        MenuResult.Failed failed = (MenuResult.Failed) result;
        assertThat(failed.correlationId()).isEqualTo(1L);
        assertThat(failed.code()).isEqualTo(FailureCode.QUOTA_EXCEEDED);
        assertThat(failed.message()).isEqualTo("World quota reached");
    }

    @Test
    @DisplayName("sendIntent completes with FailureCode.TIMEOUT when no response arrives within timeout")
    void intentTimesOut() throws Exception {
        MenuChannel quickChannel = new MenuChannel(plugin, executors, null, Duration.ofMillis(50));
        PlayerMock player = server.addPlayer();

        CompletableFuture<MenuResult> future =
                quickChannel.sendIntent(player, new MenuIntent.CreateWorld("test", null));

        MenuResult result = future.get(2, TimeUnit.SECONDS);
        assertThat(result).isInstanceOf(MenuResult.Failed.class);
        MenuResult.Failed failed = (MenuResult.Failed) result;
        assertThat(failed.code()).isEqualTo(FailureCode.TIMEOUT);
        assertThat(failed.message()).contains("timed out");
    }

    @Test
    @DisplayName("onPluginMessageReceived with OpenMenu calls MenuService.openMainMenu")
    void openMenuCallsService() {
        AtomicBoolean called = new AtomicBoolean(false);
        AtomicReference<Player> receivedPlayer = new AtomicReference<>();

        MenuService stubService = new MenuService(null, null, null, null, null, menuChannel, executors, null) {
            @Override
            public CompletableFuture<Void> openMainMenu(Player player) {
                called.set(true);
                receivedPlayer.set(player);
                return CompletableFuture.completedFuture(null);
            }
        };

        menuChannel.setMenuService(stubService);
        PlayerMock player = server.addPlayer();

        byte[] openBytes = MenuCodec.encodeOpenMenu(new OpenMenu(100L));
        menuChannel.onPluginMessageReceived(MenuChannels.CHANNEL_NAME, player, openBytes);

        assertThat(called).isTrue();
        assertThat(receivedPlayer.get()).isEqualTo(player);
    }

    @Test
    @DisplayName("onPluginMessageReceived safely ignores wrong channel or corrupt payload")
    void ignoresWrongChannelOrCorruptedBytes() {
        PlayerMock player = server.addPlayer();

        // Wrong channel
        menuChannel.onPluginMessageReceived("other:channel", player, new byte[] {1, 2, 3});

        // Corrupted payload
        menuChannel.onPluginMessageReceived(MenuChannels.CHANNEL_NAME, player, new byte[] {99, 98, 97});

        // Empty payload
        menuChannel.onPluginMessageReceived(MenuChannels.CHANNEL_NAME, player, new byte[0]);
    }

    private RecordingPlayerMock createRecordingPlayer(String name) {
        RecordingPlayerMock player = new RecordingPlayerMock(server, name);
        server.addPlayer(player);
        return player;
    }

    public static class RecordingPlayerMock extends PlayerMock {
        private final List<byte[]> sentMessages = new CopyOnWriteArrayList<>();

        public RecordingPlayerMock(ServerMock server, String name) {
            super(server, name);
        }

        @Override
        public void sendPluginMessage(Plugin source, String channel, byte[] message) {
            super.sendPluginMessage(source, channel, message);
            sentMessages.add(message);
        }

        public byte[] nextSentMessage() {
            if (sentMessages.isEmpty()) {
                throw new IllegalStateException("No sent plugin messages");
            }
            return sentMessages.removeFirst();
        }
    }
}
