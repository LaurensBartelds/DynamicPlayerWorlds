package nl.gzmn.playerworlds.proxy.menu;

import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.PluginMessageEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ServerConnection;
import com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import nl.gzmn.playerworlds.core.menu.FailureCode;
import nl.gzmn.playerworlds.core.menu.IntentEnvelope;
import nl.gzmn.playerworlds.core.menu.MenuChannels;
import nl.gzmn.playerworlds.core.menu.MenuCodec;
import nl.gzmn.playerworlds.core.menu.MenuCodecException;
import nl.gzmn.playerworlds.core.menu.MenuIntent;
import nl.gzmn.playerworlds.core.menu.MenuResult;
import nl.gzmn.playerworlds.core.model.Visibility;
import nl.gzmn.playerworlds.proxy.command.ActionResult;
import nl.gzmn.playerworlds.proxy.command.WorldActions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Velocity channel listener for the {@code gzmn:menu} plugin messaging channel.
 *
 * <p>Enforces strict source security checks (Security Rule 1 and 2), decodes incoming
 * {@link MenuIntent}s, dispatches them to {@link WorldActions}, and sends serialized
 * {@link MenuResult}s back to the backend node over the player's connection.
 */
public final class MenuChannelListener {

    private static final Logger log = LoggerFactory.getLogger(MenuChannelListener.class);

    /** Velocity channel identifier for gzmn:menu. */
    public static final MinecraftChannelIdentifier CHANNEL_IDENTIFIER =
            MinecraftChannelIdentifier.from(MenuChannels.CHANNEL_NAME);

    private final WorldActions actions;

    public MenuChannelListener(WorldActions actions) {
        this.actions = Objects.requireNonNull(actions, "actions");
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

        final IntentEnvelope envelope;
        try {
            envelope = MenuCodec.decodeIntent(event.getData());
        } catch (MenuCodecException e) {
            log.warn(
                    "Failed to decode menu intent from server {} for player {}: {}",
                    connection.getServerInfo().getName(),
                    player.getUsername(),
                    e.getMessage());
            return;
        }

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
                FailureCode code = mapFailureCode(failed.code());
                menuResult = new MenuResult.Failed(correlationId, code, msg);
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
                // GUI click in ConfirmMenu is already confirmed
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
            case MenuIntent.HardDeleteWorld hardDelete -> actions.deleteHard(player, hardDelete.worldId());
        };
    }

    /**
     * Maps an internal {@link ActionResult.Failed#code()} string to a wire protocol {@link FailureCode}.
     */
    public static FailureCode mapFailureCode(@org.jspecify.annotations.Nullable String code) {
        if (code == null) {
            return FailureCode.GENERIC_ERROR;
        }
        return switch (code) {
            case "LIMIT_REACHED", "CAP_REACHED" -> FailureCode.CAP_REACHED;
            case "NAME_TAKEN", "ALREADY_MEMBER", "ALREADY_EXISTS" -> FailureCode.ALREADY_EXISTS;
            case "QUOTA_EXCEEDED" -> FailureCode.QUOTA_EXCEEDED;
            case "ROUTING_FAILED", "NOT_ROUTABLE", "SERVER_UNROUTABLE" -> FailureCode.SERVER_UNROUTABLE;
            case "WORLD_NOT_FOUND", "NO_TARGET_WORLD" -> FailureCode.WORLD_NOT_FOUND;
            case "PLAYER_NOT_FOUND" -> FailureCode.PLAYER_NOT_FOUND;
            case "BANNED" -> FailureCode.BANNED;
            case "NOT_MEMBER", "CANNOT_KICK_OWNER", "CANNOT_BAN_OWNER", "PERMISSION_DENIED" ->
                FailureCode.PERMISSION_DENIED;
            case "ALREADY_ARCHIVED",
                    "ILLEGAL_STATE",
                    "STATE_CHANGED",
                    "NOT_ARCHIVED",
                    "LEASE_RACE",
                    "NO_LIVE_INVITE",
                    "NOT_A_MEMBER",
                    "NO_REQUEST",
                    "OWNER_CHANGED",
                    "NOT_BANNED",
                    "STATE_CONFLICT" -> FailureCode.STATE_CONFLICT;
            case "CANNOT_TARGET_SELF", "UNKNOWN_SETTING", "INVALID_NAME" -> FailureCode.INVALID_NAME;
            case "ISOLATION_VIOLATION" -> FailureCode.ISOLATION_VIOLATION;
            case "TIMEOUT" -> FailureCode.TIMEOUT;
            case "DATABASE_ERROR", "GENERIC_ERROR" -> FailureCode.GENERIC_ERROR;
            default -> {
                try {
                    yield FailureCode.fromName(code);
                } catch (IllegalArgumentException e) {
                    yield FailureCode.GENERIC_ERROR;
                }
            }
        };
    }
}
