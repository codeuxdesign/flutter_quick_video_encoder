package com.lib.flutter_quick_video_encoder;

import android.media.MediaFormat;

/**
 * The format the video encoder is configured with.
 *
 * <p>Extracted from the `setup` call so it can be asserted on. **The color tags
 * are the part that goes wrong silently**: the samples were converted with
 * Rec.601 coefficients and the format declared nothing at all, so every player
 * decoded an HD stream as Rec.709 — the whole film through a matrix it was not
 * written with, greens pulled one way and reds the other, subtly and
 * everywhere. It survived because it is invisible without a reference to
 * compare against.
 *
 * <p>A future change can drop these three lines and the pixels will still look
 * approximately right. That is the case a test has to cover, and it cannot cover
 * a format built inline inside a method-channel handler.
 */
final class EncoderFormat {

    private EncoderFormat() {
    }

    static final String MIME = "video/avc";

    /**
     * Builds the encoder's input format.
     *
     * <p>Rec.709 limited range, stated rather than left to the player's guess.
     * The draft target is 540x960, which is under the 720-line boundary where
     * some players switch their default to Rec.601 — so the inference and the
     * truth would disagree at exactly the size used for every quick iteration.
     *
     * <p>**Leaving a tag out does not leave it unset.** Deleting the line below
     * and re-running `ColorTagsTest` produces a file tagged
     * `COLOR_STANDARD_BT601_NTSC` — the platform picks, and on an S24 Ultra it
     * picks 601. So the undeclared case is not "a player will guess", it is "the
     * file positively asserts the wrong matrix", which no downstream check can
     * distinguish from a deliberate choice.
     */
    static MediaFormat video(int width, int height, int bitrate, int fps, int colorFormat) {
        final MediaFormat format = MediaFormat.createVideoFormat(MIME, width, height);
        format.setInteger(MediaFormat.KEY_BIT_RATE, bitrate);
        format.setInteger(MediaFormat.KEY_FRAME_RATE, fps);
        format.setInteger(MediaFormat.KEY_COLOR_FORMAT, colorFormat);
        format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);
        format.setInteger(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT709);
        format.setInteger(MediaFormat.KEY_COLOR_RANGE, MediaFormat.COLOR_RANGE_LIMITED);
        format.setInteger(MediaFormat.KEY_COLOR_TRANSFER,
                MediaFormat.COLOR_TRANSFER_SDR_VIDEO);
        return format;
    }
}
