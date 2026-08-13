package com.lib.flutter_quick_video_encoder;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.ImageDecoder;
import android.graphics.Matrix;
import android.graphics.ColorSpace;
import android.media.ExifInterface;
import android.os.Build;
import android.util.Log;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Decodes one still photograph to bounded RGBA, off the platform thread.
 *
 * <h2>Why this exists at all</h2>
 *
 * Flutter cannot decode HEIF itself. Skia's built-in codecs — PNG, JPEG, WebP,
 * GIF, BMP — are registered at priority 0, and Android's own generator sits
 * behind them at priority -1, so a HEIC is the one format on this app's
 * whitelist that reaches {@code AndroidImageGenerator}. Two things there end the
 * process rather than failing:
 *
 * <ul>
 *   <li>The decode is <b>eager and full-resolution</b>. {@code GetScaledDimensions}
 *       returns the file's own dimensions, and the bitmap is decoded when the
 *       generator is *created* — before any {@code cacheWidth} has been
 *       communicated. A 200 MP HEIF is an ~800 MB allocation for a 40-point
 *       thumbnail.</li>
 *   <li>The engine's Java decoder catches only {@code IOException}. Anything
 *       else — an {@code OutOfMemoryError}, a {@code NullPointerException} —
 *       reaches JNI as a pending exception, and
 *       {@code FML_CHECK(fml::jni::CheckException(env))} turns that into
 *       {@code abort()}. There is no Dart {@code catch} and no
 *       {@code errorBuilder} that runs.</li>
 * </ul>
 *
 * This class is that decode done deliberately: sampled to the size actually
 * wanted, and behind a boundary that cannot leak a throwable.
 *
 * <h2>Android only, and only Android</h2>
 *
 * macOS and iOS register a CoreGraphics generator instead — in process, no JNI,
 * and a failure there is a null rather than an abort. So this is not a
 * cross-platform decode layer and should not become one without a reason of its
 * own. (Apple's generator also reports full dimensions, so a 200 MP still is a
 * memory question on an iPhone too. Unmeasured, and deliberately not addressed
 * here.)
 */
public class StillDecoder {
    private static final String TAG = "StillDecoder";

    /**
     * Its own thread, and not the clip preview worker.
     *
     * A rider dragging a trim handle and a shot list decoding eighteen
     * thumbnails are two different urgencies; sharing one queue makes the
     * scrub wait behind the list. Single-threaded rather than a pool because
     * the whole point is that two full decodes never overlap.
     */
    private final ExecutorService mWorker =
            Executors.newSingleThreadExecutor(runnable -> {
                Thread thread = new Thread(runnable, "fqve-still-decode");
                thread.setPriority(Thread.NORM_PRIORITY - 1);
                return thread;
            });

    /** One decoded photograph: tightly packed RGBA and what it turned out to be. */
    public static class Still {
        public final byte[] rgba;
        public final int width;
        public final int height;

        /**
         * Whether the source carried an HDR gain map.
         *
         * **Reported, not used.** Nothing consumes it yet — the still path is
         * SDR throughout and `PLAN-HDR.md` keeps the HDR composite native, so
         * these pixels are inherently the SDR branch. It is here because it is
         * free to ask at decode time and because "does a bounded decode keep
         * the gain map" is a question the HDR work has to answer with a
         * measurement on a device rather than an assumption. Answering it early
         * costs one call and one boolean.
         */
        public final boolean hasGainmap;

        Still(byte[] rgba, int width, int height, boolean hasGainmap) {
            this.rgba = rgba;
            this.width = width;
            this.height = height;
            this.hasGainmap = hasGainmap;
        }
    }

    /** Called on the worker thread with the decode, or null if there is none. */
    public interface Delivery {
        void onStill(Still still);
    }

    /**
     * Decodes {@code path} so that neither edge exceeds {@code maxEdge}.
     *
     * Never upscales: a photograph smaller than the bound comes back at its own
     * size, because a thumbnail slot is a ceiling rather than a target.
     */
    public void decode(final String path, final int maxEdge, final Delivery delivery) {
        try {
            mWorker.execute(() -> deliverDecode(path, maxEdge, delivery));
        } catch (java.util.concurrent.RejectedExecutionException rejected) {
            // **A refused task still has to answer.** The queue rejects once the
            // executor is shutting down, and a caller waiting on a MethodChannel
            // result has no timeout — a decode that is simply dropped leaves the
            // Dart future pending forever, which on screen is a thumbnail stuck
            // on its placeholder and indistinguishable from a rider who took no
            // photographs. Null is the honest answer and one the caller already
            // handles.
            Log.w(TAG, "still decode refused, shutting down: " + path);
            delivery.onStill(null);
        }
    }

    private static void deliverDecode(String path, int maxEdge, Delivery delivery) {
        Still still = null;
        try {
            still = decodeBounded(new File(path), Math.max(1, maxEdge));
        } catch (Throwable error) {
            // **`Throwable`, not `Exception`, and that is the whole fix.**
            // The failures that end this process are an `OutOfMemoryError`
            // and a `NullPointerException` from inside the platform's own
            // decoder — one of which is not an `Exception` at all. Catching
            // the narrower type here would rewrite the code and keep the
            // crash.
            Log.w(TAG, "still decode failed: " + path, error);
        }
        delivery.onStill(still);
    }

    /**
     * Stops taking new work and lets what is queued answer.
     *
     * <p><b>{@code shutdown()}, never {@code shutdownNow()}.</b> The difference
     * is not tidiness: {@code shutdownNow} discards queued tasks, and a
     * discarded decode never calls its delivery, so the {@code MethodChannel}
     * result is never sent and the Dart future <i>never completes</i> — not
     * null, not an error, nothing. This is called whenever the Clips screen
     * stops being shown and at the start of every export, so a rider stepping
     * from Clips to the shot list could strand seventeen of eighteen
     * thumbnails on their placeholder, permanently and silently.
     *
     * <p>The queued decodes are short and bounded, so draining them costs a
     * moment of a worker thread nobody is waiting on.
     */
    public void release() {
        mWorker.shutdown();
    }

    private static Still decodeBounded(File file, int maxEdge) throws IOException {
        Bitmap bitmap = null;
        try {
            bitmap = viaImageDecoder(file, maxEdge);
            if (bitmap == null) {
                // **Not an optional path.** Android 16's `ImageDecoder` fails on
                // HEIFs carrying certain gain maps — the engine ships a whole
                // class working around it — and Samsung writes exactly those.
                // So the file that caused this crash is the file our own
                // preferred decoder also refuses, and a fallback that only
                // handles corrupt input would leave the crash fixed for
                // everything except the photograph that started it.
                bitmap = viaBitmapFactory(file, maxEdge);
            }
            if (bitmap == null) {
                return null;
            }
            return toStill(bitmap);
        } finally {
            if (bitmap != null) {
                bitmap.recycle();
            }
        }
    }

    /**
     * The good path: decodes *at* the reduced size rather than decoding whole
     * and shrinking.
     */
    private static Bitmap viaImageDecoder(File file, int maxEdge) {
        try {
            ImageDecoder.Source source = ImageDecoder.createSource(file);
            return ImageDecoder.decodeBitmap(source, (decoder, info, src) -> {
                int width = info.getSize().getWidth();
                int height = info.getSize().getHeight();
                int longest = Math.max(width, height);
                if (longest > maxEdge) {
                    // Rounded up, so neither edge lands a pixel over the bound.
                    double scale = (double) maxEdge / (double) longest;
                    decoder.setTargetSize(
                            Math.max(1, (int) Math.ceil(width * scale)),
                            Math.max(1, (int) Math.ceil(height * scale)));
                }
                // Software, because these pixels are read back rather than
                // drawn — a hardware bitmap has no pixels to copy out.
                decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);
                // sRGB for byte-parity with what the engine's own decoder
                // produces today. Widening the gamut is an HDR question and
                // changing two things at once would destroy the comparison.
                decoder.setTargetColorSpace(ColorSpace.get(ColorSpace.Named.SRGB));
            });
        } catch (Throwable error) {
            // Includes the API-36 gain-map `DecodeException`. Logged rather
            // than swallowed silently: a build where every photograph quietly
            // takes the slow path is one nobody would notice.
            Log.i(TAG, "ImageDecoder declined, falling back: " + error);
            return null;
        }
    }

    /**
     * The fallback, sampled and oriented by hand.
     *
     * `BitmapFactory` predates `ImageDecoder` and differs in two ways that both
     * matter: it returns **null** rather than throwing when it cannot decode,
     * and it does not apply EXIF orientation. The engine's own workaround for
     * the same platform bug forgets the first — it dereferences the bitmap
     * without a null check — which is one of the two ways this app was dying.
     */
    private static Bitmap viaBitmapFactory(File file, int maxEdge) throws IOException {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(file.getPath(), bounds);
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            return null;
        }

        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = sampleSizeFor(bounds.outWidth, bounds.outHeight, maxEdge);
        options.inPreferredConfig = Bitmap.Config.ARGB_8888;
        Bitmap decoded = BitmapFactory.decodeFile(file.getPath(), options);
        if (decoded == null) {
            // The null the engine forgot.
            return null;
        }

        Bitmap oriented = applyOrientation(decoded, file);
        return scaleDown(oriented, maxEdge);
    }

    /**
     * The largest power of two that still leaves the long edge at or above the
     * bound, so the sampled decode is never coarser than what is wanted.
     *
     * Peak memory is the full pixel count divided by the square of this — a
     * 200 MP photograph at a 2048 bound samples by 8 and costs about 12 MB
     * rather than 800.
     */
    static int sampleSizeFor(int width, int height, int maxEdge) {
        int longest = Math.max(width, height);
        int sample = 1;
        while (longest / (sample * 2) >= maxEdge) {
            sample *= 2;
        }
        return sample;
    }

    /**
     * Rotation *and* the mirrored orientations.
     *
     * All eight, because the four flips are rare and a photograph that arrives
     * mirrored is worse than one that arrives sideways — it looks correct until
     * somebody reads a sign in it. `ImageDecoder` does this itself, so only the
     * fallback needs it, and the two paths must agree or a photograph changes
     * orientation depending on which decoder took it.
     */
    private static Bitmap applyOrientation(Bitmap bitmap, File file) throws IOException {
        int orientation;
        try {
            orientation = new ExifInterface(file.getPath()).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
        } catch (Throwable error) {
            // A file with no readable EXIF is upright by definition here.
            return bitmap;
        }
        Matrix matrix = new Matrix();
        switch (orientation) {
            case ExifInterface.ORIENTATION_ROTATE_90:
                matrix.setRotate(90);
                break;
            case ExifInterface.ORIENTATION_ROTATE_180:
                matrix.setRotate(180);
                break;
            case ExifInterface.ORIENTATION_ROTATE_270:
                matrix.setRotate(270);
                break;
            case ExifInterface.ORIENTATION_FLIP_HORIZONTAL:
                matrix.setScale(-1, 1);
                break;
            case ExifInterface.ORIENTATION_FLIP_VERTICAL:
                matrix.setScale(1, -1);
                break;
            case ExifInterface.ORIENTATION_TRANSPOSE:
                matrix.setRotate(90);
                matrix.postScale(-1, 1);
                break;
            case ExifInterface.ORIENTATION_TRANSVERSE:
                matrix.setRotate(270);
                matrix.postScale(-1, 1);
                break;
            default:
                return bitmap;
        }
        Bitmap turned = Bitmap.createBitmap(
                bitmap, 0, 0, bitmap.getWidth(), bitmap.getHeight(), matrix, true);
        if (turned != bitmap) {
            bitmap.recycle();
        }
        return turned;
    }

    /** The remainder after sampling, which lands within a factor of two. */
    private static Bitmap scaleDown(Bitmap bitmap, int maxEdge) {
        int longest = Math.max(bitmap.getWidth(), bitmap.getHeight());
        if (longest <= maxEdge) {
            return bitmap;
        }
        double scale = (double) maxEdge / (double) longest;
        int width = Math.max(1, (int) Math.round(bitmap.getWidth() * scale));
        int height = Math.max(1, (int) Math.round(bitmap.getHeight() * scale));
        Bitmap scaled = Bitmap.createScaledBitmap(bitmap, width, height, true);
        if (scaled != bitmap) {
            bitmap.recycle();
        }
        return scaled;
    }

    private static Still toStill(Bitmap bitmap) {
        // `ARGB_8888` is stored little-endian as R,G,B,A bytes, which is what
        // `decodeImageFromPixels` reads as `rgba8888` — the same equivalence
        // `ClipPreviewCache` already relies on.
        Bitmap source = bitmap.getConfig() == Bitmap.Config.ARGB_8888
                ? bitmap
                : bitmap.copy(Bitmap.Config.ARGB_8888, false);
        byte[] rgba = new byte[source.getWidth() * source.getHeight() * 4];
        source.copyPixelsToBuffer(ByteBuffer.wrap(rgba));
        boolean gainmap = Build.VERSION.SDK_INT >= 34 && source.hasGainmap();
        Still still = new Still(rgba, source.getWidth(), source.getHeight(), gainmap);
        if (source != bitmap) {
            source.recycle();
        }
        return still;
    }
}
