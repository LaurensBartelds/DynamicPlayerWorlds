package nl.gzmn.playerworlds.testing;

import java.net.URI;
import java.util.Locale;
import java.util.UUID;
import nl.gzmn.playerworlds.core.config.StorageClientSettings;
import org.jspecify.annotations.Nullable;
import org.testcontainers.containers.MinIOContainer;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;
import software.amazon.awssdk.services.s3.model.NoSuchBucketException;

/**
 * Shared MinIO for object-storage tests (plan section 11, NFR-8).
 *
 * <p>One container per JVM. Isolation is a unique bucket per {@link
 * #settingsForNewBucket()} call rather than a new container — starting MinIO is
 * most of the cost, and the suite budget is five minutes.
 *
 * <p>Image is pinned. A floating {@code latest} tag is how a CI flake becomes an
 * unbisectable "storage tests broke overnight".
 *
 * <p>Clients use the JDK {@code UrlConnection} transport, matching production
 * ({@code core} excludes Netty and Apache HttpClient 5 for the same reason).
 */
public final class TestObjectStore {

    /**
     * Pinned MinIO release. Bump deliberately when a storage behaviour needs a
     * newer server; never float.
     */
    public static final String IMAGE = "minio/minio:RELEASE.2025-04-22T22-12-26Z";

    private static final String USER = "gzmn-test";
    private static final String PASSWORD = "gzmn-test-secret";

    private static @Nullable MinIOContainer container;

    private TestObjectStore() {}

    /** Starts the shared container on first use. */
    public static synchronized MinIOContainer container() {
        MinIOContainer current = container;
        if (current == null) {
            MinIOContainer started =
                    new MinIOContainer(IMAGE).withUserName(USER).withPassword(PASSWORD);
            started.start();
            container = started;
            current = started;
        }
        return current;
    }

    /**
     * Settings pointing at a freshly created unique bucket. Safe for concurrent
     * tests: each call gets its own bucket name.
     */
    public static StorageClientSettings settingsForNewBucket() {
        String bucket = uniqueBucketName();
        StorageClientSettings settings = settings(bucket);
        try (S3Client client = openClient(settings)) {
            client.createBucket(CreateBucketRequest.builder().bucket(bucket).build());
        }
        return settings;
    }

    /** Settings for an existing or not-yet-created bucket name. */
    public static StorageClientSettings settings(String bucket) {
        MinIOContainer minio = container();
        return new StorageClientSettings(
                URI.create(minio.getS3URL()),
                StorageClientSettings.DEFAULT_REGION,
                minio.getUserName(),
                minio.getPassword(),
                bucket,
                null,
                true);
    }

    /**
     * An S3 client for the given settings. Caller must close it. Uses path-style
     * addressing and the URL-connection HTTP client.
     */
    public static S3Client openClient(StorageClientSettings settings) {
        return S3Client.builder()
                .endpointOverride(settings.endpoint())
                .region(Region.of(settings.region()))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(settings.accessKey(), settings.secretKey())))
                .forcePathStyle(settings.pathStyleAccess())
                .httpClient(UrlConnectionHttpClient.builder().build())
                .build();
    }

    /** Whether {@code bucket} exists (HEAD). */
    public static boolean bucketExists(S3Client client, String bucket) {
        try {
            client.headBucket(HeadBucketRequest.builder().bucket(bucket).build());
            return true;
        } catch (NoSuchBucketException e) {
            return false;
        }
    }

    private static String uniqueBucketName() {
        // S3 bucket names: 3–63 chars, lowercase, digits, hyphens.
        return "pw-" + UUID.randomUUID().toString().toLowerCase(Locale.ROOT);
    }
}
