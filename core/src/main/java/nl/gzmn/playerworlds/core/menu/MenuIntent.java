package nl.gzmn.playerworlds.core.menu;

import java.util.Objects;
import nl.gzmn.playerworlds.core.model.Visibility;
import nl.gzmn.playerworlds.core.model.WorldId;
import org.jspecify.annotations.Nullable;

/**
 * Action intent sent from backend to proxy when a player interacts with a menu GUI.
 *
 * <p>In accordance with Security Rule 2, {@code MenuIntent} contains <b>no player UUID</b>.
 * Identity is derived strictly from the active connection on the proxy.
 */
public sealed interface MenuIntent
        permits MenuIntent.JoinWorld,
                MenuIntent.CreateWorld,
                MenuIntent.ArchiveWorld,
                MenuIntent.RestoreWorld,
                MenuIntent.InviteMember,
                MenuIntent.KickMember,
                MenuIntent.PromoteMember,
                MenuIntent.SetVisibility,
                MenuIntent.SetSetting,
                MenuIntent.BanPlayer,
                MenuIntent.UnbanPlayer,
                MenuIntent.RequestTransfer,
                MenuIntent.AcceptTransfer,
                MenuIntent.DeclineTransfer,
                MenuIntent.AcceptInvite,
                MenuIntent.HardDeleteWorld {

    record HardDeleteWorld(WorldId worldId) implements MenuIntent {
        public HardDeleteWorld {
            Objects.requireNonNull(worldId, "worldId");
        }
    }

    record JoinWorld(WorldId worldId) implements MenuIntent {
        public JoinWorld {
            Objects.requireNonNull(worldId, "worldId");
        }
    }

    record CreateWorld(String name, @Nullable String seed) implements MenuIntent {
        public CreateWorld {
            Objects.requireNonNull(name, "name");
        }
    }

    record ArchiveWorld(String worldName) implements MenuIntent {
        public ArchiveWorld {
            Objects.requireNonNull(worldName, "worldName");
        }
    }

    record RestoreWorld(String worldName) implements MenuIntent {
        public RestoreWorld {
            Objects.requireNonNull(worldName, "worldName");
        }
    }

    record InviteMember(String targetName, @Nullable WorldId worldId) implements MenuIntent {
        public InviteMember {
            Objects.requireNonNull(targetName, "targetName");
        }
    }

    record KickMember(String targetName, @Nullable WorldId worldId) implements MenuIntent {
        public KickMember {
            Objects.requireNonNull(targetName, "targetName");
        }
    }

    record PromoteMember(String targetName, @Nullable WorldId worldId) implements MenuIntent {
        public PromoteMember {
            Objects.requireNonNull(targetName, "targetName");
        }
    }

    record SetVisibility(WorldId worldId, Visibility visibility) implements MenuIntent {
        public SetVisibility {
            Objects.requireNonNull(worldId, "worldId");
            Objects.requireNonNull(visibility, "visibility");
        }
    }

    record SetSetting(WorldId worldId, String settingKey, String value) implements MenuIntent {
        public SetSetting {
            Objects.requireNonNull(worldId, "worldId");
            Objects.requireNonNull(settingKey, "settingKey");
            Objects.requireNonNull(value, "value");
        }
    }

    record BanPlayer(
            String targetName,
            @Nullable WorldId worldId,
            @Nullable String reason) implements MenuIntent {
        public BanPlayer {
            Objects.requireNonNull(targetName, "targetName");
        }
    }

    record UnbanPlayer(String targetName, @Nullable WorldId worldId) implements MenuIntent {
        public UnbanPlayer {
            Objects.requireNonNull(targetName, "targetName");
        }
    }

    record RequestTransfer(String targetName, @Nullable WorldId worldId) implements MenuIntent {
        public RequestTransfer {
            Objects.requireNonNull(targetName, "targetName");
        }
    }

    record AcceptTransfer(String ownerName) implements MenuIntent {
        public AcceptTransfer {
            Objects.requireNonNull(ownerName, "ownerName");
        }
    }

    record DeclineTransfer(String ownerName) implements MenuIntent {
        public DeclineTransfer {
            Objects.requireNonNull(ownerName, "ownerName");
        }
    }

    record AcceptInvite(String ownerName) implements MenuIntent {
        public AcceptInvite {
            Objects.requireNonNull(ownerName, "ownerName");
        }
    }
}
