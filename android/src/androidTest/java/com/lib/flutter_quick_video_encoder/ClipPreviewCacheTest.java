package com.lib.flutter_quick_video_encoder;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.content.Context;
import android.media.MediaFormat;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import java.io.File;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * The preview path against a real decoder, which is the half no laptop reaches.
 *
 * <p>{@code ClipPreviewTest} proves the downsample and the conversion agree with
 * the composite, using a frame built by hand. What it cannot touch is everything
 * between a file and that frame: MediaExtractor, MediaCodec, the crop rectangle,
 * the row and pixel strides, and — the one this class exists for — a *cached*
 * reader being scrubbed backwards and forwards.
 *
 * <p><b>A reader that ignores the seek and answers every scrub position with the
 * same keyframe produces a perfectly plausible preview.</b> That is the failure
 * this feature would otherwise ship with, and it is invisible by inspection, so
 * the assertions here are all of the form "the cache said what a reader opened
 * fresh for this one instant says" — comparing an answer against an independent
 * answer rather than against a picture that looks fine.
 *
 * <p>An emulator runs software codecs, so passing here says the logic is right
 * and says nothing about a hardware decoder's color formats, its concurrent
 * instance limit or 4K HEVC.
 */
@RunWith(AndroidJUnit4.class)
public class ClipPreviewCacheTest {

    private static final int MAX_EDGE = 64;

    private static File clip;

    @BeforeClass
    public static void writeTheClip() throws Exception {
        final Context context =
                InstrumentationRegistry.getInstrumentation().getTargetContext();
        clip = new File(context.getCacheDir(), "synthetic-preview.mp4");
        SyntheticClip.write(clip,
                MediaFormat.COLOR_STANDARD_BT709,
                MediaFormat.COLOR_TRANSFER_SDR_VIDEO,
                MediaFormat.COLOR_RANGE_LIMITED);
        assertTrue("the synthetic clip was not written", clip.length() > 0);
    }

    @AfterClass
    public static void removeTheClip() {
        if (clip != null) {
            clip.delete();
        }
    }

    /** Blocks on one preview, the way a `Future` off the method channel would. */
    private static ClipPreview.Image preview(ClipPreviewCache cache, long timeUs)
            throws InterruptedException {
        final AtomicReference<ClipPreview.Image> held = new AtomicReference<>();
        final CountDownLatch done = new CountDownLatch(1);
        cache.frameAt(clip.getAbsolutePath(), timeUs, MAX_EDGE, image -> {
            held.set(image);
            done.countDown();
        });
        assertTrue("the preview never came back for " + timeUs + "us",
                done.await(30, TimeUnit.SECONDS));
        return held.get();
    }

    /** The same question asked of a reader that has never seen another instant. */
    private static ClipPreview.Image fresh(long timeUs) throws Exception {
        final ClipReader reader = new ClipReader(clip.getAbsolutePath());
        try {
            return ClipPreview.from(reader.frameAtTime(timeUs), MAX_EDGE);
        } finally {
            reader.close();
        }
    }

    /** Which frame of the clip a preview is showing, by its own gray. */
    private static int frameIndexOf(ClipPreview.Image image, String what) {
        assertNotNull(what + " came back null", image);
        final int center = ((image.height / 2) * image.width + image.width / 2) * 4;
        final int gray = image.rgba[center] & 0xFF;
        final int index = SyntheticClip.frameOf(gray);
        if (index < 0) {
            fail(what + " is gray " + gray + ", which belongs to no frame of the clip");
        }
        return index;
    }

    /**
     * Scrubbing back and forth must answer like a fresh reader every time.
     *
     * <p>The instants are deliberately out of order, and deliberately include a
     * long jump forward, a jump back past the start of the previous read, and a
     * pair a single frame apart. A cache that reused a reader without seeking, or
     * that seeked but landed on the preceding sync sample and stopped, would pass
     * a monotonic walk and fail here.
     */
    @Test
    public void scrubbingAnswersLikeAReaderOpenedForThatInstantAlone() throws Exception {
        final long[] instants = {
                4_050_000L,   // near the end, cold
                550_000L,     // a long way back
                4_550_000L,   // and forward again
                4_650_000L,   // one frame on from that
                50_000L,      // all the way to the beginning
                2_250_000L,   // and into the middle
        };

        final ClipPreviewCache cache = new ClipPreviewCache();
        try {
            for (final long at : instants) {
                final ClipPreview.Image scrubbed = preview(cache, at);
                final ClipPreview.Image independent = fresh(at);
                assertNotNull("no preview at " + at + "us", scrubbed);
                assertNotNull("no fresh frame at " + at + "us", independent);
                assertEquals("size at " + at + "us",
                        independent.width, scrubbed.width);
                assertArrayEquals(
                        "the cached reader disagreed with a fresh one at " + at + "us "
                                + "(frame " + frameIndexOf(scrubbed, "scrubbed")
                                + " against " + frameIndexOf(independent, "fresh") + ")",
                        independent.rgba, scrubbed.rgba);
            }
        } finally {
            cache.release();
        }
    }

    /**
     * And the frames it landed on are the frames those instants belong to.
     *
     * <p>The assertion above would still pass if both readers were wrong in the
     * same way — they share `ClipReader`, so a seek that consistently landed a
     * frame early would agree with itself. This one names the answer.
     */
    @Test
    public void eachInstantShowsTheFrameThatCoversIt() throws Exception {
        final ClipPreviewCache cache = new ClipPreviewCache();
        try {
            assertEquals(40, frameIndexOf(preview(cache, 4_050_000L), "4.05s"));
            assertEquals(5, frameIndexOf(preview(cache, 550_000L), "0.55s"));
            assertEquals(6, frameIndexOf(preview(cache, 650_000L), "0.65s"));
            assertEquals(0, frameIndexOf(preview(cache, 50_000L), "0.05s"));
            assertEquals(SyntheticClip.FRAMES - 1,
                    frameIndexOf(preview(cache, 60_000_000L), "past the end"));
        } finally {
            cache.release();
        }
    }

    /**
     * Two instants have to be two pictures.
     *
     * <p>Cheap, and it is the exact shape of the defect worth fearing: a reader
     * that ignores the seek returns the same keyframe forever, and every one of
     * those frames looks like a photograph of the clip.
     */
    @Test
    public void twoInstantsAreTwoPictures() throws Exception {
        final ClipPreviewCache cache = new ClipPreviewCache();
        try {
            final byte[] early = preview(cache, 550_000L).rgba;
            final byte[] late = preview(cache, 3_550_000L).rgba;
            assertTrue("two instants a full three seconds apart came back identical",
                    !Arrays.equals(early, late));
        } finally {
            cache.release();
        }
    }

    /**
     * The dimensions come off the decoded frame, crop rectangle and all.
     *
     * <p>`SyntheticClip` is 300x188 on purpose — neither dimension is a multiple
     * of sixteen, so a decoder hands back a padded buffer and signals the picture
     * through a crop. A preview that reported the buffer's size, or the
     * container's, would hand the caller bytes it lays out sheared.
     */
    @Test
    public void theReportedSizeIsTheCroppedFrameScaled() throws Exception {
        final ClipPreviewCache cache = new ClipPreviewCache();
        try {
            final ClipPreview.Image image = preview(cache, 550_000L);
            assertEquals(MAX_EDGE, image.width);
            assertEquals(Math.round(188.0 * MAX_EDGE / 300.0), image.height);
            assertEquals(image.width * image.height * 4, image.rgba.length);
        } finally {
            cache.release();
        }
    }

    /** A file that is not a video answers null rather than throwing. */
    @Test
    public void somethingUnreadableAnswersNull() throws Exception {
        final Context context =
                InstrumentationRegistry.getInstrumentation().getTargetContext();
        final File notAVideo = new File(context.getCacheDir(), "not-a-video.mp4");
        try (java.io.FileOutputStream out = new java.io.FileOutputStream(notAVideo)) {
            out.write(new byte[]{1, 2, 3, 4, 5, 6, 7, 8});
        }

        final ClipPreviewCache cache = new ClipPreviewCache();
        final AtomicReference<ClipPreview.Image> held = new AtomicReference<>();
        final CountDownLatch done = new CountDownLatch(1);
        try {
            cache.frameAt(notAVideo.getAbsolutePath(), 0L, MAX_EDGE, image -> {
                held.set(image);
                done.countDown();
            });
            assertTrue("nothing came back at all", done.await(30, TimeUnit.SECONDS));
            assertNull("a file with no video track must answer null", held.get());
        } finally {
            cache.release();
            notAVideo.delete();
        }
    }

    /**
     * A released cache keeps working, because releasing is not closing.
     *
     * <p>`releaseClipPreviews` hands the decoders back; the screen that called it
     * may well still be alive, and an export releases previews out from under a
     * screen that never asked. Either way the next scrub has to reopen rather
     * than fail.
     */
    @Test
    public void aReleasedCacheReopensOnTheNextScrub() throws Exception {
        final ClipPreviewCache cache = new ClipPreviewCache();
        try {
            final byte[] before = preview(cache, 2_050_000L).rgba;
            cache.release();
            final ClipPreview.Image after = preview(cache, 2_050_000L);
            assertNotNull("the cache did not reopen after being released", after);
            assertArrayEquals("the same instant must survive a release",
                    before, after.rgba);
        } finally {
            cache.release();
        }
    }

    /**
     * More clips than the cache holds, still answering correctly.
     *
     * <p>Eviction is the case where a preview would come back from the wrong
     * file, and nothing about the picture would say so.
     */
    @Test
    public void evictionDoesNotMixUpClips() throws Exception {
        final Context context =
                InstrumentationRegistry.getInstrumentation().getTargetContext();
        final File second = new File(context.getCacheDir(), "synthetic-preview-2.mp4");
        // A different *aspect*, so a frame from the wrong file cannot be mistaken
        // for one from the right file even if their grays happened to collide.
        // 320x180 rather than something smaller: the Galaxy S24 Ultra's AVC
        // encoder throws `CodecException` from `native_start` for 200x120, which
        // fails inside the fixture and reads like a defect in the code under
        // test. This is the encoder's minimum, not the reader's.
        SyntheticClip.write(second, "video/avc", 320, 180, SyntheticClip.FRAMES,
                MediaFormat.COLOR_STANDARD_BT709,
                MediaFormat.COLOR_TRANSFER_SDR_VIDEO,
                MediaFormat.COLOR_RANGE_LIMITED);

        final ClipPreviewCache cache = new ClipPreviewCache();
        final AtomicReference<ClipPreview.Image> held = new AtomicReference<>();
        try {
            for (int round = 0; round < 3; round++) {
                final ClipPreview.Image first = preview(cache, 1_050_000L);
                assertEquals("round " + round + " kept the first clip's shape",
                        Math.round(188.0 * MAX_EDGE / 300.0), first.height);

                final CountDownLatch done = new CountDownLatch(1);
                cache.frameAt(second.getAbsolutePath(), 1_050_000L, MAX_EDGE, image -> {
                    held.set(image);
                    done.countDown();
                });
                assertTrue(done.await(30, TimeUnit.SECONDS));
                assertNotNull("the second clip did not decode", held.get());
                assertEquals("round " + round + " kept the second clip's shape",
                        Math.round(180.0 * MAX_EDGE / 320.0), held.get().height);
            }
        } finally {
            cache.release();
            second.delete();
        }
    }
}
