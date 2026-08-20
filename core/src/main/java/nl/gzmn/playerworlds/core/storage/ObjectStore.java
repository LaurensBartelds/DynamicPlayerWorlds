package nl.gzmn.playerworlds.core.storage;

import java.io.Closeable;
import java.nio.file.Path;
import java.util.List;
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

    /**
     * Deletes an object from storage if it exists.
     *
     * @param key object storage key to delete
     * @throws StorageException if deletion fails
     */
    void deleteObject(String key);

    /**
     * Deletes all objects matching the given key prefix.
     *
     * @param prefix object key prefix to delete
     * @throws StorageException if deletion fails
     */
    void deletePrefix(String prefix);

    /**
     * Every key under {@code prefix}, in lexicographical order.
     *
     * <p>What MN-2b's collection needs and could not have: without it, "any
     * {@code worlds/<world_id>/data/<sha256>} not referenced by a retained
     * manifest" is a set nothing can enumerate.
     *
     * <p>Reads the whole listing rather than streaming it. A world's data prefix
     * holds one key per distinct file version it has ever had, which is thousands
     * rather than millions, and the caller bounds how many worlds it visits per
     * sweep.
     *
     * @param prefix key prefix to list; {@code ""} lists the bucket
     * @return the keys found, sorted
     */
    List<String> listKeys(String prefix);

    /**
     * Returns the size of an object in bytes.
     *
     * @param key object storage key
     * @return size in bytes
     * @throws StorageException if size lookup fails or object does not exist
     */
    long getObjectSize(String key);

    @Override
    void close();
}
