package landau.smp;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.StandardCharsets;

public class ID3Extractor {
    // Maximum ID3v2 tag length
    static int kMaxSize = 3 * 1024 * 1024;
    private final String filename;
    private String artist, album, title;

    private static final CharsetDecoder userDecoder = Charset.forName("windows-1251").newDecoder();
    private static final CharsetDecoder utf8Decoder = StandardCharsets.UTF_8.newDecoder();
    private static final CharsetDecoder utf16BeDecoder = StandardCharsets.UTF_16BE.newDecoder();
    private static final CharsetDecoder utf16BomDecoder = StandardCharsets.UTF_16.newDecoder();

    public ID3Extractor(String filename) {
        this.filename = filename;
    }

    public String getAlbum() {
        return album;
    }

    public String getTitle() {
        return title;
    }

    public String getArtist() {
        return artist;
    }

    public void extractMetadata() {
        try (RandomAccessFile raf = new RandomAccessFile(filename, "r")) {
            try {
                extractV2(raf);
            } catch (IOException e) {
                // continue
            }

            if (artist == null || album == null || title == null) {
                try {
                    extractV1(raf);
                } catch (IOException e) {
                    // continue
                }
            }
        } catch (FileNotFoundException e) {
            // continue
        } catch (IOException e) {
            // continue
        }
    }

    private void extractV1(RandomAccessFile raf) throws IOException {
        long fileSize = raf.length();
        if (fileSize < 128) {
            return;
        }
        // ID3v1 is at 128 bytes before EOF
        raf.seek(fileSize - 128);
        byte[] tagBytes = new byte[128];
        raf.readFully(tagBytes);

        if (tagBytes[0] != 'T' || tagBytes[1] != 'A' || tagBytes[2] != 'G') {
            // Not a ID3v1
            return;
        }

        if (title == null) {
            title = readId3V1String(tagBytes, 3, 30);
        }
        if (artist == null) {
            artist = readId3V1String(tagBytes, 33, 30);
        }
        if (album == null) {
            album = readId3V1String(tagBytes, 63, 30);
        }
    }

    @SuppressWarnings("SameParameterValue")
    private String readId3V1String(byte[] buffer, int offset, int len) {
        CharBuffer result;
        try {
            result = utf8Decoder.decode(ByteBuffer.wrap(buffer, offset, len));
        } catch (CharacterCodingException e) {
            try {
                result = userDecoder.decode(ByteBuffer.wrap(buffer, offset, len));
            } catch (CharacterCodingException ex) {
                return null;
            }
        }
        // trim() removes \0 padding at the end
        String trimmed = result.toString().trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private void extractV2(RandomAccessFile raf) throws IOException {
        raf.seek(0);
        byte[] header = new byte[10];
        raf.readFully(header);
        if (header[0] != 'I' || header[1] != 'D' || header[2] != '3') {
            // Not a ID3v2
            return;
        }
        int version = header[3];
        if (version != 2 && version != 3 && version != 4) {
            // Bad version
            return;
        }
        int flags = header[5];
        int size = readUnsynchronizedInt(header, 6);
        if (size > kMaxSize) {
            return;
        }

        byte[] tag = new byte[size];
        raf.readFully(tag);

        int firstFrameOffset = 0;
        if (version == 2) {
            // 7 - unsync
            // 6 - compression
            // 5-0 - undefined
            if ((flags & 0x7f) != 0) {
                // compression is unsupported
                return;
            }
            if ((flags & 0x80) != 0) {
                // Unsynchronized tag
                size = fixUnsyncV23(tag, 0, size);
            }
        } else if (version == 3) {
            // 7 - unsync
            // 6 - extended header
            // 5 - experimental
            // 4-0 - undefined
            if ((flags & 0x1f) != 0) {
                return;
            }
            if ((flags & 0x80) != 0) {
                // Unsynchronized tag
                size = fixUnsyncV23(tag, 0, size);
            }
            if ((flags & 0x40) != 0) {
                // Extended header:
                // 4 bytes - ext header len (length of remaining fields in the ext header)
                // 2 bytes - ext flags (bit 7 - crc present, others - undefined)
                // 4 bytes - size of padding
                // 4 bytes (optional) - crc (if present, included in ext header len)
                if (size < 4) {
                    return;
                }
                int extHeaderSize = readIntBe(tag, 0) + 4; // including these 4 bytes themselves
                if (extHeaderSize > size) {
                    return;
                }
                firstFrameOffset = extHeaderSize;
                if (extHeaderSize >= 10) {
                    // padding is present (although it's mandatory according to spec)
                    int paddingSize = readIntBe(tag, 6);
                    if (firstFrameOffset + paddingSize > size) {
                        return;
                    }
                    size -= paddingSize;
                }
            }
        } else {
            // 7 - unsync
            // 6 - extended header
            // 5 - experimental
            // 4 - footer present
            // 3-0 - undefined
            if ((flags & 0x40) != 0) {
                // Extended header
                int extHeaderSize = readUnsynchronizedInt(tag, 0);
                if (extHeaderSize < 6 || extHeaderSize > size) {
                    return;
                }
                firstFrameOffset = extHeaderSize;
            }
            // Unsynchronization is handled per frame in v4
            // We ignore a footer, if present, since it comes at the end of the file
        }

        int remainingTags = 3;
        int offset = firstFrameOffset;
        while (remainingTags != 0) {
            if (version == 2) {
                if (offset + 6 >= size) {
                    return;
                }
                int dataSize = ((tag[offset + 3] & 0xff) << 16) | ((tag[offset + 4] & 0xff) << 8) | (tag[offset + 5] & 0xff);
                if (offset + 6 + dataSize > size) {
                    return;
                }
                if (tag[offset] == 0 && tag[offset + 1] == 0 && tag[offset + 2] == 0) {
                    break;
                } else if (artist == null && tag[offset] == 'T' && tag[offset + 1] == 'P' && tag[offset + 2] == '1') {
                    artist = textFromBytes(tag, offset + 6, dataSize);
                    if (artist != null) remainingTags--;
                } else if (album == null && tag[offset] == 'T' && tag[offset + 1] == 'A' && tag[offset + 2] == 'L') {
                    album = textFromBytes(tag, offset + 6, dataSize);
                    if (album != null) remainingTags--;
                } else if (title == null && tag[offset] == 'T' && tag[offset + 1] == 'T' && tag[offset + 2] == '2') {
                    title = textFromBytes(tag, offset + 6, dataSize);
                    if (title != null) remainingTags--;
                }
                offset += 6 + dataSize;
            } else {
                // V3 and V4
                if (offset + 10 >= size) {
                    return;
                }
                int dataSize;
                if (version == 3) {
                    dataSize = readIntBe(tag, offset + 4);
                } else {
                    dataSize = readUnsynchronizedInt(tag, offset + 4);
                }
                if (offset + 10 + dataSize > size) {
                    return;
                }
                int frameFlags = ((tag[offset + 8] & 0xff) << 8) | (tag[offset + 9] & 0xff);
                if (((version == 3 && (frameFlags & 0xc0) != 0)) || (version == 4 && (frameFlags & 0x0c) != 0)) {
                    // Skip unsupported compressed or encrypted frame
                    offset += 10 + dataSize;
                    continue;
                }
                if (tag[offset] == 0 && tag[offset + 1] == 0 && tag[offset + 2] == 0 && tag[offset + 3] == 0) {
                    break;
                } else if (artist == null && tag[offset] == 'T' && tag[offset + 1] == 'P' && tag[offset + 2] == 'E' && tag[offset + 3] == '1') {
                    OffsetAndSize offsetAndSize = fixUnsyncV4Frame(version, frameFlags, tag, offset + 10, dataSize);
                    artist = textFromBytes(tag, offsetAndSize.offset, offsetAndSize.size);
                    if (artist != null) remainingTags--;
                } else if (album == null && tag[offset] == 'T' && tag[offset + 1] == 'A' && tag[offset + 2] == 'L' && tag[offset + 3] == 'B') {
                    OffsetAndSize offsetAndSize = fixUnsyncV4Frame(version, frameFlags, tag, offset + 10, dataSize);
                    album = textFromBytes(tag, offsetAndSize.offset, offsetAndSize.size);
                    if (album != null) remainingTags--;
                } else if (title == null && tag[offset] == 'T' && tag[offset + 1] == 'I' && tag[offset + 2] == 'T' && tag[offset + 3] == '2') {
                    OffsetAndSize offsetAndSize = fixUnsyncV4Frame(version, frameFlags, tag, offset + 10, dataSize);
                    title = textFromBytes(tag, offsetAndSize.offset, offsetAndSize.size);
                    if (title != null) remainingTags--;
                }
                offset += 10 + dataSize;
            }
        }
    }

    private int readUnsynchronizedInt(byte[] buffer, int offset) {
        return ((buffer[offset] & 0xff) << 21) |
                ((buffer[offset + 1] & 0xff) << 14) |
                ((buffer[offset + 2] & 0xff) << 7) |
                (buffer[offset + 3] & 0xff);
    }

    private int readIntBe(byte[] buffer, int offset) {
        return ((buffer[offset] & 0xff) << 24) |
                ((buffer[offset + 1] & 0xff) << 16) |
                ((buffer[offset + 2] & 0xff) << 8) |
                (buffer[offset + 3] & 0xff);
    }

    private String textFromBytes(byte[] buf, int offset, int len) {
        if (len == 0) return null;
        int encodingType = buf[offset];
        offset++;
        len--;
        CharBuffer result;
        try {
            if (encodingType == 0) {
                // ISO 8859-1, but read using user encoding
                result = userDecoder.decode(ByteBuffer.wrap(buf, offset, len));
            } else if (encodingType == 1) {
                // UTF-16 with BOM
                result = utf16BomDecoder.decode(ByteBuffer.wrap(buf, offset, len));
            } else if (encodingType == 2) {
                // UTF-16BE without BOM
                result = utf16BeDecoder.decode(ByteBuffer.wrap(buf, offset, len));
            } else if (encodingType == 3) {
                // UTF-8
                result = utf8Decoder.decode(ByteBuffer.wrap(buf, offset, len));
            } else {
                return null;
            }
        } catch (CharacterCodingException ex) {
            return null;
        }
        // trim() removes \0 padding at the end
        String trimmed = result.toString().trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    int fixUnsyncV23(byte[] buf, int offset, int size) {
        int i = offset;
        int end = offset + size;
        for (int j = offset; j < end; j++) {
            buf[i++] = buf[j];
            if (j + 1 < end && (buf[j] & 0xff) == 0xff && buf[j + 1] == 0x00) {
                j++;
            }
        }
        return i - offset;
    }

    static class OffsetAndSize {
        int offset;
        int size;
        OffsetAndSize(int offset, int size) {
            this.offset = offset;
            this.size = size;
        }
    }
    OffsetAndSize fixUnsyncV4Frame(int version, int frameFlags, byte[] buf, int offset, int size) {
        if (version == 3) {
            return new OffsetAndSize(offset, size);
        }

        if ((frameFlags & 0x01) != 0) {
            // Remove data length indicator
            if (size < 4) {
                return new OffsetAndSize(offset, size);
            }
            offset += 4;
            size -= 4;
        }

        if ((frameFlags & 0x02) == 0) {
            // No unsynchronization applied, so nothing to fix
            return new OffsetAndSize(offset, size);
        }

        size = fixUnsyncV23(buf, offset, size);
        return new OffsetAndSize(offset, size);
    }
}
