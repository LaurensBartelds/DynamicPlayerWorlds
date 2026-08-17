package nl.gzmn.playerworlds.core.config;

import java.net.URI;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * One S3-compatible client configuration, shared by live world objects and cold
 * archives.
 *
 * <p>Specification section 7 listed {@code archive.s3.*} and section 12.8 listed
 * {@code storage.s3.*} as two independent credential sets. Archives and live
 * objects may use different <em>buckets</em>, but two credential sets is how an
 * operator rotates one and forgets the other. One client, with an optional
 * archive-bucket override (ADR 0007).
 *
 * @param endpoint S3 API endpoint, for example {@code https://minio.example:9000}
 * @param region region string the SDK requires; MinIO accepts any non-empty value
 * @param accessKey access key id
 * @param secretKey secret access key
 * @param bucket bucket for live content-addressed objects and manifests
 * @param archiveBucket bucket for cold archives; {@code null} means "same as
 *     {@code bucket}"
 * @param pathStyleAccess whether to use path-style addressing (required by MinIO)
 */
public record StorageClientSettings(
        URI endpoint,
        String region,
        String accessKey,
        String secretKey,
        String bucket,
        @Nullable String archiveBucket,
        boolean pathStyleAccess) {

    public static final String DEFAULT_REGION = "us-east-1";

    public StorageClientSettings {
        Objects.requireNonNull(endpoint, "endpoint");
        Objects.requireNonNull(region, "region");
        Objects.requireNonNull(accessKey, "accessKey");
        Objects.requireNonNull(secretKey, "secretKey");
        Objects.requireNonNull(bucket, "bucket");
        if (region.isBlank()) {
            throw new ConfigException("storage.s3.region must not be blank");
        }
        if (accessKey.isBlank()) {
            throw new ConfigException("storage.s3.access-key must not be blank");
        }
        if (secretKey.isBlank()) {
            throw new ConfigException("storage.s3.secret-key must not be blank");
        }
        if (bucket.isBlank()) {
            throw new ConfigException("storage.s3.bucket must not be blank");
        }
        if (archiveBucket != null && archiveBucket.isBlank()) {
            throw new ConfigException("storage.s3.archive-bucket must not be blank when set");
        }
    }

    /** Bucket cold archives are written to. */
    public String effectiveArchiveBucket() {
        return archiveBucket != null ? archiveBucket : bucket;
    }

    public static StorageClientSettings of(URI endpoint, String accessKey, String secretKey, String bucket) {
        return new StorageClientSettings(endpoint, DEFAULT_REGION, accessKey, secretKey, bucket, null, true);
    }
}
