// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.jdk;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.EnumSet;
import java.util.Set;

/**
 * Minimal TAR reader that handles {@code .tar} and {@code .tar.gz} archives
 * without any external library. Replaces Apache Commons Compress's
 * {@code TarArchiveInputStream} for the JDK install use case.
 *
 * <p>Supported entry types:
 * <ul>
 *   <li>Regular files ({@code '0'} or NUL)</li>
 *   <li>Hard links ({@code '1'}) — treated as regular files</li>
 *   <li>Symbolic links ({@code '2'})</li>
 *   <li>Directories ({@code '5'})</li>
 *   <li>GNU long-name ({@code 'L'}) and long-link ({@code 'K'}) extensions</li>
 *   <li>PAX extended headers ({@code 'x'}, {@code 'g'}) — {@code path} and
 *       {@code linkpath} fields extracted</li>
 * </ul>
 *
 * <p>All other types (device files, etc.) are skipped silently — JDK archives
 * don't use them.
 */
public final class MinimalTar {

    private MinimalTar() {}

    @FunctionalInterface
    public interface EntryHandler {
        /**
         * Called for each entry.
         *
         * @param name     entry path (may contain subdirs)
         * @param linkName symlink target, or empty string for non-symlinks
         * @param mode     POSIX permission bits (e.g. {@code 0755})
         * @param isDir    true when the entry is a directory
         * @param isLink   true when the entry is a symbolic link
         * @param data     stream positioned at entry data ({@code size} bytes available);
         *                 {@code null} for directories and symlinks
         * @param size     uncompressed byte count of the entry data
         */
        void handle(String name, String linkName, int mode, boolean isDir, boolean isLink, InputStream data, long size)
                throws IOException;
    }

    /** Stream through a raw (uncompressed) TAR archive, invoking {@code handler} for each entry. */
    public static void stream(InputStream tar, EntryHandler handler) throws IOException {
        byte[] header = new byte[512];
        String pendingName = null; // GNU 'L' or PAX 'path'
        String pendingLink = null; // GNU 'K' or PAX 'linkpath'

        while (true) {
            int read = readFully(tar, header, 0, 512);
            if (read < 512) break;

            // Two consecutive all-zero blocks = end of archive.
            if (isZeroBlock(header)) {
                readFully(tar, header, 0, 512); // consume second zero block
                break;
            }

            String name = nullTermStr(header, 0, 100);
            int mode = octalInt(header, 100, 8);
            long size = octalLong(header, 124, 12);
            char typeFlag = (char) (header[156] & 0xFF);
            String linkName = nullTermStr(header, 157, 100);

            // USTAR/POSIX prefix (bytes 345-499) extends the name.
            String prefix = nullTermStr(header, 345, 155);
            if (!prefix.isEmpty() && typeFlag != 'L' && typeFlag != 'K' && typeFlag != 'x' && typeFlag != 'g') {
                name = prefix + "/" + name;
            }

            // Apply any pending long name from a previous 'L'/'K'/PAX entry.
            if (pendingName != null) {
                name = pendingName;
                pendingName = null;
            }
            if (pendingLink != null) {
                linkName = pendingLink;
                pendingLink = null;
            }

            long dataBlocks = (size + 511) / 512;

            switch (typeFlag) {
                case 'L' -> {
                    // GNU long-name: data block contains the real file name.
                    pendingName = readString(tar, size);
                    skipPadding(tar, dataBlocks, size);
                }
                case 'K' -> {
                    // GNU long-link: data block contains the real link name.
                    pendingLink = readString(tar, size);
                    skipPadding(tar, dataBlocks, size);
                }
                case 'x', 'g' -> {
                    // PAX extended header: parse "size key=value\n" records.
                    String pax = readString(tar, size);
                    String[] lines = pax.split("\n");
                    for (String line : lines) {
                        int spaceIdx = line.indexOf(' ');
                        if (spaceIdx < 0) continue;
                        String kv = line.substring(spaceIdx + 1);
                        int eqIdx = kv.indexOf('=');
                        if (eqIdx < 0) continue;
                        String k = kv.substring(0, eqIdx);
                        String v = kv.substring(eqIdx + 1);
                        if ("path".equals(k)) pendingName = v;
                        if ("linkpath".equals(k)) pendingLink = v;
                    }
                    skipPadding(tar, dataBlocks, size);
                }
                case '5' -> {
                    // Directory.
                    handler.handle(name, linkName, mode, true, false, null, 0);
                    // Directories typically have zero data blocks.
                    skipBlocks(tar, dataBlocks);
                }
                case '2' -> {
                    // Symbolic link.
                    handler.handle(name, linkName, mode, false, true, null, 0);
                    skipBlocks(tar, dataBlocks);
                }
                case '0', '\0', '1' -> {
                    // Regular file or hard link — stream data bytes to handler.
                    LimitedInputStream limited = new LimitedInputStream(tar, size);
                    handler.handle(name, linkName, mode, false, false, limited, size);
                    // Drain any unread bytes + padding.
                    limited.skipRemaining();
                    skipPadding(tar, dataBlocks, size);
                }
                default -> {
                    // Unknown type — skip.
                    skipBlocks(tar, dataBlocks);
                }
            }
        }
    }

    // ---- helpers ----------------------------------------------------------

    public static void applyMode(Path file, int mode) {
        try {
            Set<PosixFilePermission> perms = EnumSet.noneOf(PosixFilePermission.class);
            if ((mode & 0400) != 0) perms.add(PosixFilePermission.OWNER_READ);
            if ((mode & 0200) != 0) perms.add(PosixFilePermission.OWNER_WRITE);
            if ((mode & 0100) != 0) perms.add(PosixFilePermission.OWNER_EXECUTE);
            if ((mode & 0040) != 0) perms.add(PosixFilePermission.GROUP_READ);
            if ((mode & 0020) != 0) perms.add(PosixFilePermission.GROUP_WRITE);
            if ((mode & 0010) != 0) perms.add(PosixFilePermission.GROUP_EXECUTE);
            if ((mode & 0004) != 0) perms.add(PosixFilePermission.OTHERS_READ);
            if ((mode & 0002) != 0) perms.add(PosixFilePermission.OTHERS_WRITE);
            if ((mode & 0001) != 0) perms.add(PosixFilePermission.OTHERS_EXECUTE);
            if (!perms.isEmpty()) Files.setPosixFilePermissions(file, perms);
        } catch (UnsupportedOperationException | IOException ignored) {
            // Non-POSIX filesystem — skip silently.
        }
    }

    private static String readString(InputStream in, long size) throws IOException {
        byte[] buf = new byte[(int) Math.min(size, 1 << 20)]; // 1 MB cap
        readFully(in, buf, 0, buf.length);
        return new String(buf, StandardCharsets.UTF_8).trim();
    }

    private static void skipPadding(InputStream in, long blocks, long size) throws IOException {
        long padding = blocks * 512 - size;
        if (padding > 0) in.skipNBytes(padding);
    }

    private static void skipBlocks(InputStream in, long blocks) throws IOException {
        in.skipNBytes(blocks * 512);
    }

    private static String nullTermStr(byte[] buf, int off, int len) {
        int end = off;
        while (end < off + len && buf[end] != 0) end++;
        return new String(buf, off, end - off, StandardCharsets.ISO_8859_1);
    }

    private static int octalInt(byte[] buf, int off, int len) {
        try {
            return Integer.parseInt(nullTermStr(buf, off, len).strip(), 8);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static long octalLong(byte[] buf, int off, int len) {
        // GNU extension: if high bit of first byte is set, the field is binary.
        if ((buf[off] & 0x80) != 0) {
            long v = 0;
            for (int i = off + 3; i < off + len; i++) v = (v << 8) | (buf[i] & 0xFF);
            return v;
        }
        try {
            return Long.parseLong(nullTermStr(buf, off, len).strip(), 8);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static boolean isZeroBlock(byte[] buf) {
        for (byte b : buf) if (b != 0) return false;
        return true;
    }

    private static int readFully(InputStream in, byte[] buf, int off, int len) throws IOException {
        int total = 0;
        while (total < len) {
            int n = in.read(buf, off + total, len - total);
            if (n < 0) break;
            total += n;
        }
        return total;
    }

    /**
     * Wraps an {@link InputStream} and limits reads to exactly {@code size}
     * bytes — prevents the handler from reading beyond the current TAR entry.
     */
    static final class LimitedInputStream extends InputStream {
        private final InputStream delegate;
        private long remaining;

        LimitedInputStream(InputStream delegate, long size) {
            this.delegate = delegate;
            this.remaining = size;
        }

        @Override
        public int read() throws IOException {
            if (remaining <= 0) return -1;
            int b = delegate.read();
            if (b >= 0) remaining--;
            return b;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            if (remaining <= 0) return -1;
            int n = delegate.read(b, off, (int) Math.min(len, remaining));
            if (n > 0) remaining -= n;
            return n;
        }

        void skipRemaining() throws IOException {
            if (remaining > 0) {
                delegate.skipNBytes(remaining);
                remaining = 0;
            }
        }

        @Override
        public void close() {
            /* don't close the underlying stream */
        }
    }
}
