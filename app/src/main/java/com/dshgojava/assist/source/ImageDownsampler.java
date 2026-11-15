package com.dshgojava.assist.source;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.media.ExifInterface;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

/** Decodes, rotates, bounds and re-encodes an image before it becomes model input. */
public final class ImageDownsampler {
    public record Encoded(byte[] bytes, String mediaType, int width, int height) {
        public Encoded {
            bytes = bytes == null ? new byte[0] : bytes.clone();
        }

        @Override
        public byte[] bytes() {
            return bytes.clone();
        }
    }

    private ImageDownsampler() { }

    public static Encoded downsample(byte[] source, int maxEdge, int jpegQuality)
            throws IOException {
        if (source == null || source.length == 0) throw new IOException("empty image");
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(source, 0, source.length, bounds);
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            throw new IOException("unsupported image");
        }

        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = ImageSampling.sampleSizeFor(bounds.outWidth, bounds.outHeight,
                maxEdge);
        options.inPreferredConfig = Bitmap.Config.ARGB_8888;
        Bitmap decoded = BitmapFactory.decodeByteArray(source, 0, source.length, options);
        if (decoded == null) throw new IOException("image decode failed");

        Bitmap oriented = decoded;
        try {
            int orientation = orientation(source);
            if (orientation != ExifInterface.ORIENTATION_NORMAL
                    && orientation != ExifInterface.ORIENTATION_UNDEFINED) {
                oriented = applyOrientation(decoded, orientation);
            }
            ImageSampling.Size target = ImageSampling.scaledSize(
                    oriented.getWidth(), oriented.getHeight(), maxEdge);
            Bitmap sized = oriented;
            if (sized.getWidth() != target.width() || sized.getHeight() != target.height()) {
                sized = Bitmap.createScaledBitmap(oriented, target.width(), target.height(), true);
            }
            try {
                Bitmap flattened = flattenOnWhite(sized);
                try {
                    ByteArrayOutputStream out = new ByteArrayOutputStream();
                    if (!flattened.compress(Bitmap.CompressFormat.JPEG,
                            Math.max(1, Math.min(100, jpegQuality)), out)) {
                        throw new IOException("image encode failed");
                    }
                    byte[] bytes = out.toByteArray();
                    if (bytes.length == 0) throw new IOException("image encode failed");
                    return new Encoded(bytes, "image/jpeg", flattened.getWidth(),
                            flattened.getHeight());
                } finally {
                    flattened.recycle();
                }
            } finally {
                if (sized != oriented) sized.recycle();
            }
        } finally {
            if (oriented != decoded) oriented.recycle();
            decoded.recycle();
        }
    }

    private static int orientation(byte[] source) {
        try (ByteArrayInputStream input = new ByteArrayInputStream(source)) {
            ExifInterface exif = new ExifInterface(input);
            return exif.getAttributeInt(ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL);
        } catch (Exception ignored) {
            return ExifInterface.ORIENTATION_NORMAL;
        }
    }

    private static Bitmap applyOrientation(Bitmap source, int orientation) {
        Matrix matrix = new Matrix();
        switch (orientation) {
            case ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.setScale(-1f, 1f);
            case ExifInterface.ORIENTATION_ROTATE_180 -> matrix.setRotate(180f);
            case ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.setScale(1f, -1f);
            case ExifInterface.ORIENTATION_TRANSPOSE -> {
                matrix.setRotate(90f);
                matrix.postScale(-1f, 1f);
            }
            case ExifInterface.ORIENTATION_ROTATE_90 -> matrix.setRotate(90f);
            case ExifInterface.ORIENTATION_TRANSVERSE -> {
                matrix.setRotate(-90f);
                matrix.postScale(-1f, 1f);
            }
            case ExifInterface.ORIENTATION_ROTATE_270 -> matrix.setRotate(-90f);
            default -> {
                return source;
            }
        }
        return Bitmap.createBitmap(source, 0, 0, source.getWidth(), source.getHeight(),
                matrix, true);
    }

    private static Bitmap flattenOnWhite(Bitmap source) {
        Bitmap out = Bitmap.createBitmap(source.getWidth(), source.getHeight(),
                Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(out);
        canvas.drawColor(Color.WHITE);
        canvas.drawBitmap(source, 0f, 0f, null);
        return out;
    }
}
