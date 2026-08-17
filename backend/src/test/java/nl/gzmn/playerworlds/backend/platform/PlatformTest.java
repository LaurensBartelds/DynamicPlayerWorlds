package nl.gzmn.playerworlds.backend.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class PlatformTest {

    @Test
    @DisplayName("create selects the default layout at the build data version")
    void createAtBuildVersion() {
        Platform platform = Platform.create(new ServerIdentity("26.2", Platform.BUILD_DATA_VERSION));

        assertThat(platform.worldLayout()).isSameAs(DefaultWorldLayout.INSTANCE);
        assertThat(platform.itemCodec()).isSameAs(PaperItemCodec.INSTANCE);
        assertThat(platform.worldRuntime()).isSameAs(PaperWorldRuntime.INSTANCE);
        assertThat(platform.portalRouting()).isSameAs(DefaultPortalRouting.INSTANCE);
        assertThat(platform.unknownNewerVersion()).isFalse();
        assertThat(platform.worldLayout().id()).isEqualTo("default-" + Platform.BUILD_DATA_VERSION);
    }

    @Test
    @DisplayName("unknown newer data version warns via flag and still uses the default layout")
    void unknownNewerUsesDefaultLayout() {
        Platform platform = Platform.create(new ServerIdentity("26.3", Platform.BUILD_DATA_VERSION + 50));

        assertThat(platform.unknownNewerVersion()).isTrue();
        assertThat(platform.worldLayout()).isSameAs(DefaultWorldLayout.INSTANCE);
    }

    @Test
    @DisplayName("data version below the minimum refuses enable (plan section 5.2)")
    void olderThanSupportedRefuses() {
        assertThatThrownBy(() -> Platform.create(new ServerIdentity("1.21.4", Platform.MIN_SUPPORTED_DATA_VERSION - 1)))
                .isInstanceOf(UnsupportedPlatformException.class)
                .hasMessageContaining(String.valueOf(Platform.MIN_SUPPORTED_DATA_VERSION));
    }

    @Test
    @DisplayName("selectLayout returns default for the build version and above")
    void selectLayout() {
        assertThat(Platform.selectLayout(Platform.BUILD_DATA_VERSION)).isSameAs(DefaultWorldLayout.INSTANCE);
        assertThat(Platform.selectLayout(Platform.BUILD_DATA_VERSION + 1)).isSameAs(DefaultWorldLayout.INSTANCE);
    }
}
