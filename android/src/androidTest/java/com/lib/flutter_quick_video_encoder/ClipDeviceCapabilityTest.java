package com.lib.flutter_quick_video_encoder;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.util.Log;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import java.io.File;

import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * What this particular device can actually do with the corpus.
 *
 * <p>Separate from {@link ClipReaderTest} because these are questions about the
 * hardware rather than about the code: whether a 4K HEVC clip has a decoder at
 * all, how many of them may run at once, and whether the color metadata a drone
 * writes survives a round trip through this platform's encoder and muxer.
 *
 * <p>Every one of them is a question the export has to have an answer for before
 * it starts rendering, and every one of them has a different answer on the next
 * phone.
 */
@RunWith(AndroidJUnit4.class)
public class ClipDeviceCapabilityTest {

    private static final String TAG = "[FQVE-Android]";

    /** The corpus clip's shape: 4K HEVC, ten bits, the profile a drone writes. */
    private static MediaFormat corpusLikeFormat() {
        final MediaFormat format =
                MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_HEVC, 3840, 2160);
        format.setInteger(MediaFormat.KEY_PROFILE,
                MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10);
        format.setInteger(MediaFormat.KEY_LEVEL,
                MediaCodecInfo.CodecProfileLevel.HEVCMainTierLevel51);
        return format;
    }

    /**
     * The question `checkClipsDecodable` exists to answer, asked of the shape the
     * corpus actually is. A device with no answer here is one where the export
     * must refuse before it renders rather than after.
     */
    @Test
    public void thisDeviceHasADecoderForTheCorpusClip() {
        final String decoder = ClipReader.chooseDecoder(
                corpusLikeFormat(), MediaFormat.MIMETYPE_VIDEO_HEVC);
        Log.i(TAG, "CAPABILITY 4K HEVC Main10 L5.1 decoder=" + decoder);
        assertNotNull("no decoder for 4K HEVC Main10 — the corpus clip cannot play here",
                decoder);
    }

    /**
     * How many clips may be open at once before the platform refuses.
     *
     * <p>`ClipCompositor` caps itself at three and evicts idle readers, and this
     * is where that number gets checked against something real. It has no Apple
     * counterpart at all — AVFoundation readers are cheap and a tour with six
     * clips simply opens six.
     */
    @Test
    public void concurrentDecoderInstancesAreEnoughForTheCap() {
        final String name = ClipReader.chooseDecoder(
                corpusLikeFormat(), MediaFormat.MIMETYPE_VIDEO_HEVC);
        assertNotNull(name);

        int instances = -1;
        for (final MediaCodecInfo info : new android.media.MediaCodecList(
                android.media.MediaCodecList.ALL_CODECS).getCodecInfos()) {
            if (info.getName().equals(name)) {
                instances = info.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_HEVC)
                        .getMaxSupportedInstances();
            }
        }
        Log.i(TAG, "CAPABILITY " + name + " maxSupportedInstances=" + instances);
        assertTrue("this device allows " + instances + " concurrent HEVC decoders,"
                        + " which is fewer than the compositor's cap of three",
                instances >= 3);
    }

    /**
     * The metadata the whole HDR path depends on, round-tripped through this
     * platform's own encoder and muxer.
     *
     * <p>The corpus clip is BT.2020 with an HLG transfer, and the conversion only
     * runs if the reader can find that out. The arithmetic is covered from the
     * host; what is unknown on any given device is whether the keys survive being
     * written into a file and read back. If this fails, the tone map will not run
     * on a real drone clip either, and the footage will composite washed out and
     * grey with no error anywhere.
     */
    @Test
    public void bt2020HlgSurvivesTheFile() throws Exception {
        final Context context =
                InstrumentationRegistry.getInstrumentation().getTargetContext();
        final String mime = SyntheticClip.canEncode(MediaFormat.MIMETYPE_VIDEO_HEVC)
                ? MediaFormat.MIMETYPE_VIDEO_HEVC : "video/avc";
        final File clip = new File(context.getCacheDir(), "synthetic-hlg.mp4");
        try {
            SyntheticClip.write(clip, mime,
                    MediaFormat.COLOR_STANDARD_BT2020,
                    MediaFormat.COLOR_TRANSFER_HLG,
                    MediaFormat.COLOR_RANGE_LIMITED);

            final ClipReader reader = new ClipReader(clip.getAbsolutePath());
            try {
                final ClipFrame frame = reader.frameAtTime(0L);
                Log.i(TAG, "CAPABILITY " + mime + " color round trip -> "
                        + frame.color.describe());
                assertEquals("BT.2020/HLG/limited", frame.color.describe());
                assertTrue("an HLG clip must take the float pipeline",
                        frame.color.wideGamutOrHdr);
            } finally {
                reader.close();
            }
        } finally {
            clip.delete();
        }
    }
}
