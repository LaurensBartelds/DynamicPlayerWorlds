package nl.gzmn.playerworlds.core.storage;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.Arrays;
import java.util.Objects;
import nl.gzmn.playerworlds.core.obs.StorageHealthCheck;

/**
 * A real round trip against object storage: put, then read the same bytes back
 * (plan section 10.4, MN-11a).
 *
 * <p>{@code head-bucket} alone would pass on read-only credentials that cannot
 * actually take a snapshot commit; every write MN-6a makes to object storage is
 * a {@code putObject} first, so the check has to attempt one too. The same key
 * is reused on every call — one node's health check does not accumulate
 * objects, and does not need delete permission on top of read/write.
 */
public final class ObjectStoreHealthCheck implements StorageHealthCheck {

    private final ObjectStore store;
    private final String key;
    private final Clock clock;

    public ObjectStoreHealthCheck(ObjectStore store, String key) {
        this(store, key, Clock.systemUTC());
    }

    ObjectStoreHealthCheck(ObjectStore store, String key, Clock clock) {
        this.store = Objects.requireNonNull(store, "store");
        this.key = Objects.requireNonNull(key, "key");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public void ping() throws Exception {
        byte[] probe = ("gzmn-worlds health check " + clock.instant()).getBytes(StandardCharsets.UTF_8);
        store.putBytes(key, probe, "text/plain");
        byte[] readBack = store.getBytes(key);
        if (!Arrays.equals(probe, readBack)) {
            throw new StorageException("object storage round trip returned different bytes for " + key);
        }
    }
}
