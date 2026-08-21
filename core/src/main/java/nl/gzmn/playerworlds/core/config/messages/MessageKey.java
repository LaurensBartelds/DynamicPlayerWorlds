package nl.gzmn.playerworlds.core.config.messages;

import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * One admin-configurable, MiniMessage-formatted piece of player-facing text (NFR-5).
 *
 * <p>{@code defaultTemplate} is what renders when {@code network_setting} has no row for
 * {@code key}, and what an admin's {@code /world admin message reset} reverts to. {@code
 * placeholders} names every {@code TagResolver} the call site supplies, which is what lets the
 * admin {@code set} command validate a replacement template against the same placeholders before
 * writing it (a typo'd tag is rejected at the keyboard, not silently accepted).
 *
 * <p>A key holding a list of lines (GUI item lore, boxed listings) uses {@link
 * #lore(String, List, Set)} instead: {@code defaultTemplate} is empty and {@code
 * defaultLoreLines} carries the per-line defaults, stored as a JSON string array rather than one
 * {@code \n}-joined scalar (mirrors {@code NetworkPolicy}'s existing list-valued keys).
 */
public record MessageKey(
        String key, boolean lore, String defaultTemplate, List<String> defaultLoreLines, Set<String> placeholders) {

    public MessageKey {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(defaultTemplate, "defaultTemplate");
        Objects.requireNonNull(defaultLoreLines, "defaultLoreLines");
        Objects.requireNonNull(placeholders, "placeholders");
        defaultLoreLines = List.copyOf(defaultLoreLines);
        placeholders = Set.copyOf(placeholders);
    }

    /** A single-line (or {@code \n}-containing single template) message key. */
    public static MessageKey of(String key, String defaultTemplate, Set<String> placeholders) {
        return new MessageKey(key, false, defaultTemplate, List.of(), placeholders);
    }

    public static MessageKey of(String key, String defaultTemplate) {
        return of(key, defaultTemplate, Set.of());
    }

    /** A multi-line key (GUI item lore, boxed listing bodies) stored as a list of templates. */
    public static MessageKey lore(String key, List<String> defaultLoreLines, Set<String> placeholders) {
        return new MessageKey(key, true, "", defaultLoreLines, placeholders);
    }

    public static MessageKey lore(String key, List<String> defaultLoreLines) {
        return lore(key, defaultLoreLines, Set.of());
    }
}
