package nl.gzmn.playerworlds.backend.gui;

import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.ParsingException;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import nl.gzmn.playerworlds.core.config.MessageCatalog;
import nl.gzmn.playerworlds.core.config.messages.MessageKey;
import nl.gzmn.playerworlds.core.config.messages.MessageRegistry;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Renders {@link MessageCatalog} templates (NFR-5) into Adventure {@link Component}s for the
 * backend.
 *
 * <p>{@code :core} holds only the raw template strings — it is shaded into both the Paper and
 * Velocity plugins and must not carry Adventure/MiniMessage classes. Parsing happens here, one
 * small copy per platform module; {@code proxy}'s equivalent is
 * {@code nl.gzmn.playerworlds.proxy.command.Messages}.
 *
 * <p>A template that fails to parse (a corrupted row — a hand-edited database, a future migration
 * bug) falls back to the key's coded default rather than breaking the surface it renders into;
 * see {@code MessageCatalog}'s startup-validation note for why this is a per-call fallback rather
 * than a refuse-to-enable check.
 */
public final class Messages {

    private static final Logger log = LoggerFactory.getLogger(Messages.class);
    private static final MiniMessage MINI_MESSAGE = MiniMessage.miniMessage();

    private final @Nullable Supplier<MessageCatalog> catalog;

    public Messages(@Nullable Supplier<MessageCatalog> catalog) {
        this.catalog = catalog;
    }

    /** Renders a single-line (or {@code \n}-containing) template. */
    public Component render(String key, TagResolver... placeholders) {
        Objects.requireNonNull(key, "key");
        String template = catalog().get(key);
        try {
            return MINI_MESSAGE.deserialize(template, placeholders);
        } catch (ParsingException e) {
            log.warn("message '{}' failed to render ('{}'), using its coded default", key, template, e);
            return MINI_MESSAGE.deserialize(defaultTemplateOf(key), placeholders);
        }
    }

    /**
     * Renders a lore key: one {@link Component} per stored line, italics stripped unless the
     * template explicitly set it — the same rule {@link ItemUtil#create} already applies, kept
     * here so lore is clean before it ever reaches {@code ItemUtil}.
     */
    public List<Component> renderLore(String key, TagResolver... placeholders) {
        Objects.requireNonNull(key, "key");
        List<String> lines = catalog().getLore(key);
        return lines.stream()
                .map(line -> clean(deserializeLine(key, line, placeholders)))
                .toList();
    }

    private Component deserializeLine(String key, String line, TagResolver[] placeholders) {
        try {
            return MINI_MESSAGE.deserialize(line, placeholders);
        } catch (ParsingException e) {
            log.warn("message '{}' failed to render a lore line ('{}'), rendering it blank", key, line, e);
            return Component.empty();
        }
    }

    private MessageCatalog catalog() {
        return catalog != null ? catalog.get() : MessageCatalog.defaults();
    }

    private static String defaultTemplateOf(String key) {
        MessageKey def = MessageRegistry.ALL.get(key);
        return def != null ? def.defaultTemplate() : "";
    }

    private static Component clean(Component component) {
        if (!component.hasDecoration(TextDecoration.ITALIC)) {
            return component.decoration(TextDecoration.ITALIC, false);
        }
        return component;
    }
}
