package nl.gzmn.playerworlds.proxy.menu.screens;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import nl.gzmn.playerworlds.core.menu.MenuItemDescriptor;
import nl.gzmn.playerworlds.core.menu.RenderMenuPayload;
import nl.gzmn.playerworlds.core.model.PlayerWorld;
import nl.gzmn.playerworlds.core.model.Role;
import nl.gzmn.playerworlds.core.model.WorldId;
import nl.gzmn.playerworlds.core.model.WorldState;
import nl.gzmn.playerworlds.proxy.command.Messages;
import nl.gzmn.playerworlds.proxy.command.Placeholders;
import org.jspecify.annotations.Nullable;

/**
 * Builds the paginated screen payload listing the worlds a player can reach with
 * quick actions and world creation triggers.
 *
 * <p>Two lists, one screen: the worlds the player owns come first, then the ones
 * they were invited to and accepted (FR-7). A world an invite made reachable is
 * otherwise findable only by remembering its owner's name, which is the state
 * the invite was supposed to end. The owned count on the create button still
 * counts only owned worlds, because that is the number FR-1's cap is about.
 */
public final class MyWorldsScreenBuilder {

    public static final String SCREEN_TYPE = "MY_WORLDS";
    public static final int SIZE = 54;
    public static final int PAGE_SIZE = 36;

    public static final int SLOT_PREVIOUS_PAGE = 45;
    public static final int SLOT_BACK = 48;
    public static final int SLOT_CREATE = 49;
    public static final int SLOT_NEXT_PAGE = 53;

    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacySection();

    private MyWorldsScreenBuilder() {}

    public static RenderMenuPayload build(
            Messages messages, long correlationId, List<PlayerWorld> worlds, int page, int maxWorlds) {
        return build(messages, correlationId, worlds, List.of(), Map.of(), page, maxWorlds);
    }

    /**
     * Builds the screen from the two lists separately, so the owned/shared split
     * survives into the rendering.
     *
     * @param owned worlds whose {@code owner_uuid} is the viewing player (FR-31a)
     * @param shared worlds the player is a member of but does not own
     * @param sharedRoles the player's role in each shared world, for the lore line
     */
    public static RenderMenuPayload build(
            Messages messages,
            long correlationId,
            List<PlayerWorld> owned,
            List<PlayerWorld> shared,
            Map<WorldId, Role> sharedRoles,
            int page,
            int maxWorlds) {
        Objects.requireNonNull(messages, "messages");
        Objects.requireNonNull(owned, "owned");
        Objects.requireNonNull(shared, "shared");
        Objects.requireNonNull(sharedRoles, "sharedRoles");

        List<PlayerWorld> all = new ArrayList<>(owned.size() + shared.size());
        all.addAll(owned);
        all.addAll(shared);

        int validPage = Math.max(0, page);
        int totalPages = Math.max(1, (int) Math.ceil((double) all.size() / PAGE_SIZE));
        String title = legacy(messages.render(
                "messages.gui.my-worlds-menu.title",
                Placeholders.count("page", validPage + 1),
                Placeholders.count("pages", totalPages)));

        List<MenuItemDescriptor> items = new ArrayList<>(SIZE);
        for (int i = 0; i < SIZE; i++) {
            items.add(new MenuItemDescriptor(i, "GRAY_STAINED_GLASS_PANE", 1, " ", List.of(), null, ""));
        }

        int startIndex = validPage * PAGE_SIZE;
        int endIndex = Math.min(all.size(), startIndex + PAGE_SIZE);

        for (int i = startIndex; i < endIndex; i++) {
            int slot = i - startIndex;
            PlayerWorld world = all.get(i);
            boolean isOwned = i < owned.size();
            items.set(
                    slot,
                    renderWorldItem(
                            messages, slot, world, isOwned, isOwned ? Role.OWNER : sharedRoles.get(world.id())));
        }

        // Divider row
        for (int i = 36; i < 45; i++) {
            items.set(i, new MenuItemDescriptor(i, "BLACK_STAINED_GLASS_PANE", 1, " ", List.of(), null, ""));
        }

        // Navigation bottom row
        if (validPage > 0) {
            items.set(
                    SLOT_PREVIOUS_PAGE,
                    new MenuItemDescriptor(
                            SLOT_PREVIOUS_PAGE,
                            "ARROW",
                            1,
                            legacy(messages.render("messages.gui.my-worlds-menu.item.previous-page.name")),
                            List.of(),
                            null,
                            "NAV:MY_WORLDS:" + (validPage - 1)));
        }

        items.set(
                SLOT_BACK,
                new MenuItemDescriptor(
                        SLOT_BACK,
                        "OAK_DOOR",
                        1,
                        legacy(messages.render("messages.gui.my-worlds-menu.item.back.name")),
                        legacyLore(messages.renderLore("messages.gui.my-worlds-menu.item.back.lore")),
                        null,
                        "NAV:MAIN"));

        List<Component> createLore = new ArrayList<>();
        createLore.add(messages.render(
                "messages.gui.my-worlds-menu.item.create.owned-line",
                Placeholders.count("owned", owned.size()),
                Placeholders.count("max", maxWorlds)));
        if (!shared.isEmpty()) {
            createLore.add(messages.render(
                    "messages.gui.my-worlds-menu.item.create.shared-line", Placeholders.count("count", shared.size())));
        }
        createLore.add(Component.empty());
        createLore.add(messages.render("messages.gui.my-worlds-menu.item.create.hint"));
        items.set(
                SLOT_CREATE,
                new MenuItemDescriptor(
                        SLOT_CREATE,
                        "NETHER_STAR",
                        1,
                        legacy(messages.render("messages.gui.my-worlds-menu.item.create.name")),
                        legacyLore(createLore),
                        null,
                        "ACTION:CREATE"));

        if ((validPage + 1) * PAGE_SIZE < all.size()) {
            items.set(
                    SLOT_NEXT_PAGE,
                    new MenuItemDescriptor(
                            SLOT_NEXT_PAGE,
                            "ARROW",
                            1,
                            legacy(messages.render("messages.gui.my-worlds-menu.item.next-page.name")),
                            List.of(),
                            null,
                            "NAV:MY_WORLDS:" + (validPage + 1)));
        }

        return new RenderMenuPayload(correlationId, SCREEN_TYPE, title, SIZE, items);
    }

    private static MenuItemDescriptor renderWorldItem(
            Messages messages, int slot, PlayerWorld world, boolean owned, @Nullable Role role) {
        String material =
                switch (world.state()) {
                    case READY -> owned ? "GRASS_BLOCK" : "PLAYER_HEAD";
                    case CREATING -> "OAK_SAPLING";
                    case ARCHIVED -> "CHEST";
                    case ARCHIVING, RESTORING -> "CLOCK";
                };

        Component nameValue = Component.text(
                world.name(), owned ? NamedTextColor.AQUA : NamedTextColor.LIGHT_PURPLE, TextDecoration.BOLD);
        Component name = messages.render(
                "messages.gui.my-worlds-menu.item.world-entry.name", Placeholders.component("world", nameValue));

        List<Component> lore = new ArrayList<>();
        Component stateValue = Component.text("State: " + world.state().name(), stateColor(world.state()));
        lore.add(messages.render(
                "messages.gui.my-worlds-menu.item.world-entry.state-line",
                Placeholders.component("state", stateValue)));
        if (!owned) {
            lore.add(messages.render(
                    "messages.gui.my-worlds-menu.item.world-entry.shared-note",
                    Placeholders.raw("role-suffix", role == null ? "" : " — " + role.name())));
        }
        lore.add(messages.render(
                "messages.gui.my-worlds-menu.item.world-entry.visibility-line",
                Placeholders.raw("visibility", world.visibility().name())));
        lore.add(messages.render(
                "messages.gui.my-worlds-menu.item.world-entry.size-line",
                Placeholders.bytes("size", world.storageBytes())));
        lore.add(messages.render(
                "messages.gui.my-worlds-menu.item.world-entry.border-line",
                Placeholders.count("radius", world.borderRadius())));
        lore.add(Component.empty());

        if (world.state() == WorldState.READY) {
            lore.add(messages.render("messages.gui.my-worlds-menu.item.world-entry.join-hint"));
        }
        // The proxy is the authority on who may manage a world (FR-31a); a
        // non-owner opening the detail screen reads it rather than being told
        // the world does not exist, so the entry is offered either way.
        lore.add(messages.render(
                "messages.gui.my-worlds-menu.item.world-entry.manage-hint",
                Placeholders.raw("action", owned ? "Manage World" : "World Details")));

        return new MenuItemDescriptor(
                slot,
                material,
                1,
                legacy(name),
                legacyLore(lore),
                null,
                "NAV:WORLD:" + world.id().value());
    }

    private static TextColor stateColor(WorldState state) {
        return switch (state) {
            case READY -> NamedTextColor.GREEN;
            case CREATING -> NamedTextColor.YELLOW;
            case ARCHIVED -> NamedTextColor.GRAY;
            case ARCHIVING, RESTORING -> NamedTextColor.GOLD;
        };
    }

    private static String legacy(Component component) {
        return LEGACY.serialize(component);
    }

    private static List<String> legacyLore(List<Component> lines) {
        return lines.stream().map(MyWorldsScreenBuilder::legacy).toList();
    }
}
