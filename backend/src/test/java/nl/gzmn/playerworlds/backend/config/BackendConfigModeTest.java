package nl.gzmn.playerworlds.backend.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import nl.gzmn.playerworlds.core.config.ConfigException;
import nl.gzmn.playerworlds.core.config.NodeConfig;
import nl.gzmn.playerworlds.core.config.NodeMode;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BackendConfigModeTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("defaults to WORLDS mode when node.mode is omitted")
    void defaultModeIsWorlds() throws Exception {
        FileConfiguration config = validConfig();
        NodeConfig node = BackendConfig.node(config, tempDir.resolve("data"), tempDir.resolve("worlds"));
        assertThat(node.mode()).isEqualTo(NodeMode.WORLDS);
    }

    @Test
    @DisplayName("parses node.mode: worlds explicitly")
    void explicitWorldsMode() throws Exception {
        FileConfiguration config = validConfig();
        config.set("node.mode", "worlds");
        NodeConfig node = BackendConfig.node(config, tempDir.resolve("data"), tempDir.resolve("worlds"));
        assertThat(node.mode()).isEqualTo(NodeMode.WORLDS);
    }

    @Test
    @DisplayName("parses node.mode: gui-only")
    void explicitGuiOnlyMode() throws Exception {
        FileConfiguration config = validConfig();
        config.set("node.mode", "gui-only");
        NodeConfig node = BackendConfig.node(config, tempDir.resolve("data"), tempDir.resolve("worlds"));
        assertThat(node.mode()).isEqualTo(NodeMode.GUI_ONLY);
    }

    @Test
    @DisplayName("parses node.mode case-insensitively")
    void caseInsensitiveMode() throws Exception {
        FileConfiguration config = validConfig();
        config.set("node.mode", "GUI-ONLY");
        NodeConfig node = BackendConfig.node(config, tempDir.resolve("data"), tempDir.resolve("worlds"));
        assertThat(node.mode()).isEqualTo(NodeMode.GUI_ONLY);
    }

    @Test
    @DisplayName("throws ConfigException on invalid node.mode")
    void invalidModeThrowsConfigException() throws Exception {
        FileConfiguration config = validConfig();
        config.set("node.mode", "invalid-mode");

        assertThatThrownBy(() -> BackendConfig.node(config, tempDir.resolve("data"), tempDir.resolve("worlds")))
                .isInstanceOf(ConfigException.class)
                .hasMessageContaining("unknown node.mode");
    }

    private FileConfiguration validConfig() throws Exception {
        Path data = Files.createDirectories(tempDir.resolve("data"));
        Path worlds = Files.createDirectories(tempDir.resolve("worlds"));
        Path cache = Files.createDirectories(data.resolve("cache"));
        Path quarantine = Files.createDirectories(data.resolve("quarantine"));

        FileConfiguration config = new YamlConfiguration();
        config.set("node.id", "test-node");
        config.set("node.address", "127.0.0.1:25565");
        config.set("database.url", "jdbc:postgresql://localhost/test");
        config.set("database.user", "test");
        config.set("storage.local-scratch-path", worlds.toString());
        config.set("storage.local-cache-path", cache.toString());
        config.set("storage.quarantine-path", quarantine.toString());
        return config;
    }
}
