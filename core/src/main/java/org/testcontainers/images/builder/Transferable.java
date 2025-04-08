package org.testcontainers.images.builder;

import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.apache.commons.io.IOUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.zip.Checksum;

public interface Transferable {
    int DEFAULT_FILE_MODE = 0100644;

    int DEFAULT_DIR_MODE = 040755;

    long DEFAULT_OWNER_ID = 0L;

    long DEFAULT_GROUP_ID = 0L;

    static Transferable of(String string) {
        return of(string, DEFAULT_FILE_MODE);
    }

    static Transferable of(String string, int fileMode) {
        return of(string, fileMode, DEFAULT_OWNER_ID, DEFAULT_GROUP_ID);
    }

    static Transferable of(String string, int fileMode, long ownerId, long groupId) {
        return of(string.getBytes(StandardCharsets.UTF_8), fileMode, ownerId, groupId);
    }

    static Transferable of(byte[] bytes) {
        return of(bytes, DEFAULT_FILE_MODE, DEFAULT_OWNER_ID, DEFAULT_GROUP_ID);
    }

    static Transferable of(byte[] bytes, int fileMode) {
        return of(bytes, fileMode, DEFAULT_OWNER_ID, DEFAULT_GROUP_ID);
    }

    static Transferable of(byte[] bytes, int fileMode, long ownerId, long groupId) {
        return new Transferable() {
            @Override
            public long getSize() {
                return bytes.length;
            }

            @Override
            public byte[] getBytes() {
                return bytes;
            }

            @Override
            public void updateChecksum(Checksum checksum) {
                checksum.update(bytes, 0, bytes.length);
            }

            @Override
            public int getFileMode() {
                return fileMode;
            }

            @Override
            public long getOwnerId() {
                return ownerId;
            }

            @Override
            public long getGroupId() {
                return groupId;
            }
        };
    }

    /**
     * Get file mode. Default is 0100644.
     *
     * @return file mode
     * @see Transferable#DEFAULT_FILE_MODE
     */
    default int getFileMode() {
        return DEFAULT_FILE_MODE;
    }

    /**
     * Get file owner id. Default is 0 (root).
     *
     * @return file UID
     * @see Transferable#DEFAULT_OWNER_ID
     */
    default long getOwnerId() {
        return DEFAULT_OWNER_ID;
    }

    /**
     * Get file group id. Default is 0 (root).
     *
     * @return file GID
     * @see Transferable#DEFAULT_GROUP_ID
     */
    default long getGroupId() {
        return DEFAULT_GROUP_ID;
    }

    /**
     * Size of an object.
     *
     * @return size in bytes
     */
    long getSize();

    /**
     * transfer content of this Transferable to the output stream. <b>Must not</b> close the stream.
     *
     * @param tarArchiveOutputStream stream to output
     * @param destination
     */
    default void transferTo(TarArchiveOutputStream tarArchiveOutputStream, final String destination) {
        TarArchiveEntry tarEntry = new TarArchiveEntry(destination);
        tarEntry.setSize(getSize());
        tarEntry.setMode(getFileMode());
        tarEntry.setUserId(getOwnerId());
        tarEntry.setGroupId(getGroupId());

        try {
            tarArchiveOutputStream.putArchiveEntry(tarEntry);
            IOUtils.write(getBytes(), tarArchiveOutputStream);
            tarArchiveOutputStream.closeArchiveEntry();
        } catch (IOException e) {
            throw new RuntimeException("Can't transfer " + getDescription(), e);
        }
    }

    default byte[] getBytes() {
        return new byte[0];
    }

    default String getDescription() {
        return "";
    }

    default void updateChecksum(Checksum checksum) {
        throw new UnsupportedOperationException("Provide implementation in subclass");
    }
}
