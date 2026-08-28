package nl.gzmn.playerworlds.core.config.messages;

import java.util.List;
import java.util.Set;

/** {@code backend/gui/screen/MyWorldsMenu.java} and its proxy mirror, {@code MyWorldsScreenBuilder}. */
public final class GuiMyWorldsMenuMessages {

    private GuiMyWorldsMenuMessages() {}

    public static final List<MessageKey> ENTRIES = List.of(
            MessageKey.of(
                    "messages.gui.my-worlds-menu.title",
                    "<dark_gray>My Worlds (Page <page>/<pages>)</dark_gray>",
                    Set.of("page", "pages")),
            // The world-entry item's name and its "State:" lore line are pre-colored
            // Components inserted whole (owned/shared and per-WorldState colors are
            // chosen from a fixed, code-controlled set) — the template just places them.
            MessageKey.of("messages.gui.my-worlds-menu.item.world-entry.name", "<world>", Set.of("world")),
            MessageKey.of("messages.gui.my-worlds-menu.item.world-entry.state-line", "<state>", Set.of("state")),
            MessageKey.of(
                    "messages.gui.my-worlds-menu.item.world-entry.shared-note",
                    "<light_purple>Shared with you<role-suffix></light_purple>",
                    Set.of("role-suffix")),
            MessageKey.of(
                    "messages.gui.my-worlds-menu.item.world-entry.visibility-line",
                    "<gray>Visibility: <visibility></gray>",
                    Set.of("visibility")),
            MessageKey.of(
                    "messages.gui.my-worlds-menu.item.world-entry.size-line",
                    "<gray>Size: <size></gray>",
                    Set.of("size")),
            MessageKey.of(
                    "messages.gui.my-worlds-menu.item.world-entry.border-line",
                    "<dark_gray>Border: ±<radius>m</dark_gray>",
                    Set.of("radius")),
            MessageKey.of(
                    "messages.gui.my-worlds-menu.item.world-entry.join-hint",
                    "<green>▶ Left-Click: Join World</green>"),
            MessageKey.of(
                    "messages.gui.my-worlds-menu.item.world-entry.manage-hint",
                    "<yellow>▶ Right-Click: <action></yellow>",
                    Set.of("action")),
            MessageKey.of(
                    "messages.gui.my-worlds-menu.item.previous-page.name",
                    "<yellow><bold>◀ Previous Page</bold></yellow>"),
            MessageKey.of("messages.gui.my-worlds-menu.item.back.name", "<red><bold>Back to Main Menu</bold></red>"),
            MessageKey.lore(
                    "messages.gui.my-worlds-menu.item.back.lore", List.of("<dark_gray>▶ Click to return</dark_gray>")),
            MessageKey.of(
                    "messages.gui.my-worlds-menu.item.create.name", "<green><bold>Create New World</bold></green>"),
            MessageKey.of(
                    "messages.gui.my-worlds-menu.item.create.owned-line",
                    "<gray>Owned: <owned> / <max></gray>",
                    Set.of("owned", "max")),
            MessageKey.of(
                    "messages.gui.my-worlds-menu.item.create.shared-line",
                    "<gray>Shared with you: <count></gray>",
                    Set.of("count")),
            MessageKey.of("messages.gui.my-worlds-menu.item.create.hint", "<yellow>▶ Click to create a world</yellow>"),
            MessageKey.of(
                    "messages.gui.my-worlds-menu.item.next-page.name", "<yellow><bold>Next Page ▶</bold></yellow>"));
}
