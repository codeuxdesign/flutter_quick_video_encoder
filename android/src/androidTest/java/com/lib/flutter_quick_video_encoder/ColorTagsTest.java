package com.lib.flutter_quick_video_encoder;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.media.MediaCodecInfo;
import android.media.MediaExtractor;
import android.media.MediaFormat;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import java.io.File;

import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * That an exported film says which matrix it was written with.
 *
 * <p>**The tags are the part that breaks silently.** The encoder converted with
 * Rec.601 coefficients and declared no color at all, so every player treated an
 * HD stream as Rec.709 — the whole film through a matrix it was not encoded
 * with. Greens pulled one way, reds the other, subtly and everywhere. It
 * survived for as long as it did because there was nothing to compare against,
 * and a slightly-off green looks like a slightly-off green.
 *
 * <p>It matters beyond a render being faithful: a brand mark is composited into
 * a published video and read back out of the `.mp4` to check its color, and a
 * mark burnt into a film cannot be patched. A discrepancy of this size is
 * exactly the size that gets explained away as codec noise.
 *
 * <p>So this asserts it twice, because the two can fail separately. **The
 * format carries the tags** — which a change deleting three lines would break —
 * and **a file encoded with that format still reports them when read back**,
 * which the platform could drop at the muxer without anyone noticing.
 */
@RunWith(AndroidJUnit4.class)
public class ColorTagsTest {

    private static final int WIDTH = 320;
    private static final int HEIGHT = 240;

    @Test
    public void theEncoderFormatStatesRec709LimitedRange() {
        final MediaFormat format = EncoderFormat.video(
                WIDTH, HEIGHT, 2_000_000, 30,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible);

        assertTrue("no color standard on the encoder format",
                format.containsKey(MediaFormat.KEY_COLOR_STANDARD));
        assertEquals(MediaFormat.COLOR_STANDARD_BT709,
                format.getInteger(MediaFormat.KEY_COLOR_STANDARD));
        assertEquals(MediaFormat.COLOR_RANGE_LIMITED,
                format.getInteger(MediaFormat.KEY_COLOR_RANGE));
        assertEquals(MediaFormat.COLOR_TRANSFER_SDR_VIDEO,
                format.getInteger(MediaFormat.KEY_COLOR_TRANSFER));
    }

    /**
     * The half the format alone cannot promise: that the tags survive being
     * written into a container and read back out of it.
     *
     * <p>Encoded through {@link EncoderFormat} rather than through values typed
     * here. Stating the tags in the test would have made it a test of the
     * platform's muxer — green no matter what this plugin declares, which is the
     * half that actually regressed.
     */
    @Test
    public void anEncodedFileStillReportsThemWhenReadBack() throws Exception {
        final File film = new File(
                InstrumentationRegistry.getInstrumentation().getTargetContext().getCacheDir(),
                "color-tags.mp4");
        try {
            SyntheticClip.writeWithFormat(film, EncoderFormat.MIME, WIDTH, HEIGHT, 6,
                    EncoderFormat.video(WIDTH, HEIGHT, 2_000_000, SyntheticClip.FPS,
                            MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible));

            final MediaExtractor extractor = new MediaExtractor();
            try {
                extractor.setDataSource(film.getAbsolutePath());
                MediaFormat track = null;
                for (int i = 0; i < extractor.getTrackCount(); i++) {
                    final MediaFormat candidate = extractor.getTrackFormat(i);
                    final String mime = candidate.getString(MediaFormat.KEY_MIME);
                    if (mime != null && mime.startsWith("video/")) {
                        track = candidate;
                        break;
                    }
                }
                assertTrue("no video track in the encoded file", track != null);

                assertTrue("the encoded file declares no color standard",
                        track.containsKey(MediaFormat.KEY_COLOR_STANDARD));
                // Not containsKey-then-guess: with the tag left off the format,
                // this device writes COLOR_STANDARD_BT601_NTSC (4) into the file
                // rather than nothing. The wrong matrix is asserted, not absent.
                assertEquals(MediaFormat.COLOR_STANDARD_BT709,
                        track.getInteger(MediaFormat.KEY_COLOR_STANDARD));
                assertEquals(MediaFormat.COLOR_RANGE_LIMITED,
                        track.getInteger(MediaFormat.KEY_COLOR_RANGE));
            } finally {
                extractor.release();
            }
        } finally {
            film.delete();
        }
    }
}
