package nl.gzmn.playerworlds.core.storage;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
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
import software.amazon.awssdk.services.s3.model.Delete;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectsRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.ObjectIdentifier;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.model.S3Object;

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
    public void deleteObject(String key) {
        Objects.requireNonNull(key, "key");
        try {
            client.deleteObject(
                    DeleteObjectRequest.builder().bucket(bucket).key(key).build());
        } catch (Exception e) {
            throw new StorageException("Failed to delete object: " + key, e);
        }
    }

    @Override
    public List<String> listKeys(String prefix) {
        Objects.requireNonNull(prefix, "prefix");
        try {
            List<String> keys = new ArrayList<>();
            String continuationToken = null;
            do {
                ListObjectsV2Request.Builder request =
                        ListObjectsV2Request.builder().bucket(bucket).prefix(prefix);
                if (continuationToken != null) {
                    request.continuationToken(continuationToken);
                }
                ListObjectsV2Response response = client.listObjectsV2(request.build());
                for (S3Object object : response.contents()) {
                    keys.add(object.key());
                }
                continuationToken = response.isTruncated() ? response.nextContinuationToken() : null;
            } while (continuationToken != null);
            keys.sort(Comparator.naturalOrder());
            return List.copyOf(keys);
        } catch (Exception e) {
            throw new StorageException("Failed to list prefix: " + prefix, e);
        }
    }

    @Override
    public void deletePrefix(String prefix) {
        Objects.requireNonNull(prefix, "prefix");
        try {
            String continuationToken = null;
            do {
                ListObjectsV2Request.Builder reqBuilder =
                        ListObjectsV2Request.builder().bucket(bucket).prefix(prefix);
                if (continuationToken != null) {
                    reqBuilder.continuationToken(continuationToken);
                }
                ListObjectsV2Response listRes = client.listObjectsV2(reqBuilder.build());
                List<ObjectIdentifier> toDelete = listRes.contents().stream()
                        .map(s3Obj ->
                                ObjectIdentifier.builder().key(s3Obj.key()).build())
                        .toList();
                if (!toDelete.isEmpty()) {
                    client.deleteObjects(DeleteObjectsRequest.builder()
                            .bucket(bucket)
                            .delete(Delete.builder().objects(toDelete).build())
                            .build());
                }
                continuationToken = listRes.isTruncated() ? listRes.nextContinuationToken() : null;
            } while (continuationToken != null);
        } catch (Exception e) {
            throw new StorageException("Failed to delete prefix: " + prefix, e);
        }
    }

    @Override
    public long getObjectSize(String key) {
        Objects.requireNonNull(key, "key");
        try {
            HeadObjectResponse head = client.headObject(
                    HeadObjectRequest.builder().bucket(bucket).key(key).build());
            return head.contentLength();
        } catch (NoSuchKeyException e) {
            throw new StorageException("Object does not exist: " + key, e);
        } catch (S3Exception e) {
            if (e.statusCode() == 404) {
                throw new StorageException("Object does not exist: " + key, e);
            }
            throw new StorageException("Failed to get size for: " + key, e);
        } catch (Exception e) {
            throw new StorageException("Failed to get size for: " + key, e);
        }
    }

    @Override
    public void close() {
        client.close();
    }
}
