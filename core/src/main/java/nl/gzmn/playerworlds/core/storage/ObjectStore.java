package nl.gzmn.playerworlds.core.storage;

import java.io.Closeable;
import java.nio.file.Path;
import org.jspecify.annotations.Nullable;

/**
 * High-level abstraction for storing and retrieving immutable objects and manifests
 * in S3-compatible object storage.
 */
public interface ObjectStore extends Closeable {

    /**
     * Uploads the contents of a local file to the given object key.
     *
     * @param key target object storage key
     * @param sourceFile local file to upload
     * @throws StorageException if the upload fails
     */
    void putObject(String key, Path sourceFile);

    /**
     * Uploads in-memory bytes to the given object key with an optional content type.
     *
     * @param key target object storage key
     * @param bytes binary data to store
     * @param contentType MIME content type, or {@code null} for default binary stream
     * @throws StorageException if the upload fails
     */
    void putBytes(String key, byte[] bytes, @Nullable String contentType);

    /**
     * Downloads an object to the specified destination path atomically.
     *
     * @param key object storage key to retrieve
     * @param destinationFile local destination path
     * @throws StorageException if the download or atomic write fails
     */
    void getObject(String key, Path destinationFile);

    /**
     * Retrieves the entire content of an object into memory.
     *
     * @param key object storage key to retrieve
     * @return object content as byte array
     * @throws StorageException if retrieval fails or object does not exist
     */
    byte[] getBytes(String key);

    /**
     * Checks whether an object exists in storage.
     *
     * @param key object storage key to check
     * @return {@code true} if the object exists, {@code false} if missing (404 / NoSuchKey)
     * @throws StorageException if existence check fails due to an unexpected error
     */
    boolean exists(String key);

    @Override
    void close();
}
