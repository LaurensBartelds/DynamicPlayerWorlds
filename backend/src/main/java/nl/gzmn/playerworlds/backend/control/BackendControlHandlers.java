package nl.gzmn.playerworlds.backend.control;

import java.util.Objects;
import java.util.Optional;
import nl.gzmn.playerworlds.backend.storage.WorldArchiver;
import nl.gzmn.playerworlds.backend.storage.WorldRestorer;
import nl.gzmn.playerworlds.core.control.ArchivePayload;
import nl.gzmn.playerworlds.core.control.CommandHandler;
import nl.gzmn.playerworlds.core.control.CommandKind;
import nl.gzmn.playerworlds.core.control.CommandResult;
import nl.gzmn.playerworlds.core.control.ControlPlane;
import nl.gzmn.playerworlds.core.control.NodeCommand;
import nl.gzmn.playerworlds.core.model.WorldId;
import org.jspecify.annotations.Nullable;

/**
 * Control plane handlers for node-level world operations, including cold archival
 * ({@link CommandKind#ARCHIVE_WORLD}) and restore ({@link CommandKind#RESTORE_WORLD}).
 */
public final class BackendControlHandlers {

    private BackendControlHandlers() {}

    /**
     * Registers archival and restore command handlers on the control plane if the services are present.
     *
     * <p>Both services are absent on a node with no storage configured. Leaving the kinds
     * unregistered is deliberate: an unhandled command surfaces as such, rather than as a
     * handler that silently reports success without archiving anything.
     */
    public static void registerStorageHandlers(
            ControlPlane plane, @Nullable WorldArchiver archiver, @Nullable WorldRestorer restorer) {
        Objects.requireNonNull(plane, "plane");

        if (archiver != null) {
            plane.register(CommandKind.ARCHIVE_WORLD, new ArchiveWorldHandler(archiver));
        }
        if (restorer != null) {
            plane.register(CommandKind.RESTORE_WORLD, new RestoreWorldHandler(restorer));
        }
    }

    /**
     * Handles {@link CommandKind#ARCHIVE_WORLD} control plane commands (FR-35).
     */
    public static final class ArchiveWorldHandler implements CommandHandler {

        private final WorldArchiver archiver;

        public ArchiveWorldHandler(WorldArchiver archiver) {
            this.archiver = Objects.requireNonNull(archiver, "archiver");
        }

        @Override
        public CommandResult handle(NodeCommand command) {
            WorldId worldId = command.worldId();
            if (worldId == null) {
                return CommandResult.error("missing world_id");
            }

            Optional<ArchivePayload> payload = ArchivePayload.parse(command.payloadJson());
            if (payload.isEmpty()) {
                return CommandResult.error("malformed payload");
            }

            WorldArchiver.ArchiveResult result =
                    archiver.archiveWorld(worldId, payload.get().ownerUuid());
            if (result.success()) {
                return CommandResult.ok();
            }
            return CommandResult.error(result.message() != null ? result.message() : "Archival failed");
        }
    }

    /**
     * Handles {@link CommandKind#RESTORE_WORLD} control plane commands (FR-36).
     */
    public static final class RestoreWorldHandler implements CommandHandler {

        private final WorldRestorer restorer;

        public RestoreWorldHandler(WorldRestorer restorer) {
            this.restorer = Objects.requireNonNull(restorer, "restorer");
        }

        @Override
        public CommandResult handle(NodeCommand command) {
            WorldId worldId = command.worldId();
            if (worldId == null) {
                return CommandResult.error("missing world_id");
            }

            Optional<ArchivePayload> payload = ArchivePayload.parse(command.payloadJson());
            if (payload.isEmpty()) {
                return CommandResult.error("malformed payload");
            }

            WorldRestorer.RestoreResult result =
                    restorer.restoreWorld(worldId, payload.get().ownerUuid());
            if (result.success()) {
                return CommandResult.ok();
            }
            return CommandResult.error(result.message() != null ? result.message() : "Restore failed");
        }
    }
}
