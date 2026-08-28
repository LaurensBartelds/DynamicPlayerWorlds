package nl.gzmn.playerworlds.proxy.menu;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import nl.gzmn.playerworlds.core.concurrent.PluginExecutors;
import nl.gzmn.playerworlds.core.config.NetworkPolicy;
import nl.gzmn.playerworlds.core.db.Database;
import nl.gzmn.playerworlds.core.db.MembershipRepository;
import nl.gzmn.playerworlds.core.db.PlayerNameRepository;
import nl.gzmn.playerworlds.core.db.PlayerWorldRepository;
import nl.gzmn.playerworlds.core.db.Schema;
import nl.gzmn.playerworlds.core.db.TransferRequestRepository;
import nl.gzmn.playerworlds.core.db.WorldBanRepository;
import nl.gzmn.playerworlds.core.menu.MenuItemDescriptor;
import nl.gzmn.playerworlds.core.menu.RenderMenuPayload;
import nl.gzmn.playerworlds.core.model.PlayerWorld;
import nl.gzmn.playerworlds.core.model.Role;
import nl.gzmn.playerworlds.core.model.Visibility;
import nl.gzmn.playerworlds.core.model.WorldId;
import nl.gzmn.playerworlds.core.model.WorldState;
import nl.gzmn.playerworlds.proxy.menu.screens.WorldDetailScreenBuilder;
import nl.gzmn.playerworlds.testing.TestDatabase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class MenuViewServiceTest {

    private Database database;
    private PluginExecutors executors;
    private PlayerWorldRepository worldRepo;
    private MembershipRepository memberRepo;
    private TransferRequestRepository transferRepo;
    private WorldBanRepository banRepo;
    private PlayerNameRepository nameRepo;
    private MenuViewService service;

    private UUID playerUuid;
    private String playerName;

    @BeforeEach
    void setUp() throws Exception {
        database = TestDatabase.openFresh();
        Schema.migrate(database);
        executors = PluginExecutors.create(2, 2, Runnable::run);

        worldRepo = new PlayerWorldRepository(database);
        memberRepo = new MembershipRepository(database);
        transferRepo = new TransferRequestRepository(database);
        banRepo = new WorldBanRepository(database);
        nameRepo = new PlayerNameRepository(database);

        service = new MenuViewService(
                worldRepo, memberRepo, transferRepo, banRepo, nameRepo, NetworkPolicy::defaults, executors);

        playerUuid = UUID.randomUUID();
        playerName = "TestPlayer";
        nameRepo.remember(playerUuid, playerName);
    }

    @AfterEach
    void tearDown() {
        executors.shutdown(Duration.ofSeconds(2));
        database.close();
    }

    @Nested
    @DisplayName("Main screen")
    class MainScreenTests {

        @Test
        @DisplayName("builds main menu payload with quota and navigation items")
        void buildsMainScreenPayload() throws Exception {
            PlayerWorld world = worldRepo.create(
                    WorldId.random(),
                    playerUuid,
                    "world1",
                    12345L,
                    500,
                    Visibility.PRIVATE,
                    "node-1",
                    Duration.ofMinutes(10));
            worldRepo.transitionState(world.id(), WorldState.CREATING, WorldState.READY);

            UUID inviterUuid = UUID.randomUUID();
            nameRepo.remember(inviterUuid, "Inviter");
            PlayerWorld otherWorld = worldRepo.create(
                    WorldId.random(),
                    inviterUuid,
                    "other-world",
                    999L,
                    500,
                    Visibility.PRIVATE,
                    "node-1",
                    Duration.ofMinutes(10));
            memberRepo.invite(otherWorld.id(), playerUuid, inviterUuid, Duration.ofHours(1));

            RenderMenuPayload payload = service.buildMainMenu(playerUuid, 1001L).get();

            assertThat(payload.correlationId()).isEqualTo(1001L);
            assertThat(payload.screenType()).isEqualTo("MAIN");
            assertThat(payload.title()).isEqualTo("§8Dynamic Player Worlds");
            assertThat(payload.size()).isEqualTo(27);
            assertThat(payload.items()).hasSize(27);

            MenuItemDescriptor myWorlds = payload.items().get(10);
            assertThat(myWorlds.materialName()).isEqualTo("GRASS_BLOCK");
            assertThat(myWorlds.displayName()).isEqualTo("§a§lMy Worlds");
            assertThat(myWorlds.actionTag()).isEqualTo("NAV:MY_WORLDS");
            assertThat(myWorlds.lore()).contains("§8Owned: 1 / 2");

            MenuItemDescriptor storage = payload.items().get(12);
            assertThat(storage.materialName()).isEqualTo("CHEST");
            assertThat(storage.displayName()).isEqualTo("§b§lStorage Usage");
            assertThat(storage.actionTag()).isEqualTo("NAV:STORAGE");

            MenuItemDescriptor invites = payload.items().get(14);
            assertThat(invites.materialName()).isEqualTo("WRITABLE_BOOK");
            assertThat(invites.displayName()).isEqualTo("§6§lPending Invites");
            assertThat(invites.actionTag()).isEqualTo("NAV:INVITES");
            assertThat(invites.lore()).contains("§7Pending: 1");

            MenuItemDescriptor browse = payload.items().get(16);
            assertThat(browse.materialName()).isEqualTo("COMPASS");
            assertThat(browse.displayName()).isEqualTo("§d§lBrowse Public Worlds");
            assertThat(browse.actionTag()).isEqualTo("NAV:BROWSE");

            MenuItemDescriptor close = payload.items().get(22);
            assertThat(close.materialName()).isEqualTo("BARRIER");
            assertThat(close.displayName()).isEqualTo("§c§lClose Menu");
            assertThat(close.actionTag()).isEqualTo("ACTION:CLOSE");

            // Check filler slot
            MenuItemDescriptor filler = payload.items().get(0);
            assertThat(filler.materialName()).isEqualTo("GRAY_STAINED_GLASS_PANE");
            assertThat(filler.actionTag()).isEmpty();
        }
    }

    @Nested
    @DisplayName("My Worlds screen")
    class MyWorldsScreenTests {

        @Test
        @DisplayName("builds my worlds menu with world items, status colors, and controls")
        void buildsMyWorldsScreen() throws Exception {
            PlayerWorld readyWorld = worldRepo.create(
                    WorldId.random(),
                    playerUuid,
                    "alpha",
                    100L,
                    500,
                    Visibility.PRIVATE,
                    "node-1",
                    Duration.ofMinutes(10));
            worldRepo.transitionState(readyWorld.id(), WorldState.CREATING, WorldState.READY);

            PlayerWorld archivedWorld = worldRepo.create(
                    WorldId.random(),
                    playerUuid,
                    "beta",
                    200L,
                    500,
                    Visibility.PUBLIC,
                    "node-1",
                    Duration.ofMinutes(10));
            worldRepo.transitionState(archivedWorld.id(), WorldState.CREATING, WorldState.READY);
            worldRepo.transitionState(archivedWorld.id(), WorldState.READY, WorldState.ARCHIVED);

            RenderMenuPayload payload =
                    service.buildMyWorldsMenu(playerUuid, 0, 1002L).get();

            assertThat(payload.correlationId()).isEqualTo(1002L);
            assertThat(payload.screenType()).isEqualTo("MY_WORLDS");
            assertThat(payload.size()).isEqualTo(54);
            assertThat(payload.items()).hasSize(54);

            MenuItemDescriptor item0 = payload.items().get(0);
            assertThat(item0.displayName()).contains("beta");
            assertThat(item0.materialName()).isEqualTo("CHEST");
            assertThat(item0.actionTag())
                    .isEqualTo("NAV:WORLD:" + archivedWorld.id().value());

            MenuItemDescriptor item1 = payload.items().get(1);
            assertThat(item1.displayName()).contains("alpha");
            assertThat(item1.materialName()).isEqualTo("GRASS_BLOCK");
            assertThat(item1.actionTag())
                    .isEqualTo("NAV:WORLD:" + readyWorld.id().value());

            // Check navigation items
            MenuItemDescriptor back = payload.items().get(48);
            assertThat(back.materialName()).isEqualTo("OAK_DOOR");
            assertThat(back.actionTag()).isEqualTo("NAV:MAIN");

            MenuItemDescriptor create = payload.items().get(49);
            assertThat(create.materialName()).isEqualTo("NETHER_STAR");
            assertThat(create.actionTag()).isEqualTo("ACTION:CREATE");

            // Divider check
            assertThat(payload.items().get(36).materialName()).isEqualTo("BLACK_STAINED_GLASS_PANE");
        }

        @Test
        @DisplayName("paginates my worlds correctly across multiple pages")
        void paginatesMyWorlds() throws Exception {
            for (int i = 0; i < 40; i++) {
                PlayerWorld w = worldRepo.create(
                        WorldId.random(),
                        playerUuid,
                        "world-" + i,
                        100L + i,
                        500,
                        Visibility.PRIVATE,
                        "node-1",
                        Duration.ofMinutes(10));
                worldRepo.transitionState(w.id(), WorldState.CREATING, WorldState.READY);
            }

            RenderMenuPayload page0 =
                    service.buildMyWorldsMenu(playerUuid, 0, 1003L).get();
            assertThat(page0.title()).isEqualTo("§8My Worlds (Page 1/2)");
            assertThat(page0.items().get(53).materialName()).isEqualTo("ARROW");
            assertThat(page0.items().get(53).actionTag()).isEqualTo("NAV:MY_WORLDS:1");
            assertThat(page0.items().get(45).materialName()).isEqualTo("GRAY_STAINED_GLASS_PANE");

            RenderMenuPayload page1 =
                    service.buildMyWorldsMenu(playerUuid, 1, 1004L).get();
            assertThat(page1.title()).isEqualTo("§8My Worlds (Page 2/2)");
            assertThat(page1.items().get(45).materialName()).isEqualTo("ARROW");
            assertThat(page1.items().get(45).actionTag()).isEqualTo("NAV:MY_WORLDS:0");
            assertThat(page1.items().get(53).materialName()).isEqualTo("GRAY_STAINED_GLASS_PANE");
        }

        @Test
        @DisplayName("lists worlds an accepted invite made reachable, after the owned ones (FR-7)")
        void listsWorldsSharedByInvite() throws Exception {
            PlayerWorld mine = worldRepo.create(
                    WorldId.random(),
                    playerUuid,
                    "mine",
                    100L,
                    500,
                    Visibility.PRIVATE,
                    "node-1",
                    Duration.ofMinutes(10));
            worldRepo.transitionState(mine.id(), WorldState.CREATING, WorldState.READY);

            UUID hostUuid = UUID.randomUUID();
            nameRepo.remember(hostUuid, "Host");
            PlayerWorld theirs = worldRepo.create(
                    WorldId.random(),
                    hostUuid,
                    "theirs",
                    200L,
                    500,
                    Visibility.PRIVATE,
                    "node-1",
                    Duration.ofMinutes(10));
            worldRepo.transitionState(theirs.id(), WorldState.CREATING, WorldState.READY);
            memberRepo.invite(theirs.id(), playerUuid, hostUuid, Duration.ofMinutes(10));
            assertThat(memberRepo.acceptInvite(theirs.id(), playerUuid))
                    .isInstanceOf(MembershipRepository.AcceptOutcome.Accepted.class);

            // A world they were invited to but never accepted stays invisible.
            PlayerWorld unaccepted = worldRepo.create(
                    WorldId.random(),
                    hostUuid,
                    "unaccepted",
                    300L,
                    500,
                    Visibility.PRIVATE,
                    "node-1",
                    Duration.ofMinutes(10));
            memberRepo.invite(unaccepted.id(), playerUuid, hostUuid, Duration.ofMinutes(10));

            RenderMenuPayload payload =
                    service.buildMyWorldsMenu(playerUuid, 0, 1010L).get();

            MenuItemDescriptor owned = payload.items().get(0);
            assertThat(owned.displayName()).contains("mine");
            assertThat(owned.materialName()).isEqualTo("GRASS_BLOCK");

            MenuItemDescriptor invited = payload.items().get(1);
            assertThat(invited.displayName()).contains("theirs");
            assertThat(invited.actionTag()).isEqualTo("NAV:WORLD:" + theirs.id().value());
            assertThat(invited.lore())
                    .as("the member has to be able to tell whose world it is and what they may do there")
                    .anyMatch(line -> line.contains("Shared with you") && line.contains(Role.BUILDER.name()));

            assertThat(payload.items().get(2).materialName())
                    .as("an invite nobody accepted is not membership")
                    .isEqualTo("GRAY_STAINED_GLASS_PANE");

            assertThat(payload.items().get(49).lore())
                    .as("FR-1's cap counts owned worlds, not shared ones")
                    .contains("§7Owned: 1 / 2", "§7Shared with you: 1");
        }
    }

    @Nested
    @DisplayName("World Detail screen")
    class WorldDetailScreenTests {

        @Test
        @DisplayName("builds world detail screen for ready world")
        void buildsWorldDetailScreenForReadyWorld() throws Exception {
            PlayerWorld world = worldRepo.create(
                    WorldId.random(),
                    playerUuid,
                    "survival",
                    12345L,
                    500,
                    Visibility.PRIVATE,
                    "node-1",
                    Duration.ofMinutes(10));
            worldRepo.transitionState(world.id(), WorldState.CREATING, WorldState.READY);

            RenderMenuPayload payload =
                    service.buildWorldMenu(world.id(), 1005L).get();

            assertThat(payload.screenType()).isEqualTo("WORLD_DETAILS");
            assertThat(payload.size()).isEqualTo(27);
            assertThat(payload.title()).isEqualTo("§8Manage: survival");

            MenuItemDescriptor info = payload.items().get(4);
            assertThat(info.materialName()).isEqualTo("BEACON");
            assertThat(info.displayName()).contains("survival");

            MenuItemDescriptor join = payload.items().get(10);
            assertThat(join.materialName()).isEqualTo("ENDER_PEARL");
            assertThat(join.actionTag()).isEqualTo("ACTION:JOIN:" + world.id().value());

            MenuItemDescriptor members = payload.items().get(11);
            assertThat(members.materialName()).isEqualTo("PLAYER_HEAD");
            assertThat(members.actionTag())
                    .isEqualTo("NAV:MEMBERS:" + world.id().value());

            MenuItemDescriptor settings = payload.items().get(12);
            assertThat(settings.materialName()).isEqualTo("COMPARATOR");
            assertThat(settings.actionTag())
                    .isEqualTo("NAV:SETTINGS:" + world.id().value());

            MenuItemDescriptor visibility = payload.items().get(13);
            assertThat(visibility.materialName()).isEqualTo("ENDER_EYE");
            assertThat(visibility.actionTag())
                    .isEqualTo("ACTION:SET_VISIBILITY:" + world.id().value() + ":PUBLIC");

            MenuItemDescriptor bans = payload.items().get(14);
            assertThat(bans.materialName()).isEqualTo("IRON_BARS");
            assertThat(bans.actionTag()).isEqualTo("NAV:BANS:" + world.id().value());

            MenuItemDescriptor storage = payload.items().get(15);
            assertThat(storage.materialName()).isEqualTo("CHEST");
            assertThat(storage.actionTag()).isEqualTo("NAV:STORAGE");

            MenuItemDescriptor archive = payload.items().get(16);
            assertThat(archive.materialName()).isEqualTo("TNT");
            assertThat(archive.actionTag()).isEqualTo("ACTION:ARCHIVE:survival");

            MenuItemDescriptor back = payload.items().get(18);
            assertThat(back.materialName()).isEqualTo("OAK_DOOR");
            assertThat(back.actionTag()).isEqualTo("NAV:MY_WORLDS");
        }

        @Test
        @DisplayName("builds world detail screen for archived world with restore and delete options")
        void buildsWorldDetailScreenForArchivedWorld() throws Exception {
            PlayerWorld world = worldRepo.create(
                    WorldId.random(),
                    playerUuid,
                    "old-world",
                    12345L,
                    500,
                    Visibility.PUBLIC,
                    "node-1",
                    Duration.ofMinutes(10));
            worldRepo.transitionState(world.id(), WorldState.CREATING, WorldState.READY);
            worldRepo.transitionState(world.id(), WorldState.READY, WorldState.ARCHIVED);

            RenderMenuPayload payload =
                    service.buildWorldMenu(world.id(), 1006L).get();

            MenuItemDescriptor restore = payload.items().get(10);
            assertThat(restore.materialName()).isEqualTo("ANVIL");
            assertThat(restore.actionTag()).isEqualTo("ACTION:RESTORE:old-world");

            MenuItemDescriptor delete = payload.items().get(16);
            assertThat(delete.materialName()).isEqualTo("LAVA_BUCKET");
            assertThat(delete.actionTag()).isEqualTo("ACTION:ARCHIVE:old-world");
        }
    }

    @Nested
    @DisplayName("World Detail screen for a member who is not the owner")
    class WorldDetailForMemberTests {

        @Test
        @DisplayName("a non-owner sees the world without the controls only its owner may use (FR-31a)")
        void memberSeesNoManagementControls() throws Exception {
            UUID hostUuid = UUID.randomUUID();
            PlayerWorld world = worldRepo.create(
                    WorldId.random(),
                    hostUuid,
                    "theirs",
                    12345L,
                    500,
                    Visibility.PRIVATE,
                    "node-1",
                    Duration.ofMinutes(10));
            worldRepo.transitionState(world.id(), WorldState.CREATING, WorldState.READY);

            RenderMenuPayload asMember =
                    service.buildWorldMenu(world.id(), playerUuid, 1020L).get();

            assertThat(asMember.title()).isEqualTo("§8World: theirs");
            assertThat(asMember.items().get(WorldDetailScreenBuilder.SLOT_JOIN).actionTag())
                    .as("a member is here to go there")
                    .isEqualTo("ACTION:JOIN:" + world.id().value());
            assertThat(asMember.items().stream()
                            .map(MenuItemDescriptor::actionTag)
                            .filter(tag -> tag.startsWith("ACTION:ARCHIVE:")))
                    .as("ACTION:ARCHIVE names a world by name and resolves it against the caller's own worlds; "
                            + "offering it to a visitor offers a button that hits the wrong world")
                    .isEmpty();
            assertThat(asMember.items()
                            .get(WorldDetailScreenBuilder.SLOT_SETTINGS)
                            .actionTag())
                    .isEmpty();
            assertThat(asMember.items()
                            .get(WorldDetailScreenBuilder.SLOT_VISIBILITY)
                            .actionTag())
                    .isEmpty();

            RenderMenuPayload asOwner =
                    service.buildWorldMenu(world.id(), hostUuid, 1021L).get();
            assertThat(asOwner.title()).isEqualTo("§8Manage: theirs");
            assertThat(asOwner.items()
                            .get(WorldDetailScreenBuilder.SLOT_ARCHIVE)
                            .actionTag())
                    .isEqualTo("ACTION:ARCHIVE:theirs");
        }
    }

    @Nested
    @DisplayName("Settings screen")
    class SettingsScreenTests {

        @Test
        @DisplayName("builds settings screen with toggleable options")
        void buildsSettingsScreen() throws Exception {
            PlayerWorld world = worldRepo.create(
                    WorldId.random(),
                    playerUuid,
                    "custom-settings",
                    12345L,
                    500,
                    Visibility.PRIVATE,
                    "node-1",
                    Duration.ofMinutes(10));
            worldRepo.transitionState(world.id(), WorldState.CREATING, WorldState.READY);

            RenderMenuPayload payload =
                    service.buildSettingsMenu(world.id(), 1007L).get();

            assertThat(payload.screenType()).isEqualTo("SETTINGS");
            assertThat(payload.size()).isEqualTo(27);
            assertThat(payload.title()).isEqualTo("§8Settings: custom-settings");

            MenuItemDescriptor pvp = payload.items().get(10);
            assertThat(pvp.materialName()).isEqualTo("DIAMOND_SWORD");
            assertThat(pvp.displayName()).contains("Enabled");
            assertThat(pvp.actionTag())
                    .as("PVP defaults on (FR-9e), so the toggle a fresh world offers is the one that turns it off")
                    .isEqualTo("ACTION:SET_SETTING:" + world.id().value() + ":pvp:false");

            MenuItemDescriptor containers = payload.items().get(12);
            assertThat(containers.materialName()).isEqualTo("CHEST");
            assertThat(containers.actionTag())
                    .isEqualTo("ACTION:SET_SETTING:" + world.id().value() + ":containers:true");

            MenuItemDescriptor interact = payload.items().get(14);
            assertThat(interact.materialName()).isEqualTo("LEVER");
            assertThat(interact.actionTag())
                    .isEqualTo("ACTION:SET_SETTING:" + world.id().value() + ":interact:false");

            MenuItemDescriptor mobGriefing = payload.items().get(16);
            assertThat(mobGriefing.materialName()).isEqualTo("CREEPER_HEAD");
            assertThat(mobGriefing.actionTag())
                    .isEqualTo("ACTION:SET_SETTING:" + world.id().value() + ":mob-griefing:false");

            MenuItemDescriptor back = payload.items().get(22);
            assertThat(back.materialName()).isEqualTo("OAK_DOOR");
            assertThat(back.actionTag()).isEqualTo("NAV:WORLD:" + world.id().value());
        }
    }

    @Nested
    @DisplayName("Members screen")
    class MembersScreenTests {

        @Test
        @DisplayName("builds members screen with player heads and action tags")
        void buildsMembersScreen() throws Exception {
            PlayerWorld world = worldRepo.create(
                    WorldId.random(),
                    playerUuid,
                    "members-world",
                    12345L,
                    500,
                    Visibility.PRIVATE,
                    "node-1",
                    Duration.ofMinutes(10));
            worldRepo.transitionState(world.id(), WorldState.CREATING, WorldState.READY);

            UUID builderUuid = UUID.randomUUID();
            nameRepo.remember(builderUuid, "BuilderBob");

            database.inTransaction(conn -> {
                memberRepo.insertMember(conn, world.id(), builderUuid, Role.BUILDER, playerUuid);
                return null;
            });

            RenderMenuPayload payload =
                    service.buildMembersMenu(world.id(), 0, 1008L).get();

            assertThat(payload.screenType()).isEqualTo("MEMBERS");
            assertThat(payload.size()).isEqualTo(54);

            MenuItemDescriptor ownerItem = payload.items().get(0);
            assertThat(ownerItem.materialName()).isEqualTo("PLAYER_HEAD");
            assertThat(ownerItem.displayName()).contains("TestPlayer");
            assertThat(ownerItem.skullOwner()).isEqualTo(playerUuid);
            assertThat(ownerItem.actionTag()).isEmpty();

            MenuItemDescriptor builderItem = payload.items().get(1);
            assertThat(builderItem.materialName()).isEqualTo("PLAYER_HEAD");
            assertThat(builderItem.displayName()).contains("BuilderBob");
            assertThat(builderItem.skullOwner()).isEqualTo(builderUuid);
            assertThat(builderItem.actionTag())
                    .isEqualTo("ACTION:PROMOTE:" + world.id().value() + ":BuilderBob");

            MenuItemDescriptor back = payload.items().get(48);
            assertThat(back.actionTag()).isEqualTo("NAV:WORLD:" + world.id().value());
        }
    }

    @Nested
    @DisplayName("Storage screen")
    class StorageScreenTests {

        @Test
        @DisplayName("builds storage breakdown screen with allowance bar and owned worlds")
        void buildsStorageScreen() throws Exception {
            PlayerWorld world = worldRepo.create(
                    WorldId.random(),
                    playerUuid,
                    "stored-world",
                    12345L,
                    500,
                    Visibility.PRIVATE,
                    "node-1",
                    Duration.ofMinutes(10));
            worldRepo.transitionState(world.id(), WorldState.CREATING, WorldState.READY);

            RenderMenuPayload payload =
                    service.buildStorageMenu(playerUuid, 1009L).get();

            assertThat(payload.screenType()).isEqualTo("STORAGE");
            assertThat(payload.size()).isEqualTo(36);

            MenuItemDescriptor overview = payload.items().get(4);
            assertThat(overview.materialName()).isEqualTo("ENDER_CHEST");
            assertThat(overview.displayName()).isEqualTo("§6§lStorage Allowance");

            MenuItemDescriptor worldItem = payload.items().get(9);
            assertThat(worldItem.displayName()).contains("stored-world");
            assertThat(worldItem.actionTag())
                    .isEqualTo("NAV:WORLD:" + world.id().value());

            MenuItemDescriptor back = payload.items().get(31);
            assertThat(back.actionTag()).isEqualTo("NAV:MAIN");
        }
    }

    @Nested
    @DisplayName("Invites screen")
    class InvitesScreenTests {

        @Test
        @DisplayName("builds empty invites screen when no invites exist")
        void buildsEmptyInvitesScreen() throws Exception {
            RenderMenuPayload payload =
                    service.buildInvitesMenu(playerUuid, 0, 1010L).get();

            assertThat(payload.screenType()).isEqualTo("INVITES");
            assertThat(payload.size()).isEqualTo(54);

            MenuItemDescriptor empty = payload.items().get(22);
            assertThat(empty.materialName()).isEqualTo("WRITABLE_BOOK");
            assertThat(empty.displayName()).isEqualTo("§6§lNo Pending Invites");
        }

        @Test
        @DisplayName("builds invites screen with pending invites and transfer requests")
        void buildsInvitesScreenWithEntries() throws Exception {
            UUID inviterUuid = UUID.randomUUID();
            nameRepo.remember(inviterUuid, "InviterPlayer");

            PlayerWorld world1 = worldRepo.create(
                    WorldId.random(),
                    inviterUuid,
                    "shared-world",
                    100L,
                    500,
                    Visibility.PRIVATE,
                    "node-1",
                    Duration.ofMinutes(10));
            worldRepo.transitionState(world1.id(), WorldState.CREATING, WorldState.READY);
            memberRepo.invite(world1.id(), playerUuid, inviterUuid, Duration.ofHours(1));

            PlayerWorld world2 = worldRepo.create(
                    WorldId.random(),
                    inviterUuid,
                    "transfer-world",
                    200L,
                    500,
                    Visibility.PRIVATE,
                    "node-1",
                    Duration.ofMinutes(10));
            worldRepo.transitionState(world2.id(), WorldState.CREATING, WorldState.READY);
            transferRepo.requestTransfer(world2.id(), playerUuid, inviterUuid, Duration.ofHours(1));

            RenderMenuPayload payload =
                    service.buildInvitesMenu(playerUuid, 0, 1011L).get();

            assertThat(payload.items().get(0).materialName()).isEqualTo("WRITABLE_BOOK");
            assertThat(payload.items().get(0).actionTag()).isEqualTo("ACTION:ACCEPT_INVITE:InviterPlayer");

            assertThat(payload.items().get(1).materialName()).isEqualTo("NETHER_STAR");
            assertThat(payload.items().get(1).actionTag()).isEqualTo("ACTION:ACCEPT_TRANSFER:InviterPlayer");
        }
    }

    @Nested
    @DisplayName("Bans screen")
    class BansScreenTests {

        @Test
        @DisplayName("builds empty bans screen when no players are banned")
        void buildsEmptyBansScreen() throws Exception {
            PlayerWorld world = worldRepo.create(
                    WorldId.random(),
                    playerUuid,
                    "peaceful-world",
                    12345L,
                    500,
                    Visibility.PRIVATE,
                    "node-1",
                    Duration.ofMinutes(10));
            worldRepo.transitionState(world.id(), WorldState.CREATING, WorldState.READY);

            RenderMenuPayload payload =
                    service.buildBansMenu(world.id(), 0, 1012L).get();

            assertThat(payload.screenType()).isEqualTo("BANS");
            assertThat(payload.size()).isEqualTo(54);

            MenuItemDescriptor empty = payload.items().get(22);
            assertThat(empty.materialName()).isEqualTo("IRON_BARS");
            assertThat(empty.displayName()).isEqualTo("§a§lNo Banned Players");
        }

        @Test
        @DisplayName("builds bans screen with banned player skull and unban tag")
        void buildsBansScreenWithEntries() throws Exception {
            PlayerWorld world = worldRepo.create(
                    WorldId.random(),
                    playerUuid,
                    "strict-world",
                    12345L,
                    500,
                    Visibility.PRIVATE,
                    "node-1",
                    Duration.ofMinutes(10));
            worldRepo.transitionState(world.id(), WorldState.CREATING, WorldState.READY);

            UUID bannedUuid = UUID.randomUUID();
            nameRepo.remember(bannedUuid, "GrieferGreg");
            banRepo.ban(world.id(), bannedUuid, playerUuid, "Griefing");

            RenderMenuPayload payload =
                    service.buildBansMenu(world.id(), 0, 1013L).get();

            MenuItemDescriptor banItem = payload.items().get(0);
            assertThat(banItem.materialName()).isEqualTo("PLAYER_HEAD");
            assertThat(banItem.displayName()).contains("GrieferGreg");
            assertThat(banItem.skullOwner()).isEqualTo(bannedUuid);
            assertThat(banItem.actionTag())
                    .isEqualTo("ACTION:UNBAN:" + world.id().value() + ":GrieferGreg");
        }
    }

    @Nested
    @DisplayName("Browse screen")
    class BrowseScreenTests {

        @Test
        @DisplayName("builds browse screen with public worlds list")
        void buildsBrowseScreen() throws Exception {
            UUID creator = UUID.randomUUID();
            nameRepo.remember(creator, "CommunityCreator");

            PlayerWorld publicWorld = worldRepo.create(
                    WorldId.random(),
                    creator,
                    "hub-world",
                    12345L,
                    500,
                    Visibility.PUBLIC,
                    "node-1",
                    Duration.ofMinutes(10));
            worldRepo.transitionState(publicWorld.id(), WorldState.CREATING, WorldState.READY);
            worldRepo.updateVisibility(publicWorld.id(), Visibility.PUBLIC, "Welcome to our hub!");

            RenderMenuPayload payload = service.buildBrowseMenu(0, 1014L).get();

            assertThat(payload.screenType()).isEqualTo("BROWSE");
            assertThat(payload.size()).isEqualTo(54);

            MenuItemDescriptor worldItem = payload.items().get(0);
            assertThat(worldItem.materialName()).isEqualTo("GRASS_BLOCK");
            assertThat(worldItem.displayName()).contains("hub-world");
            assertThat(worldItem.actionTag())
                    .isEqualTo("ACTION:JOIN:" + publicWorld.id().value());
            assertThat(worldItem.lore()).contains("§7Welcome to our hub!");
        }
    }

    @Nested
    @DisplayName("Confirm screen")
    class ConfirmScreenTests {

        @Test
        @DisplayName("builds confirm modal payload with confirm and cancel buttons")
        void buildsConfirmScreen() {
            RenderMenuPayload payload = service.buildConfirmMenu(
                    "§4§lArchive World?",
                    "§7This will archive the world.",
                    "ACTION:CONFIRM:ARCHIVE:demo",
                    "NAV:WORLD:demo-id",
                    1015L);

            assertThat(payload.screenType()).isEqualTo("CONFIRM");
            assertThat(payload.size()).isEqualTo(27);

            MenuItemDescriptor info = payload.items().get(4);
            assertThat(info.materialName()).isEqualTo("PAPER");
            assertThat(info.displayName()).isEqualTo("§4§lArchive World?");

            MenuItemDescriptor confirm = payload.items().get(11);
            assertThat(confirm.materialName()).isEqualTo("LIME_CONCRETE");
            assertThat(confirm.actionTag()).isEqualTo("ACTION:CONFIRM:ARCHIVE:demo");

            MenuItemDescriptor cancel = payload.items().get(15);
            assertThat(cancel.materialName()).isEqualTo("RED_CONCRETE");
            assertThat(cancel.actionTag()).isEqualTo("NAV:WORLD:demo-id");
        }
    }

    @Nested
    @DisplayName("Error handling")
    class ErrorHandlingTests {

        @Test
        @DisplayName("fails future when world does not exist")
        void failsWhenWorldNotFound() {
            WorldId missing = WorldId.random();
            assertThatThrownBy(() -> service.buildWorldMenu(missing, 1016L).get())
                    .isInstanceOf(ExecutionException.class)
                    .hasCauseInstanceOf(IllegalArgumentException.class);
        }
    }
}
