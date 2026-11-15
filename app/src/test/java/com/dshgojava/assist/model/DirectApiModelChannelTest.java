package com.dshgojava.assist.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Response;
import okhttp3.ResponseBody;

class DirectApiModelChannelTest {
    private static final List<OkHttpClient> TEST_CLIENTS = new CopyOnWriteArrayList<>();

    @AfterAll
    static void closeTestClients() {
        for (OkHttpClient client : TEST_CLIENTS) {
            client.dispatcher().executorService().shutdownNow();
            client.connectionPool().evictAll();
        }
        TEST_CLIENTS.clear();
    }

    @Test
    void normalizesOpenAiCompatibleEndpoints() {
        assertEquals("https://api.openai.com/v1/chat/completions",
                DirectApiModelChannel.endpointFor("https://api.openai.com"));
        assertEquals("https://api.openai.com/v1/chat/completions",
                DirectApiModelChannel.endpointFor(" https://api.openai.com/v1/ "));
        assertEquals("https://host/openai/v1/chat/completions",
                DirectApiModelChannel.endpointFor("https://host/openai/v1"));
        assertEquals("https://host/custom/chat/completions",
                DirectApiModelChannel.endpointFor("https://host/custom/chat/completions/"));
        assertThrows(IllegalArgumentException.class,
                () -> DirectApiModelChannel.endpointFor("  "));
    }

    @Test
    void configReadinessAndToStringDoNotExposeTheKey() {
        DirectApiModelChannel.Config empty = DirectApiModelChannel.Config.unconfigured();
        assertFalse(empty.configured());

        DirectApiModelChannel channel = new DirectApiModelChannel(
                new DirectApiModelChannel.Config(
                        " https://api.openai.com/v1 ", "sk-secret-value",
                        " gpt-4o-mini ", true, ""));

        assertTrue(channel.ready());
        assertTrue(channel.supportsVision());
        assertEquals("https://api.openai.com/v1/chat/completions", channel.endpoint());
        assertFalse(channel.config().toString().contains("sk-secret-value"));
        assertEquals(DirectApiModelChannel.DEFAULT_SYSTEM_PROMPT,
                channel.config().systemPrompt());
    }

    @Test
    void requestBodyUsesSystemUserMessagesAndDataUrlImages() {
        PromptImage image = new PromptImage("image/png", new byte[] {1, 2, 3}, "shot.png");

        String body = DirectApiModelChannel.buildRequestBody(
                "gpt-4o-mini", "system text", "look at this", List.of(image));
        JsonObject root = JsonParser.parseString(body).getAsJsonObject();

        assertEquals("gpt-4o-mini", root.get("model").getAsString());
        assertTrue(root.get("stream").getAsBoolean());
        JsonArray messages = root.getAsJsonArray("messages");
        assertEquals(2, messages.size());
        assertEquals("system", messages.get(0).getAsJsonObject().get("role").getAsString());
        assertEquals("system text",
                messages.get(0).getAsJsonObject().get("content").getAsString());

        JsonObject user = messages.get(1).getAsJsonObject();
        assertEquals("user", user.get("role").getAsString());
        JsonArray content = user.getAsJsonArray("content");
        assertEquals(2, content.size());
        assertEquals("text", content.get(0).getAsJsonObject().get("type").getAsString());
        assertEquals("look at this",
                content.get(0).getAsJsonObject().get("text").getAsString());
        JsonObject imagePart = content.get(1).getAsJsonObject();
        assertEquals("image_url", imagePart.get("type").getAsString());
        assertEquals("data:image/png;base64,AQID",
                imagePart.getAsJsonObject("image_url").get("url").getAsString());
        assertFalse(body.contains("sk-"));
    }

    @Test
    void textOnlyRequestUsesAStringContent() {
        String body = DirectApiModelChannel.buildRequestBody(
                "model", "system", "plain prompt", List.of());
        JsonObject root = JsonParser.parseString(body).getAsJsonObject();
        JsonObject user = root.getAsJsonArray("messages").get(1).getAsJsonObject();

        assertTrue(user.get("content").isJsonPrimitive());
        assertEquals("plain prompt", user.get("content").getAsString());
    }

    @Test
    void nullChannelFailsWithTheDesignedMessage() {
        NullModelChannel channel = NullModelChannel.INSTANCE;

        assertFalse(channel.ready());
        assertFalse(channel.supportsVision());
        ExecutionException error = assertThrows(ExecutionException.class,
                () -> channel.complete("prompt", List.of(), ignored -> { })
                        .get(1, TimeUnit.SECONDS));
        IllegalStateException cause = assertInstanceOf(IllegalStateException.class, error.getCause());
        assertEquals("未配置模型", cause.getMessage());
    }

    @Test
    void directChannelRejectsMissingConfigWithoutNetwork() {
        DirectApiModelChannel channel = new DirectApiModelChannel(
                DirectApiModelChannel.Config.unconfigured());

        ExecutionException error = assertThrows(ExecutionException.class,
                () -> channel.complete("prompt", List.of(), ignored -> { })
                        .get(1, TimeUnit.SECONDS));
        ModelException cause = assertInstanceOf(ModelException.class, error.getCause());
        assertEquals(ModelException.Kind.NOT_CONFIGURED, cause.kind());
        assertEquals("未配置模型", cause.getMessage());
    }

    @Test
    void directChannelRejectsImagesWhenVisionIsNotConfigured() {
        DirectApiModelChannel channel = new DirectApiModelChannel(
                new DirectApiModelChannel.Config(
                        "https://example.invalid/v1", "sk-test", "text-model", false, ""));
        PromptImage image = PromptImage.jpeg(new byte[] {1}, "shot.jpg");

        ExecutionException error = assertThrows(ExecutionException.class,
                () -> channel.complete("prompt", List.of(image), ignored -> { })
                        .get(1, TimeUnit.SECONDS));
        ModelException cause = assertInstanceOf(ModelException.class, error.getCause());
        assertEquals(ModelException.Kind.VISION_UNSUPPORTED, cause.kind());
        assertEquals("当前模型不支持读图", cause.getMessage());
    }

    @Test
    void streamsSseDeltasAndCompletesWithJoinedText() throws Exception {
        String sse = "data: {\"choices\":[{\"delta\":{\"content\":\"你\"}}]}\n\n"
                + "data: {\"choices\":[{\"delta\":{\"content\":\"好\"}}]}\n\n"
                + "data: [DONE]\n\n";
        DirectApiModelChannel channel = channelWithResponse(
                200, "text/event-stream; charset=utf-8", sse);
        List<String> deltas = new CopyOnWriteArrayList<>();

        String answer = channel.complete("prompt", List.of(), deltas::add)
                .get(2, TimeUnit.SECONDS);

        assertEquals("你好", answer);
        assertEquals(List.of("你", "好"), deltas);
    }

    @Test
    void acceptsANonStreamingJsonResponseAsOneDelta() throws Exception {
        DirectApiModelChannel channel = channelWithResponse(
                200,
                "application/json; charset=utf-8",
                "{\"choices\":[{\"message\":{\"content\":\"完整回答\"}}]}");
        List<String> deltas = new ArrayList<>();

        String answer = channel.complete("prompt", List.of(), deltas::add)
                .get(2, TimeUnit.SECONDS);

        assertEquals("完整回答", answer);
        assertEquals(List.of("完整回答"), deltas);
    }

    @Test
    void httpErrorsAreExplicitAndRedactTheApiKey() {
        DirectApiModelChannel channel = channelWithResponse(
                401,
                "application/json; charset=utf-8",
                "{\"error\":{\"message\":\"bad key sk-test-secret\"}}");

        ExecutionException error = assertThrows(ExecutionException.class,
                () -> channel.complete("prompt", List.of(), ignored -> { })
                        .get(2, TimeUnit.SECONDS));
        ModelException cause = assertInstanceOf(ModelException.class, error.getCause());

        assertEquals(ModelException.Kind.HTTP, cause.kind());
        assertEquals(401, cause.statusCode());
        assertTrue(cause.getMessage().contains("HTTP 401"));
        assertFalse(cause.getMessage().contains("sk-test-secret"));
        assertTrue(cause.getMessage().contains("<redacted>"));
        assertFalse(cause.retryable());
    }

    @Test
    void anEmptyStreamIsAnExplicitEmptyResponse() {
        DirectApiModelChannel channel = channelWithResponse(
                200, "text/event-stream; charset=utf-8", "data: [DONE]\n\n");

        ExecutionException error = assertThrows(ExecutionException.class,
                () -> channel.complete("prompt", List.of(), ignored -> { })
                        .get(2, TimeUnit.SECONDS));
        ModelException cause = assertInstanceOf(ModelException.class, error.getCause());

        assertEquals(ModelException.Kind.EMPTY_RESPONSE, cause.kind());
        assertEquals("模型返回为空", cause.getMessage());
    }

    private static DirectApiModelChannel channelWithResponse(
            int code, String contentType, String body) {
        OkHttpClient client = new OkHttpClient.Builder()
                .addInterceptor(chain -> new Response.Builder()
                        .request(chain.request())
                        .protocol(Protocol.HTTP_1_1)
                        .code(code)
                        .message("test")
                        .body(ResponseBody.create(body, MediaType.get(contentType)))
                        .build())
                .build();
        TEST_CLIENTS.add(client);
        return new DirectApiModelChannel(
                new DirectApiModelChannel.Config(
                        "https://example.invalid/v1", "sk-test-secret",
                        "test-model", false, "system"),
                client);
    }
}
