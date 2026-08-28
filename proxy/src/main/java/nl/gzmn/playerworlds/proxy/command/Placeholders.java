package nl.gzmn.playerworlds.proxy.command;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import nl.gzmn.playerworlds.core.config.StorageQuotaResolver;

/**
 * Builds the {@link TagResolver}s {@link Messages} takes for a template's placeholders.
 *
 * <p>The rule that matters here: a value that came from a player (a world name, a player name, a
 * ban reason) must go in via {@link #text}, which inserts it as a literal {@link Component} that
 * is never re-lexed as MiniMessage. Passing it through {@link #raw} or string-concatenating it
 * into the template before parsing would let a player-chosen name containing {@code <red>} or
 * {@code <click:...>} be interpreted as markup. {@link #raw} is only for values this code itself
 * formats and knows contain no markup — counts, byte sizes, node ids, enum names.
 */
public final class Placeholders {

    private Placeholders() {}

    /** For untrusted text: inserted as a literal component, never re-parsed as MiniMessage. */
    public static TagResolver text(String name, String rawUntrustedValue) {
        return Placeholder.component(name, Component.text(rawUntrustedValue));
    }

    /** For a value this code already rendered as a {@link Component} (e.g. a nested template). */
    public static TagResolver component(String name, Component value) {
        return Placeholder.component(name, value);
    }

    /** For a number this code computed; safe because it cannot contain markup. */
    public static TagResolver count(String name, long value) {
        return Placeholder.unparsed(name, Long.toString(value));
    }

    /** For a plain, code-controlled string (an enum name, a node id); no markup risk. */
    public static TagResolver raw(String name, String value) {
        return Placeholder.unparsed(name, value);
    }

    /** For a byte count, formatted with the existing {@link StorageQuotaResolver} convention. */
    public static TagResolver bytes(String name, long byteCount) {
        return Placeholder.unparsed(name, StorageQuotaResolver.formatBytes(byteCount));
    }
}
