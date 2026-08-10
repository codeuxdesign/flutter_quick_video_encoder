package com.lib.flutter_quick_video_encoder;

import android.graphics.ImageFormat;
import android.graphics.Rect;
import android.media.Image;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.util.Log;

import java.io.IOException;
import java.nio.ByteBuffer;

/**
 * One clip source file, decoded forward, holding the frame that is on screen.
 *
 * <p>The Apple counterpart is {@code FQVEClipReader}: one {@code AVAssetReader}
 * per path, cached across frames, never seeking, holding the last sample because
 * several output frames land inside one source frame whenever the film runs
 * slower than the clip. The shape here is the same. Two things differ, and both
 * are facts about Android rather than choices.
 *
 * <p><b>It seeks on a large jump.</b> The Apple reader walks the file from the
 * beginning and pays for it once: the corpus film wants four seconds starting at
 * 0:12 and five starting at 1:36, so a reader that never seeks decodes eighty
 * seconds of 4K HEVC inside one output frame's turn. On a Mac that is a pause.
 * On a phone it is a pause long enough to look like a hang, in a run nobody can
 * attach a debugger to. A seek to the sync sample at or before the wanted
 * instant costs one decoder rebuild and lands in the same place. Seeks are
 * counted and logged, because the failure mode of this optimization is seeking
 * every frame, and that has to be visible rather than inferred from how long the
 * export took.
 *
 * <p><b>It rebuilds the decoder to seek rather than flushing it.</b>
 * {@code MediaCodec.flush()} in synchronous mode leaves the codec in a state
 * whose documented recovery — whether {@code start()} must follow — is read
 * differently by different codebases, and this code cannot be run on a device
 * before it ships. A seek happens about twice per film, a rebuild costs tens of
 * milliseconds, and the ambiguity disappears.
 */
final class ClipReader {

    private static final String TAG = "[FQVE-Android]";

    /**
     * How far ahead the film may ask before it is cheaper to seek than to
     * decode. Two seconds is well past any within-clip jitter, so ordinary
     * playback never trips it, while the eighty-second jump between the corpus
     * clip's two ranges does.
     */
    private static final long SEEK_AHEAD_US = 2_000_000L;

    private static final long DEQUEUE_TIMEOUT_US = 10_000L;

    /**
     * How long one {@code frameAtTime} may spend before it is called wedged.
     * Generous, because a seek that lands at the start of a long GOP legitimately
     * decodes a second of video — but bounded, so a decoder that has stopped
     * producing fails the export loudly instead of hanging the platform thread
     * with nothing on screen to say why.
     */
    private static final long FRAME_DEADLINE_NANOS = 20_000_000_000L;

    final String path;

    private final MediaExtractor extractor = new MediaExtractor();
    private final MediaFormat trackFormat;
    private final String decoderName;
    private final String mime;
    private final int trackIndex;

    private MediaCodec codec;
    private final MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();

    private ClipColor color;
    private boolean colorFromOutputFormat;

    /** The frame covering the last requested instant. */
    private ClipFrame current;
    /** The first decoded frame past that instant, kept so it is not decoded twice. */
    private ClipFrame ahead;

    private short[][] slotA;
    private short[][] slotB;
    private int slotWidth;
    private int slotHeight;

    private boolean inputDone;
    private boolean outputDone;
    private boolean loggedLayout;
    private int seekCount;
    private int decodedFrames;

    ClipReader(String path) throws IOException {
        this.path = path;
        extractor.setDataSource(path);

        int track = -1;
        MediaFormat format = null;
        for (int i = 0; i < extractor.getTrackCount(); i++) {
            final MediaFormat candidate = extractor.getTrackFormat(i);
            final String type = candidate.getString(MediaFormat.KEY_MIME);
            if (type != null && type.startsWith("video/")) {
                track = i;
                format = candidate;
                break;
            }
        }
        if (track < 0) {
            extractor.release();
            throw new IOException("no video track in " + path);
        }
        trackIndex = track;
        trackFormat = format;
        mime = format.getString(MediaFormat.KEY_MIME);
        extractor.selectTrack(trackIndex);

        decoderName = chooseDecoder(format, mime);
        if (decoderName == null) {
            extractor.release();
            throw new IOException(noDecoderReason(format, mime));
        }

        color = colorFrom(format, defaultColor(format));
        // Started here rather than lazily, so that a file this device cannot
        // configure a decoder for fails while it is still being opened. The
        // first `frameAtTime` seeks and rebuilds it; that is one wasted decoder
        // per clip and it buys the preflight in `ClipCompositor.undecodable`.
        startDecoder();

        Log.i(TAG, "CLIP open " + path
                + " " + format.getInteger(MediaFormat.KEY_WIDTH)
                + "x" + format.getInteger(MediaFormat.KEY_HEIGHT)
                + " " + mime
                + " color=" + color.describe()
                + " decoder=" + decoderName);
    }

    /**
     * The frame covering [timeUs], decoded forward from wherever the reader is.
     *
     * <p>Holds the last frame rather than decoding one per call, because several
     * output frames land inside one source frame whenever the film runs slower
     * than the clip — and because a source that has run out should keep showing
     * its final frame rather than vanish.
     */
    ClipFrame frameAtTime(long timeUs) throws IOException {
        final long from = current == null ? Long.MIN_VALUE : current.presentationTimeUs;
        // The forward test is skipped once the file has run out: a film that
        // keeps asking past the end would otherwise seek on every frame, each
        // one landing on the same last sync sample.
        final boolean farAhead = !outputDone && timeUs > from + SEEK_AHEAD_US;
        // **Comparing against the held frame is safe here, and is not on Apple.**
        // The loop below only adopts a frame whose timestamp is at or before the
        // instant asked for, so `current.presentationTimeUs <= timeUs` holds
        // after every call and a repeated instant can never read as a scrub
        // backwards. The Apple reader selects on the frame's *end* instead, and
        // sample durations do not always tile — measured on a 120 fps drone clip,
        // the frame covering 30.000s reports a start of 30.0007s — so there the
        // same comparison rebuilt the reader to answer with the frame it was
        // already holding, at 79 ms a time. It is tracked against the requested
        // instant there for that reason; the two are not gratuitously different.
        if (from == Long.MIN_VALUE || timeUs < from || farAhead) {
            seekTo(timeUs);
        }

        final long deadline = System.nanoTime() + FRAME_DEADLINE_NANOS;
        while (true) {
            if (ahead != null) {
                if (ahead.presentationTimeUs <= timeUs) {
                    current = ahead;
                    ahead = null;
                    continue;
                }
                break;
            }
            if (outputDone) {
                break;
            }
            final ClipFrame decoded = decodeOne(deadline);
            if (decoded == null) {
                break;
            }
            if (decoded.presentationTimeUs <= timeUs || current == null) {
                current = decoded;
            } else {
                ahead = decoded;
                break;
            }
        }

        if (current == null) {
            throw new IOException("decoded no frames at " + timeUs + "us from " + path);
        }
        return current;
    }

    void close() {
        Log.i(TAG, "CLIP close " + path
                + " decoded=" + decodedFrames + " seeks=" + seekCount);
        releaseDecoder();
        try {
            extractor.release();
        } catch (Exception ignored) {
            // Releasing twice, or releasing one that never opened, is not worth
            // failing an export that has otherwise finished.
        }
        current = null;
        ahead = null;
        slotA = null;
        slotB = null;
    }

    // ---- decoding ----------------------------------------------------------

    private void seekTo(long timeUs) throws IOException {
        extractor.seekTo(Math.max(0L, timeUs), MediaExtractor.SEEK_TO_PREVIOUS_SYNC);
        releaseDecoder();
        current = null;
        ahead = null;
        inputDone = false;
        outputDone = false;
        seekCount++;
        startDecoder();
        Log.i(TAG, "CLIP seek " + path + " to " + timeUs + "us"
                + " landed=" + extractor.getSampleTime() + "us"
                + " total=" + seekCount);
    }

    private void startDecoder() throws IOException {
        // Asked for again rather than reused. `getTrackFormat` builds a fresh
        // MediaFormat every call, and with it fresh `csd-N` buffers — and
        // `configure` consumes those buffers from their current position, so a
        // second decoder built from the same format object after a seek would
        // be handed empty parameter sets and refuse to start on the devices
        // that need them.
        final MediaFormat configure = extractor.getTrackFormat(trackIndex);
        // **Flexible, and read through getOutputImage.** Asking for a concrete
        // layout is how the classic Android video bug is written: one device
        // hands back planar, the next semiplanar, and the code that assumed one
        // of them is wrong on half the phones in the world with no error
        // anywhere. Flexible plus the Image's own row and pixel strides is the
        // only portable read.
        //
        // **Except for ten-bit sources, which have to ask by name.** Flexible is
        // satisfied by 8-bit: a Galaxy S24 Ultra answers a Main10 stream with
        // `YUV_420_888`, having truncated on the way, and the frame then decodes
        // and converts perfectly well two bits short. Nothing about that is
        // visible in the output — which is why `ClipTenBitTest` asserts the
        // format the decoder returned rather than the colours it produced.
        // `COLOR_FormatYUVP010` is only requested where the decoder advertises
        // it, so a device without it keeps the portable path instead of failing
        // to configure.
        final boolean wantTenBit = isTenBitSource() && decoderOffersP010();
        configure.setInteger(MediaFormat.KEY_COLOR_FORMAT,
                wantTenBit
                        ? COLOR_FormatYUVP010
                        : MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible);
        if (wantTenBit) {
            Log.i(TAG, "CLIP requesting P010 for " + path);
        }

        codec = MediaCodec.createByCodecName(decoderName);
        codec.configure(configure, null, null, 0);
        codec.start();
    }

    /**
     * `MediaCodecInfo.CodecCapabilities.COLOR_FormatYUVP010`, by value.
     *
     * <p>Named as a constant because the symbol arrived in API 29 and this
     * module still compiles against older platform stubs in places. The value is
     * stable; a symbolic reference that fails to resolve at build time would be
     * worse than a documented literal.
     */
    private static final int COLOR_FormatYUVP010 = 54;

    /**
     * Whether the track claims more than eight bits per sample.
     *
     * <p><b>The profile, and only the profile. Transfer function is not bit
     * depth.</b> This first also treated any HLG or PQ file as ten-bit, on the
     * theory that a profile key might be missing — and that broke every 8-bit
     * HDR clip on the device, including the corpus one, which is BT.2020 with an
     * HLG transfer and eight bits per sample. Asking a decoder for P010 on an
     * 8-bit stream does not fail loudly: it produces no frames at all, and the
     * export reports "decoded no frames at 0us".
     *
     * <p>The two are separate axes and conflating them is the same mistake in
     * code that PLAN §7 item 8 exists to correct in prose. A file that declares
     * no profile keeps the portable 8-bit path, which costs two bits on a clip
     * that did not say what it was — much better than not decoding it.
     */
    private boolean isTenBitSource() {
        if (!trackFormat.containsKey(MediaFormat.KEY_PROFILE)) {
            return false;
        }
        final int profile = trackFormat.getInteger(MediaFormat.KEY_PROFILE);
        return profile == MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10
                || profile == MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10HDR10
                || profile == MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10HDR10Plus;
    }

    /**
     * Whether the chosen decoder will actually hand back P010 for this mime.
     *
     * <p>Asked of the codec *list* rather than by instantiating the codec.
     * Creating one to read its capabilities and dropping the reference leaks a
     * decoder per clip, and a leaked decoder is a resource the next clip cannot
     * have — on a phone that surfaces as an unrelated clip failing to open.
     */
    private boolean decoderOffersP010() {
        try {
            final MediaCodecList list = new MediaCodecList(MediaCodecList.ALL_CODECS);
            for (final MediaCodecInfo info : list.getCodecInfos()) {
                if (info.isEncoder() || !info.getName().equals(decoderName)) {
                    continue;
                }
                for (final int format : info.getCapabilitiesForType(mime).colorFormats) {
                    if (format == COLOR_FormatYUVP010) {
                        return true;
                    }
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "CLIP could not read colour formats for " + decoderName + ": " + e);
        }
        return false;
    }

    private void releaseDecoder() {
        if (codec == null) {
            return;
        }
        try {
            codec.stop();
        } catch (Exception ignored) {
            // A codec that never produced anything can refuse to stop. It is
            // still going to be released on the next line.
        }
        try {
            codec.release();
        } catch (Exception ignored) {
            // Same.
        }
        codec = null;
    }

    private ClipFrame decodeOne(long deadlineNanos) throws IOException {
        while (true) {
            feedInput();
            final int index = codec.dequeueOutputBuffer(bufferInfo, DEQUEUE_TIMEOUT_US);
            if (index >= 0) {
                final boolean eos =
                        (bufferInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
                final boolean config =
                        (bufferInfo.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0;
                if (config || bufferInfo.size == 0) {
                    codec.releaseOutputBuffer(index, false);
                    if (eos) {
                        outputDone = true;
                        return null;
                    }
                    continue;
                }
                final ClipFrame frame = takeFrame(index, bufferInfo.presentationTimeUs);
                if (eos) {
                    outputDone = true;
                }
                if (frame != null) {
                    decodedFrames++;
                    return frame;
                }
                if (outputDone) {
                    return null;
                }
                continue;
            }
            if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                adoptOutputFormat(codec.getOutputFormat());
                continue;
            }
            if (index == MediaCodec.INFO_TRY_AGAIN_LATER && System.nanoTime() > deadlineNanos) {
                throw new IOException("decoder produced nothing for "
                        + (FRAME_DEADLINE_NANOS / 1_000_000_000L) + "s on " + path
                        + " (decoder=" + decoderName + ")");
            }
        }
    }

    private void feedInput() {
        while (!inputDone) {
            final int index = codec.dequeueInputBuffer(0);
            if (index < 0) {
                return;
            }
            final ByteBuffer buffer = codec.getInputBuffer(index);
            if (buffer == null) {
                return;
            }
            buffer.clear();
            final int size = extractor.readSampleData(buffer, 0);
            if (size < 0) {
                codec.queueInputBuffer(index, 0, 0, 0L,
                        MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                inputDone = true;
                return;
            }
            codec.queueInputBuffer(index, 0, size, extractor.getSampleTime(), 0);
            extractor.advance();
        }
    }

    /** Copies one decoded frame out of the codec and gives the buffer back. */
    private ClipFrame takeFrame(int index, long presentationTimeUs) throws IOException {
        Image image = null;
        try {
            image = codec.getOutputImage(index);
            if (image == null) {
                return null;
            }
            return copyImage(image, presentationTimeUs);
        } finally {
            if (image != null) {
                image.close();
            }
            codec.releaseOutputBuffer(index, false);
        }
    }

    private ClipFrame copyImage(Image image, long presentationTimeUs) throws IOException {
        final int format = image.getFormat();
        final boolean tenBit = format == ImageFormat.YCBCR_P010;
        if (!tenBit && format != ImageFormat.YUV_420_888) {
            throw new IOException("decoder handed back image format " + format
                    + ", which is neither YUV_420_888 nor YCBCR_P010, for " + path);
        }

        Rect crop = image.getCropRect();
        int left = 0;
        int top = 0;
        int width = image.getWidth();
        int height = image.getHeight();
        if (crop != null && crop.width() > 0 && crop.height() > 0) {
            left = crop.left;
            top = crop.top;
            width = crop.width();
            height = crop.height();
        }
        // Odd sizes would put the last chroma column outside the plane. No
        // encoder in this pipeline accepts an odd dimension either, so rounding
        // the sampled area down by a pixel is invisible and safe.
        width &= ~1;
        height &= ~1;
        if (width <= 0 || height <= 0) {
            throw new IOException("decoded frame has no usable area for " + path);
        }

        final int chromaWidth = width / 2;
        final int chromaHeight = height / 2;
        final short[][] slot = slotFor(width, height, chromaWidth, chromaHeight);

        final Image.Plane[] planes = image.getPlanes();
        logLayoutOnce(image, planes, format, left, top, width, height);
        // The file's own range decides how an 8-bit sample widens, so it has to
        // travel with the copy rather than be assumed by it.
        final boolean fullRange = color != null && color.fullRange;
        copyPlane(planes[0].getBuffer(), planes[0].getRowStride(), planes[0].getPixelStride(),
                slot[0], left, top, width, height, tenBit, fullRange);
        copyPlane(planes[1].getBuffer(), planes[1].getRowStride(), planes[1].getPixelStride(),
                slot[1], left / 2, top / 2, chromaWidth, chromaHeight, tenBit, fullRange);
        copyPlane(planes[2].getBuffer(), planes[2].getRowStride(), planes[2].getPixelStride(),
                slot[2], left / 2, top / 2, chromaWidth, chromaHeight, tenBit, fullRange);

        return new ClipFrame(slot[0], slot[1], slot[2], width, height, color,
                presentationTimeUs, tenBit);
    }

    /**
     * The layout this device actually handed back, printed once per clip.
     *
     * <p>Effective, not intended. `COLOR_FormatYUV420Flexible` is a family — one
     * device is planar with a chroma pixel stride of 1, the next semiplanar with
     * 2, and the row stride is whatever the hardware wanted — and code written
     * against one of them is wrong on the others with no error anywhere. When a
     * clip comes out sheared or the wrong color on somebody's phone, this line is
     * the difference between reading it off the log and guessing.
     */
    private void logLayoutOnce(Image image, Image.Plane[] planes, int format,
                               int left, int top, int width, int height) {
        if (loggedLayout) {
            return;
        }
        loggedLayout = true;
        Log.i(TAG, "CLIP layout " + path
                + " format=" + format
                + " buffer=" + image.getWidth() + "x" + image.getHeight()
                + " used=" + width + "x" + height + "+" + left + "+" + top
                + " y(row=" + planes[0].getRowStride() + ",px=" + planes[0].getPixelStride() + ")"
                + " u(row=" + planes[1].getRowStride() + ",px=" + planes[1].getPixelStride() + ")"
                + " v(row=" + planes[2].getRowStride() + ",px=" + planes[2].getPixelStride() + ")");
    }

    /**
     * Reads one plane into a tightly packed array, whatever the device's layout.
     *
     * <p>Row stride is almost never the width and chroma pixel stride is 1 on a
     * planar device and 2 on a semiplanar one. For 10-bit output each sample is
     * two little-endian bytes with the data in the top bits, so the high byte
     * alone is the 8-bit value — which is all an 8-bit sRGB frame can carry.
     *
     * <p><b>Takes a buffer and its strides rather than an {@code Image.Plane},
     * so that it can be tested at all.</b> Every device this has run on hands
     * back the friendliest layout there is — planar, no padding, no crop — so
     * the branches that matter are exactly the ones a real run never exercises.
     * Given the numbers instead of the plane, a plain JVM test can lay out a
     * semiplanar, padded or 10-bit buffer by hand and check what comes out.
     */
    /**
     * How an 8-bit code becomes its 10-bit equivalent.
     *
     * <p><b>The two ranges widen differently, and using one rule for both costs
     * a code everywhere.</b> Limited range is an exact shift: 16 lands on 64 and
     * 235 on 940, because both scales put black and white at the same fractions.
     * Full range is not — 255 has to reach 1023, and shifting leaves it at 1020,
     * so every non-zero sample decodes one low and white comes back 254. That is
     * a uniform darkening of the whole clip with nothing logged, and the corpus
     * has full-range files in it: the action-cam `yuvj420p` clips are exactly
     * this case.
     *
     * <p>The full-range form is a ceiling rather than a round, because
     * {@link ClipColor}'s integer path floors on the way back to 8 bits. The
     * widened code has to sit at or above the exact {@code v * 1023 / 255} to
     * survive that floor; rounding puts half the values a fraction below it.
     * The bias is under half a code in 1023 and buys an exact round trip for all
     * 256 inputs, which is what a clip that was 8-bit all along deserves.
     */
    private static short widen(int eightBit, boolean fullRange) {
        return (short) (fullRange ? (eightBit * 1023 + 254) / 255 : eightBit << 2);
    }

    static void copyPlane(ByteBuffer buffer, int rowStride, int pixelStride, short[] out,
                          int left, int top, int width, int height,
                          boolean tenBit, boolean fullRange) {
        if (!tenBit && pixelStride == 1) {
            // The common planar 8-bit case. One bulk copy per row into a scratch
            // byte array — a direct ByteBuffer's bulk get is a native memcpy and
            // the per-sample loop below is not — then widened on the way out.
            final byte[] row = new byte[width];
            for (int y = 0; y < height; y++) {
                buffer.position((top + y) * rowStride + left);
                buffer.get(row, 0, width);
                final int dst = y * width;
                for (int x = 0; x < width; x++) {
                    out[dst + x] = widen(row[x] & 0xFF, fullRange);
                }
            }
            return;
        }

        // Semiplanar chroma, or 10-bit, or both. Each row is bulk-copied first
        // and gathered afterwards in plain array space, rather than reading the
        // direct buffer a byte at a time: a bulk get is a memcpy, while
        // `ByteBuffer.get(int)` on a direct buffer is a bounds-checked access
        // that the JIT will not turn into one. This is the path a real phone
        // takes — the Galaxy S24 Ultra reports a chroma pixel stride of 2 — so
        // it is worth the scratch row.
        final int span = (width - 1) * pixelStride + (tenBit ? 2 : 1);
        final byte[] row = new byte[span];
        for (int y = 0; y < height; y++) {
            final int base = (top + y) * rowStride + left * pixelStride;
            final int dst = y * width;
            final boolean bulk = base + span <= buffer.limit();
            if (bulk) {
                buffer.position(base);
                buffer.get(row, 0, span);
            }
            for (int x = 0; x < width; x++) {
                final int at = x * pixelStride;
                if (tenBit) {
                    // **Both bytes now, where this used to take the high one and
                    // throw the rest away.** P010 stores ten bits in the high
                    // bits of a little-endian 16-bit word, so the sample is the
                    // pair shifted back down by six — not the top byte, which
                    // silently cost two bits on every HDR clip.
                    final int lo = (bulk ? row[at] : buffer.get(base + at)) & 0xFF;
                    final int hi = (bulk ? row[at + 1] : buffer.get(base + at + 1)) & 0xFF;
                    out[dst + x] = (short) ((((hi << 8) | lo) >> 6) & 0x3FF);
                } else {
                    final int v = (bulk ? row[at] : buffer.get(base + at)) & 0xFF;
                    out[dst + x] = widen(v, fullRange);
                }
            }
        }
    }

    private short[][] slotFor(int width, int height, int chromaWidth, int chromaHeight) {
        if (slotA == null || slotWidth != width || slotHeight != height) {
            slotWidth = width;
            slotHeight = height;
            slotA = newSlot(width, height, chromaWidth, chromaHeight);
            slotB = newSlot(width, height, chromaWidth, chromaHeight);
            current = null;
            ahead = null;
        }
        // Two plane sets are enough: a frame is only ever decoded while `ahead`
        // is empty, so at most one of them is live when a new one is written.
        if (current != null && current.luma == slotA[0]) {
            return slotB;
        }
        return slotA;
    }

    private static short[][] newSlot(int width, int height, int chromaWidth, int chromaHeight) {
        return new short[][]{
                new short[width * height],
                new short[chromaWidth * chromaHeight],
                new short[chromaWidth * chromaHeight],
        };
    }

    // ---- color -------------------------------------------------------------

    private void adoptOutputFormat(MediaFormat format) {
        final ClipColor refined = colorFrom(format, null);
        if (refined == null || colorFromOutputFormat) {
            return;
        }
        colorFromOutputFormat = true;
        color = refined;
        Log.i(TAG, "CLIP color " + path + " " + color.describe()
                + " (from the decoder's output format)");
    }

    /**
     * What the file says its color is, or [fallback] when it does not say.
     *
     * <p>Returns null rather than guessing when [fallback] is null, so the
     * decoder's output format can be used to refine the track format's answer
     * without overwriting it with silence.
     */
    private static ClipColor colorFrom(MediaFormat format, ClipColor fallback) {
        final boolean hasStandard = format.containsKey(MediaFormat.KEY_COLOR_STANDARD);
        final boolean hasTransfer = format.containsKey(MediaFormat.KEY_COLOR_TRANSFER);
        final boolean hasRange = format.containsKey(MediaFormat.KEY_COLOR_RANGE);
        if (!hasStandard && !hasTransfer && !hasRange) {
            return fallback;
        }

        int standard = fallback != null ? fallback.standard : ClipColor.STANDARD_BT709;
        if (hasStandard) {
            switch (format.getInteger(MediaFormat.KEY_COLOR_STANDARD)) {
                case MediaFormat.COLOR_STANDARD_BT601_PAL:
                case MediaFormat.COLOR_STANDARD_BT601_NTSC:
                    standard = ClipColor.STANDARD_BT601;
                    break;
                case MediaFormat.COLOR_STANDARD_BT2020:
                    standard = ClipColor.STANDARD_BT2020;
                    break;
                default:
                    standard = ClipColor.STANDARD_BT709;
                    break;
            }
        }

        int transfer = fallback != null ? fallback.transfer : ClipColor.TRANSFER_SDR;
        if (hasTransfer) {
            switch (format.getInteger(MediaFormat.KEY_COLOR_TRANSFER)) {
                case MediaFormat.COLOR_TRANSFER_HLG:
                    transfer = ClipColor.TRANSFER_HLG;
                    break;
                case MediaFormat.COLOR_TRANSFER_ST2084:
                    transfer = ClipColor.TRANSFER_PQ;
                    break;
                default:
                    transfer = ClipColor.TRANSFER_SDR;
                    break;
            }
        }

        boolean fullRange = fallback != null && fallback.fullRange;
        if (hasRange) {
            fullRange = format.getInteger(MediaFormat.KEY_COLOR_RANGE)
                    == MediaFormat.COLOR_RANGE_FULL;
        }

        return new ClipColor(standard, transfer, fullRange);
    }

    /**
     * What to assume about a file that carries no color keys at all.
     *
     * <p>Rec.709 limited above standard definition and Rec.601 limited below it,
     * which is what every player assumes and what the file was almost certainly
     * authored against. Guessing is unavoidable here; what is avoidable is
     * guessing quietly, so the assumption is printed with the clip.
     */
    private static ClipColor defaultColor(MediaFormat format) {
        final int height = format.containsKey(MediaFormat.KEY_HEIGHT)
                ? format.getInteger(MediaFormat.KEY_HEIGHT) : 1080;
        return new ClipColor(
                height >= 720 ? ClipColor.STANDARD_BT709 : ClipColor.STANDARD_BT601,
                ClipColor.TRANSFER_SDR,
                false);
    }

    // ---- decoder selection -------------------------------------------------

    /**
     * A decoder that supports this file's profile and level, or null.
     *
     * <p><b>4K HEVC decode is not universal</b>, and
     * {@code createDecoderByType("video/hevc")} answers a question nobody asked:
     * it returns a decoder for the *mime type*, which then fails at configure
     * time or, worse, produces garbage. Matching on a format that carries the
     * profile and level is what makes "this phone cannot play this clip" a fact
     * discovered before the export starts rather than a black rectangle
     * discovered by the audience.
     */
    static String chooseDecoder(MediaFormat trackFormat, String mime) {
        final MediaCodecList list = new MediaCodecList(MediaCodecList.REGULAR_CODECS);
        final String strict = findDecoder(list, probeFormat(trackFormat, mime, true));
        if (strict != null) {
            return strict;
        }

        // A file whose declared profile and level nothing claims is not
        // necessarily unplayable — some decoders under-declare. Fall back to
        // matching on size alone, and say so, because a clip that plays only
        // because a check was relaxed is worth seeing in the log if it later
        // comes out wrong.
        final String loose = findDecoder(list, probeFormat(trackFormat, mime, false));
        if (loose != null) {
            Log.w(TAG, "CLIP no decoder declares " + mime
                    + " profile=" + intOrMinusOne(trackFormat, MediaFormat.KEY_PROFILE)
                    + " level=" + intOrMinusOne(trackFormat, MediaFormat.KEY_LEVEL)
                    + "; falling back to " + loose + " on size alone");
        }
        return loose;
    }

    private static String findDecoder(MediaCodecList list, MediaFormat probe) {
        try {
            return list.findDecoderForFormat(probe);
        } catch (IllegalArgumentException e) {
            // Thrown for a format the framework will not even consider. That is
            // an answer — nothing here can play it — not a reason to stop.
            Log.w(TAG, "CLIP findDecoderForFormat rejected " + probe, e);
            return null;
        }
    }

    private static MediaFormat probeFormat(MediaFormat trackFormat, String mime,
                                           boolean withProfileLevel) {
        final MediaFormat probe = MediaFormat.createVideoFormat(
                mime,
                trackFormat.getInteger(MediaFormat.KEY_WIDTH),
                trackFormat.getInteger(MediaFormat.KEY_HEIGHT));
        if (withProfileLevel) {
            copyIntIfPresent(trackFormat, probe, MediaFormat.KEY_PROFILE);
            copyIntIfPresent(trackFormat, probe, MediaFormat.KEY_LEVEL);
        }
        return probe;
    }

    /** Why nothing on this device can decode the file, in words a rider can act on. */
    private static String noDecoderReason(MediaFormat format, String mime) {
        return "no decoder on this device supports " + mime + " "
                + format.getInteger(MediaFormat.KEY_WIDTH) + "x"
                + format.getInteger(MediaFormat.KEY_HEIGHT)
                + " profile=" + intOrMinusOne(format, MediaFormat.KEY_PROFILE)
                + " level=" + intOrMinusOne(format, MediaFormat.KEY_LEVEL);
    }

    private static void copyIntIfPresent(MediaFormat from, MediaFormat to, String key) {
        if (from.containsKey(key)) {
            to.setInteger(key, from.getInteger(key));
        }
    }

    private static int intOrMinusOne(MediaFormat format, String key) {
        return format.containsKey(key) ? format.getInteger(key) : -1;
    }
}
