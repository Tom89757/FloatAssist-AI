package com.dshgojava.assist.source;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import com.dshgojava.assist.AssistRequest;
import com.dshgojava.assist.SourceKind;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;

/** Reads clipboard text only after the user selects a clipboard task. */
public final class ClipSource implements AssistSource {
    /** Text captured by the foreground clipboard bridge, already read with clipboard access. */
    public static final String CONTEXT_TEXT = "clip_text";

    private final Context context;
    private final Executor executor;

    public ClipSource(Context context) {
        this.context = context.getApplicationContext();
        this.executor = Executors.newSingleThreadExecutor(runnable ->
                new Thread(runnable, "assist-clipboard"));
    }

    @Override
    public SourceKind kind() {
        return SourceKind.CLIP;
    }

    @Override
    public boolean available() {
        return context.getSystemService(ClipboardManager.class) != null;
    }

    @Override
    public CompletableFuture<CaptureBundle> gather(AssistRequest request) {
        try {
            String bridged = request.context().get(CONTEXT_TEXT);
            if (bridged != null) {
                return SourceFutures.supply(executor, () -> bundleFromText(bridged));
            }
            return SourceFutures.supply(executor, this::readClipboard);
        } catch (RejectedExecutionException error) {
            return SourceFutures.failed(new CaptureException("剪贴板读取已停止"));
        }
    }

    /** Normalizes text captured while this app briefly held clipboard access. */
    static CaptureBundle bundleFromText(String text) {
        if (text == null) throw new CaptureException("剪贴板为空或系统限制读取");
        String normalized = ClipText.join(List.of(text));
        return CaptureBundle.text(normalized, Map.of(
                "source", "clipboard",
                "items", "1",
                "chars", Integer.toString(normalized.length())));
    }

    /** Stops the source executor when its owning foreground service is destroyed. */
    public void close() {
        if (executor instanceof java.util.concurrent.ExecutorService service) {
            service.shutdownNow();
        }
    }

    private CaptureBundle readClipboard() {
        ClipboardManager clipboard = context.getSystemService(ClipboardManager.class);
        if (clipboard == null) throw new CaptureException("剪贴板不可用");
        ClipData clip;
        try {
            clip = clipboard.getPrimaryClip();
        } catch (SecurityException error) {
            throw new CaptureException("系统限制了剪贴板读取，请回到应用后重试", error);
        }
        if (clip == null || clip.getItemCount() == 0) {
            throw new CaptureException("剪贴板为空或系统限制读取");
        }
        List<CharSequence> values = new ArrayList<>(clip.getItemCount());
        for (int i = 0; i < clip.getItemCount(); i++) {
            ClipData.Item item = clip.getItemAt(i);
            if (item == null) continue;
            try {
                values.add(item.coerceToText(context));
            } catch (RuntimeException error) {
                throw new CaptureException("剪贴板内容无法读取", error);
            }
        }
        String text = ClipText.join(values);
        return CaptureBundle.text(text, Map.of(
                "source", "clipboard",
                "items", Integer.toString(clip.getItemCount()),
                "chars", Integer.toString(text.length())));
    }
}
