package com.lib.flutter_quick_video_encoder;

import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.util.Log;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * What color formats this device's HEVC decoders will actually hand back.
 *
 * <p>Reports rather than asserts. The question it answers — can this phone give
 * us ten bits at all — decides whether widening the pipeline is worth building,
 * and the answer is a property of the hardware rather than of this code. An
 * assertion would only encode one phone's answer as a requirement.
 */
@RunWith(AndroidJUnit4.class)
public class P010CapabilityTest {

    private static final String TAG = "P010CapabilityTest";
    private static final int COLOR_FormatYUVP010 = 54;  // API 29+, named in newer SDKs.

    @Test
    public void reportsWhichColorFormatsTheHevcDecodersOffer() {
        final MediaCodecList list = new MediaCodecList(MediaCodecList.ALL_CODECS);
        for (final MediaCodecInfo info : list.getCodecInfos()) {
            final String role = info.isEncoder() ? "ENC" : "DEC";
            for (final String type : info.getSupportedTypes()) {
                if (!type.equalsIgnoreCase("video/hevc") && !type.equalsIgnoreCase("video/av01")) {
                    continue;
                }
                final MediaCodecInfo.CodecCapabilities caps =
                        info.getCapabilitiesForType(type);
                final StringBuilder formats = new StringBuilder();
                boolean p010 = false;
                for (final int format : caps.colorFormats) {
                    formats.append(format).append(' ');
                    if (format == COLOR_FormatYUVP010) {
                        p010 = true;
                    }
                }
                Log.i(TAG, "P010CAP " + role + " " + info.getName() + " " + type
                        + " p010=" + p010 + " formats=[" + formats.toString().trim() + "]");

                // Which profiles it claims, so Main10 support is visible too.
                final StringBuilder profiles = new StringBuilder();
                for (final MediaCodecInfo.CodecProfileLevel pl : caps.profileLevels) {
                    profiles.append(pl.profile).append('/').append(pl.level).append(' ');
                }
                Log.i(TAG, "P010CAP " + info.getName() + " profiles=["
                        + profiles.toString().trim() + "]");
            }
        }
    }
}
