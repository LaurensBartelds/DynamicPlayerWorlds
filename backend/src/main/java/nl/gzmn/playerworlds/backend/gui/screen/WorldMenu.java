package nl.gzmn.playerworlds.backend.gui.screen;

import java.util.Objects;
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer;
import nl.gzmn.playerworlds.backend.gui.GuiScreen;
import nl.gzmn.playerworlds.backend.gui.ItemUtil;
import nl.gzmn.playerworlds.backend.gui.MenuChannel;
import nl.gzmn.playerworlds.backend.gui.MenuHolder;
import nl.gzmn.playerworlds.backend.gui.MenuService;
import nl.gzmn.playerworlds.backend.gui.Messages;
import nl.gzmn.playerworlds.backend.gui.Placeholders;
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
 *
 * <p>A member who is not the owner gets the same screen without the management
 * half. That is not decoration: the proxy resolves {@code /world archive} by
 * <em>name against the caller's own worlds</em>, so a visitor pressing Archive on
 * a world called "home" would have archived their own world of that name. The
 * proxy refuses everything here for a non-owner (FR-31a), but a control that
 * cannot succeed should not be drawn, and this one could succeed against the
 * wrong world.
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

    /**
     * Whether this viewer may manage the world.
     *
     * <p>{@code owner_uuid} is the authority, never the {@code OWNER} role value
     * (FR-31a).
     */
    private boolean manageable(Player viewer) {
        return world.ownerUuid().equals(viewer.getUniqueId());
    }

    @Override
    public Inventory render(Player player) {
        Objects.requireNonNull(player, "player");
        Messages messages = menuService.messages();
        boolean manage = manageable(player);
        MenuHolder holder = new MenuHolder(this);
        Inventory inventory = Bukkit.createInventory(
                holder,
                27,
                messages.render(
                        "messages.gui.world-menu.title",
                        Placeholders.raw("prefix", manage ? "Manage" : "World"),
                        Placeholders.text("world", world.name())));
        holder.setInventory(inventory);

        for (int i = 0; i < 27; i++) {
            inventory.setItem(i, ItemUtil.filler());
        }

        // Slot 4: Overview
        inventory.setItem(
                SLOT_INFO,
                ItemUtil.create(
                        Material.BEACON,
                        messages.render(
                                "messages.gui.world-menu.item.info.name", Placeholders.text("world", world.name())),
                        messages.renderLore(
                                "messages.gui.world-menu.item.info.lore",
                                Placeholders.raw("state", world.state().name()),
                                Placeholders.raw(
                                        "visibility", world.visibility().name()),
                                Placeholders.count("radius", world.borderRadius()),
                                Placeholders.count("seed", world.seed()),
                                Placeholders.bytes("size", world.storageBytes()))));
        // Slot 10: Join or Restore
        if (world.state() == WorldState.ARCHIVED) {
            inventory.setItem(
                    SLOT_JOIN,
                    manage
                            ? ItemUtil.create(
                                    Material.ANVIL,
                                    messages.render("messages.gui.world-menu.item.restore.name"),
                                    messages.renderLore("messages.gui.world-menu.item.restore.lore"))
                            : ItemUtil.create(
                                    Material.ANVIL,
                                    messages.render("messages.gui.world-menu.item.archived-locked.name"),
                                    messages.renderLore("messages.gui.world-menu.item.archived-locked.lore")));
        } else {
            inventory.setItem(
                    SLOT_JOIN,
                    ItemUtil.create(
                            Material.ENDER_PEARL,
                            messages.render("messages.gui.world-menu.item.join.name"),
                            messages.renderLore("messages.gui.world-menu.item.join.lore")));
        }

        if (manage) {
            renderManagementControls(inventory, messages);
        }

        // Slot 18: Back
        inventory.setItem(
                SLOT_BACK,
                ItemUtil.create(
                        Material.OAK_DOOR,
                        messages.render("messages.gui.world-menu.item.back.name"),
                        messages.renderLore("messages.gui.world-menu.item.back.lore")));

        return inventory;
    }

    /** The half of the screen only {@code owner_uuid} may act on (FR-31a). */
    private void renderManagementControls(Inventory inventory, Messages messages) {
        // Slot 11: Members
        inventory.setItem(
                SLOT_MEMBERS,
                ItemUtil.create(
                        Material.PLAYER_HEAD,
                        messages.render("messages.gui.world-menu.item.members.name"),
                        messages.renderLore("messages.gui.world-menu.item.members.lore")));

        // Slot 12: Settings
        inventory.setItem(
                SLOT_SETTINGS,
                ItemUtil.create(
                        Material.COMPARATOR,
                        messages.render("messages.gui.world-menu.item.settings.name"),
                        messages.renderLore("messages.gui.world-menu.item.settings.lore")));

        // Slot 13: Visibility
        String visibilityDescription = world.visibility() == Visibility.PUBLIC
                ? "Public (anyone can browse and join)"
                : "Private (invite-only)";
        inventory.setItem(
                SLOT_VISIBILITY,
                ItemUtil.create(
                        Material.ENDER_EYE,
                        messages.render(
                                "messages.gui.world-menu.item.visibility.name",
                                Placeholders.raw(
                                        "visibility", world.visibility().name())),
                        messages.renderLore(
                                "messages.gui.world-menu.item.visibility.lore",
                                Placeholders.raw("description", visibilityDescription))));

        // Slot 14: Bans
        inventory.setItem(
                SLOT_BANS,
                ItemUtil.create(
                        Material.IRON_BARS,
                        messages.render("messages.gui.world-menu.item.bans.name"),
                        messages.renderLore("messages.gui.world-menu.item.bans.lore")));

        // Slot 15: Storage
        inventory.setItem(
                SLOT_STORAGE,
                ItemUtil.create(
                        Material.CHEST,
                        messages.render("messages.gui.world-menu.item.storage.name"),
                        messages.renderLore(
                                "messages.gui.world-menu.item.storage.lore",
                                Placeholders.bytes("size", world.storageBytes()))));

        // Slot 16: Archive or Permanently Delete
        if (world.state() == WorldState.ARCHIVED) {
            inventory.setItem(
                    SLOT_ARCHIVE,
                    ItemUtil.create(
                            Material.LAVA_BUCKET,
                            messages.render("messages.gui.world-menu.item.delete-permanently.name"),
                            messages.renderLore("messages.gui.world-menu.item.delete-permanently.lore")));
        } else {
            inventory.setItem(
                    SLOT_ARCHIVE,
                    ItemUtil.create(
                            Material.TNT,
                            messages.render("messages.gui.world-menu.item.archive.name"),
                            messages.renderLore("messages.gui.world-menu.item.archive.lore")));
        }
    }

    @Override
    public void handleClick(Player player, int slot, ClickType clickType) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(clickType, "clickType");

        if (!manageable(player) && slot != SLOT_JOIN && slot != SLOT_STORAGE && slot != SLOT_BACK) {
            // Nothing was drawn there for them, and a click on a filler slot is
            // not a request for anything.
            return;
        }

        switch (slot) {
            case SLOT_JOIN -> {
                if (world.state() == WorldState.ARCHIVED) {
                    if (!manageable(player)) {
                        // Restore resolves by name against the caller's own
                        // worlds, so it is the owner's to press.
                        return;
                    }
                    if (menuChannel != null) {
                        var _ = menuChannel
                                .sendIntent(player, new MenuIntent.RestoreWorld(world.name()))
                                .whenComplete((result, ex) -> {
                                    if (result instanceof MenuResult.Failed failed) {
                                        player.sendMessage(
                                                GsonComponentSerializer.gson().deserialize(failed.message()));
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
                                        player.sendMessage(
                                                GsonComponentSerializer.gson().deserialize(failed.message()));
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
                                    player.sendMessage(
                                            GsonComponentSerializer.gson().deserialize(failed.message()));
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
                Messages messages = menuService.messages();
                if (world.state() == WorldState.ARCHIVED) {
                    menuService.openConfirmMenu(
                            player,
                            messages.render(
                                    "messages.gui.world-menu.confirm.delete.title",
                                    Placeholders.text("world", world.name())),
                            messages.render(
                                    "messages.gui.world-menu.confirm.delete.body",
                                    Placeholders.text("world", world.name())),
                            () -> {
                                if (menuChannel != null) {
                                    var _ = menuChannel
                                            .sendIntent(player, new MenuIntent.HardDeleteWorld(world.id()))
                                            .whenComplete((result, ex) -> {
                                                if (result instanceof MenuResult.Ok ok) {
                                                    player.sendMessage(GsonComponentSerializer.gson()
                                                            .deserialize(ok.message()));
                                                    var _ = menuService.openMyWorldsMenu(player);
                                                } else if (result instanceof MenuResult.Failed failed) {
                                                    player.sendMessage(GsonComponentSerializer.gson()
                                                            .deserialize(failed.message()));
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
                            messages.render(
                                    "messages.gui.world-menu.confirm.archive.title",
                                    Placeholders.text("world", world.name())),
                            messages.render("messages.gui.world-menu.confirm.archive.body"),
                            () -> {
                                if (menuChannel != null) {
                                    var _ = menuChannel
                                            .sendIntent(player, new MenuIntent.ArchiveWorld(world.name()))
                                            .whenComplete((result, ex) -> {
                                                if (result instanceof MenuResult.Ok) {
                                                    var _ = menuService.openMyWorldsMenu(player);
                                                } else if (result instanceof MenuResult.Failed failed) {
                                                    player.sendMessage(GsonComponentSerializer.gson()
                                                            .deserialize(failed.message()));
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
