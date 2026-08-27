package nl.gzmn.playerworlds.core.config.messages;

import java.util.List;
import java.util.Set;

/**
 * {@code /world <subcommand>} chat replies — {@code WorldActions}' {@code info}/{@code
 * success}/{@code error} builders and {@code WorldCommand}'s own direct replies (usage lines,
 * {@code /world admin list}'s node status line, etc).
 *
 * <p>One key per distinct message, except a {@code generic.*} handful reused verbatim across
 * several subcommands (a player-not-found refusal, a permission denial, a world-gone refusal) —
 * consolidated so an admin edits the wording once rather than N times out of step with each
 * other. Grouped by subcommand with a comment banner, in the order the subcommands appear in
 * {@code WorldCommand.SUBCOMMANDS}/{@code ADMIN_SUBCOMMANDS}, so a reviewer can check this file
 * against {@code WorldActions.java} section by section.
 */
public final class CommandMessages {

    private CommandMessages() {}

    public static final List<MessageKey> ENTRIES = List.of(
            // --- shared -----------------------------------------------------
            MessageKey.of(
                    "messages.command.generic-failure",
                    "<red>that did not work; the failure is in the proxy log</red>"),
            MessageKey.of("messages.command.usage", "<yellow>/world <<subcommands>></yellow>", Set.of("subcommands")),
            MessageKey.of(
                    "messages.command.generic.player-not-found",
                    "<red>no player called '<player>' has been seen on this network</red>",
                    Set.of("player")),
            MessageKey.of("messages.command.generic.unroutable", "<red>that server is not routable right now</red>"),
            MessageKey.of(
                    "messages.command.generic.owns-no-world-named",
                    "<red>you own no world called '<world>'</red>",
                    Set.of("world")),
            MessageKey.of(
                    "messages.command.generic.not-member",
                    "<red><target> is not a member of '<world>'</red>",
                    Set.of("target", "world")),
            MessageKey.of("messages.command.generic.world-gone", "<red>that world no longer exists</red>"),
            MessageKey.of("messages.command.generic.not-owner", "<red>you do not own '<world>'</red>", Set.of("world")),
            MessageKey.of("messages.command.generic.no-world-owned", "<red>you do not own a world yet</red>"),
            MessageKey.of(
                    "messages.command.generic.ambiguous-world-with-usage",
                    "<red>you own <count> worlds (<names>) and are not standing in one; say which: <usage></red>",
                    Set.of("count", "names", "usage")),
            MessageKey.of(
                    "messages.command.generic.ambiguous-world-free-text",
                    "<red>you own <count> worlds (<names>) and are not standing in one; this command ends in free "
                            + "text so it cannot also take a world name -- run it inside the world, or use /world menu</red>",
                    Set.of("count", "names")),
            MessageKey.of(
                    "messages.command.generic.permission-denied",
                    "<red>you do not have permission to do that (<permission>)</red>",
                    Set.of("permission")),
            MessageKey.of(
                    "messages.command.generic.quota-exceeded",
                    "<red>you cannot <attempted>: that would use <used> of your <limit> storage allowance</red>",
                    Set.of("attempted", "used", "limit")),
            MessageKey.of(
                    "messages.command.generic.quota-hint",
                    "<gray>/world storage shows where it has gone; archiving a world does not free it, deleting does</gray>"),
            MessageKey.of("messages.command.generic.quota-attempted-create", "create another world"),
            MessageKey.of("messages.command.generic.quota-attempted-restore", "restore '<world>'", Set.of("world")),
            MessageKey.of("messages.command.generic.quota-attempted-accept", "accept '<world>'", Set.of("world")),
            MessageKey.of(
                    "messages.command.generic.version-too-new",
                    "<red>that world was saved by a newer Minecraft version than any server currently running. "
                            + "It is safe, and it will be reachable again when one is back.</red>"),
            MessageKey.of(
                    "messages.command.generic.no-capacity",
                    "<red>every server is full right now; please try again in a few minutes</red>"),
            MessageKey.of("messages.command.generic.no-nodes-alive", "<red>no server is available right now</red>"),
            MessageKey.of(
                    "messages.command.generic.command-refused",
                    "<red><what> did not happen: <detail></red>",
                    Set.of("what", "detail")),
            MessageKey.of(
                    "messages.command.generic.command-lost",
                    "<red><what> did not happen; the instruction was lost</red>",
                    Set.of("what")),
            MessageKey.of(
                    "messages.command.generic.no-pending-transfer",
                    "<red>you have no pending transfer requests from <owner></red>",
                    Set.of("owner")),
            MessageKey.of("messages.command.delete.archiving-what", "archiving '<world>'", Set.of("world")),
            MessageKey.of(
                    "messages.command.delete-hard.deleting-what", "permanently deleting '<world>'", Set.of("world")),
            MessageKey.of("messages.command.restore.restoring-what", "restoring '<world>'", Set.of("world")),

            // --- create (FR-1, FR-1a) ----------------------------------------
            MessageKey.of(
                    "messages.command.create.cap-reached",
                    "<red>you already own <owned> worlds (limit <max>)</red>",
                    Set.of("owned", "max")),
            MessageKey.of(
                    "messages.command.create.already-exists",
                    "<red>you already own a world called '<world>'</red>",
                    Set.of("world")),
            MessageKey.of(
                    "messages.command.create.started",
                    "<gray>creating '<world>' on <node>; this may take a few seconds...</gray>",
                    Set.of("world", "node")),

            // --- delete (FR-27, FR-35) ----------------------------------------
            MessageKey.of(
                    "messages.command.delete.already-archived",
                    "<gray>'<world>' is already archived; use /world restore <world> to bring it back</gray>",
                    Set.of("world")),
            MessageKey.of(
                    "messages.command.delete.wrong-state",
                    "<red>'<world>' is <state> and cannot be deleted right now</red>",
                    Set.of("world", "state")),
            MessageKey.of(
                    "messages.command.delete.confirm-creating",
                    "<red>'<world>' was never completed. This removes the incomplete world and frees your slot.</red>",
                    Set.of("world")),
            MessageKey.of(
                    "messages.command.delete.confirm-archive",
                    "<red>this archives '<world>' and frees a world slot.</red>",
                    Set.of("world")),
            MessageKey.of(
                    "messages.command.delete.confirm-hint",
                    "<gray>type /world delete <world> confirm to go ahead</gray>",
                    Set.of("world")),
            MessageKey.of(
                    "messages.command.delete.changed-while-confirming",
                    "<red>'<world>' changed while you were confirming; try again</red>",
                    Set.of("world")),
            MessageKey.of(
                    "messages.command.delete.incomplete-removed",
                    "<green>removed incomplete world '<world>'; you have a world slot free</green>",
                    Set.of("world")),
            MessageKey.of(
                    "messages.command.delete.archiving",
                    "<gray>archiving '<world>' on <node>; this may take a few minutes for a large world</gray>",
                    Set.of("world", "node")),
            MessageKey.of(
                    "messages.command.delete.archiving-hint",
                    "<gray>nothing is erased - the world is packed to cold storage and /world restore <world> brings it back</gray>",
                    Set.of("world")),

            // --- delete hard (FR-37) -------------------------------------------
            MessageKey.of("messages.command.delete-hard.not-found", "<red>world not found</red>"),
            MessageKey.of("messages.command.delete-hard.not-owner", "<red>you are not the owner of this world</red>"),
            MessageKey.of(
                    "messages.command.delete-hard.no-permission",
                    "<red>you do not have permission to permanently delete worlds</red>"),
            MessageKey.of(
                    "messages.command.delete-hard.wrong-state",
                    "<red>'<world>' is <state> and cannot be permanently deleted right now</red>",
                    Set.of("world", "state")),
            MessageKey.of(
                    "messages.command.delete-hard.confirm-never-archived",
                    "<red>'<world>' has never been archived, so there is no backup. This destroys the world itself. This cannot be undone.</red>",
                    Set.of("world")),
            MessageKey.of(
                    "messages.command.delete-hard.confirm-archived",
                    "<red>this permanently destroys '<world>' and all backup archives. This cannot be undone.</red>",
                    Set.of("world")),
            MessageKey.of(
                    "messages.command.delete-hard.confirm-hint",
                    "<gray>type /world delete <world> hard confirm to permanently delete</gray>",
                    Set.of("world")),
            MessageKey.of(
                    "messages.command.delete-hard.queued",
                    "<gray>permanently deleting '<world>' <suffix> <node></gray>",
                    Set.of("world", "suffix", "node")),

            // --- restore (FR-36) ------------------------------------------------
            MessageKey.of(
                    "messages.command.restore.not-archived",
                    "<red>'<world>' is <state> and does not need restoring</red>",
                    Set.of("world", "state")),
            MessageKey.of(
                    "messages.command.restore.cap-reached",
                    "<red>you already own <owned> worlds (limit <max>); archive one before restoring this</red>",
                    Set.of("owned", "max")),
            MessageKey.of(
                    "messages.command.restore.started",
                    "<gray>restoring '<world>' on <node>; this may take a few minutes</gray>",
                    Set.of("world", "node")),

            // --- join (FR-10) ----------------------------------------------------
            MessageKey.of("messages.command.join.not-found", "<red>no world you can join matches that</red>"),
            MessageKey.of(
                    "messages.command.join.banned",
                    "<red>you are banned from '<world>'<reason></red>",
                    Set.of("world", "reason")),
            MessageKey.of(
                    "messages.command.join.lease-conflict",
                    "<red>that world is being opened elsewhere right now; try again in a moment</red>"),
            MessageKey.of("messages.command.join.started", "<gray>sending you to '<world>'...</gray>", Set.of("world")),
            MessageKey.of(
                    "messages.command.join.already-here", "<gray>you are already in '<world>'</gray>", Set.of("world")),

            // --- invite (FR-6) ----------------------------------------------------
            MessageKey.of(
                    "messages.command.invite.already-owner", "<red>you are already the owner of that world</red>"),
            MessageKey.of(
                    "messages.command.invite.already-member",
                    "<red><target> is already a member of '<world>'</red>",
                    Set.of("target", "world")),
            MessageKey.of(
                    "messages.command.invite.sent",
                    "<green>invited <target> to '<world>'; the invite expires in <minutes> minutes</green>",
                    Set.of("target", "world", "minutes")),

            // --- accept (FR-7) ----------------------------------------------------
            MessageKey.of(
                    "messages.command.accept.success",
                    "<green>you are now a <role> of '<world>'</green>",
                    Set.of("role", "world")),
            MessageKey.of(
                    "messages.command.accept.go-hint",
                    "<gray>open /worlds and pick '<world>' to go there</gray>",
                    Set.of("world")),
            MessageKey.of(
                    "messages.command.accept.already-member",
                    "<gray>you were already a <role> of '<world>'</gray>",
                    Set.of("role", "world")),
            MessageKey.of(
                    "messages.command.accept.no-invite",
                    "<red>you have no live invite from <owner></red>",
                    Set.of("owner")),

            // --- kick (FR-8) ----------------------------------------------------
            MessageKey.of(
                    "messages.command.kick.self",
                    "<red>you cannot kick yourself from your own world; use /world transfer or /world delete</red>"),
            MessageKey.of(
                    "messages.command.kick.success",
                    "<green>removed <target> from '<world>'; if they are in it now, they are on their way to lobby</green>",
                    Set.of("target", "world")),

            // --- promote (FR-9c) ----------------------------------------------------
            MessageKey.of(
                    "messages.command.promote.success",
                    "<green><target> is now a BUILDER of '<world>'</green>",
                    Set.of("target", "world")),
            MessageKey.of(
                    "messages.command.promote.not-member",
                    "<red><target> is not a member of '<world>', or is its owner</red>",
                    Set.of("target", "world")),

            // --- transfer (FR-29, FR-30, FR-31, FR-32) --------------------------------
            MessageKey.of("messages.command.transfer.self", "<red>you already own this world</red>"),
            MessageKey.of(
                    "messages.command.transfer.cap-reached",
                    "<red><target> has reached their world limit (<max>)</red>",
                    Set.of("target", "max")),
            MessageKey.of(
                    "messages.command.transfer.confirm",
                    "<gray>Are you sure you want to transfer ownership of '<world>' to <target>? You will become "
                            + "a BUILDER. Type /world transfer <target> confirm to proceed.</gray>",
                    Set.of("world", "target")),
            MessageKey.of(
                    "messages.command.transfer.failed",
                    "<red>could not transfer ownership of '<world>'</red>",
                    Set.of("world")),
            MessageKey.of(
                    "messages.command.transfer.success-online",
                    "<green>transferred ownership of '<world>' to <target>; you are now a BUILDER</green>",
                    Set.of("world", "target")),
            MessageKey.of(
                    "messages.command.transfer.new-owner-notice",
                    "<green>You are now the owner of '<world>'!</green>",
                    Set.of("world")),
            MessageKey.of(
                    "messages.command.transfer.request-created",
                    "<green>created transfer request for <target>; they can accept it next time they log in</green>",
                    Set.of("target")),

            // --- transfer accept (FR-32) ----------------------------------------
            MessageKey.of(
                    "messages.command.transfer-accept.cap-reached",
                    "<red>you have reached your world limit (<max>)</red>",
                    Set.of("max")),
            MessageKey.of(
                    "messages.command.transfer-accept.owner-changed",
                    "<red><owner> is no longer the owner of '<world>'</red>",
                    Set.of("owner", "world")),
            MessageKey.of(
                    "messages.command.transfer-accept.failed",
                    "<red>could not accept transfer of '<world>'</red>",
                    Set.of("world")),
            MessageKey.of(
                    "messages.command.transfer-accept.success",
                    "<green>you are now the owner of '<world>'!</green>",
                    Set.of("world")),
            MessageKey.of(
                    "messages.command.transfer-accept.old-owner-notice",
                    "<green><accepter> accepted ownership transfer of '<world>'!</green>",
                    Set.of("accepter", "world")),

            // --- transfer decline (FR-32) ----------------------------------------
            MessageKey.of(
                    "messages.command.transfer-decline.success",
                    "<green>declined transfer request from <owner></green>",
                    Set.of("owner")),
            MessageKey.of(
                    "messages.command.transfer-decline.notice",
                    "<yellow><decliner> declined ownership transfer of your world</yellow>",
                    Set.of("decliner")),

            // --- public (FR-9a, FR-9f, FR-9h) ----------------------------------------------------
            MessageKey.of(
                    "messages.command.set-public.failed", "<red>could not update world visibility; try again</red>"),
            MessageKey.of(
                    "messages.command.set-public.now-public",
                    "<green>'<world>' is now PUBLIC; strangers can now browse and join as visitors</green>",
                    Set.of("world")),
            MessageKey.of(
                    "messages.command.set-public.now-public-with-description",
                    "<green>'<world>' is now PUBLIC (\"<description>\"); strangers can now browse and join as visitors</green>",
                    Set.of("world", "description")),
            MessageKey.of(
                    "messages.command.set-public.now-private",
                    "<green>'<world>' is now PRIVATE; existing members are still members</green>",
                    Set.of("world")),

            // --- set (FR-9e, FR-9i) ----------------------------------------------------
            MessageKey.of(
                    "messages.command.set-setting.unknown",
                    "<red>unknown setting '<setting>'; valid settings: pvp, containers, interact, mob-griefing, "
                            + "keep-inventory, fall-damage, fire-damage, freeze-damage, drowning-damage, "
                            + "daylight-cycle, weather-cycle, insomnia, immediate-respawn, natural-regeneration, "
                            + "sleep-percentage, entity-cramming, respawn-radius, snow-height</red>",
                    Set.of("setting")),
            MessageKey.of(
                    "messages.command.set-setting.failed", "<red>could not update world settings; try again</red>"),
            MessageKey.of(
                    "messages.command.set-setting.success",
                    "<green>set <setting> = <value> for '<world>'</green>",
                    Set.of("setting", "value", "world")),
            MessageKey.of(
                    "messages.command.set-setting.invalid-int",
                    "<red>'<setting>' must be a whole number <range></red>",
                    Set.of("setting", "range")),

            // --- settings (FR-9e, FR-9i) ----------------------------------------------------
            MessageKey.of(
                    "messages.command.settings.header",
                    "<dark_gray>┌─ <gray>Settings: <world></gray> ─────────────┐</dark_gray>",
                    Set.of("world")),
            MessageKey.of(
                    "messages.command.settings.row",
                    "<dark_gray>│</dark_gray> <gray><label>: <value></gray>",
                    Set.of("label", "value")),
            MessageKey.of(
                    "messages.command.settings.footer", "<dark_gray>└───────────────────────────────┘</dark_gray>"),

            // --- ban / unban / bans (FR-9d) ----------------------------------------------------
            MessageKey.of("messages.command.ban.self", "<red>you cannot ban yourself from your own world</red>"),
            MessageKey.of(
                    "messages.command.ban.success",
                    "<green>banned <target> from '<world>'</green>",
                    Set.of("target", "world")),
            MessageKey.of(
                    "messages.command.unban.success",
                    "<green>unbanned <target> from '<world>'</green>",
                    Set.of("target", "world")),
            MessageKey.of(
                    "messages.command.unban.not-banned",
                    "<red><target> was not banned from '<world>'</red>",
                    Set.of("target", "world")),
            MessageKey.of(
                    "messages.command.bans.empty",
                    "<gray>No players are currently banned from '<world>'.</gray>",
                    Set.of("world")),
            MessageKey.of(
                    "messages.command.bans.header",
                    "<dark_gray>┌─ <gray>Bans: <world></gray> ─────────────┐</dark_gray>",
                    Set.of("world")),
            MessageKey.of(
                    "messages.command.bans.entry",
                    "<dark_gray>│</dark_gray> <gray><player><reason></gray>",
                    Set.of("player", "reason")),
            MessageKey.of("messages.command.bans.footer", "<dark_gray>└───────────────────────────────┘</dark_gray>"),

            // --- members (FR-8) ----------------------------------------------------
            MessageKey.of(
                    "messages.command.members.header",
                    "<dark_gray>┌─ <gray>Members: <world></gray> ─────────────┐</dark_gray>",
                    Set.of("world")),
            MessageKey.of(
                    "messages.command.members.entry",
                    "<dark_gray>│</dark_gray> <gray><player>  <role></gray>",
                    Set.of("player", "role")),
            MessageKey.of(
                    "messages.command.members.footer", "<dark_gray>└───────────────────────────────┘</dark_gray>"),

            // --- list ----------------------------------------------------
            MessageKey.of(
                    "messages.command.list.no-worlds",
                    "<gray>You do not own or belong to any worlds yet. Use /world create \\<name\\> to create one.</gray>"),
            MessageKey.of(
                    "messages.command.list.header",
                    "<dark_gray>┌─ <gray>Your Worlds</gray> ─────────────────┐</dark_gray>"),
            MessageKey.of(
                    "messages.command.list.owned-entry",
                    "<dark_gray>│</dark_gray> <green>●</green> <white><world></white> <dark_gray>[<state>]</dark_gray> <gray>(visibility: <visibility>)</gray>",
                    Set.of("world", "state", "visibility")),
            MessageKey.of(
                    "messages.command.list.owned-empty", "<dark_gray>│</dark_gray> <dark_gray>(none)</dark_gray>"),
            MessageKey.of(
                    "messages.command.list.section-shared",
                    "<dark_gray>├─ <gray>Shared worlds (member)</gray> ─────────┤</dark_gray>"),
            MessageKey.of(
                    "messages.command.list.shared-entry",
                    "<dark_gray>│</dark_gray> <green>●</green> <white><world></white> <gray>(Owner: <owner>) - <role></gray>",
                    Set.of("world", "owner", "role")),
            MessageKey.of(
                    "messages.command.list.shared-empty", "<dark_gray>│</dark_gray> <dark_gray>(none)</dark_gray>"),
            MessageKey.of("messages.command.list.footer", "<dark_gray>└───────────────────────────────┘</dark_gray>"),
            MessageKey.of("messages.command.list.summary", "<gray>Your worlds</gray>"),

            // --- browse (FR-9b) ----------------------------------------------------
            MessageKey.of(
                    "messages.command.browse.empty", "<gray>There are no public worlds available right now.</gray>"),
            MessageKey.of(
                    "messages.command.browse.header",
                    "<dark_gray>┌─ <gray>Public Worlds</gray> ─────────────────┐</dark_gray>"),
            MessageKey.of(
                    "messages.command.browse.entry",
                    "<dark_gray>│</dark_gray> <green>●</green> <white><world></white> <gray>(Owner: <owner>) <status><description></gray>",
                    Set.of("world", "owner", "status", "description")),
            MessageKey.of("messages.command.browse.footer", "<dark_gray>└───────────────────────────────┘</dark_gray>"),
            MessageKey.of("messages.command.browse.summary", "<gray>Public worlds:</gray>"),

            // --- storage (FR-30a) ----------------------------------------------------
            MessageKey.of(
                    "messages.command.storage.probed-note",
                    "<gray>  (no enumerable permission plugin: only the <count> tiers in storage.quota-tiers are recognised)</gray>",
                    Set.of("count")),
            MessageKey.of("messages.command.storage.summary", "<gray>Storage: <size></gray>", Set.of("size")),
            MessageKey.of(
                    "messages.command.storage.summary-unlimited",
                    "<gray><who> storage: <used> (unlimited)</gray>",
                    Set.of("who", "used")),
            MessageKey.of(
                    "messages.command.storage.summary-limited",
                    "<gray><who> storage: <used> / <limit> <bar> <percent>%</gray>",
                    Set.of("who", "used", "limit", "bar", "percent")),
            MessageKey.of("messages.command.storage.no-worlds", "<gray>  no worlds owned</gray>"),
            MessageKey.of(
                    "messages.command.storage.world-entry",
                    "<gray>  <world> - <size> - <state></gray>",
                    Set.of("world", "size", "state")),

            // --- WorldCommand's own direct replies (usage, admin subtree) ----------------
            MessageKey.of(
                    "messages.command.player-only",
                    "<red>/world acts on the caller's own worlds and must be run by a player</red>"),
            MessageKey.of(
                    "messages.command.admin.usage",
                    "<yellow>/world admin <<subcommands>></yellow>",
                    Set.of("subcommands")),
            MessageKey.of(
                    "messages.command.admin.invalid-world-id",
                    "<red>'<raw>' is not a world id (section 6 takes the uuid, not the name)</red>",
                    Set.of("raw")),
            MessageKey.of("messages.command.admin.no-world-for-id", "<red>no world with that id</red>"),
            MessageKey.of(
                    "messages.command.admin.unknown-node",
                    "<red>no node called '<node>' has ever registered</red>",
                    Set.of("node")),

            // --- admin list ----------------
            MessageKey.of(
                    "messages.command.admin.list.no-heartbeat", "<gray>no node has ever published a heartbeat</gray>"),
            MessageKey.of(
                    "messages.command.admin.list.summary",
                    "<gray><count> node(s); alive means a heartbeat within <seconds>s and not draining (MN-18)</gray>",
                    Set.of("count", "seconds")),
            MessageKey.of("messages.command.admin.list.node-status", "<line>", Set.of("line")),

            // --- admin unload ----------------
            MessageKey.of(
                    "messages.command.admin.unload.no-lease",
                    "<gray>that world holds no live lease; nothing to unload</gray>"),
            MessageKey.of(
                    "messages.command.admin.unload.success",
                    "<green>asked <holder> to commit and unload <world></green>",
                    Set.of("holder", "world")),

            // --- admin migrate ----------------
            MessageKey.of(
                    "messages.command.admin.migrate.not-available",
                    "<red>'<node>' <reason></red>",
                    Set.of("node", "reason")),
            MessageKey.of(
                    "messages.command.admin.migrate.too-old",
                    "<red>'<node>' runs data version <node-version> and that world was last saved at "
                            + "<world-version>; it cannot open it (MN-26)</red>",
                    Set.of("node", "node-version", "world-version")),
            MessageKey.of(
                    "messages.command.admin.migrate.lease-race",
                    "<red>a node took that world while you were typing; try again</red>"),
            MessageKey.of(
                    "messages.command.admin.migrate.unloaded-relocated",
                    "<green><world> was not loaded; its lease is now on <node></green>",
                    Set.of("world", "node")),
            MessageKey.of(
                    "messages.command.admin.migrate.already-there",
                    "<gray>that world is already on <node></gray>",
                    Set.of("node")),
            MessageKey.of(
                    "messages.command.admin.migrate.handoff-requested",
                    "<gray>asked <holder> to hand <world> over to <node>; players inside get a <countdown>s countdown (MN-21)</gray>",
                    Set.of("holder", "world", "node", "countdown")),
            MessageKey.of(
                    "messages.command.admin.migrate.no-answer",
                    "<red>no answer from <holder> yet; the world stays where it is. Check /world admin list and the node's log, then try again</red>",
                    Set.of("holder")),
            MessageKey.of(
                    "messages.command.admin.migrate.refused",
                    "<red><holder> refused to give the world up: <result></red>",
                    Set.of("holder", "result")),
            MessageKey.of(
                    "messages.command.admin.migrate.race-after-release",
                    "<red>the world was released but <now-on> took the lease before <node> could</red>",
                    Set.of("now-on", "node")),
            MessageKey.of(
                    "messages.command.admin.migrate.success",
                    "<green><world> moved from <from> to <to> (MN-19)</green>",
                    Set.of("world", "from", "to")),

            // --- admin drain ----------------
            MessageKey.of(
                    "messages.command.admin.drain.on",
                    "<green>draining <node>: it takes no new placements and releases its <count> world(s) in place (MN-22)</green>",
                    Set.of("node", "count")),
            MessageKey.of(
                    "messages.command.admin.drain.on-hint",
                    "<gray>its worlds are placed fresh on the next join (MN-20); it leaves the proxy's server list on the next sweep</gray>"),
            MessageKey.of(
                    "messages.command.admin.drain.off",
                    "<green><node> will take new placements again</green>",
                    Set.of("node")),

            // --- admin transfer ----------------
            MessageKey.of(
                    "messages.command.admin.transfer.already-owner",
                    "<red><target> is already the owner of that world</red>",
                    Set.of("target")),
            MessageKey.of(
                    "messages.command.admin.transfer.cap-reached",
                    "<red><target> has reached their world limit (<max>)</red>",
                    Set.of("target", "max")),
            MessageKey.of(
                    "messages.command.admin.transfer.failed",
                    "<red>could not transfer world <world></red>",
                    Set.of("world")),
            MessageKey.of(
                    "messages.command.admin.transfer.success",
                    "<green>transferred ownership of '<world>' (<id>) to <target> with reason ADMIN</green>",
                    Set.of("world", "id", "target")),
            MessageKey.of(
                    "messages.command.admin.transfer.new-owner-notice",
                    "<green>You were granted ownership of '<world>' by an administrator.</green>",
                    Set.of("world")),
            MessageKey.of(
                    "messages.command.admin.transfer.old-owner-notice",
                    "<yellow>Ownership of '<world>' was transferred to <target> by an administrator.</yellow>",
                    Set.of("world", "target")),

            // --- admin storage ----------------
            MessageKey.of(
                    "messages.command.admin.storage.offline-note",
                    "<gray>  (offline: allowance shown is the network default, not their permission tier)</gray>"),

            // --- admin archive ----------------
            MessageKey.of(
                    "messages.command.admin.archive.already-archived",
                    "<gray>'<world>' is already archived</gray>",
                    Set.of("world")),
            MessageKey.of(
                    "messages.command.admin.archive.success",
                    "<green>queued archival of '<world>' on <node></green>",
                    Set.of("world", "node")),

            // --- admin restore ----------------
            MessageKey.of(
                    "messages.command.admin.restore.not-archived",
                    "<red>'<world>' is <state> and does not need restoring</red>",
                    Set.of("world", "state")),
            MessageKey.of(
                    "messages.command.admin.restore.success",
                    "<green>queued restore of '<world>' on <node><target-suffix></green>",
                    Set.of("world", "node", "target-suffix")),

            // --- admin delete ----------------
            MessageKey.of(
                    "messages.command.admin.delete.confirm",
                    "<red>this permanently destroys '<world>' and every archive of it. There is no undo and no other command undoes it.</red>",
                    Set.of("world")),
            MessageKey.of(
                    "messages.command.admin.delete.confirm-hint",
                    "<gray>type /world admin delete <id> confirm to go ahead</gray>",
                    Set.of("id")),
            MessageKey.of(
                    "messages.command.admin.delete.changed",
                    "<red>'<world>' changed while you were confirming; try again</red>",
                    Set.of("world")),
            MessageKey.of(
                    "messages.command.admin.delete.success",
                    "<green>permanently deleted '<world>' (<id>)</green>",
                    Set.of("world", "id")));
}
