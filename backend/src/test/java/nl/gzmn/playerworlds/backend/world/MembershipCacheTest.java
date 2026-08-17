package nl.gzmn.playerworlds.backend.world;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.UUID;
import nl.gzmn.playerworlds.core.model.Role;
import nl.gzmn.playerworlds.core.model.WorldId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The roles role enforcement reads on the tick thread (FR-9, FR-31a). */
class MembershipCacheTest {

    private final MembershipCache cache = new MembershipCache();
    private final WorldId worldId = WorldId.random();
    private final UUID owner = UUID.randomUUID();
    private final UUID builder = UUID.randomUUID();
    private final UUID visitor = UUID.randomUUID();

    private void fill() {
        cache.put(worldId, owner, Map.of(owner, Role.OWNER, builder, Role.BUILDER, visitor, Role.VISITOR));
    }

    @Test
    @DisplayName("each member resolves to their role")
    void rolesResolve() {
        fill();

        assertThat(cache.roleOf(worldId, owner)).contains(Role.OWNER);
        assertThat(cache.roleOf(worldId, builder)).contains(Role.BUILDER);
        assertThat(cache.roleOf(worldId, visitor)).contains(Role.VISITOR);
    }

    @Test
    @DisplayName("owner_uuid beats the role map when the two disagree (FR-31a)")
    void ownerUuidWins() {
        // A half-applied transfer, or a hand-edited row: the world still answers
        // to whoever player_world.owner_uuid says owns it.
        cache.put(worldId, owner, Map.of(owner, Role.VISITOR, builder, Role.OWNER));

        assertThat(cache.roleOf(worldId, owner)).contains(Role.OWNER);
        assertThat(cache.effectiveRole(worldId, owner).canBuild()).isTrue();
    }

    @Test
    @DisplayName("a non-member is a visitor, not a builder")
    void nonMembersAreVisitors() {
        fill();

        assertThat(cache.roleOf(worldId, UUID.randomUUID())).isEmpty();
        assertThat(cache.effectiveRole(worldId, UUID.randomUUID())).isEqualTo(Role.VISITOR);
    }

    @Test
    @DisplayName("an uncached world fails closed, treating everyone as a visitor")
    void uncachedWorldsFailClosed() {
        // The safe direction. Assuming a cache miss means "may build" hands a
        // stranger a world the moment a membership read fails.
        assertThat(cache.isCached(worldId)).isFalse();
        assertThat(cache.effectiveRole(worldId, owner)).isEqualTo(Role.VISITOR);
    }

    @Test
    @DisplayName("invalidate drops one world and leaves the others")
    void invalidateIsScoped() {
        fill();
        WorldId other = WorldId.random();
        cache.put(other, owner, Map.of(owner, Role.OWNER));

        cache.invalidate(worldId);

        assertThat(cache.isCached(worldId)).isFalse();
        assertThat(cache.isCached(other)).isTrue();
        assertThat(cache.size()).isEqualTo(1);
    }

    @Test
    @DisplayName("the FR-9 capability matrix")
    void capabilities() {
        assertThat(Role.OWNER.canBuild()).isTrue();
        assertThat(Role.BUILDER.canBuild()).isTrue();
        assertThat(Role.VISITOR.canBuild()).isFalse();

        // Containers are locked to BUILDER and above by default; the per-world
        // FR-9e setting only ever widens it for visitors.
        assertThat(Role.OWNER.canOpenContainers(false)).isTrue();
        assertThat(Role.BUILDER.canOpenContainers(false)).isTrue();
        assertThat(Role.VISITOR.canOpenContainers(false)).isFalse();
        assertThat(Role.VISITOR.canOpenContainers(true)).isTrue();

        assertThat(Role.OWNER.canManage()).isTrue();
        assertThat(Role.BUILDER.canManage()).isFalse();
        assertThat(Role.VISITOR.canManage()).isFalse();
    }
}
