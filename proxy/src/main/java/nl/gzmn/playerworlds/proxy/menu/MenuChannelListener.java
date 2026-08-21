package nl.gzmn.playerworlds.proxy.menu;

import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.PluginMessageEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ServerConnection;
import com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import nl.gzmn.playerworlds.core.menu.CloseMenuMessage;
import nl.gzmn.playerworlds.core.menu.FailureCode;
import nl.gzmn.playerworlds.core.menu.IntentEnvelope;
import nl.gzmn.playerworlds.core.menu.MenuChannels;
import nl.gzmn.playerworlds.core.menu.MenuClickIntent;
import nl.gzmn.playerworlds.core.menu.MenuClosedNotice;
import nl.gzmn.playerworlds.core.menu.MenuCodec;
import nl.gzmn.playerworlds.core.menu.MenuCodecException;
import nl.gzmn.playerworlds.core.menu.MenuIntent;
import nl.gzmn.playerworlds.core.menu.MenuResult;
import nl.gzmn.playerworlds.core.menu.OpenMenu;
import nl.gzmn.playerworlds.core.menu.RenderMenuPayload;
import nl.gzmn.playerworlds.core.menu.WorldPresenceNotice;
import nl.gzmn.playerworlds.core.model.Visibility;
import nl.gzmn.playerworlds.core.model.WorldId;
import nl.gzmn.playerworlds.proxy.command.ActionResult;
import nl.gzmn.playerworlds.proxy.command.WorldActions;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Velocity channel listener for the {@code gzmn:menu} plugin messaging channel.
 *
 * <p>Enforces strict source security checks (Security Rule 1 and 2), decodes incoming
 * {@link OpenMenu}, {@link MenuClickIntent}, {@link MenuClosedNotice}, {@link WorldPresenceNotice}, and legacy {@link IntentEnvelope}
 * messages, dispatches them to {@link WorldActions} and {@link MenuViewService}, and sends serialized
 * {@link RenderMenuPayload}, {@link CloseMenuMessage}, or {@link MenuResult} messages back to the backend node.
 */
public final class MenuChannelListener {

    private static final Logger log = LoggerFactory.getLogger(MenuChannelListener.class);

    /** Velocity channel identifier for gzmn:menu. */
    public static final MinecraftChannelIdentifier CHANNEL_IDENTIFIER =
            MinecraftChannelIdentifier.from(MenuChannels.CHANNEL_NAME);

    private final WorldActions actions;
    private final MenuViewService viewService;

    public MenuChannelListener(WorldActions actions, MenuViewService viewService) {
        this.actions = Objects.requireNonNull(actions, "actions");
        this.viewService = Objects.requireNonNull(viewService, "viewService");
    }

    @Subscribe
    public void onPluginMessage(PluginMessageEvent event) {
        if (!CHANNEL_IDENTIFIER.equals(event.getIdentifier())) {
            return;
        }

        // Always mark the event handled so it is not forwarded to backend or client
        event.setResult(PluginMessageEvent.ForwardResult.handled());

        // Security Rule 1 (Source Check): Accept only from backend ServerConnection.
        // A Player source is a client-forged packet; drop immediately.
        if (!(event.getSource() instanceof ServerConnection connection)) {
            log.warn("Dropped gzmn:menu plugin message from non-server source: {}", event.getSource());
            return;
        }

        // Security Rule 2 (Identity from connection): Identity derived strictly from active connection.
        Player player = connection.getPlayer();

        final Object decoded;
        try {
            decoded = MenuCodec.decode(event.getData());
        } catch (MenuCodecException e) {
            log.warn(
                    "Failed to decode menu packet from server {} for player {}: {}",
                    connection.getServerInfo().getName(),
                    player.getUsername(),
                    e.getMessage());
            return;
        }

        if (decoded instanceof IntentEnvelope envelope) {
            handleIntentEnvelope(connection, player, envelope);
        } else if (decoded instanceof OpenMenu openMenu) {
            handleOpenMenu(connection, player, openMenu);
        } else if (decoded instanceof MenuClickIntent clickIntent) {
            handleMenuClickIntent(connection, player, clickIntent);
        } else if (decoded instanceof MenuClosedNotice closedNotice) {
            handleMenuClosedNotice(player, closedNotice);
        } else if (decoded instanceof WorldPresenceNotice presenceNotice) {
            // Identity and node both come from the connection, never the payload
            // (Security Rule 2). A node can only speak for players on it.
            actions.presence()
                    .entered(player.getUniqueId(), connection.getServerInfo().getName(), presenceNotice.worldId());
        } else {
            log.warn(
                    "Unhandled menu message type {} from server {} for player {}",
                    decoded.getClass().getSimpleName(),
                    connection.getServerInfo().getName(),
                    player.getUsername());
        }
    }

    private void handleOpenMenu(ServerConnection connection, Player player, OpenMenu openMenu) {
        var _ = viewService
                .buildMainMenu(player.getUniqueId(), player::hasPermission, openMenu.correlationId())
                .whenComplete((payload, throwable) -> sendRenderMenu(connection, payload, throwable));
    }

    private void handleMenuClosedNotice(Player player, MenuClosedNotice notice) {
        log.debug("Menu closed for player {} (correlationId: {})", player.getUsername(), notice.correlationId());
    }

    private void handleMenuClickIntent(ServerConnection connection, Player player, MenuClickIntent clickIntent) {
        String tag = clickIntent.actionTag();
        if (tag.isBlank()) {
            return;
        }
        long correlationId = clickIntent.correlationId();
        if (tag.startsWith("NAV:")) {
            handleNavigationTag(connection, player, tag, correlationId);
        } else if (tag.startsWith("ACTION:")) {
            handleActionTag(connection, player, tag, correlationId);
        } else {
            log.warn("Unknown tag format in MenuClickIntent: {}", tag);
        }
    }

    private void handleNavigationTag(ServerConnection connection, Player player, String tag, long correlationId) {
        java.util.List<String> parts = com.google.common.base.Splitter.on(':').splitToList(tag);
        if (parts.size() < 2) {
            return;
        }
        String target = parts.get(1).toUpperCase(Locale.ROOT);
        CompletableFuture<RenderMenuPayload> future =
                switch (target) {
                    case "MAIN" ->
                        viewService.buildMainMenu(player.getUniqueId(), player::hasPermission, correlationId);
                    case "MY_WORLDS" -> {
                        int page = parts.size() >= 3 ? parsePage(parts.get(2)) : 0;
                        yield viewService.buildMyWorldsMenu(player.getUniqueId(), page, correlationId);
                    }
                    case "WORLD" -> {
                        if (parts.size() < 3) yield null;
                        WorldId worldId = parseWorldId(parts.get(2));
                        yield worldId != null
                                ? viewService.buildWorldMenu(worldId, player.getUniqueId(), correlationId)
                                : null;
                    }
                    case "SETTINGS" -> {
                        if (parts.size() < 3) yield null;
                        WorldId worldId = parseWorldId(parts.get(2));
                        yield worldId != null ? viewService.buildSettingsMenu(worldId, correlationId) : null;
                    }
                    case "MEMBERS" -> {
                        if (parts.size() < 3) yield null;
                        WorldId worldId = parseWorldId(parts.get(2));
                        int page = parts.size() >= 4 ? parsePage(parts.get(3)) : 0;
                        yield worldId != null ? viewService.buildMembersMenu(worldId, page, correlationId) : null;
                    }
                    case "STORAGE" ->
                        viewService.buildStorageMenu(player.getUniqueId(), player::hasPermission, correlationId);
                    case "INVITES" -> {
                        int page = parts.size() >= 3 ? parsePage(parts.get(2)) : 0;
                        yield viewService.buildInvitesMenu(player.getUniqueId(), page, correlationId);
                    }
                    case "BANS" -> {
                        if (parts.size() < 3) yield null;
                        WorldId worldId = parseWorldId(parts.get(2));
                        int page = parts.size() >= 4 ? parsePage(parts.get(3)) : 0;
                        yield worldId != null ? viewService.buildBansMenu(worldId, page, correlationId) : null;
                    }
                    case "BROWSE" -> {
                        int page = parts.size() >= 3 ? parsePage(parts.get(2)) : 0;
                        yield viewService.buildBrowseMenu(page, correlationId);
                    }
                    default -> {
                        log.warn("Unknown navigation target '{}' in tag {}", target, tag);
                        yield null;
                    }
                };

        if (future != null) {
            var _ = future.whenComplete((payload, throwable) -> sendRenderMenu(connection, payload, throwable));
        }
    }

    private void handleActionTag(ServerConnection connection, Player player, String tag, long correlationId) {
        java.util.List<String> parts = com.google.common.base.Splitter.on(':').splitToList(tag);
        if (parts.size() < 2) {
            return;
        }
        String action = parts.get(1).toUpperCase(Locale.ROOT);
        switch (action) {
            case "CLOSE" ->
                connection.sendPluginMessage(
                        CHANNEL_IDENTIFIER, MenuCodec.encodeCloseMenu(new CloseMenuMessage(correlationId)));
            case "JOIN" -> {
                if (parts.size() >= 3) {
                    WorldId worldId = parseWorldId(parts.get(2));
                    connection.sendPluginMessage(
                            CHANNEL_IDENTIFIER, MenuCodec.encodeCloseMenu(new CloseMenuMessage(correlationId)));
                    if (worldId != null) {
                        var _ = actions.join(player, worldId)
                                .whenComplete((res, ex) -> handleActionOutcome(player, res, ex));
                    }
                }
            }
            case "CREATE" -> {
                String suffix = UUID.randomUUID().toString().substring(0, 8);
                String name =
                        parts.size() >= 3 ? parts.get(2) : player.getUsername().toLowerCase(Locale.ROOT) + "-" + suffix;
                executeActionAndRerender(
                        connection,
                        player,
                        actions.create(player, name, null),
                        () -> viewService.buildMyWorldsMenu(player.getUniqueId(), 0, correlationId));
            }
            case "ARCHIVE" -> {
                if (parts.size() >= 3) {
                    String worldName = parts.get(2);
                    executeActionAndRerender(
                            connection,
                            player,
                            actions.delete(player, worldName, true),
                            () -> viewService.buildMyWorldsMenu(player.getUniqueId(), 0, correlationId));
                }
            }
            case "RESTORE" -> {
                if (parts.size() >= 3) {
                    String worldName = parts.get(2);
                    executeActionAndRerender(
                            connection,
                            player,
                            actions.restore(player, worldName),
                            () -> viewService.buildMyWorldsMenu(player.getUniqueId(), 0, correlationId));
                }
            }
            case "SET_VISIBILITY" -> {
                if (parts.size() >= 4) {
                    WorldId worldId = parseWorldId(parts.get(2));
                    if (worldId != null) {
                        boolean isPublic = "PUBLIC".equalsIgnoreCase(parts.get(3));
                        executeActionAndRerender(
                                connection,
                                player,
                                actions.setPublic(player, isPublic, null, worldId),
                                () -> viewService.buildWorldMenu(worldId, player.getUniqueId(), correlationId));
                    }
                }
            }
            case "SET_SETTING" -> {
                if (parts.size() >= 5) {
                    WorldId worldId = parseWorldId(parts.get(2));
                    if (worldId != null) {
                        String key = parts.get(3);
                        String val = parts.get(4);
                        executeActionAndRerender(
                                connection,
                                player,
                                actions.setSetting(player, key, val, worldId),
                                () -> viewService.buildSettingsMenu(worldId, correlationId));
                    }
                }
            }
            case "PROMOTE" -> {
                if (parts.size() >= 4) {
                    WorldId worldId = parseWorldId(parts.get(2));
                    if (worldId != null) {
                        String targetName = parts.get(3);
                        executeActionAndRerender(
                                connection,
                                player,
                                actions.promote(player, targetName, worldId),
                                () -> viewService.buildMembersMenu(worldId, 0, correlationId));
                    }
                }
            }
            case "KICK" -> {
                if (parts.size() >= 4) {
                    WorldId worldId = parseWorldId(parts.get(2));
                    if (worldId != null) {
                        String targetName = parts.get(3);
                        executeActionAndRerender(
                                connection,
                                player,
                                actions.kick(player, targetName, worldId),
                                () -> viewService.buildMembersMenu(worldId, 0, correlationId));
                    }
                }
            }
            case "UNBAN" -> {
                if (parts.size() >= 4) {
                    WorldId worldId = parseWorldId(parts.get(2));
                    if (worldId != null) {
                        String targetName = parts.get(3);
                        executeActionAndRerender(
                                connection,
                                player,
                                actions.unban(player, targetName, worldId),
                                () -> viewService.buildBansMenu(worldId, 0, correlationId));
                    }
                }
            }
            case "ACCEPT_INVITE" -> {
                if (parts.size() >= 3) {
                    String owner = parts.get(2);
                    executeActionAndRerender(
                            connection,
                            player,
                            actions.accept(player, owner),
                            () -> viewService.buildInvitesMenu(player.getUniqueId(), 0, correlationId));
                }
            }
            case "ACCEPT_TRANSFER" -> {
                if (parts.size() >= 3) {
                    String owner = parts.get(2);
                    executeActionAndRerender(
                            connection,
                            player,
                            actions.transferAccept(player, owner),
                            () -> viewService.buildInvitesMenu(player.getUniqueId(), 0, correlationId));
                }
            }
            case "DECLINE_TRANSFER" -> {
                if (parts.size() >= 3) {
                    String owner = parts.get(2);
                    executeActionAndRerender(
                            connection,
                            player,
                            actions.transferDecline(player, owner),
                            () -> viewService.buildInvitesMenu(player.getUniqueId(), 0, correlationId));
                }
            }
            case "INVITE_INFO" ->
                player.sendMessage(
                        Component.text("Use /world invite <player> to invite a member.", NamedTextColor.YELLOW));
            default -> log.warn("Unknown action target '{}' in tag {}", action, tag);
        }
    }

    private void executeActionAndRerender(
            ServerConnection connection,
            Player player,
            CompletableFuture<ActionResult> actionFuture,
            Supplier<CompletableFuture<RenderMenuPayload>> rerenderSupplier) {
        var _ = actionFuture.whenComplete((actionResult, throwable) -> {
            if (throwable != null) {
                handleActionOutcome(player, null, throwable);
            } else if (actionResult instanceof ActionResult.Failed failed) {
                handleActionOutcome(player, failed, null);
            } else {
                var _ = rerenderSupplier
                        .get()
                        .whenComplete((payload, renderEx) -> sendRenderMenu(connection, payload, renderEx));
            }
        });
    }

    private void sendRenderMenu(
            ServerConnection connection, @Nullable RenderMenuPayload payload, @Nullable Throwable throwable) {
        if (throwable != null) {
            log.error("Failed to render menu payload", throwable);
            return;
        }
        if (payload != null) {
            byte[] bytes = MenuCodec.encodeRenderMenu(payload);
            connection.sendPluginMessage(CHANNEL_IDENTIFIER, bytes);
        }
    }

    private void handleActionOutcome(Player player, @Nullable ActionResult result, @Nullable Throwable throwable) {
        if (throwable != null) {
            log.error("Unhandled error executing action for player {}", player.getUsername(), throwable);
            String err = throwable.getMessage() != null ? throwable.getMessage() : "Internal server error";
            player.sendMessage(Component.text(err, NamedTextColor.RED));
        } else if (result instanceof ActionResult.Failed failed) {
            player.sendMessage(failed.message());
        }
    }

    private static int parsePage(String s) {
        try {
            return Math.max(0, Integer.parseInt(s));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static @Nullable WorldId parseWorldId(String raw) {
        try {
            return new WorldId(UUID.fromString(raw.trim()));
        } catch (IllegalArgumentException e) {
            log.warn("Invalid WorldId in tag: {}", raw);
            return null;
        }
    }

    private void handleIntentEnvelope(ServerConnection connection, Player player, IntentEnvelope envelope) {
        long correlationId = envelope.correlationId();
        MenuIntent intent = envelope.intent();

        CompletableFuture<ActionResult> future = dispatch(player, intent);
        var _ = future.whenComplete((actionResult, throwable) -> {
            MenuResult menuResult;
            if (throwable != null) {
                log.error(
                        "Unhandled error executing menu intent {} for player {}",
                        intent.getClass().getSimpleName(),
                        player.getUsername(),
                        throwable);
                String err = throwable.getMessage() != null ? throwable.getMessage() : "Internal server error";
                menuResult = new MenuResult.Failed(correlationId, FailureCode.GENERIC_ERROR, err);
            } else if (actionResult instanceof ActionResult.Ok ok) {
                String msg = PlainTextComponentSerializer.plainText().serialize(ok.message());
                menuResult = new MenuResult.Ok(correlationId, msg);
            } else if (actionResult instanceof ActionResult.Failed failed) {
                String msg = PlainTextComponentSerializer.plainText().serialize(failed.message());
                menuResult = new MenuResult.Failed(correlationId, failed.code(), msg);
            } else {
                menuResult =
                        new MenuResult.Failed(correlationId, FailureCode.GENERIC_ERROR, "Unknown action result type");
            }

            byte[] responseBytes = MenuCodec.encodeResult(menuResult);
            connection.sendPluginMessage(CHANNEL_IDENTIFIER, responseBytes);
        });
    }

    private CompletableFuture<ActionResult> dispatch(Player player, MenuIntent intent) {
        return switch (intent) {
            case MenuIntent.JoinWorld joinWorld -> actions.join(player, joinWorld.worldId());
            case MenuIntent.CreateWorld createWorld -> actions.create(player, createWorld.name(), createWorld.seed());
            case MenuIntent.ArchiveWorld archiveWorld ->
                // ConfirmMenu is FR-27's typed-confirmation substitute: the backend only
                // emits this intent after the owner clicks confirm in the modal.
                actions.delete(player, archiveWorld.worldName(), true);
            case MenuIntent.RestoreWorld restoreWorld -> actions.restore(player, restoreWorld.worldName());
            case MenuIntent.InviteMember inviteMember ->
                actions.invite(player, inviteMember.targetName(), inviteMember.worldId());
            case MenuIntent.KickMember kickMember ->
                actions.kick(player, kickMember.targetName(), kickMember.worldId());
            case MenuIntent.PromoteMember promoteMember ->
                actions.promote(player, promoteMember.targetName(), promoteMember.worldId());
            case MenuIntent.SetVisibility setVisibility ->
                actions.setPublic(
                        player, setVisibility.visibility() == Visibility.PUBLIC, null, setVisibility.worldId());
            case MenuIntent.SetSetting setSetting ->
                actions.setSetting(player, setSetting.settingKey(), setSetting.value(), setSetting.worldId());
            case MenuIntent.BanPlayer banPlayer ->
                actions.ban(player, banPlayer.targetName(), banPlayer.reason(), banPlayer.worldId());
            case MenuIntent.UnbanPlayer unbanPlayer ->
                actions.unban(player, unbanPlayer.targetName(), unbanPlayer.worldId());
            case MenuIntent.RequestTransfer requestTransfer ->
                actions.transfer(player, requestTransfer.targetName(), false, requestTransfer.worldId());
            case MenuIntent.AcceptTransfer acceptTransfer -> actions.transferAccept(player, acceptTransfer.ownerName());
            case MenuIntent.DeclineTransfer declineTransfer ->
                actions.transferDecline(player, declineTransfer.ownerName());
            case MenuIntent.AcceptInvite acceptInvite -> actions.accept(player, acceptInvite.ownerName());
            case MenuIntent.HardDeleteWorld hardDelete ->
                // ConfirmMenu is FR-37's confirmation substitute (admin hard-delete).
                actions.deleteHard(player, hardDelete.worldId());
        };
    }
}
