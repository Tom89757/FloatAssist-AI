package com.dshgojava.assist.source;

import android.content.Context;
import android.net.Uri;
import com.dshgojava.assist.AssistRequest;
import com.dshgojava.assist.SourceKind;
import com.dshgojava.assist.model.PromptImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicReference;

/** Reads a user-selected image URI; selection is supplied by the UI or request context. */
public final class ImageSource implements AssistSource {
    public static final String CONTEXT_URI = "image_uri";
    private static final int MAX_INPUT_BYTES = 32 * 1024 * 1024;
    private static final int MAX_EDGE_PX = 1_024;

    private final Context context;
    private final Executor executor;
    private final AtomicReference<Uri> selected = new AtomicReference<>();

    public ImageSource(Context context) {
        this.context = context.getApplicationContext();
        this.executor = Executors.newSingleThreadExecutor(runnable ->
                new Thread(runnable, "assist-image"));
    }

    @Override
    public SourceKind kind() {
        return SourceKind.IMAGE;
    }

    @Override
    public boolean available() {
        return selected.get() != null;
    }

    public void select(Uri uri) {
        selected.set(uri == null || uri.toString().isBlank() ? null : uri);
    }

    public void clearSelection() {
        selected.set(null);
    }

    @Override
    public CompletableFuture<CaptureBundle> gather(AssistRequest request) {
        String supplied = request.context().get(CONTEXT_URI);
        Uri uri = supplied == null || supplied.isBlank()
                ? selected.get() : Uri.parse(supplied.strip());
        if (uri == null) return SourceFutures.failed(new CaptureException("尚未选择图片"));
        try {
            return SourceFutures.supply(executor, () -> read(uri));
        } catch (RejectedExecutionException error) {
            return SourceFutures.failed(new CaptureException("图片读取已停止"));
        }
    }

    /** Stops the source executor when its owning foreground service is destroyed. */
    public void close() {
        selected.set(null);
        if (executor instanceof java.util.concurrent.ExecutorService service) {
            service.shutdownNow();
        }
    }

    private CaptureBundle read(Uri uri) {
        String declaredType = null;
        try {
            declaredType = context.getContentResolver().getType(uri);
        } catch (RuntimeException ignored) {
        }
        if (declaredType != null && !declaredType.startsWith("image/")) {
            throw new CaptureException("所选文件不是图片");
        }
        byte[] source;
        try {
            source = readBytes(uri);
        } catch (SecurityException error) {
            throw new CaptureException("无法读取所选图片", error);
        } catch (IOException error) {
            throw new CaptureException("图片读取失败", error);
        }
        try {
            ImageDownsampler.Encoded encoded = ImageDownsampler.downsample(source, MAX_EDGE_PX, 85);
            PromptImage image = new PromptImage(encoded.mediaType(), encoded.bytes(), "image.jpg");
            return new CaptureBundle("", List.of(image), Map.of(
                    "source", "image",
                    "input_bytes", Integer.toString(source.length),
                    "image_bytes", Integer.toString(encoded.bytes().length),
                    "image_width", Integer.toString(encoded.width()),
                    "image_height", Integer.toString(encoded.height())));
        } catch (IOException error) {
            throw new CaptureException("无法解析所选图片", error);
        } catch (OutOfMemoryError error) {
            throw new CaptureException("图片过大，无法处理", error);
        }
    }

    private byte[] readBytes(Uri uri) throws IOException {
        InputStream input = null;
        try {
            if ("file".equals(uri.getScheme())) {
                String path = uri.getPath();
                if (path == null) throw new IOException("missing image path");
                input = new FileInputStream(new File(path));
            } else {
                input = context.getContentResolver().openInputStream(uri);
            }
            if (input == null) throw new IOException("image stream unavailable");
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[16 * 1024];
            int total = 0;
            int read;
            while ((read = input.read(buffer)) >= 0) {
                total += read;
                if (total > MAX_INPUT_BYTES) {
                    throw new CaptureException("图片文件过大，无法安全处理");
                }
                out.write(buffer, 0, read);
            }
            return out.toByteArray();
        } finally {
            if (input != null) {
                try {
                    input.close();
                } catch (IOException ignored) {
                }
            }
        }
    }
}
