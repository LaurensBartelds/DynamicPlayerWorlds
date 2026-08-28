package nl.gzmn.playerworlds.proxy.permission;

import static org.assertj.core.api.Assertions.assertThat;

import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.permission.Tristate;
import java.lang.reflect.Proxy;
import java.util.Map;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

/**
 * D14 / R5: one semantic for UNDEFINED, applied everywhere.
 */
class WorldPermissionsTest {

    @Test
    void undefinedIsDeniedExceptForDefaultGrantedPermissions_R5() {
        CommandSource unset = subject(permission -> Tristate.UNDEFINED);

        assertThat(WorldPermissions.allows(unset, WorldPermissions.CREATE)).isTrue();
        assertThat(WorldPermissions.allows(unset, WorldPermissions.JOIN)).isTrue();
        assertThat(WorldPermissions.allows(unset, WorldPermissions.PUBLIC)).isFalse();
        assertThat(WorldPermissions.allows(unset, WorldPermissions.ADMIN)).isFalse();
        assertThat(WorldPermissions.allows(unset, WorldPermissions.HARD_DELETE)).isFalse();
    }

    @Test
    void explicitFalseDeniesEvenDefaultGrantedPermissions_R5() {
        CommandSource denied = subject(Map.of(
                WorldPermissions.CREATE, Tristate.FALSE,
                WorldPermissions.JOIN, Tristate.FALSE,
                WorldPermissions.PUBLIC, Tristate.FALSE)::get);

        assertThat(WorldPermissions.allows(denied, WorldPermissions.CREATE)).isFalse();
        assertThat(WorldPermissions.allows(denied, WorldPermissions.JOIN)).isFalse();
        assertThat(WorldPermissions.allows(denied, WorldPermissions.PUBLIC)).isFalse();
    }

    @Test
    void explicitTrueAllowsPublicAndAdmin_FR9h() {
        CommandSource granted = subject(Map.of(
                WorldPermissions.PUBLIC, Tristate.TRUE,
                WorldPermissions.ADMIN, Tristate.TRUE)::get);

        assertThat(WorldPermissions.allows(granted, WorldPermissions.PUBLIC)).isTrue();
        assertThat(WorldPermissions.allows(granted, WorldPermissions.ADMIN)).isTrue();
    }

    @Test
    void defaultGrantedNamesOnlyCreateAndJoin() {
        assertThat(WorldPermissions.defaultGranted(WorldPermissions.CREATE)).isTrue();
        assertThat(WorldPermissions.defaultGranted(WorldPermissions.JOIN)).isTrue();
        assertThat(WorldPermissions.defaultGranted(WorldPermissions.PUBLIC)).isFalse();
        assertThat(WorldPermissions.defaultGranted(WorldPermissions.ADMIN)).isFalse();
        assertThat(WorldPermissions.defaultGranted(WorldPermissions.HARD_DELETE))
                .isFalse();
    }

    private static CommandSource subject(Function<String, Tristate> values) {
        return (CommandSource) Proxy.newProxyInstance(
                CommandSource.class.getClassLoader(), new Class<?>[] {CommandSource.class}, (proxy, method, args) -> {
                    if ("getPermissionValue".equals(method.getName())) {
                        Tristate value = values.apply((String) args[0]);
                        return value != null ? value : Tristate.UNDEFINED;
                    }
                    if ("hasPermission".equals(method.getName())) {
                        Tristate value = values.apply((String) args[0]);
                        return value == Tristate.TRUE;
                    }
                    return null;
                });
    }
}
