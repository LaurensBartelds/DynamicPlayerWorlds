package nl.gzmn.playerworlds.core.config.messages;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Aggregates every area's {@code List<MessageKey> ENTRIES} into one lookup table.
 *
 * <p>The only place that assembles the full catalog. Each area file (one command surface or GUI
 * screen) is authored and reviewed independently; this class just lists them. Adding an area
 * means adding one line to {@link #AREAS} — nothing else in this file changes.
 */
public final class MessageRegistry {

    private MessageRegistry() {}

    /** One area per command surface or GUI screen. Keep sorted by key prefix for scanability. */
    private static final List<List<MessageKey>> AREAS = List.of(
            AdminMessages.ENTRIES,
            CommandMessages.ENTRIES,
            NoticeMessages.ENTRIES,
            GuiMainMenuMessages.ENTRIES,
            GuiMyWorldsMenuMessages.ENTRIES,
            GuiWorldMenuMessages.ENTRIES,
            GuiMembersMenuMessages.ENTRIES,
            GuiBansMenuMessages.ENTRIES,
            GuiInvitesMenuMessages.ENTRIES,
            GuiBrowseMenuMessages.ENTRIES,
            GuiStorageMenuMessages.ENTRIES,
            GuiSettingsMenuMessages.ENTRIES,
            GuiConfirmMenuMessages.ENTRIES);

    /** Every declared key, keyed by {@link MessageKey#key()}. Duplicate keys are a build error. */
    public static final Map<String, MessageKey> ALL = buildAll();

    private static Map<String, MessageKey> buildAll() {
        List<MessageKey> all = new ArrayList<>();
        for (List<MessageKey> area : AREAS) {
            all.addAll(area);
        }
        try {
            return all.stream().collect(Collectors.toUnmodifiableMap(MessageKey::key, k -> k));
        } catch (IllegalStateException e) {
            throw new ExceptionInInitializerError("duplicate message key across areas: " + e.getMessage());
        }
    }
}
