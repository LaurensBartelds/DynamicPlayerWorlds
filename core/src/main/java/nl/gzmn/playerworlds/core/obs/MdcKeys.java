package nl.gzmn.playerworlds.core.obs;

/**
 * Standard MDC keys for structured logs (plan section 10.1).
 *
 * <p>Every log line that concerns a world, player or operation should carry the
 * keys that apply. Keys are short snake_case strings so a dashboard filter and a
 * {@code grep} agree on the same name. Renaming one breaks alerts — treat these
 * as an interface.
 */
public final class MdcKeys {

    public static final String NODE_ID = "node_id";
    public static final String WORLD_ID = "world_id";
    public static final String GENERATION = "generation";
    public static final String PLAYER_UUID = "player_uuid";
    /** Short name of the operation in flight, for example {@code sync} or {@code join}. */
    public static final String OP = "op";
    /** Correlates one player-facing request across proxy and node log streams. */
    public static final String TRACE_ID = "trace_id";
    /** Value of {@link LogEvent#key()} when the line is one of NFR-6's events. */
    public static final String EVENT = "event";

    private MdcKeys() {}
}
