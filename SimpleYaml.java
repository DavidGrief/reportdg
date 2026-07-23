package ru.teamworld.reports;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

final class DiscordRestClient implements AutoCloseable {
    private static final String API = "https://discord.com/api/v10";
    private final HttpClient http;
    private final ScheduledExecutorService scheduler;
    private final String token;

    DiscordRestClient(String token) {
        this.token = token;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(daemonFactory("ReportDG-Discord-REST"));
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();
    }

    CompletableFuture<Map<String, Object>> sendChannelMessage(String channelId, Map<String, Object> payload) {
        return request("POST", API + "/channels/" + channelId + "/messages", payload, true, 0)
                .thenApply(body -> {
                    Map<String, Object> object = Json.asObject(Json.parse(body));
                    if (object == null) throw new IllegalStateException("Discord returned invalid message JSON");
                    return object;
                });
    }

    CompletableFuture<Void> interactionCallback(String interactionId, String interactionToken, Map<String, Object> payload) {
        return request("POST", API + "/interactions/" + interactionId + "/" + interactionToken + "/callback", payload, false, 0)
                .thenApply(ignored -> null);
    }

    private CompletableFuture<String> request(String method, String url, Map<String, Object> payload, boolean authorization, int attempt) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(20))
                .header("Content-Type", "application/json; charset=UTF-8")
                .header("User-Agent", "ReportDG/1.1.0 (Minecraft Discord reports)")
                .method(method, HttpRequest.BodyPublishers.ofString(Json.stringify(payload), java.nio.charset.StandardCharsets.UTF_8));
        if (authorization) builder.header("Authorization", "Bot " + token);

        return http.sendAsync(builder.build(), HttpResponse.BodyHandlers.ofString(java.nio.charset.StandardCharsets.UTF_8))
                .thenCompose(response -> {
                    int code = response.statusCode();
                    if (code >= 200 && code < 300) return CompletableFuture.completedFuture(response.body());
                    if (code == 429 && attempt < 4) {
                        long delayMillis = retryAfterMillis(response.body(), attempt);
                        CompletableFuture<String> delayed = new CompletableFuture<String>();
                        scheduler.schedule(() -> request(method, url, payload, authorization, attempt + 1)
                                .whenComplete((value, error) -> {
                                    if (error != null) delayed.completeExceptionally(error); else delayed.complete(value);
                                }), delayMillis, TimeUnit.MILLISECONDS);
                        return delayed;
                    }
                    String body = Text.truncate(response.body(), 600);
                    return failed(new DiscordHttpException(code, body));
                });
    }

    private static long retryAfterMillis(String body, int attempt) {
        try {
            Map<String, Object> object = Json.asObject(Json.parse(body));
            Object retry = object == null ? null : object.get("retry_after");
            if (retry instanceof Number) return Math.max(250L, (long) (((Number) retry).doubleValue() * 1000.0) + 100L);
        } catch (RuntimeException ignored) {}
        return Math.min(10_000L, 1000L << attempt);
    }

    private static <T> CompletableFuture<T> failed(Throwable throwable) {
        CompletableFuture<T> future = new CompletableFuture<T>();
        future.completeExceptionally(throwable);
        return future;
    }

    @Override
    public void close() {
        scheduler.shutdownNow();
    }

    static ThreadFactory daemonFactory(String name) {
        return runnable -> {
            Thread thread = new Thread(runnable, name);
            thread.setDaemon(true);
            return thread;
        };
    }

    static final class DiscordHttpException extends RuntimeException {
        private final int statusCode;
        DiscordHttpException(int statusCode, String responseBody) {
            super("Discord HTTP " + statusCode + ": " + responseBody);
            this.statusCode = statusCode;
        }
        int statusCode() { return statusCode; }
    }
}
