package io.atomity.generalcms.media.service;

import io.atomity.generalcms.shared.ApiException;
import org.springframework.http.HttpStatus;

/**
 * Identifies an uploaded image from its leading bytes (never trusting the client's
 * Content-Type or file name) and reads its pixel dimensions from the header.
 *
 * Headers only: the image is never decoded, so a small file claiming huge
 * dimensions can't exhaust memory, and WebP needs no extra ImageIO plugin.
 */
public final class ImageInspector {

    public record ImageInfo(String contentType, String extension, int width, int height) {
    }

    private ImageInspector() {
    }

    public static ImageInfo inspect(byte[] data) {
        ImageInfo info = null;
        if (startsWith(data, 0, 0xFF, 0xD8, 0xFF)) {
            info = jpeg(data);
        } else if (startsWith(data, 0, 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A)) {
            info = png(data);
        } else if ((startsWith(data, 0, 'G', 'I', 'F', '8', '7', 'a') || startsWith(data, 0, 'G', 'I', 'F', '8', '9', 'a'))
                && data.length >= 10) {
            info = new ImageInfo("image/gif", "gif", u16le(data, 6), u16le(data, 8));
        } else if (startsWith(data, 0, 'R', 'I', 'F', 'F') && startsWith(data, 8, 'W', 'E', 'B', 'P')) {
            info = webp(data);
        }
        if (info == null) {
            throw new ApiException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "UNSUPPORTED_TYPE",
                    "Only JPEG, PNG, WebP and GIF images are supported");
        }
        if (info.width() <= 0 || info.height() <= 0) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_IMAGE", "The file is not a readable image");
        }
        return info;
    }

    private static ImageInfo png(byte[] d) {
        // The first chunk of every PNG is IHDR; the width and height sit right after its tag.
        if (d.length < 24 || !startsWith(d, 12, 'I', 'H', 'D', 'R')) return null;
        return new ImageInfo("image/png", "png", u32be(d, 16), u32be(d, 20));
    }

    private static ImageInfo webp(byte[] d) {
        if (d.length < 20) return null;
        long fileSize = 8 + u32le(d, 4);
        long chunkSize = u32le(d, 16);
        // The declared container and first chunk (including padding) must fit the supplied bytes.
        if ((fileSize & 1) != 0 || fileSize > d.length
                || 20 + chunkSize + (chunkSize & 1) > fileSize) return null;
        if (startsWith(d, 12, 'V', 'P', '8', ' ')) {
            if (chunkSize < 10) return null;
            return new ImageInfo("image/webp", "webp", u16le(d, 26) & 0x3FFF, u16le(d, 28) & 0x3FFF);
        }
        if (startsWith(d, 12, 'V', 'P', '8', 'L')) {
            if (chunkSize < 5 || d[20] != 0x2F || (d[24] & 0xE0) != 0) return null;
            int b0 = d[21] & 0xFF, b1 = d[22] & 0xFF, b2 = d[23] & 0xFF, b3 = d[24] & 0xFF;
            int width = 1 + (((b1 & 0x3F) << 8) | b0);
            int height = 1 + (((b3 & 0x0F) << 10) | (b2 << 2) | ((b1 & 0xC0) >> 6));
            return new ImageInfo("image/webp", "webp", width, height);
        }
        if (startsWith(d, 12, 'V', 'P', '8', 'X')) {
            if (chunkSize < 10) return null;
            return new ImageInfo("image/webp", "webp", 1 + u24le(d, 24), 1 + u24le(d, 27));
        }
        return null;
    }

    /** Walks the JPEG segments to the first start-of-frame marker, which holds the dimensions. */
    private static ImageInfo jpeg(byte[] d) {
        int i = 2;
        while (i + 9 < d.length) {
            if ((d[i] & 0xFF) != 0xFF) return null;
            int marker = d[i + 1] & 0xFF;
            if (marker == 0xFF) { // fill byte
                i++;
                continue;
            }
            if (marker == 0xD8 || marker == 0x01 || (marker >= 0xD0 && marker <= 0xD7)) { // no payload
                i += 2;
                continue;
            }
            int length = u16be(d, i + 2);
            boolean startOfFrame = marker >= 0xC0 && marker <= 0xCF && marker != 0xC4 && marker != 0xC8 && marker != 0xCC;
            if (startOfFrame) {
                return new ImageInfo("image/jpeg", "jpg", u16be(d, i + 7), u16be(d, i + 5));
            }
            if (length < 2) return null;
            i += 2 + length;
        }
        return null;
    }

    private static boolean startsWith(byte[] data, int offset, int... expected) {
        if (data.length < offset + expected.length) return false;
        for (int i = 0; i < expected.length; i++) {
            if ((data[offset + i] & 0xFF) != expected[i]) return false;
        }
        return true;
    }

    private static int u16le(byte[] d, int i) {
        return (d[i] & 0xFF) | (d[i + 1] & 0xFF) << 8;
    }

    private static int u24le(byte[] d, int i) {
        return (d[i] & 0xFF) | (d[i + 1] & 0xFF) << 8 | (d[i + 2] & 0xFF) << 16;
    }

    private static long u32le(byte[] d, int i) {
        return (d[i] & 0xFFL) | (d[i + 1] & 0xFFL) << 8
                | (d[i + 2] & 0xFFL) << 16 | (d[i + 3] & 0xFFL) << 24;
    }

    private static int u16be(byte[] d, int i) {
        return (d[i] & 0xFF) << 8 | (d[i + 1] & 0xFF);
    }

    private static int u32be(byte[] d, int i) {
        return (d[i] & 0xFF) << 24 | (d[i + 1] & 0xFF) << 16 | (d[i + 2] & 0xFF) << 8 | (d[i + 3] & 0xFF);
    }
}
