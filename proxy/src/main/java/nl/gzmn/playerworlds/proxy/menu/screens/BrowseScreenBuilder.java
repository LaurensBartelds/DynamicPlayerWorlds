package nl.gzmn.playerworlds.proxy.menu.screens;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import nl.gzmn.playerworlds.core.menu.MenuItemDescriptor;
import nl.gzmn.playerworlds.core.menu.RenderMenuPayload;
import nl.gzmn.playerworlds.core.model.WorldId;
import nl.gzmn.playerworlds.proxy.command.Messages;
import nl.gzmn.playerworlds.proxy.command.Placeholders;
import org.jspecify.annotations.Nullable;

/**
 * Builds the paginated screen payload displaying public worlds across the network.
 */
public final class BrowseScreenBuilder {

    public static final String SCREEN_TYPE = "BROWSE";
    public static final int SIZE = 54;
    public static final int PAGE_SIZE = 36;

    public static final int SLOT_PREVIOUS_PAGE = 45;
    public static final int SLOT_BACK = 48;
    public static final int SLOT_NEXT_PAGE = 53;

    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacySection();

    public record PublicWorldEntry(
            WorldId worldId,
            String worldName,
            UUID ownerUuid,
            String ownerName,
            @Nullable String description) {
        public PublicWorldEntry {
            Objects.requireNonNull(worldId, "worldId");
            Objects.requireNonNull(worldName, "worldName");
            Objects.requireNonNull(ownerUuid, "ownerUuid");
            Objects.requireNonNull(ownerName, "ownerName");
        }
    }

    private BrowseScreenBuilder() {}

    public static RenderMenuPayload build(
            Messages messages, long correlationId, List<PublicWorldEntry> worlds, int page) {
        Objects.requireNonNull(messages, "messages");
        Objects.requireNonNull(worlds, "worlds");
        int validPage = Math.max(0, page);
        int totalPages = Math.max(1, (int) Math.ceil((double) worlds.size() / PAGE_SIZE));
        String title = legacy(messages.render(
                "messages.gui.browse-menu.title",
                Placeholders.count("page", validPage + 1),
                Placeholders.count("pages", totalPages)));

        List<MenuItemDescriptor> items = new ArrayList<>(SIZE);
        for (int i = 0; i < SIZE; i++) {
            items.add(new MenuItemDescriptor(i, "GRAY_STAINED_GLASS_PANE", 1, " ", List.of(), null, ""));
        }

        if (worlds.isEmpty()) {
            items.set(
                    22,
                    new MenuItemDescriptor(
                            22,
                            "COMPASS",
                            1,
                            legacy(messages.render("messages.gui.browse-menu.item.empty.name")),
                            legacyLore(messages.renderLore("messages.gui.browse-menu.item.empty.lore")),
                            null,
                            ""));
        } else {
            int startIndex = validPage * PAGE_SIZE;
            int endIndex = Math.min(worlds.size(), startIndex + PAGE_SIZE);

            for (int i = startIndex; i < endIndex; i++) {
                int slot = i - startIndex;
                PublicWorldEntry entry = worlds.get(i);
                items.set(slot, renderWorldItem(messages, slot, entry));
            }
        }

        // Divider row
        for (int i = 36; i < 45; i++) {
            items.set(i, new MenuItemDescriptor(i, "BLACK_STAINED_GLASS_PANE", 1, " ", List.of(), null, ""));
        }

        if (validPage > 0) {
            items.set(
                    SLOT_PREVIOUS_PAGE,
                    new MenuItemDescriptor(
                            SLOT_PREVIOUS_PAGE,
                            "ARROW",
                            1,
                            legacy(messages.render("messages.gui.browse-menu.item.previous-page.name")),
                            List.of(),
                            null,
                            "NAV:BROWSE:" + (validPage - 1)));
        }

        items.set(
                SLOT_BACK,
                new MenuItemDescriptor(
                        SLOT_BACK,
                        "OAK_DOOR",
                        1,
                        legacy(messages.render("messages.gui.browse-menu.item.back.name")),
                        legacyLore(messages.renderLore("messages.gui.browse-menu.item.back.lore")),
                        null,
                        "NAV:MAIN"));

        if ((validPage + 1) * PAGE_SIZE < worlds.size()) {
            items.set(
                    SLOT_NEXT_PAGE,
                    new MenuItemDescriptor(
                            SLOT_NEXT_PAGE,
                            "ARROW",
                            1,
                            legacy(messages.render("messages.gui.browse-menu.item.next-page.name")),
                            List.of(),
                            null,
                            "NAV:BROWSE:" + (validPage + 1)));
        }

        return new RenderMenuPayload(correlationId, SCREEN_TYPE, title, SIZE, items);
    }

    private static MenuItemDescriptor renderWorldItem(Messages messages, int slot, PublicWorldEntry entry) {
        Component name = messages.render(
                "messages.gui.browse-menu.item.world-entry.name", Placeholders.text("world", entry.worldName()));

        List<Component> lore;
        if (entry.description() != null && !entry.description().isBlank()) {
            lore = messages.renderLore(
                    "messages.gui.browse-menu.item.world-entry.lore",
                    Placeholders.text("owner", entry.ownerName()),
                    Placeholders.text("description", entry.description()));
        } else {
            lore = messages.renderLore(
                    "messages.gui.browse-menu.item.world-entry.lore-no-description",
                    Placeholders.text("owner", entry.ownerName()));
        }

        return new MenuItemDescriptor(
                slot,
                "GRASS_BLOCK",
                1,
                legacy(name),
                legacyLore(lore),
                null,
                "ACTION:JOIN:" + entry.worldId().value());
    }

    private static String legacy(Component component) {
        return LEGACY.serialize(component);
    }

    private static List<String> legacyLore(List<Component> lines) {
        return lines.stream().map(BrowseScreenBuilder::legacy).toList();
    }
}
