package nl.gzmn.playerworlds.proxy.menu.screens;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import nl.gzmn.playerworlds.core.menu.MenuItemDescriptor;
import nl.gzmn.playerworlds.core.menu.RenderMenuPayload;
import nl.gzmn.playerworlds.core.model.PlayerWorld;
import org.jspecify.annotations.Nullable;

/**
 * Builds the paginated screen payload displaying players banned from a specific world.
 */
public final class BansScreenBuilder {

    public static final String SCREEN_TYPE = "BANS";
    public static final int SIZE = 54;
    public static final int PAGE_SIZE = 36;

    public static final int SLOT_PREVIOUS_PAGE = 45;
    public static final int SLOT_BACK = 48;
    public static final int SLOT_NEXT_PAGE = 53;

    public record BanEntry(UUID uuid, String name, @Nullable String reason, Instant bannedAt) {
        public BanEntry {
            Objects.requireNonNull(uuid, "uuid");
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(bannedAt, "bannedAt");
        }
    }

    private BansScreenBuilder() {}

    public static RenderMenuPayload build(long correlationId, PlayerWorld world, List<BanEntry> bans, int page) {
        Objects.requireNonNull(world, "world");
        Objects.requireNonNull(bans, "bans");
        int validPage = Math.max(0, page);
        int totalPages = Math.max(1, (int) Math.ceil((double) bans.size() / PAGE_SIZE));
        String title = "§8Bans: " + world.name() + " (" + (validPage + 1) + "/" + totalPages + ")";

        List<MenuItemDescriptor> items = new ArrayList<>(SIZE);
        for (int i = 0; i < SIZE; i++) {
            items.add(new MenuItemDescriptor(i, "GRAY_STAINED_GLASS_PANE", 1, " ", List.of(), null, ""));
        }

        if (bans.isEmpty()) {
            items.set(
                    22,
                    new MenuItemDescriptor(
                            22,
                            "IRON_BARS",
                            1,
                            "§a§lNo Banned Players",
                            List.of("§7No players are currently banned from this world."),
                            null,
                            ""));
        } else {
            int startIndex = validPage * PAGE_SIZE;
            int endIndex = Math.min(bans.size(), startIndex + PAGE_SIZE);

            for (int i = startIndex; i < endIndex; i++) {
                int slot = i - startIndex;
                BanEntry ban = bans.get(i);
                items.set(slot, renderBanItem(slot, world, ban));
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
                            "§e§l◀ Previous Page",
                            List.of(),
                            null,
                            "NAV:BANS:" + world.id().value() + ":" + (validPage - 1)));
        }

        items.set(
                SLOT_BACK,
                new MenuItemDescriptor(
                        SLOT_BACK,
                        "OAK_DOOR",
                        1,
                        "§c§lBack to World Menu",
                        List.of("§8▶ Click to return"),
                        null,
                        "NAV:WORLD:" + world.id().value()));

        if ((validPage + 1) * PAGE_SIZE < bans.size()) {
            items.set(
                    SLOT_NEXT_PAGE,
                    new MenuItemDescriptor(
                            SLOT_NEXT_PAGE,
                            "ARROW",
                            1,
                            "§e§lNext Page ▶",
                            List.of(),
                            null,
                            "NAV:BANS:" + world.id().value() + ":" + (validPage + 1)));
        }

        return new RenderMenuPayload(correlationId, SCREEN_TYPE, title, SIZE, items);
    }

    private static MenuItemDescriptor renderBanItem(int slot, PlayerWorld world, BanEntry ban) {
        List<String> lore = new ArrayList<>();
        lore.add("§7Reason: " + (ban.reason() != null ? ban.reason() : "No reason provided"));
        lore.add("§8Banned: " + ban.bannedAt().toString().substring(0, 10));
        lore.add("");
        lore.add("§e▶ Click to unban");

        return new MenuItemDescriptor(
                slot,
                "PLAYER_HEAD",
                1,
                "§c§l" + ban.name(),
                lore,
                ban.uuid(),
                "ACTION:UNBAN:" + world.id().value() + ":" + ban.name());
    }
}
