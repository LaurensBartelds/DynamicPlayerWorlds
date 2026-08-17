package nl.gzmn.playerworlds.backend.world;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Command-root parsing for the FR-22 allow-list.
 *
 * <p>FR-22 spends a paragraph on why an allow-list drifts less than a deny-list;
 * the way an allow-list is defeated is a root it does not recognise, which is
 * what these cover.
 */
class CommandGuardListenerTest {

    @Test
    @DisplayName("the root is the first word, without the slash")
    void rootIsTheFirstWord() {
        assertThat(CommandGuardListener.rootOf("/list")).isEqualTo("list");
        assertThat(CommandGuardListener.rootOf("/msg Steve hello")).isEqualTo("msg");
        assertThat(CommandGuardListener.rootOf("tell Steve hi")).isEqualTo("tell");
    }

    @Test
    @DisplayName("a plugin prefix cannot walk around the allow-list")
    void pluginPrefixesAreStripped() {
        // /minecraft:list and /essentials:list reach the same command as /list.
        // An allow-list that only knew the bare name would be walked straight
        // around, which is exactly the drift FR-22 is guarding against.
        assertThat(CommandGuardListener.rootOf("/minecraft:list")).isEqualTo("list");
        assertThat(CommandGuardListener.rootOf("/essentials:msg Steve hi")).isEqualTo("msg");
    }

    @Test
    @DisplayName("roots are case-insensitive, because commands are")
    void rootsAreLowercased() {
        assertThat(CommandGuardListener.rootOf("/LIST")).isEqualTo("list");
        assertThat(CommandGuardListener.rootOf("/Minecraft:TELL x")).isEqualTo("tell");
    }
}
