package nl.gzmn.playerworlds.proxy;

import static org.assertj.core.api.Assertions.assertThat;

import com.velocitypowered.api.command.CommandManager;
import com.velocitypowered.api.command.CommandMeta;
import com.velocitypowered.api.event.connection.PostLoginEvent;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import com.velocitypowered.api.proxy.server.ServerInfo;
import com.velocitypowered.api.scheduler.ScheduledTask;
import com.velocitypowered.api.scheduler.Scheduler;
import java.io.IOException;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import nl.gzmn.playerworlds.core.db.Database;
import nl.gzmn.playerworlds.core.db.DatabaseSettings;
import nl.gzmn.playerworlds.core.db.PlayerNameRepository;
import nl.gzmn.playerworlds.core.db.PlayerWorldRepository;
import nl.gzmn.playerworlds.core.db.Schema;
import nl.gzmn.playerworlds.core.db.TransferRequestRepository;
import nl.gzmn.playerworlds.core.model.Visibility;
import nl.gzmn.playerworlds.core.model.WorldId;
import nl.gzmn.playerworlds.proxy.config.ProxyConfigLoader;
import nl.gzmn.playerworlds.testing.TestDatabase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

class GzmnWorldsProxyPluginTest {

    @TempDir
    Path tempDir;

    private Database database;
    private PlayerWorldRepository worldRepo;
    private TransferRequestRepository transferRequests;
    private PlayerNameRepository playerNames;

    private Map<UUID, Player> playersByUuid;
    private Map<String, Player> playersByName;
    private Map<String, RegisteredServer> registeredServers;
    private Map<UUID, List<Component>> playerMessages;
    private ProxyServer proxyServer;
    private GzmnWorldsProxyPlugin plugin;

    @BeforeEach
    void setUp() throws Exception {
        database = TestDatabase.openFresh();
        Schema.migrate(database);
        worldRepo = new PlayerWorldRepository(database);
        transferRequests = new TransferRequestRepository(database);
        playerNames = new PlayerNameRepository(database);

        playersByUuid = new ConcurrentHashMap<>();
        playersByName = new ConcurrentHashMap<>();
        registeredServers = new ConcurrentHashMap<>();
        playerMessages = new ConcurrentHashMap<>();

        proxyServer = mockProxy(playersByUuid, playersByName, registeredServers);

        DatabaseSettings dbSettings = TestDatabase.settings();
        writeConfigFile(tempDir, dbSettings);

        plugin = new GzmnWorldsProxyPlugin(
                proxyServer, LoggerFactory.getLogger(GzmnWorldsProxyPluginTest.class), tempDir);
        plugin.onProxyInitialize(new ProxyInitializeEvent());
    }

    @AfterEach
    void tearDown() {
        if (plugin != null) {
            plugin.onProxyShutdown(new ProxyShutdownEvent());
        }
        database.close();
    }

    @Test
    @DisplayName("post login remembers player username in database")
    void postLoginRemembersPlayerUsername() throws Exception {
        UUID uuid = UUID.randomUUID();
        Player player = registerPlayer(uuid, "Alice");

        plugin.onPostLogin(new PostLoginEvent(player));

        awaitCondition(() -> playerNames.nameOf(uuid).isPresent());
        assertThat(playerNames.nameOf(uuid)).contains("Alice");
    }

    @Test
    @DisplayName("post login without pending transfers sends no reminder message")
    void postLoginWithoutPendingTransfersSendsNoReminder() throws Exception {
        UUID uuid = UUID.randomUUID();
        Player player = registerPlayer(uuid, "Bob");

        plugin.onPostLogin(new PostLoginEvent(player));

        awaitCondition(() -> playerNames.nameOf(uuid).isPresent());
        List<Component> messages = playerMessages.get(uuid);
        assertThat(messages).isEmpty();
    }

    @Test
    @DisplayName("post login with single pending transfer sends reminder in gold")
    void postLoginWithSinglePendingTransferSendsReminder() throws Exception {
        UUID ownerUuid = UUID.randomUUID();
        UUID targetUuid = UUID.randomUUID();
        Player targetPlayer = registerPlayer(targetUuid, "Charlie");

        WorldId worldId = WorldId.random();
        worldRepo.create(worldId, ownerUuid, "world1", 12345L, 5000, Visibility.PRIVATE);
        transferRequests.requestTransfer(worldId, targetUuid, ownerUuid, Duration.ofMinutes(15));

        plugin.onPostLogin(new PostLoginEvent(targetPlayer));

        awaitCondition(() -> !playerMessages.get(targetUuid).isEmpty());

        List<Component> messages = playerMessages.get(targetUuid);
        assertThat(messages).hasSize(1);
        Component msg = messages.getFirst();
        String plain = PlainTextComponentSerializer.plainText().serialize(msg);
        assertThat(plain)
                .isEqualTo(
                        "You have 1 pending world ownership transfer request(s)! Use /world transfer accept <owner> to accept.");
        assertThat(msg.color()).isEqualTo(NamedTextColor.GOLD);
    }

    @Test
    @DisplayName("post login with multiple pending transfers sends correct count in gold")
    void postLoginWithMultiplePendingTransfersSendsCount() throws Exception {
        UUID owner1 = UUID.randomUUID();
        UUID owner2 = UUID.randomUUID();
        UUID targetUuid = UUID.randomUUID();
        Player targetPlayer = registerPlayer(targetUuid, "Dana");

        WorldId worldId1 = WorldId.random();
        WorldId worldId2 = WorldId.random();
        worldRepo.create(worldId1, owner1, "world-alpha", 12345L, 5000, Visibility.PRIVATE);
        worldRepo.create(worldId2, owner2, "world-beta", 67890L, 5000, Visibility.PRIVATE);

        transferRequests.requestTransfer(worldId1, targetUuid, owner1, Duration.ofMinutes(15));
        transferRequests.requestTransfer(worldId2, targetUuid, owner2, Duration.ofMinutes(15));

        plugin.onPostLogin(new PostLoginEvent(targetPlayer));

        awaitCondition(() -> !playerMessages.get(targetUuid).isEmpty());

        List<Component> messages = playerMessages.get(targetUuid);
        assertThat(messages).hasSize(1);
        Component msg = messages.getFirst();
        String plain = PlainTextComponentSerializer.plainText().serialize(msg);
        assertThat(plain)
                .isEqualTo(
                        "You have 2 pending world ownership transfer request(s)! Use /world transfer accept <owner> to accept.");
        assertThat(msg.color()).isEqualTo(NamedTextColor.GOLD);
    }

    private Player registerPlayer(UUID uuid, String username) {
        List<Component> messages = Collections.synchronizedList(new ArrayList<>());
        playerMessages.put(uuid, messages);
        Player player = mockPlayer(uuid, username, messages);
        playersByUuid.put(uuid, player);
        playersByName.put(username, player);
        return player;
    }

    private void writeConfigFile(Path dir, DatabaseSettings dbSettings) throws IOException {
        Files.writeString(
                dir.resolve(ProxyConfigLoader.FILE_NAME),
                "lobby-server = \"lobby\"\n\n"
                        + "[database]\n"
                        + "url = \"" + dbSettings.jdbcUrl() + "\"\n"
                        + "user = \"" + dbSettings.username() + "\"\n"
                        + "password = \"" + dbSettings.password() + "\"\n"
                        + "pool-size = 4\n"
                        + "connection-timeout-seconds = 10\n");
    }

    private void awaitCondition(Callable<Boolean> condition) throws Exception {
        long deadline = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < deadline) {
            if (Boolean.TRUE.equals(condition.call())) {
                return;
            }
            Thread.sleep(25);
        }
        throw new AssertionError("Condition not met within timeout");
    }

    private ProxyServer mockProxy(
            Map<UUID, Player> playersByUuid, Map<String, Player> playersByName, Map<String, RegisteredServer> servers) {
        Scheduler scheduler = mockScheduler();
        CommandManager commandManager = mockCommandManager();

        return (ProxyServer) Proxy.newProxyInstance(
                ProxyServer.class.getClassLoader(), new Class<?>[] {ProxyServer.class}, (proxy, method, args) -> {
                    if ("getPlayer".equals(method.getName()) && args != null && args.length == 1) {
                        if (args[0] instanceof UUID uuid) {
                            return Optional.ofNullable(playersByUuid.get(uuid));
                        }
                        if (args[0] instanceof String name) {
                            return Optional.ofNullable(playersByName.get(name));
                        }
                    }
                    if ("getAllPlayers".equals(method.getName())) {
                        return playersByUuid.values();
                    }
                    if ("getServer".equals(method.getName()) && args != null && args.length == 1) {
                        if (args[0] instanceof String name) {
                            return Optional.ofNullable(servers.get(name));
                        }
                    }
                    if ("registerServer".equals(method.getName()) && args != null && args.length == 1) {
                        if (args[0] instanceof ServerInfo info) {
                            servers.put(info.getName(), mockServer(info.getName(), info.getAddress()));
                        }
                        return null;
                    }
                    if ("unregisterServer".equals(method.getName()) && args != null && args.length == 1) {
                        if (args[0] instanceof ServerInfo info) {
                            servers.remove(info.getName());
                        }
                        return null;
                    }
                    if ("getScheduler".equals(method.getName())) {
                        return scheduler;
                    }
                    if ("getCommandManager".equals(method.getName())) {
                        return commandManager;
                    }
                    if ("toString".equals(method.getName())) {
                        return "MockProxyServer";
                    }
                    return defaultValue(method.getReturnType());
                });
    }

    private Scheduler mockScheduler() {
        ScheduledTask scheduledTask = (ScheduledTask) Proxy.newProxyInstance(
                ScheduledTask.class.getClassLoader(),
                new Class<?>[] {ScheduledTask.class},
                (proxy, method, args) -> defaultValue(method.getReturnType()));

        Scheduler.TaskBuilder taskBuilder = (Scheduler.TaskBuilder) Proxy.newProxyInstance(
                Scheduler.TaskBuilder.class.getClassLoader(),
                new Class<?>[] {Scheduler.TaskBuilder.class},
                (proxy, method, args) -> {
                    if ("schedule".equals(method.getName())) {
                        return scheduledTask;
                    }
                    if ("repeat".equals(method.getName())
                            || "delay".equals(method.getName())
                            || "clearDelay".equals(method.getName())
                            || "clearRepeat".equals(method.getName())) {
                        return proxy;
                    }
                    return defaultValue(method.getReturnType());
                });

        return (Scheduler) Proxy.newProxyInstance(
                Scheduler.class.getClassLoader(), new Class<?>[] {Scheduler.class}, (proxy, method, args) -> {
                    if ("buildTask".equals(method.getName())) {
                        return taskBuilder;
                    }
                    return defaultValue(method.getReturnType());
                });
    }

    private CommandManager mockCommandManager() {
        CommandMeta commandMeta = (CommandMeta) Proxy.newProxyInstance(
                CommandMeta.class.getClassLoader(),
                new Class<?>[] {CommandMeta.class},
                (p, m, a) -> defaultValue(m.getReturnType()));

        CommandMeta.Builder metaBuilder = (CommandMeta.Builder) Proxy.newProxyInstance(
                CommandMeta.Builder.class.getClassLoader(),
                new Class<?>[] {CommandMeta.Builder.class},
                (proxy, method, args) -> {
                    if ("plugin".equals(method.getName())) {
                        return proxy;
                    }
                    if ("build".equals(method.getName())) {
                        return commandMeta;
                    }
                    return defaultValue(method.getReturnType());
                });

        return (CommandManager) Proxy.newProxyInstance(
                CommandManager.class.getClassLoader(), new Class<?>[] {CommandManager.class}, (proxy, method, args) -> {
                    if ("metaBuilder".equals(method.getName())) {
                        return metaBuilder;
                    }
                    if ("register".equals(method.getName())) {
                        return null;
                    }
                    return defaultValue(method.getReturnType());
                });
    }

    private Player mockPlayer(UUID uuid, String username, List<Component> receivedMessages) {
        return (Player) Proxy.newProxyInstance(
                Player.class.getClassLoader(), new Class<?>[] {Player.class}, (proxy, method, args) -> {
                    if ("getUniqueId".equals(method.getName())) {
                        return uuid;
                    }
                    if ("getUsername".equals(method.getName())) {
                        return username;
                    }
                    if ("sendMessage".equals(method.getName()) && args != null && args.length > 0) {
                        if (args[0] instanceof Component comp) {
                            receivedMessages.add(comp);
                        }
                        return null;
                    }
                    if ("hasPermission".equals(method.getName())) {
                        return true;
                    }
                    if ("toString".equals(method.getName())) {
                        return "MockPlayer[" + username + "]";
                    }
                    return defaultValue(method.getReturnType());
                });
    }

    private RegisteredServer mockServer(String name, java.net.InetSocketAddress address) {
        ServerInfo info = new ServerInfo(name, address);
        return (RegisteredServer) Proxy.newProxyInstance(
                RegisteredServer.class.getClassLoader(),
                new Class<?>[] {RegisteredServer.class},
                (proxy, method, args) -> {
                    if ("getServerInfo".equals(method.getName())) {
                        return info;
                    }
                    if ("toString".equals(method.getName())) {
                        return "MockServer[" + name + "]";
                    }
                    return defaultValue(method.getReturnType());
                });
    }

    private static Object defaultValue(Class<?> returnType) {
        if (returnType == void.class) {
            return null;
        }
        if (returnType == boolean.class) {
            return false;
        }
        if (returnType == byte.class || returnType == short.class || returnType == int.class) {
            return 0;
        }
        if (returnType == long.class) {
            return 0L;
        }
        if (returnType == float.class) {
            return 0.0f;
        }
        if (returnType == double.class) {
            return 0.0d;
        }
        if (returnType == char.class) {
            return '\0';
        }
        if (returnType == Optional.class) {
            return Optional.empty();
        }
        return null;
    }
}
