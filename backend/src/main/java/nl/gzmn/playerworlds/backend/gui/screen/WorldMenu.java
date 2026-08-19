package nl.gzmn.playerworlds.backend.gui.screen;

import java.util.Objects;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import nl.gzmn.playerworlds.backend.gui.GuiScreen;
import nl.gzmn.playerworlds.backend.gui.ItemUtil;
import nl.gzmn.playerworlds.backend.gui.MenuChannel;
import nl.gzmn.playerworlds.backend.gui.MenuHolder;
import nl.gzmn.playerworlds.backend.gui.MenuService;
import nl.gzmn.playerworlds.core.config.StorageQuotaResolver;
import nl.gzmn.playerworlds.core.menu.MenuIntent;
import nl.gzmn.playerworlds.core.menu.MenuResult;
import nl.gzmn.playerworlds.core.model.PlayerWorld;
import nl.gzmn.playerworlds.core.model.Visibility;
import nl.gzmn.playerworlds.core.model.WorldState;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.Inventory;
import org.jspecify.annotations.Nullable;

/**
 * Management screen for a single world, allowing the owner to join, manage members/bans,
 * toggle visibility, configure settings, view storage, or archive the world.
 */
public final class WorldMenu implements GuiScreen {

    public static final int SLOT_INFO = 4;
    public static final int SLOT_JOIN = 10;
    public static final int SLOT_MEMBERS = 11;
    public static final int SLOT_SETTINGS = 12;
    public static final int SLOT_VISIBILITY = 13;
    public static final int SLOT_BANS = 14;
    public static final int SLOT_STORAGE = 15;
    public static final int SLOT_ARCHIVE = 16;
    public static final int SLOT_BACK = 18;

    private final MenuService menuService;
    private final @Nullable MenuChannel menuChannel;
    private final PlayerWorld world;

    public WorldMenu(MenuService menuService, @Nullable MenuChannel menuChannel, PlayerWorld world) {
        this.menuService = Objects.requireNonNull(menuService, "menuService");
        this.menuChannel = menuChannel;
        this.world = Objects.requireNonNull(world, "world");
    }

    public PlayerWorld world() {
        return world;
    }

    @Override
    public Inventory render(Player player) {
        Objects.requireNonNull(player, "player");
        MenuHolder holder = new MenuHolder(this);
        Inventory inventory =
                Bukkit.createInventory(holder, 27, Component.text("Manage: " + world.name(), NamedTextColor.DARK_GRAY));
        holder.setInventory(inventory);

        for (int i = 0; i < 27; i++) {
            inventory.setItem(i, ItemUtil.filler());
        }

        // Slot 4: Overview
        inventory.setItem(
                SLOT_INFO,
                ItemUtil.create(
                        Material.BEACON,
                        Component.text(world.name(), NamedTextColor.GOLD, TextDecoration.BOLD),
                        Component.text("State: " + world.state().name(), NamedTextColor.GRAY),
                        Component.text("Visibility: " + world.visibility().name(), NamedTextColor.GRAY),
                        Component.text("Border: ±" + world.borderRadius() + "m", NamedTextColor.GRAY),
                        Component.text("Seed: " + world.seed(), NamedTextColor.DARK_GRAY),
                        Component.text(
                                "Storage: " + StorageQuotaResolver.formatBytes(world.storageBytes()),
                                NamedTextColor.GRAY)));
        // Slot 10: Join or Restore
        if (world.state() == WorldState.ARCHIVED) {
            inventory.setItem(
                    SLOT_JOIN,
                    ItemUtil.create(
                            Material.ANVIL,
                            Component.text("Restore World", NamedTextColor.GREEN, TextDecoration.BOLD),
                            Component.text("Restore this world from cold storage", NamedTextColor.GRAY),
                            Component.empty(),
                            Component.text("▶ Click to restore", NamedTextColor.YELLOW)));
        } else {
            inventory.setItem(
                    SLOT_JOIN,
                    ItemUtil.create(
                            Material.ENDER_PEARL,
                            Component.text("Join World", NamedTextColor.GREEN, TextDecoration.BOLD),
                            Component.text("Teleport directly to this world", NamedTextColor.GRAY),
                            Component.empty(),
                            Component.text("▶ Click to join", NamedTextColor.YELLOW)));
        }

        // Slot 11: Members
        inventory.setItem(
                SLOT_MEMBERS,
                ItemUtil.create(
                        Material.PLAYER_HEAD,
                        Component.text("Members & Permissions", NamedTextColor.AQUA, TextDecoration.BOLD),
                        Component.text("View members, invite players, or promote builders", NamedTextColor.GRAY),
                        Component.empty(),
                        Component.text("▶ Click to manage members", NamedTextColor.YELLOW)));

        // Slot 12: Settings
        inventory.setItem(
                SLOT_SETTINGS,
                ItemUtil.create(
                        Material.COMPARATOR,
                        Component.text("World Settings", NamedTextColor.YELLOW, TextDecoration.BOLD),
                        Component.text("Configure PvP, container access, and mob griefing", NamedTextColor.GRAY),
                        Component.empty(),
                        Component.text("▶ Click to configure", NamedTextColor.YELLOW)));

        // Slot 13: Visibility
        inventory.setItem(
                SLOT_VISIBILITY,
                ItemUtil.create(
                        Material.ENDER_EYE,
                        Component.text(
                                "Visibility: " + world.visibility().name(),
                                NamedTextColor.LIGHT_PURPLE,
                                TextDecoration.BOLD),
                        Component.text(
                                "Current: "
                                        + (world.visibility() == Visibility.PUBLIC
                                                ? "Public (anyone can browse and join)"
                                                : "Private (invite-only)"),
                                NamedTextColor.GRAY),
                        Component.empty(),
                        Component.text("▶ Click to toggle Public / Private", NamedTextColor.YELLOW)));

        // Slot 14: Bans
        inventory.setItem(
                SLOT_BANS,
                ItemUtil.create(
                        Material.IRON_BARS,
                        Component.text("Banned Players", NamedTextColor.RED, TextDecoration.BOLD),
                        Component.text("View and revoke bans from this world", NamedTextColor.GRAY),
                        Component.empty(),
                        Component.text("▶ Click to manage bans", NamedTextColor.YELLOW)));

        // Slot 15: Storage
        inventory.setItem(
                SLOT_STORAGE,
                ItemUtil.create(
                        Material.CHEST,
                        Component.text("Storage Usage", NamedTextColor.BLUE, TextDecoration.BOLD),
                        Component.text(
                                "World size: " + StorageQuotaResolver.formatBytes(world.storageBytes()),
                                NamedTextColor.GRAY),
                        Component.empty(),
                        Component.text("▶ Click to view storage breakdown", NamedTextColor.YELLOW)));

        // Slot 16: Archive or Permanently Delete
        if (world.state() == WorldState.ARCHIVED) {
            inventory.setItem(
                    SLOT_ARCHIVE,
                    ItemUtil.create(
                            Material.LAVA_BUCKET,
                            Component.text("Permanently Delete World", NamedTextColor.DARK_RED, TextDecoration.BOLD),
                            Component.text("⚠ Irreversible Action", NamedTextColor.RED, TextDecoration.BOLD),
                            Component.text("Permanently destroys all chunks and backup archives.", NamedTextColor.GRAY),
                            Component.empty(),
                            Component.text("▶ Click to delete permanently (requires confirm)", NamedTextColor.DARK_RED)));
        } else {
            inventory.setItem(
                    SLOT_ARCHIVE,
                    ItemUtil.create(
                            Material.TNT,
                            Component.text("Archive World", NamedTextColor.DARK_RED, TextDecoration.BOLD),
                            Component.text("Pack this world into cold storage and free a slot", NamedTextColor.GRAY),
                            Component.empty(),
                            Component.text("▶ Click to archive (requires confirm)", NamedTextColor.RED)));
        }

        // Slot 18: Back
        inventory.setItem(
                SLOT_BACK,
                ItemUtil.create(
                        Material.OAK_DOOR,
                        Component.text("Back to My Worlds", NamedTextColor.RED, TextDecoration.BOLD),
                        Component.text("▶ Click to return", NamedTextColor.DARK_GRAY)));

        return inventory;
    }

    @Override
    public void handleClick(Player player, int slot, ClickType clickType) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(clickType, "clickType");

        switch (slot) {
            case SLOT_JOIN -> {
                if (world.state() == WorldState.ARCHIVED) {
                    if (menuChannel != null) {
                        var _ = menuChannel
                                .sendIntent(player, new MenuIntent.RestoreWorld(world.name()))
                                .whenComplete((result, ex) -> {
                                    if (result instanceof MenuResult.Failed failed) {
                                        player.sendMessage(Component.text(
                                                "Could not restore world: " + failed.message(), NamedTextColor.RED));
                                    }
                                    var _ = menuService.openWorldMenu(player, world.id());
                                });
                    }
                } else {
                    if (menuChannel != null) {
                        var _ = menuChannel
                                .sendIntent(player, new MenuIntent.JoinWorld(world.id()))
                                .whenComplete((result, ex) -> {
                                    if (result instanceof MenuResult.Failed failed) {
                                        player.sendMessage(Component.text(
                                                "Could not join world: " + failed.message(), NamedTextColor.RED));
                                        var _ = menuService.openWorldMenu(player, world.id());
                                    }
                                });
                    }
                }
            }
            case SLOT_MEMBERS -> {
                var _ = menuService.openMembersMenu(player, world.id());
            }
            case SLOT_SETTINGS -> {
                var _ = menuService.openSettingsMenu(player, world.id());
            }
            case SLOT_VISIBILITY -> {
                Visibility next = (world.visibility() == Visibility.PUBLIC) ? Visibility.PRIVATE : Visibility.PUBLIC;
                if (menuChannel != null) {
                    var _ = menuChannel
                            .sendIntent(player, new MenuIntent.SetVisibility(world.id(), next))
                            .whenComplete((result, ex) -> {
                                if (result instanceof MenuResult.Ok) {
                                    var _ = menuService.openWorldMenu(player, world.id());
                                } else if (result instanceof MenuResult.Failed failed) {
                                    player.sendMessage(Component.text(
                                            "Could not change visibility: " + failed.message(), NamedTextColor.RED));
                                    var _ = menuService.openWorldMenu(player, world.id());
                                }
                            });
                }
            }
            case SLOT_BANS -> {
                var _ = menuService.openBansMenu(player, world.id());
            }
            case SLOT_STORAGE -> {
                var _ = menuService.openStorageMenu(player);
            }
            case SLOT_ARCHIVE -> {
                if (world.state() == WorldState.ARCHIVED) {
                    menuService.openConfirmMenu(
                            player,
                            Component.text(
                                    "Permanently Delete '" + world.name() + "'?", NamedTextColor.DARK_RED, TextDecoration.BOLD),
                            Component.text(
                                    "Permanently destroy '" + world.name() + "'? All archives will be lost forever.",
                                    NamedTextColor.RED),
                            () -> {
                                if (menuChannel != null) {
                                    var _ = menuChannel
                                            .sendIntent(player, new MenuIntent.HardDeleteWorld(world.id()))
                                            .whenComplete((result, ex) -> {
                                                if (result instanceof MenuResult.Ok ok) {
                                                    player.sendMessage(Component.text(ok.message(), NamedTextColor.GREEN));
                                                    var _ = menuService.openMyWorldsMenu(player);
                                                } else if (result instanceof MenuResult.Failed failed) {
                                                    player.sendMessage(Component.text(
                                                            "Could not delete world: " + failed.message(),
                                                            NamedTextColor.RED));
                                                    var _ = menuService.openWorldMenu(player, world.id());
                                                }
                                            });
                                }
                            },
                            () -> {
                                var _ = menuService.openWorldMenu(player, world.id());
                            });
                } else {
                    menuService.openConfirmMenu(
                            player,
                            Component.text(
                                    "Archive '" + world.name() + "'?", NamedTextColor.DARK_RED, TextDecoration.BOLD),
                            Component.text(
                                    "This packs the world to cold storage. You can restore it later.",
                                    NamedTextColor.GRAY),
                            () -> {
                                if (menuChannel != null) {
                                    var _ = menuChannel
                                            .sendIntent(player, new MenuIntent.ArchiveWorld(world.name()))
                                            .whenComplete((result, ex) -> {
                                                if (result instanceof MenuResult.Ok) {
                                                    var _ = menuService.openMyWorldsMenu(player);
                                                } else if (result instanceof MenuResult.Failed failed) {
                                                    player.sendMessage(Component.text(
                                                            "Could not archive world: " + failed.message(),
                                                            NamedTextColor.RED));
                                                    var _ = menuService.openWorldMenu(player, world.id());
                                                }
                                            });
                                }
                            },
                            () -> {
                                var _ = menuService.openWorldMenu(player, world.id());
                            });
                }
            }
            case SLOT_BACK -> {
                var _ = menuService.openMyWorldsMenu(player);
            }
            default -> {
                // Non-clickable filler
            }
        }
    }

    @Override
    public void refresh(Player player) {
        Objects.requireNonNull(player, "player");
        var _ = menuService.openWorldMenu(player, world.id());
    }
}
