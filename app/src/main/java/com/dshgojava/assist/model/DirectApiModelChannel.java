package com.dshgojava.assist.model;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okio.Buffer;
import okio.BufferedSource;

/**
 * Stateless OpenAI-compatible Chat Completions client. Requests are sent with
 * {@code stream=true}; every response is parsed off the caller's thread.
 */
public final class DirectApiModelChannel implements ModelChannel {
    public static final String DEFAULT_SYSTEM_PROMPT =
            "你是 QuickAssist 悬浮助手。只完成用户当前请求，使用简洁、准确的中文回答；"
                    + "不编造看不见或无法确认的内容。";

    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();
    private static final int MAX_ERROR_BODY_BYTES = 8 * 1024;
    private static final int MAX_NON_STREAM_BODY_BYTES = 2 * 1024 * 1024;
    private static final int MAX_ERROR_DETAIL_CHARS = 300;
    private static final OkHttpClient DEFAULT_CLIENT = new OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(90, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .callTimeout(0, TimeUnit.MILLISECONDS)
            .build();

    private final Config config;
    private final OkHttpClient client;

    /** Immutable connection settings; {@link #toString()} never exposes the key. */
    public record Config(
            String baseUrl,
            String apiKey,
            String model,
            boolean supportsVision,
            String systemPrompt) {
        public Config {
            baseUrl = strip(baseUrl);
            apiKey = strip(apiKey);
            model = strip(model);
            systemPrompt = strip(systemPrompt);
            if (systemPrompt.isEmpty()) {
                systemPrompt = DEFAULT_SYSTEM_PROMPT;
            }
        }

        public static Config unconfigured() {
            return new Config("", "", "", false, "");
        }

        public boolean configured() {
            return !baseUrl.isEmpty() && !apiKey.isEmpty() && !model.isEmpty();
        }

        @Override
        public String toString() {
            return "Config{baseUrl='" + baseUrl + "', model='" + model
                    + "', supportsVision=" + supportsVision + ", apiKey=<redacted>}";
        }
    }

    public DirectApiModelChannel(Config config) {
        this(config, DEFAULT_CLIENT);
    }

    /** Injection seam for tests and for callers that already own an OkHttp client. */
    public DirectApiModelChannel(Config config, OkHttpClient client) {
        this.config = Objects.requireNonNull(config, "config");
        this.client = Objects.requireNonNull(client, "client");
    }

    public Config config() {
        return config;
    }

    @Override
    public boolean ready() {
        return config.configured();
    }

    @Override
    public boolean supportsVision() {
        return config.supportsVision();
    }

    public String endpoint() {
        return endpointFor(config.baseUrl());
    }

    @Override
    public CompletableFuture<String> complete(
            String prompt,
            List<PromptImage> images,
            Consumer<String> onDelta) {
        return complete(prompt, images, List.of(), onDelta);
    }

    @Override
    public CompletableFuture<String> complete(
            String prompt,
            List<PromptImage> images,
            List<ChatMessage> history,
            Consumer<String> onDelta) {
        Objects.requireNonNull(prompt, "prompt");
        List<PromptImage> safeImages = images == null ? List.of() : List.copyOf(images);
        List<ChatMessage> safeHistory = history == null ? List.of() : List.copyOf(history);
        Consumer<String> listener = onDelta == null ? ignored -> { } : onDelta;

        if (!ready()) {
            return failed(new ModelException(ModelException.Kind.NOT_CONFIGURED, "未配置模型"));
        }
        if (!safeImages.isEmpty() && !supportsVision()) {
            return failed(new ModelException(
                    ModelException.Kind.VISION_UNSUPPORTED, "当前模型不支持读图"));
        }
        if (prompt.isBlank()) {
            return failed(new ModelException(
                    ModelException.Kind.INVALID_CONFIG, "提示词为空"));
        }

        HttpUrl url;
        try {
            url = HttpUrl.get(endpoint());
        } catch (IllegalArgumentException error) {
            return failed(new ModelException(
                    ModelException.Kind.INVALID_CONFIG, "模型接口地址无效", error));
        }

        String body = buildRequestBody(
                config.model(), config.systemPrompt(), prompt, safeImages, safeHistory);
        Request request;
        try {
            request = new Request.Builder()
                    .url(url)
                    .header("Authorization", "Bearer " + config.apiKey())
                    .header("Accept", "text/event-stream")
                    .header("Content-Type", "application/json; charset=utf-8")
                    .post(RequestBody.create(body, JSON))
                    .build();
        } catch (IllegalArgumentException error) {
            return failed(new ModelException(
                    ModelException.Kind.INVALID_CONFIG, "模型请求配置无效", error));
        }

        CompletableFuture<String> future = new CompletableFuture<>();
        Call call = client.newCall(request);
        future.whenComplete((value, error) -> {
            if (future.isCancelled()) {
                call.cancel();
            }
        });
        call.enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException error) {
                if (future.isCancelled()) {
                    return;
                }
                future.completeExceptionally(new ModelException(
                        ModelException.Kind.TRANSPORT,
                        redact("网络请求失败：" + safeMessage(error)), error));
            }

            @Override
            public void onResponse(Call call, Response response) {
                try (Response closed = response) {
                    if (!closed.isSuccessful()) {
                        String raw = readLimited(closed.body(), MAX_ERROR_BODY_BYTES);
                        future.completeExceptionally(httpError(closed.code(), raw));
                        return;
                    }
                    ResponseBody responseBody = closed.body();
                    if (responseBody == null) {
                        future.completeExceptionally(new ModelException(
                                ModelException.Kind.PROTOCOL, "模型返回了空响应"));
                        return;
                    }
                    readSuccessful(responseBody, future, listener);
                } catch (ModelException error) {
                    future.completeExceptionally(error);
                } catch (IOException error) {
                    if (!future.isCancelled()) {
                        future.completeExceptionally(new ModelException(
                                ModelException.Kind.TRANSPORT,
                                redact("读取模型响应失败：" + safeMessage(error)), error));
                    }
                } catch (RuntimeException error) {
                    if (!future.isCancelled()) {
                        future.completeExceptionally(new ModelException(
                                ModelException.Kind.PROTOCOL, "处理模型响应失败", error));
                    }
                }
            }
        });
        return future;
    }

    static String endpointFor(String baseUrl) {
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalArgumentException("baseUrl must not be blank");
        }
        String endpoint = baseUrl.strip();
        while (endpoint.endsWith("/")) {
            endpoint = endpoint.substring(0, endpoint.length() - 1);
        }
        String lower = endpoint.toLowerCase(Locale.ROOT);
        if (lower.endsWith("/chat/completions")) {
            return endpoint;
        }
        if (lower.endsWith("/v1")) {
            return endpoint + "/chat/completions";
        }
        return endpoint + "/v1/chat/completions";
    }

    /** Package-visible pure builder so the wire shape can be tested without a network. */
    static String buildRequestBody(
            String model,
            String systemPrompt,
            String prompt,
            List<PromptImage> images) {
        return buildRequestBody(model, systemPrompt, prompt, images, List.of());
    }

    /** Builds system + history + the new user turn into one Chat Completions payload. */
    static String buildRequestBody(
            String model,
            String systemPrompt,
            String prompt,
            List<PromptImage> images,
            List<ChatMessage> history) {
        JsonObject root = new JsonObject();
        root.addProperty("model", model);
        root.addProperty("stream", true);

        JsonArray messages = new JsonArray();
        if (systemPrompt != null && !systemPrompt.isBlank()) {
            appendMessage(messages, ChatMessage.system(systemPrompt));
        }
        if (history != null) {
            for (ChatMessage message : history) {
                appendMessage(messages, message);
            }
        }
        appendMessage(messages, ChatMessage.user(prompt, images));
        root.add("messages", messages);
        return GSON.toJson(root);
    }

    private static void appendMessage(JsonArray messages, ChatMessage message) {
        JsonObject json = new JsonObject();
        json.addProperty("role", message.role());
        if (message.images().isEmpty()) {
            json.addProperty("content", message.text());
        } else {
            JsonArray content = new JsonArray();
            if (!message.text().isBlank()) {
                JsonObject text = new JsonObject();
                text.addProperty("type", "text");
                text.addProperty("text", message.text());
                content.add(text);
            }
            for (PromptImage image : message.images()) {
                JsonObject part = new JsonObject();
                part.addProperty("type", "image_url");
                JsonObject imageUrl = new JsonObject();
                imageUrl.addProperty("url", "data:" + image.mediaType() + ";base64,"
                        + Base64.getEncoder().encodeToString(image.data()));
                part.add("image_url", imageUrl);
                content.add(part);
            }
            json.add("content", content);
        }
        messages.add(json);
    }

    private void readSuccessful(
            ResponseBody body,
            CompletableFuture<String> future,
            Consumer<String> onDelta) throws IOException {
        BufferedSource source = body.source();
        if (looksLikeSse(body, source)) {
            readSse(source, future, onDelta);
            return;
        }
        String payload = readLimited(source, MAX_NON_STREAM_BODY_BYTES);
        String text = ChatCompletionJson.completedText(payload);
        if (text == null || text.isBlank()) {
            future.completeExceptionally(new ModelException(
                    ModelException.Kind.EMPTY_RESPONSE, "模型返回为空"));
            return;
        }
        onDelta.accept(text);
        future.complete(text);
    }

    private void readSse(
            BufferedSource source,
            CompletableFuture<String> future,
            Consumer<String> onDelta) throws IOException {
        ChatSseParser parser = new ChatSseParser();
        StringBuilder answer = new StringBuilder();
        boolean done = false;
        String line;
        while (!done && (line = source.readUtf8Line()) != null) {
            ChatSseParser.Event event = parser.accept(line);
            if (event != null) {
                done = consume(event, answer, onDelta);
            }
        }
        if (!done) {
            ChatSseParser.Event tail = parser.flush();
            if (tail != null) {
                consume(tail, answer, onDelta);
            }
        }
        if (future.isDone()) {
            return;
        }
        if (answer.toString().isBlank()) {
            future.completeExceptionally(new ModelException(
                    ModelException.Kind.EMPTY_RESPONSE, "模型返回为空"));
            return;
        }
        future.complete(answer.toString());
    }

    private boolean consume(
            ChatSseParser.Event event,
            StringBuilder answer,
            Consumer<String> onDelta) {
        if ("[DONE]".equals(event.data().strip())) {
            return true;
        }
        String delta = ChatCompletionJson.deltaText(event.data());
        if (delta != null && !delta.isEmpty()) {
            answer.append(delta);
            onDelta.accept(delta);
        }
        return false;
    }

    private ModelException httpError(int statusCode, String payload) {
        String detail = ChatCompletionJson.errorMessage(payload);
        if (detail == null || detail.isBlank()) {
            detail = payload;
        }
        detail = oneLine(detail);
        if (detail.length() > MAX_ERROR_DETAIL_CHARS) {
            detail = detail.substring(0, MAX_ERROR_DETAIL_CHARS) + "…";
        }
        String message = "模型接口返回 HTTP " + statusCode;
        if (!detail.isBlank()) {
            message += "：" + detail;
        }
        return new ModelException(
                ModelException.Kind.HTTP, statusCode, redact(message));
    }

    private String redact(String value) {
        String key = config.apiKey();
        if (value == null || key.isEmpty()) {
            return value;
        }
        return value.replace(key, "<redacted>");
    }

    private static String oneLine(String value) {
        if (value == null) {
            return "";
        }
        String cleaned = value.replace('\r', ' ').replace('\n', ' ').strip();
        StringBuilder safe = new StringBuilder(cleaned.length());
        for (int i = 0; i < cleaned.length(); i++) {
            char ch = cleaned.charAt(i);
            safe.append(Character.isISOControl(ch) ? ' ' : ch);
        }
        return safe.toString().replaceAll("\\s+", " ").strip();
    }

    private static String safeMessage(Throwable error) {
        String message = error == null ? "" : error.getMessage();
        return message == null || message.isBlank()
                ? (error == null ? "未知错误" : error.getClass().getSimpleName())
                : message;
    }

    private static String readLimited(ResponseBody body, int maxBytes) throws IOException {
        if (body == null) {
            return "";
        }
        return readLimited(body.source(), maxBytes);
    }

    private static String readLimited(BufferedSource source, int maxBytes) throws IOException {
        Buffer buffer = new Buffer();
        source.read(buffer, maxBytes);
        return buffer.readUtf8();
    }

    private static boolean looksLikeSse(ResponseBody body, BufferedSource source) throws IOException {
        MediaType mediaType = body.contentType();
        if (mediaType != null) {
            if ("text".equals(mediaType.type()) && "event-stream".equals(mediaType.subtype())) {
                return true;
            }
            if ("application".equals(mediaType.type())
                    && ("json".equals(mediaType.subtype())
                    || mediaType.subtype().endsWith("+json"))) {
                return false;
            }
        }
        BufferedSource peek = source.peek();
        for (int i = 0; i < 8; i++) {
            String line = peek.readUtf8Line();
            if (line == null) {
                return false;
            }
            String trimmed = line.strip();
            if (trimmed.isEmpty()) {
                continue;
            }
            return trimmed.startsWith("data:")
                    || trimmed.startsWith("event:")
                    || trimmed.startsWith("id:")
                    || trimmed.startsWith("retry:")
                    || trimmed.startsWith(":");
        }
        return false;
    }

    private static CompletableFuture<String> failed(ModelException error) {
        return CompletableFuture.failedFuture(error);
    }

    private static String strip(String value) {
        return value == null ? "" : value.strip();
    }
}
