package nl.gzmn.playerworlds.core.storage;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Objects;
import java.util.UUID;
import nl.gzmn.playerworlds.core.config.StorageClientSettings;
import org.jspecify.annotations.Nullable;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;

/**
 * S3-compatible implementation of {@link ObjectStore} using AWS SDK v2 with
 * {@link UrlConnectionHttpClient}.
 */
public final class S3ObjectStore implements ObjectStore {

    private final S3Client client;
    private final String bucket;

    public S3ObjectStore(S3Client client, String bucket) {
        this.client = Objects.requireNonNull(client, "client");
        this.bucket = Objects.requireNonNull(bucket, "bucket");
    }

    /**
     * Opens an {@link S3ObjectStore} configured with the given {@link StorageClientSettings}.
     *
     * @param settings storage configuration
     * @return a new {@code S3ObjectStore} instance
     */
    public static S3ObjectStore open(StorageClientSettings settings) {
        Objects.requireNonNull(settings, "settings");
        S3Client client = S3Client.builder()
                .endpointOverride(settings.endpoint())
                .region(Region.of(settings.region()))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(settings.accessKey(), settings.secretKey())))
                .forcePathStyle(settings.pathStyleAccess())
                .httpClient(UrlConnectionHttpClient.builder().build())
                .build();
        return new S3ObjectStore(client, settings.bucket());
    }

    @Override
    public void putObject(String key, Path sourceFile) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(sourceFile, "sourceFile");
        try {
            client.putObject(
                    PutObjectRequest.builder().bucket(bucket).key(key).build(), RequestBody.fromFile(sourceFile));
        } catch (Exception e) {
            throw new StorageException("Failed to upload object: " + key, e);
        }
    }

    @Override
    public void putBytes(String key, byte[] bytes, @Nullable String contentType) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(bytes, "bytes");
        try {
            client.putObject(
                    PutObjectRequest.builder()
                            .bucket(bucket)
                            .key(key)
                            .contentType(contentType != null ? contentType : "application/octet-stream")
                            .build(),
                    RequestBody.fromBytes(bytes));
        } catch (Exception e) {
            throw new StorageException("Failed to put bytes: " + key, e);
        }
    }

    @Override
    public void getObject(String key, Path destinationFile) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(destinationFile, "destinationFile");
        Path temp = null;
        try {
            Path parent = destinationFile.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            temp = destinationFile.resolveSibling(destinationFile.getFileName() + ".tmp." + UUID.randomUUID());
            client.getObject(GetObjectRequest.builder().bucket(bucket).key(key).build(), temp);
            try {
                Files.move(temp, destinationFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, destinationFile, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (Exception e) {
            if (temp != null) {
                try {
                    Files.deleteIfExists(temp);
                } catch (IOException ignored) {
                    // Suppress secondary cleanup error
                }
            }
            throw new StorageException("Failed to download object: " + key, e);
        }
    }

    @Override
    public byte[] getBytes(String key) {
        Objects.requireNonNull(key, "key");
        try {
            return client.getObjectAsBytes(
                            GetObjectRequest.builder().bucket(bucket).key(key).build())
                    .asByteArray();
        } catch (Exception e) {
            throw new StorageException("Failed to get bytes for: " + key, e);
        }
    }

    @Override
    public boolean exists(String key) {
        Objects.requireNonNull(key, "key");
        try {
            client.headObject(
                    HeadObjectRequest.builder().bucket(bucket).key(key).build());
            return true;
        } catch (NoSuchKeyException e) {
            return false;
        } catch (S3Exception e) {
            if (e.statusCode() == 404) {
                return false;
            }
            throw new StorageException("Failed to check existence for: " + key, e);
        }
    }

    @Override
    public void close() {
        client.close();
    }
}
