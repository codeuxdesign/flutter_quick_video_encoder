package com.lib.flutter_quick_video_encoder;

import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import org.junit.Assume;
import org.junit.Test;

/**
 * Renders one real HDR frame through {@link ClipColor} and writes a PNG.
 *
 * <p>Temporary diagnostic. The point is to compare our BT.2020 + HLG conversion
 * against what AVFoundation produces for the same frame, because the two
 * platforms have to agree — the Apple path converts inside AVFoundation and the
 * Android path converts here, so a divergence is a film that looks different
 * depending on the phone it was made on.
 *
 * <p>Input is the 8-bit Y/Cb/Cr that `copyPlane` would hand over: a bit-depth
 * reduction of the decoded 10-bit planes with no color conversion of any kind.
 */
public class ClipColorRealFrameTest {

    private static final String DIR = System.getenv("FRAME_DIR");
    private static final int W = 960;
    private static final int H = 540;

    @Test
    public void convertsARealHlgFrame() throws Exception {
        Assume.assumeTrue("set FRAME_DIR to run this diagnostic", DIR != null);
        final File raw = new File(DIR, "frame_yuv420p.raw");
        Assume.assumeTrue("no frame_yuv420p.raw in " + DIR, raw.exists());

        final byte[] all = Files.readAllBytes(raw.toPath());
        final int lumaSize = W * H;
        final int chromaSize = (W / 2) * (H / 2);
        assertTrue("unexpected raw size " + all.length,
                all.length >= lumaSize + 2 * chromaSize);

        final byte[] luma = new byte[lumaSize];
        final byte[] cb = new byte[chromaSize];
        final byte[] cr = new byte[chromaSize];
        System.arraycopy(all, 0, luma, 0, lumaSize);
        System.arraycopy(all, lumaSize, cb, 0, chromaSize);
        System.arraycopy(all, lumaSize + chromaSize, cr, 0, chromaSize);

        writeWith(luma, cb, cr,
                new ClipColor(ClipColor.STANDARD_BT2020, ClipColor.TRANSFER_HLG, false),
                new File(DIR, "ours_bt2020_hlg.ppm"));

        // The same samples decoded as if the file had said nothing: Rec.709 SDR.
        // This is what a player does with an untagged file, and what our own
        // reader falls back to. Kept as the contrast image — it is the picture
        // of the question "can this be done without knowing the source space".
        writeWith(luma, cb, cr,
                new ClipColor(ClipColor.STANDARD_BT709, ClipColor.TRANSFER_SDR, false),
                new File(DIR, "ours_as_if_rec709.ppm"));
    }

    /** Binary PPM, because the Android unit-test classpath has no ImageIO. */
    private static void writeWith(byte[] luma, byte[] cb, byte[] cr,
                                  ClipColor color, File out) throws Exception {
        final ClipFrame frame = new ClipFrame(luma, cb, cr, W, H, color, 0L, false);
        try (OutputStream os = new FileOutputStream(out)) {
            os.write(("P6\n" + W + " " + H + "\n255\n").getBytes(StandardCharsets.US_ASCII));
            final byte[] row = new byte[W * 3];
            for (int y = 0; y < H; y++) {
                for (int x = 0; x < W; x++) {
                    final int rgb = frame.rgbAt(x, y);
                    row[x * 3] = (byte) ((rgb >> 16) & 0xFF);
                    row[x * 3 + 1] = (byte) ((rgb >> 8) & 0xFF);
                    row[x * 3 + 2] = (byte) (rgb & 0xFF);
                }
                os.write(row);
            }
        }
        System.out.println("WROTE " + out.getAbsolutePath() + " " + color.describe());
    }
}
