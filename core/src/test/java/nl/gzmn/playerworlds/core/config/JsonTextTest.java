package nl.gzmn.playerworlds.core.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class JsonTextTest {

    @Test
    @DisplayName("quoteString escapes quotes, backslashes and control characters")
    void quoteStringEscapes() {
        assertThat(JsonText.quoteString("hi")).isEqualTo("\"hi\"");
        assertThat(JsonText.quoteString("say \"hi\"")).isEqualTo("\"say \\\"hi\\\"\"");
        assertThat(JsonText.quoteString("a\\b")).isEqualTo("\"a\\\\b\"");
        assertThat(JsonText.quoteString("line1\nline2")).isEqualTo("\"line1\\nline2\"");
    }

    @Test
    @DisplayName("quoteStringList produces a JSON array of quoted strings")
    void quoteStringListProducesArray() {
        assertThat(JsonText.quoteStringList(List.of("a", "b \"c\""))).isEqualTo("[\"a\",\"b \\\"c\\\"\"]");
        assertThat(JsonText.quoteStringList(List.of())).isEqualTo("[]");
    }

    @Test
    @DisplayName("round-trips through MessageCatalog.fromRaw")
    void roundTripsThroughMessageCatalog() {
        String template = "<red>hi \"<name>\"</red>\nsecond line";
        String json = JsonText.quoteString(template);

        MessageCatalog catalog = MessageCatalog.fromRaw(java.util.Map.of("messages.notice.invite", json));

        assertThat(catalog.get("messages.notice.invite")).isEqualTo(template);
    }

    @Test
    @DisplayName("lore round-trips a list containing commas and quotes")
    void loreRoundTripsThroughMessageCatalog() {
        List<String> lines = List.of("<gray>line, with a comma</gray>", "<gray>a \"quoted\" word</gray>", "");
        String json = JsonText.quoteStringList(lines);

        MessageCatalog catalog =
                MessageCatalog.fromRaw(java.util.Map.of("messages.gui.main-menu.item.my-worlds.lore", json));

        assertThat(catalog.getLore("messages.gui.main-menu.item.my-worlds.lore"))
                .containsExactlyElementsOf(lines);
    }
}
