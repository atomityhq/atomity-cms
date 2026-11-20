package io.atomity.generalcms.media;

import io.atomity.generalcms.media.service.ImageInspector;
import io.atomity.generalcms.shared.ApiException;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HexFormat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ImageInspectorTest {

    @Test
    void readsPngJpegAndGif() {
        assertThat(ImageInspector.inspect(TestImages.encode("png", 120, 45)))
                .isEqualTo(new ImageInspector.ImageInfo("image/png", "png", 120, 45));
        assertThat(ImageInspector.inspect(TestImages.encode("jpg", 640, 480)))
                .isEqualTo(new ImageInspector.ImageInfo("image/jpeg", "jpg", 640, 480));
        assertThat(ImageInspector.inspect(TestImages.encode("gif", 33, 17)))
                .isEqualTo(new ImageInspector.ImageInfo("image/gif", "gif", 33, 17));
    }

    @Test
    void readsWebp() {
        assertThat(ImageInspector.inspect(TestImages.webpExtended(1200, 630)))
                .isEqualTo(new ImageInspector.ImageInfo("image/webp", "webp", 1200, 630));
    }

    @Test
    void readsShortLosslessWebp() {
        byte[] webp = shortLosslessWebp();

        assertThat(webp).hasSize(28);
        assertThat(ImageInspector.inspect(webp))
                .isEqualTo(new ImageInspector.ImageInfo("image/webp", "webp", 1, 1));
    }

    @Test
    void readsLossyWebp() {
        assertThat(ImageInspector.inspect(lossyWebp()))
                .isEqualTo(new ImageInspector.ImageInfo("image/webp", "webp", 3, 2));
    }

    @Test
    void readsLosslessWebpWithOddSizedChunkAndPadding() {
        assertThat(ImageInspector.inspect(paddedLosslessWebp()))
                .isEqualTo(new ImageInspector.ImageInfo("image/webp", "webp", 1, 1));
    }

    @Test
    void rejectsTruncatedWebpFiles() {
        for (byte[] webp : new byte[][] {lossyWebp(), shortLosslessWebp(), TestImages.webpExtended(1200, 630)}) {
            for (int length = 0; length < webp.length; length++) {
                byte[] truncated = Arrays.copyOf(webp, length);
                assertThatThrownBy(() -> ImageInspector.inspect(truncated))
                        .as("WebP truncated to %d of %d bytes", length, webp.length)
                        .isInstanceOf(ApiException.class)
                        .extracting("errorCode").isEqualTo("UNSUPPORTED_TYPE");
            }
        }
    }

    @Test
    void rejectsChunksTooShortForTheirVariantHeaders() {
        byte[][] variants = {lossyWebp(), shortLosslessWebp(), TestImages.webpExtended(1200, 630)};
        int[] lengths = {28, 24, 28};
        for (int i = 0; i < variants.length; i++) {
            byte[] truncated = Arrays.copyOf(variants[i], lengths[i]);
            // Keep the container/chunk lengths consistent, but omit required dimension bytes.
            truncated[4] = (byte) (truncated.length - 8);
            truncated[16] = (byte) (truncated.length - 20);
            assertThatThrownBy(() -> ImageInspector.inspect(truncated))
                    .isInstanceOf(ApiException.class)
                    .extracting("errorCode").isEqualTo("UNSUPPORTED_TYPE");
        }
    }

    @Test
    void rejectsMalformedWebpSignaturesAndUnknownVariants() {
        for (int offset : new int[] {0, 8, 12}) {
            byte[] malformed = shortLosslessWebp();
            malformed[offset] = 'X';
            assertThatThrownBy(() -> ImageInspector.inspect(malformed))
                    .isInstanceOf(ApiException.class)
                    .extracting("errorCode").isEqualTo("UNSUPPORTED_TYPE");
        }
    }

    @Test
    void rejectsInvalidLosslessWebpSignature() {
        for (int signature : new int[] {0, 0x2E, 0xFF}) {
            byte[] malformed = shortLosslessWebp();
            malformed[20] = (byte) signature;
            assertThatThrownBy(() -> ImageInspector.inspect(malformed))
                    .as("VP8L signature 0x%02X", signature)
                    .isInstanceOf(ApiException.class)
                    .extracting("errorCode").isEqualTo("UNSUPPORTED_TYPE");
        }
    }

    @Test
    void rejectsNonzeroLosslessWebpVersion() {
        for (int version = 1; version <= 7; version++) {
            byte[] malformed = shortLosslessWebp();
            malformed[24] |= (byte) (version << 5);
            assertThatThrownBy(() -> ImageInspector.inspect(malformed))
                    .as("VP8L version %d", version)
                    .isInstanceOf(ApiException.class)
                    .extracting("errorCode").isEqualTo("UNSUPPORTED_TYPE");
        }
    }

    @Test
    void rejectsOddDeclaredRiffSize() {
        byte[] malformed = Arrays.copyOf(shortLosslessWebp(), 29);
        malformed[4] = 21; // The declared container fits the bytes but has an invalid odd size.
        assertThatThrownBy(() -> ImageInspector.inspect(malformed))
                .isInstanceOf(ApiException.class)
                .extracting("errorCode").isEqualTo("UNSUPPORTED_TYPE");
    }

    @Test
    void rejectsWebpLengthsOutsideTheContainer() {
        byte[] smallContainer = shortLosslessWebp();
        smallContainer[4] = 18; // RIFF ends at byte 26, before the chunk ends at byte 28.
        byte[] largeChunk = shortLosslessWebp();
        largeChunk[16] = 10;
        byte[] unsignedContainer = shortLosslessWebp();
        Arrays.fill(unsignedContainer, 4, 8, (byte) 0xFF);
        byte[] unsignedChunk = shortLosslessWebp();
        Arrays.fill(unsignedChunk, 16, 20, (byte) 0xFF);
        byte[] missingPadding = Arrays.copyOf(paddedLosslessWebp(), 35);
        missingPadding[4] = 27; // The odd-sized chunk needs a padding byte outside this container.
        for (byte[] malformed : new byte[][] {smallContainer, largeChunk, unsignedContainer, unsignedChunk, missingPadding}) {
            assertThatThrownBy(() -> ImageInspector.inspect(malformed))
                    .isInstanceOf(ApiException.class)
                    .extracting("errorCode").isEqualTo("UNSUPPORTED_TYPE");
        }
    }

    private static byte[] shortLosslessWebp() {
        return HexFormat.of().parseHex("5249464614000000574542505650384c080000002f00000010888808");
    }

    private static byte[] paddedLosslessWebp() {
        return HexFormat.of().parseHex("524946461c000000574542505650384c0f0000002f000000000710fd8ffe0722a2ff0100");
    }

    private static byte[] lossyWebp() {
        // A real 3x2 lossy image, including its compressed payload.
        return HexFormat.of().parseHex(
                "524946463c000000574542505650382030000000d001009d012a0300020001402625a00274ba"
                        + "01f80003b000fef2eb7ffcd815cd73eff7ffd2e0fd2e0fd2e0ffd2900000");
    }

    @Test
    void identifiesByContentNotByName() {
        byte[] svg = "<svg xmlns=\"http://www.w3.org/2000/svg\"><script>alert(1)</script></svg>".getBytes(StandardCharsets.UTF_8);
        assertThatThrownBy(() -> ImageInspector.inspect(svg))
                .isInstanceOf(ApiException.class)
                .extracting("errorCode").isEqualTo("UNSUPPORTED_TYPE");
    }

    @Test
    void rejectsFilesThatOnlyStartLikeAGif() {
        byte[] notAGif = "GIF8-this is plain text, not an image".getBytes(StandardCharsets.UTF_8);
        assertThatThrownBy(() -> ImageInspector.inspect(notAGif))
                .isInstanceOf(ApiException.class)
                .extracting("errorCode").isEqualTo("UNSUPPORTED_TYPE");
    }

    @Test
    void acceptsBothGifVersions() {
        byte[] gif = TestImages.encode("gif", 33, 17);
        byte[] gif87 = gif.clone();
        gif87[4] = '7';
        assertThat(ImageInspector.inspect(gif87))
                .isEqualTo(new ImageInspector.ImageInfo("image/gif", "gif", 33, 17));
    }

    @Test
    void rejectsPngSignatureWithoutAnIhdrChunk() {
        byte[] png = TestImages.encode("png", 10, 10);
        byte[] broken = png.clone();
        broken[12] = 'X'; // IHDR → XHDR
        assertThatThrownBy(() -> ImageInspector.inspect(broken))
                .isInstanceOf(ApiException.class)
                .extracting("errorCode").isEqualTo("UNSUPPORTED_TYPE");
    }

    @Test
    void rejectsTruncatedHeaders() {
        byte[] png = TestImages.encode("png", 10, 10);
        assertThatThrownBy(() -> ImageInspector.inspect(java.util.Arrays.copyOf(png, 12)))
                .isInstanceOf(ApiException.class);
    }
}
