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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicReference;

/** Reads user-selected image URIs; selection is supplied by the UI or request context. */
public final class ImageSource implements AssistSource {
    public static final String CONTEXT_URI = "image_uri";
    public static final int MAX_IMAGES = 4;
    private static final int MAX_INPUT_BYTES = 32 * 1024 * 1024;
    private static final int MAX_EDGE_PX = 1_024;

    private final Context context;
    private final Executor executor;
    private final AtomicReference<List<Uri>> selected = new AtomicReference<>(List.of());

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
        return !selected.get().isEmpty();
    }

    public void select(Uri uri) {
        select(uri == null ? List.of() : List.of(uri));
    }

    /** Stores the picked selection, capped at {@link #MAX_IMAGES}. */
    public void select(List<Uri> uris) {
        if (uris == null || uris.isEmpty()) {
            selected.set(List.of());
            return;
        }
        List<Uri> cleaned = new ArrayList<>(Math.min(uris.size(), MAX_IMAGES));
        for (Uri uri : uris) {
            if (uri == null || uri.toString().isBlank()) continue;
            cleaned.add(uri);
            if (cleaned.size() >= MAX_IMAGES) break;
        }
        selected.set(List.copyOf(cleaned));
    }

    public int selectedCount() {
        return selected.get().size();
    }

    public void clearSelection() {
        selected.set(List.of());
    }

    @Override
    public CompletableFuture<CaptureBundle> gather(AssistRequest request) {
        String supplied = request.context().get(CONTEXT_URI);
        List<Uri> uris = supplied == null || supplied.isBlank()
                ? selected.get() : List.of(Uri.parse(supplied.strip()));
        if (uris.isEmpty()) return SourceFutures.failed(new CaptureException("尚未选择图片"));
        try {
            return SourceFutures.supply(executor, () -> read(uris));
        } catch (RejectedExecutionException error) {
            return SourceFutures.failed(new CaptureException("图片读取已停止"));
        }
    }

    /** Stops the source executor when its owning foreground service is destroyed. */
    public void close() {
        selected.set(List.of());
        if (executor instanceof java.util.concurrent.ExecutorService service) {
            service.shutdownNow();
        }
    }

    private CaptureBundle read(List<Uri> uris) {
        List<PromptImage> images = new ArrayList<>(uris.size());
        int inputBytes = 0;
        int imageBytes = 0;
        for (int index = 0; index < uris.size(); index++) {
            Uri uri = uris.get(index);
            byte[] source = readSource(uri);
            inputBytes += source.length;
            try {
                ImageDownsampler.Encoded encoded =
                        ImageDownsampler.downsample(source, MAX_EDGE_PX, 85);
                images.add(new PromptImage(encoded.mediaType(), encoded.bytes(),
                        "image-" + (index + 1) + ".jpg"));
                imageBytes += encoded.bytes().length;
            } catch (IOException error) {
                throw new CaptureException("无法解析所选图片", error);
            } catch (OutOfMemoryError error) {
                throw new CaptureException("图片过大，无法处理", error);
            }
        }
        return new CaptureBundle("", images, Map.of(
                "source", "image",
                "images", Integer.toString(images.size()),
                "input_bytes", Integer.toString(inputBytes),
                "image_bytes", Integer.toString(imageBytes)));
    }

    private byte[] readSource(Uri uri) {
        String declaredType = null;
        try {
            declaredType = context.getContentResolver().getType(uri);
        } catch (RuntimeException ignored) {
        }
        if (declaredType != null && !declaredType.startsWith("image/")) {
            throw new CaptureException("所选文件不是图片");
        }
        try {
            return readBytes(uri);
        } catch (SecurityException error) {
            throw new CaptureException("无法读取所选图片", error);
        } catch (IOException error) {
            throw new CaptureException("图片读取失败", error);
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
