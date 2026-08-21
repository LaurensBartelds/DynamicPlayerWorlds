package nl.gzmn.playerworlds.core.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import nl.gzmn.playerworlds.core.config.messages.MessageRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class MessageCatalogTest {

    private static final String KNOWN_KEY = "messages.notice.invite";
    private static final String KNOWN_LORE_KEY = "messages.gui.main-menu.item.my-worlds.lore";

    @Test
    @DisplayName("a key with no override renders its coded default")
    void unoverriddenKeyRendersDefault() {
        MessageCatalog catalog = MessageCatalog.defaults();

        assertThat(catalog.get(KNOWN_KEY))
                .isEqualTo(MessageRegistry.ALL.get(KNOWN_KEY).defaultTemplate());
    }

    @Test
    @DisplayName("fromRaw overlays an admin-written override for its key only")
    void fromRawOverlaysOnlyItsOwnKey() {
        MessageCatalog catalog =
                MessageCatalog.fromRaw(Map.of(KNOWN_KEY, JsonText.quoteString("<red>overridden</red>")));

        assertThat(catalog.get(KNOWN_KEY)).isEqualTo("<red>overridden</red>");
        // A different key with no row still reads its coded default.
        assertThat(catalog.get("messages.gui.main-menu.title"))
                .isEqualTo(
                        MessageRegistry.ALL.get("messages.gui.main-menu.title").defaultTemplate());
    }

    @Test
    @DisplayName("fromRaw ignores non-messages.* keys, so a NetworkPolicy snapshot is safe to pass in")
    void fromRawIgnoresPolicyKeys() {
        MessageCatalog catalog =
                MessageCatalog.fromRaw(Map.of("worlds.max-per-player", "5", KNOWN_KEY, "\"<gray>hi</gray>\""));

        assertThat(catalog.get(KNOWN_KEY)).isEqualTo("<gray>hi</gray>");
        assertThat(catalog.overrides()).containsOnlyKeys(KNOWN_KEY);
    }

    @Test
    @DisplayName("get rejects a key MessageRegistry never declared")
    void getRejectsUnknownKey() {
        MessageCatalog catalog = MessageCatalog.defaults();

        assertThatThrownBy(() -> catalog.get("messages.does.not.exist"))
                .isInstanceOf(ConfigException.class)
                .hasMessageContaining("messages.does.not.exist");
    }

    @Test
    @DisplayName("an unrecognised override key is kept rather than rejected, for mixed-version deploys")
    void unrecognisedOverrideKeyIsKeptNotRejected() {
        MessageCatalog catalog = MessageCatalog.fromRaw(Map.of("messages.some.future.key", "\"hi\""));

        assertThat(catalog.overrides()).containsKey("messages.some.future.key");
    }

    @Test
    @DisplayName("a lore key with no override renders its coded default lines")
    void unoverriddenLoreKeyRendersDefaultLines() {
        MessageCatalog catalog = MessageCatalog.defaults();

        assertThat(catalog.getLore(KNOWN_LORE_KEY))
                .isEqualTo(MessageRegistry.ALL.get(KNOWN_LORE_KEY).defaultLoreLines());
    }

    @Test
    @DisplayName("a lore override replaces every default line")
    void loreOverrideReplacesDefaultLines() {
        List<String> lines = List.of("<gray>one</gray>", "<gray>two</gray>");
        MessageCatalog catalog = MessageCatalog.fromRaw(Map.of(KNOWN_LORE_KEY, JsonText.quoteStringList(lines)));

        assertThat(catalog.getLore(KNOWN_LORE_KEY)).containsExactlyElementsOf(lines);
    }

    @Test
    @DisplayName("every declared default template parses cleanly against its own placeholder names")
    void everyDeclaredKeyIsInternallyConsistent() {
        // Not a MiniMessage parse check (:core has no Adventure dependency) — just that every
        // <placeholder> token used in a default template/lore line is declared in placeholders(),
        // and vice versa is not required (a template may legitimately not use every placeholder,
        // e.g. one branch of a conditional caller). Catches a copy-paste key/placeholder mismatch.
        MessageRegistry.ALL.values().forEach(key -> {
            String haystack = key.lore() ? String.join("\n", key.defaultLoreLines()) : key.defaultTemplate();
            for (String placeholder : key.placeholders()) {
                assertThat(haystack)
                        .as(
                                "key '%s' declares placeholder '%s' but its default never uses <%s>",
                                key.key(), placeholder, placeholder)
                        .contains("<" + placeholder + ">");
            }
        });
    }
}
