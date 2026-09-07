package nl.gzmn.playerworlds.core.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Subscription slot and border tiers (FR-42, FR-43). */
class EntitlementTiersTest {

    @Test
    @DisplayName("the highest tier a player holds is the one that counts_FR43")
    void highestTierWins() {
        List<String> held = List.of("gzmn.worlds.slots.3", "gzmn.worlds.slots.10", "gzmn.worlds.slots.5");
        assertThat(EntitlementTiers.resolveSlots(held, 2)).isEqualTo(10);

        List<String> borders = List.of("gzmn.worlds.border.7500", "gzmn.worlds.border.20000");
        assertThat(EntitlementTiers.resolveBorder(borders, 5000)).isEqualTo(20000);
    }

    @Test
    @DisplayName("the network default is a floor a tier cannot drop a player below_FR42")
    void defaultIsAFloor() {
        // An operator who raises the network default has raised it for subscribers too. A
        // tier below it is not a demotion, it is simply already covered.
        assertThat(EntitlementTiers.resolveSlots(List.of("gzmn.worlds.slots.2"), 5))
                .isEqualTo(5);
        assertThat(EntitlementTiers.resolveBorder(List.of("gzmn.worlds.border.1000"), 5000))
                .isEqualTo(5000);
    }

    @Test
    @DisplayName("a player with no tier at all gets the network default_FR42")
    void noTierGetsDefault() {
        assertThat(EntitlementTiers.resolveSlots(List.of(), 2)).isEqualTo(2);
        assertThat(EntitlementTiers.resolveSlots(List.of("gzmn.worlds.create", "some.other.node"), 2))
                .isEqualTo(2);
        assertThat(EntitlementTiers.resolveBorder(List.of(), 5000)).isEqualTo(5000);
    }

    @Test
    @DisplayName("a node that is not a tier is not read as one")
    void malformedNodesAreIgnored() {
        assertThat(EntitlementTiers.parseSlots("gzmn.worlds.slots.")).isEqualTo(-1);
        assertThat(EntitlementTiers.parseSlots("gzmn.worlds.slots.many")).isEqualTo(-1);
        assertThat(EntitlementTiers.parseSlots("gzmn.worlds.slots.5.extra")).isEqualTo(-1);
        assertThat(EntitlementTiers.parseSlots("gzmn.worlds.storage.5gb")).isEqualTo(-1);
        assertThat(EntitlementTiers.parseSlots("")).isEqualTo(-1);

        // More digits than an int holds is a typo, and honouring it as MAX_VALUE would hand
        // out an unbounded allowance.
        assertThat(EntitlementTiers.parseBorder("gzmn.worlds.border.99999999999999999999"))
                .isEqualTo(-1);
    }

    @Test
    @DisplayName("a probed backend only sees the tiers an operator configured_FR43")
    void probedBackendSeesOnlyConfiguredTiers() {
        Set<String> granted = Set.of("gzmn.worlds.slots.10", "gzmn.worlds.slots.25");

        // Velocity's own permission API cannot list what a player holds, so a tier nobody
        // named in worlds.slot-tiers is invisible: 25 is granted but never asked about.
        assertThat(EntitlementTiers.resolveSlots(granted::contains, List.of("3", "5", "10"), 2))
                .isEqualTo(10);

        // Configure it, and the same player gets it.
        assertThat(EntitlementTiers.resolveSlots(granted::contains, List.of("3", "5", "10", "25"), 2))
                .isEqualTo(25);

        // Enumerating them, as LuckPerms can, needs no configuration at all.
        assertThat(EntitlementTiers.resolveSlots(granted, 2)).isEqualTo(25);
    }

    @Test
    @DisplayName("configured suffixes become the permission nodes they name")
    void candidatePermissionsQualifyTiers() {
        assertThat(EntitlementTiers.candidatePermissions(
                        List.of("3", " 5 ", "", "10"), EntitlementTiers.PERMISSION_SLOTS_PREFIX))
                .containsExactly("gzmn.worlds.slots.3", "gzmn.worlds.slots.5", "gzmn.worlds.slots.10");
    }
}
