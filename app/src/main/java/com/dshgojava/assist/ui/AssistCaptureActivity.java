package com.dshgojava.assist.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.widget.Toast;
import com.dshgojava.assist.QuickTask;
import com.dshgojava.assist.R;
import com.dshgojava.assist.SourceKind;
import com.dshgojava.assist.TaskRegistry;
import com.dshgojava.assist.service.AssistOverlayService;
import com.dshgojava.assist.source.ImageSource;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;

/**
 * Short-lived foreground bridge for platform-restricted sources. Android 10+
 * only lets the focused app read the clipboard, so clipboard tasks briefly open
 * this transparent activity before handing the text to the overlay service. The
 * same activity owns the system image picker and the image share target.
 */
public final class AssistCaptureActivity extends Activity {
    public static final String ACTION_CLIP_TEXT =
            "com.dshgojava.assist.action.CLIP_TEXT";
    public static final String ACTION_IMAGE_SELECTED =
            "com.dshgojava.assist.action.IMAGE_SELECTED";
    public static final String EXTRA_TASK_ID = "task_id";
    public static final String EXTRA_CLIP_TEXT = "clip_text";
    public static final String EXTRA_IMAGE_URI = "image_uri";
    /** Multiple picked images, newest selection replacing the previous one. */
    public static final String EXTRA_IMAGE_URIS = "image_uris";

    private static final String EXTRA_MODE = "mode";
    private static final String MODE_CLIP = "clip";
    private static final String MODE_IMAGE = "image";
    private static final int REQUEST_IMAGE = 0x4153;
    private static final int READ_FLAGS = Intent.FLAG_GRANT_READ_URI_PERMISSION;
    private static final int MAX_IMAGE_BYTES = 32 * 1024 * 1024;

    private ExecutorService io;
    private String taskId;
    private QuickTask clipboardTask;
    private boolean clipboardReadStarted;
    private boolean finished;

    public static void readClipboard(Context context, QuickTask task) {
        launch(context, task, MODE_CLIP);
    }

    public static void pickImage(Context context, QuickTask task) {
        launch(context, task, MODE_IMAGE);
    }

    private static void launch(Context context, QuickTask task, String mode) {
        Intent intent = new Intent(context, AssistCaptureActivity.class)
                .putExtra(EXTRA_TASK_ID, task.id())
                .putExtra(EXTRA_MODE, mode)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        try {
            if (android.os.Build.VERSION.SDK_INT >= 34) {
                // Android 14+ 后台启动 Activity 需要显式声明：用 PendingIntent 发送并允许后台启动。
                android.app.ActivityOptions options = android.app.ActivityOptions.makeBasic()
                        .setPendingIntentBackgroundActivityStartMode(
                                android.app.ActivityOptions
                                        .MODE_BACKGROUND_ACTIVITY_START_ALLOWED);
                android.app.PendingIntent pending = android.app.PendingIntent.getActivity(
                        context,
                        (task.id() + ":" + mode).hashCode(),
                        intent,
                        android.app.PendingIntent.FLAG_UPDATE_CURRENT
                                | android.app.PendingIntent.FLAG_IMMUTABLE);
                pending.send(context, 0, null, null, null, null, options.toBundle());
            } else {
                context.startActivity(intent);
            }
        } catch (android.app.PendingIntent.CanceledException | RuntimeException error) {
            Toast.makeText(context, R.string.assist_capture_unavailable,
                    Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        io = Executors.newSingleThreadExecutor(runnable ->
                new Thread(runnable, "assist-capture"));

        Intent intent = getIntent();
        if (intent != null && Intent.ACTION_SEND.equals(intent.getAction())) {
            handleSharedImage(intent);
            return;
        }

        taskId = intent == null ? null : intent.getStringExtra(EXTRA_TASK_ID);
        String mode = intent == null ? null : intent.getStringExtra(EXTRA_MODE);
        QuickTask task = TaskRegistry.IMAGE_PICK.equals(taskId)
                ? TaskRegistry.imagePicker()
                : (taskId == null ? null : TaskRegistry.find(taskId).orElse(null));
        if (task == null || !modeMatches(task, mode)) {
            fail(R.string.assist_capture_unavailable);
            return;
        }
        if (MODE_CLIP.equals(mode)) {
            clipboardTask = task;
        } else {
            openImagePicker();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (clipboardTask != null) {
            getWindow().getDecorView().post(this::startClipboardRead);
        }
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) startClipboardRead();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (intent != null && Intent.ACTION_SEND.equals(intent.getAction())) {
            handleSharedImage(intent);
        }
    }

    @Override
    protected void onDestroy() {
        if (io != null) {
            io.shutdownNow();
            io = null;
        }
        super.onDestroy();
    }

    private static boolean modeMatches(QuickTask task, String mode) {
        if (MODE_CLIP.equals(mode)) {
            return task.source() == SourceKind.CLIP || TaskRegistry.isClipboardWeb(task);
        }
        if (MODE_IMAGE.equals(mode)) return task.source() == SourceKind.IMAGE;
        return false;
    }

    private void readClipboardAsync(QuickTask task) {
        try {
            io.execute(() -> {
                String text = null;
                int error = 0;
                ClipboardManager clipboard = getSystemService(ClipboardManager.class);
                if (clipboard == null) {
                    error = R.string.assist_clipboard_unavailable;
                } else {
                    try {
                        ClipData clip = clipboard.getPrimaryClip();
                        if (clip == null || clip.getItemCount() == 0) {
                            error = R.string.assist_clipboard_empty;
                        } else {
                            StringBuilder joined = new StringBuilder();
                            for (int i = 0; i < clip.getItemCount(); i++) {
                                ClipData.Item item = clip.getItemAt(i);
                                if (item == null) continue;
                                CharSequence value = item.coerceToText(this);
                                if (value == null || value.toString().isBlank()) continue;
                                if (joined.length() > 0) joined.append('\n');
                                joined.append(value);
                            }
                            if (joined.toString().isBlank()) {
                                error = R.string.assist_clipboard_empty;
                            } else {
                                text = joined.toString();
                            }
                        }
                    } catch (RuntimeException denied) {
                        error = R.string.assist_clipboard_unavailable;
                    }
                }
                String captured = text;
                int failure = error;
                runOnUiThread(() -> {
                    if (finished || isFinishing() || isDestroyed()) return;
                    if (failure != 0 || captured == null) {
                        fail(failure == 0 ? R.string.assist_clipboard_empty : failure);
                        return;
                    }
                    deliver(AssistCaptureActivity.ACTION_CLIP_TEXT,
                            task, EXTRA_CLIP_TEXT, captured);
                });
            });
        } catch (RejectedExecutionException error) {
            fail(R.string.assist_capture_unavailable);
        }
    }

    private void startClipboardRead() {
        if (finished || clipboardReadStarted || clipboardTask == null) return;
        clipboardReadStarted = true;
        readClipboardAsync(clipboardTask);
    }

    private void openImagePicker() {
        Intent picker = new Intent(Intent.ACTION_OPEN_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType("image/*")
                .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                .addFlags(READ_FLAGS);
        try {
            startActivityForResult(picker, REQUEST_IMAGE);
        } catch (RuntimeException error) {
            fail(R.string.assist_image_picker_unavailable);
        }
    }

    @Override
    @SuppressWarnings("deprecation")
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode != REQUEST_IMAGE) {
            super.onActivityResult(requestCode, resultCode, data);
            return;
        }
        if (resultCode != RESULT_OK || data == null) {
            finishNow();
            return;
        }
        List<Uri> uris = pickedUris(data);
        if (uris.isEmpty()) {
            fail(R.string.assist_image_pick_failed);
            return;
        }
        QuickTask task = TaskRegistry.IMAGE_PICK.equals(taskId)
                ? TaskRegistry.imagePicker()
                : (taskId == null ? null : TaskRegistry.find(taskId).orElse(null));
        if (task == null || task.source() != SourceKind.IMAGE) {
            fail(R.string.assist_capture_unavailable);
            return;
        }
        copyImagesAsync(uris, task);
    }

    /** Reads every picked image; older pickers only fill {@code data}. */
    private static List<Uri> pickedUris(Intent data) {
        List<Uri> uris = new ArrayList<>();
        ClipData clip = data.getClipData();
        if (clip != null) {
            for (int i = 0; i < clip.getItemCount(); i++) {
                Uri uri = clip.getItemAt(i) == null ? null : clip.getItemAt(i).getUri();
                if (uri != null) uris.add(uri);
            }
        }
        if (uris.isEmpty() && data.getData() != null) uris.add(data.getData());
        return uris;
    }

    private void handleSharedImage(Intent intent) {
        Uri uri = sharedUri(intent);
        if (uri == null) {
            fail(R.string.assist_image_pick_failed);
            return;
        }
        if (!isImage(intent, uri)) {
            fail(R.string.assist_image_not_supported);
            return;
        }
        if (intent.getClipData() != null && intent.getClipData().getItemCount() > 1) {
            Toast.makeText(this, R.string.assist_image_first_only, Toast.LENGTH_SHORT).show();
        }
        String[] tasks = {
                getString(R.string.assist_task_image_ocr),
                getString(R.string.assist_task_image_explain)
        };
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(R.string.assist_image_share_choose)
                .setItems(tasks, (ignored, which) -> {
                    QuickTask task = TaskRegistry.require(which == 0
                            ? "image.ocr" : "image.explain");
                    copyImagesAsync(List.of(uri), task);
                })
                .setOnCancelListener(ignored -> finishNow())
                .create();
        dialog.setOnDismissListener(ignored -> {
            if (!finished) finishNow();
        });
        dialog.show();
    }

    private Uri sharedUri(Intent intent) {
        Object stream = parcelableExtra(intent, Intent.EXTRA_STREAM);
        if (stream instanceof Uri uri) return uri;
        ClipData clip = intent.getClipData();
        if (clip != null && clip.getItemCount() > 0) {
            return clip.getItemAt(0).getUri();
        }
        return null;
    }

    @SuppressWarnings("deprecation")
    private static Object parcelableExtra(Intent intent, String name) {
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            return intent.getParcelableExtra(name, android.os.Parcelable.class);
        }
        return intent.getParcelableExtra(name);
    }

    private boolean isImage(Intent intent, Uri uri) {
        String type = intent.getType();
        if (type == null) {
            try {
                type = getContentResolver().getType(uri);
            } catch (RuntimeException ignored) {
                type = null;
            }
        }
        return type == null || type.startsWith("image/");
    }

    private void copyImagesAsync(List<Uri> sources, QuickTask task) {
        try {
            io.execute(() -> {
                List<Uri> copied = new ArrayList<>(Math.min(sources.size(), ImageSource.MAX_IMAGES));
                int error = 0;
                for (Uri source : sources) {
                    File target = null;
                    try {
                        File directory = new File(getCacheDir(), "assist-capture");
                        if (!directory.isDirectory() && !directory.mkdirs()) {
                            throw new IOException("cannot create capture cache");
                        }
                        target = new File(directory, "image-" + System.nanoTime() + ".img");
                        try (InputStream input = getContentResolver().openInputStream(source);
                             OutputStream output = new FileOutputStream(target)) {
                            if (input == null) throw new IOException("image stream unavailable");
                            byte[] buffer = new byte[16 * 1024];
                            int total = 0;
                            int read;
                            while ((read = input.read(buffer)) >= 0) {
                                total += read;
                                if (total > MAX_IMAGE_BYTES) {
                                    error = R.string.assist_image_too_large;
                                    break;
                                }
                                output.write(buffer, 0, read);
                            }
                            if (error == 0 && total == 0) error = R.string.assist_image_pick_failed;
                        }
                    } catch (SecurityException | IOException failure) {
                        error = R.string.assist_image_pick_failed;
                    }
                    if (error != 0) {
                        if (target != null) target.delete();
                        break;
                    }
                    copied.add(Uri.fromFile(target));
                    if (copied.size() >= ImageSource.MAX_IMAGES) break;
                }
                List<Uri> files = List.copyOf(copied);
                int failure = error;
                runOnUiThread(() -> {
                    if (finished || isFinishing() || isDestroyed()) {
                        deleteFiles(files);
                        return;
                    }
                    if (failure != 0 || files.isEmpty()) {
                        deleteFiles(files);
                        fail(failure == 0 ? R.string.assist_image_pick_failed : failure);
                        return;
                    }
                    deliver(ACTION_IMAGE_SELECTED, task, EXTRA_IMAGE_URIS, files);
                });
            });
        } catch (RejectedExecutionException failure) {
            fail(R.string.assist_capture_unavailable);
        }
    }

    private static void deleteFiles(List<Uri> files) {
        for (Uri file : files) {
            String path = file.getPath();
            if (path != null) new File(path).delete();
        }
    }

    private void deliver(String action, QuickTask task, String extra, Object value) {
        if (finished) return;
        finished = true;
        Intent forward = new Intent(this, AssistOverlayService.class)
                .setAction(action)
                .putExtra(EXTRA_TASK_ID, task.id());
        if (value instanceof Uri uri) {
            forward.putExtra(extra, uri);
            if ("content".equals(uri.getScheme())) {
                forward.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            }
        } else if (value instanceof List<?> list) {
            ArrayList<Uri> uris = new ArrayList<>(list.size());
            for (Object item : list) {
                if (item instanceof Uri uri) uris.add(uri);
            }
            forward.putParcelableArrayListExtra(extra, uris);
        } else {
            forward.putExtra(extra, String.valueOf(value));
        }
        try {
            startForegroundService(forward);
        } catch (RuntimeException error) {
            Toast.makeText(this, R.string.assist_capture_unavailable,
                    Toast.LENGTH_SHORT).show();
        }
        finishNow();
    }

    /** Removes private image copies once the overlay service is no longer using them. */
    public static void clearCache(Context context) {
        File directory = new File(context.getCacheDir(), "assist-capture");
        File[] files = directory.listFiles();
        if (files == null) return;
        for (File file : files) {
            if (file.isFile()) file.delete();
        }
    }

    private void fail(int message) {
        if (finished) return;
        finished = true;
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
        finishNow();
    }

    private void finishNow() {
        if (!isFinishing()) finish();
    }
}
