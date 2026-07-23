package ru.teamworld.reports;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;

final class DiscordBotClient implements AutoCloseable {
    private static final URI GATEWAY = URI.create("wss://gateway.discord.gg/?v=10&encoding=json");

    private final ReportDGPlugin plugin;
    private final PluginConfig config;
    private final ReportService reportService;
    private final DiscordRestClient rest;
    private final HttpClient gatewayHttp;
    private final ScheduledExecutorService executor;
    private final AtomicBoolean stopped = new AtomicBoolean(false);
    private final AtomicBoolean reconnectScheduled = new AtomicBoolean(false);
    private volatile WebSocket socket;
    private volatile long sequence = -1L;
    private volatile ScheduledFuture<?> heartbeatTask;
    private volatile boolean ready;

    DiscordBotClient(ReportDGPlugin plugin, PluginConfig config, ReportService reportService) {
        this.plugin = plugin;
        this.config = config;
        this.reportService = reportService;
        this.rest = new DiscordRestClient(config.botToken);
        this.gatewayHttp = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();
        this.executor = Executors.newScheduledThreadPool(2, DiscordRestClient.daemonFactory("ReportDG-Discord-Gateway"));
    }

    void start() {
        connect();
    }

    boolean isReady() { return ready; }

    CompletableFuture<String> sendReport(ReportRecord report) {
        return rest.sendChannelMessage(config.reportChannelId, DiscordPayloads.newReport(report, config))
                .thenApply(message -> Json.string(message, "id", ""));
    }

    private void connect() {
        if (stopped.get()) return;
        ready = false;
        reconnectScheduled.set(false);
        sequence = -1L;
        gatewayHttp.newWebSocketBuilder()
                .connectTimeout(Duration.ofSeconds(20))
                .buildAsync(GATEWAY, new GatewayListener())
                .whenComplete((webSocket, error) -> {
                    if (error != null) {
                        plugin.getLogger().log(Level.WARNING, "Не удалось подключиться к Discord Gateway: " + rootMessage(error));
                        scheduleReconnect();
                    } else {
                        socket = webSocket;
                    }
                });
    }

    private void processGatewayMessage(String json) {
        try {
            Map<String, Object> root = Json.asObject(Json.parse(json));
            if (root == null) return;
            Object seq = root.get("s");
            if (seq instanceof Number) sequence = ((Number) seq).longValue();
            int op = (int) Json.longValue(root, "op", -1L);
            if (op == 10) {
                Map<String, Object> data = Json.asObject(root.get("d"));
                long interval = Math.max(1000L, Json.longValue(data, "heartbeat_interval", 45_000L));
                startHeartbeat(interval);
                identify();
            } else if (op == 0) {
                String event = Json.string(root, "t", "");
                if ("READY".equals(event)) {
                    ready = true;
                    Map<String, Object> data = Json.asObject(root.get("d"));
                    Map<String, Object> user = data == null ? null : Json.asObject(data.get("user"));
                    plugin.getLogger().info("Discord-бот подключён как " + Json.string(user, "username", "неизвестный бот") + ".");
                } else if ("INTERACTION_CREATE".equals(event)) {
                    Map<String, Object> interaction = Json.asObject(root.get("d"));
                    if (interaction != null) handleInteraction(interaction);
                }
            } else if (op == 1) {
                sendHeartbeat();
            } else if (op == 7 || op == 9) {
                reconnectNow();
            }
        } catch (RuntimeException exception) {
            plugin.getLogger().log(Level.WARNING, "Ошибка обработки события Discord: " + exception.getMessage(), exception);
        }
    }

    private void identify() {
        Map<String, Object> properties = Json.object();
        properties.put("os", System.getProperty("os.name", "unknown"));
        properties.put("browser", "ReportDG");
        properties.put("device", "ReportDG");

        Map<String, Object> data = Json.object();
        data.put("token", config.botToken);
        data.put("intents", Integer.valueOf(1));
        data.put("properties", properties);

        Map<String, Object> payload = Json.object();
        payload.put("op", Integer.valueOf(2));
        payload.put("d", data);
        sendGateway(payload);
    }

    private void startHeartbeat(long interval) {
        ScheduledFuture<?> old = heartbeatTask;
        if (old != null) old.cancel(false);
        heartbeatTask = executor.scheduleAtFixedRate(this::sendHeartbeat, interval / 2L, interval, TimeUnit.MILLISECONDS);
    }

    private void sendHeartbeat() {
        Map<String, Object> payload = Json.object();
        payload.put("op", Integer.valueOf(1));
        payload.put("d", sequence < 0L ? null : Long.valueOf(sequence));
        sendGateway(payload);
    }

    private void sendGateway(Map<String, Object> payload) {
        WebSocket current = socket;
        if (current == null || stopped.get()) return;
        current.sendText(Json.stringify(payload), true).exceptionally(error -> {
            plugin.getLogger().warning("Не удалось отправить пакет Discord Gateway: " + rootMessage(error));
            scheduleReconnect();
            return null;
        });
    }

    private void handleInteraction(Map<String, Object> interaction) {
        long type = Json.longValue(interaction, "type", -1L);
        if (type == 3L) {
            handleComponentInteraction(interaction);
        } else if (type == 5L) {
            handleModalSubmit(interaction);
        }
    }

    private void handleComponentInteraction(Map<String, Object> interaction) {
        Map<String, Object> data = Json.asObject(interaction.get("data"));
        String customId = Json.string(data, "custom_id", "");
        String[] parts = parseReportCustomId(customId);
        if (parts == null) return;

        String interactionId = Json.string(interaction, "id", "");
        String interactionToken = Json.string(interaction, "token", "");
        if (interactionId.isBlank() || interactionToken.isBlank()) return;

        Moderator moderator = moderator(interaction);
        if (!isAuthorized(moderator)) {
            rest.interactionCallback(interactionId, interactionToken,
                    DiscordPayloads.ephemeral("⛔ У вас нет роли или разрешения для обработки репортов."))
                    .exceptionally(error -> { logCallbackError(error); return null; });
            return;
        }

        String action = parts[1];
        String reportId = parts[2];
        if ("reply".equals(action)) {
            ReportRecord report = reportService.findReport(reportId);
            if (report == null) {
                rest.interactionCallback(interactionId, interactionToken,
                        DiscordPayloads.ephemeral("Репорт не найден в базе сервера."))
                        .exceptionally(error -> { logCallbackError(error); return null; });
                return;
            }
            rest.interactionCallback(interactionId, interactionToken, DiscordPayloads.replyModal(report, config))
                    .exceptionally(error -> { logCallbackError(error); return null; });
            return;
        }

        ReportStatus targetStatus;
        if ("accept".equals(action)) targetStatus = ReportStatus.ACCEPTED;
        else if ("reject".equals(action)) targetStatus = ReportStatus.REJECTED;
        else return;

        ReportService.HandleResult result = reportService.handleFromDiscord(reportId, targetStatus, moderator.name, moderator.id);
        if (result.code == ReportService.HandleCode.NOT_FOUND) {
            rest.interactionCallback(interactionId, interactionToken, DiscordPayloads.ephemeral("Репорт не найден в базе сервера."))
                    .exceptionally(error -> { logCallbackError(error); return null; });
            return;
        }
        if (result.code == ReportService.HandleCode.ALREADY_HANDLED) {
            String by = result.report.moderatorName().isBlank() ? "другим администратором" : result.report.moderatorName();
            rest.interactionCallback(interactionId, interactionToken,
                    DiscordPayloads.ephemeral("Этот репорт уже обработан: " + result.report.status().name() + " — " + by + "."))
                    .exceptionally(error -> { logCallbackError(error); return null; });
            return;
        }
        if (result.code == ReportService.HandleCode.SAVE_FAILED) {
            rest.interactionCallback(interactionId, interactionToken,
                    DiscordPayloads.ephemeral("Ошибка сохранения репорта на Minecraft-сервере. Проверьте консоль."))
                    .exceptionally(error -> { logCallbackError(error); return null; });
            return;
        }

        ReportRecord report = result.report;
        rest.interactionCallback(interactionId, interactionToken, DiscordPayloads.handledUpdate(report, config))
                .whenComplete((ignored, error) -> {
                    if (error != null) logCallbackError(error);
                    if (report.status() == ReportStatus.REJECTED && PluginConfig.isSnowflake(config.rejectedChannelId)) {
                        rest.sendChannelMessage(config.rejectedChannelId, DiscordPayloads.rejectedLog(report, config))
                                .exceptionally(logError -> {
                                    plugin.getLogger().warning("Не удалось отправить отклонённый репорт в отдельный канал: " + rootMessage(logError));
                                    return null;
                                });
                    }
                });
        reportService.notifyReporterHandled(report);
    }

    private void handleModalSubmit(Map<String, Object> interaction) {
        Map<String, Object> data = Json.asObject(interaction.get("data"));
        String customId = Json.string(data, "custom_id", "");
        String[] parts = parseReportCustomId(customId);
        if (parts == null || !"reply".equals(parts[1])) return;

        String interactionId = Json.string(interaction, "id", "");
        String interactionToken = Json.string(interaction, "token", "");
        if (interactionId.isBlank() || interactionToken.isBlank()) return;

        Moderator moderator = moderator(interaction);
        if (!isAuthorized(moderator)) {
            rest.interactionCallback(interactionId, interactionToken,
                    DiscordPayloads.ephemeral("⛔ У вас нет роли или разрешения для ответа на репорты."))
                    .exceptionally(error -> { logCallbackError(error); return null; });
            return;
        }

        String answer = findInputValue(data == null ? null : data.get("components"), "reply_text").trim();
        if (answer.isEmpty()) {
            rest.interactionCallback(interactionId, interactionToken,
                    DiscordPayloads.ephemeral("Ответ не может быть пустым."))
                    .exceptionally(error -> { logCallbackError(error); return null; });
            return;
        }
        if (answer.length() > config.maxReplyLength) {
            rest.interactionCallback(interactionId, interactionToken,
                    DiscordPayloads.ephemeral("Ответ слишком длинный. Максимум: " + config.maxReplyLength + " символов."))
                    .exceptionally(error -> { logCallbackError(error); return null; });
            return;
        }

        ReportService.ReplyResult result = reportService.replyFromDiscord(parts[2], moderator.name, moderator.id, answer);
        if (result.code == ReportService.ReplyCode.NOT_FOUND) {
            rest.interactionCallback(interactionId, interactionToken,
                    DiscordPayloads.ephemeral("Репорт не найден в базе сервера."))
                    .exceptionally(error -> { logCallbackError(error); return null; });
            return;
        }
        if (result.code == ReportService.ReplyCode.SAVE_FAILED) {
            rest.interactionCallback(interactionId, interactionToken,
                    DiscordPayloads.ephemeral("Не удалось сохранить ответ на Minecraft-сервере. Проверьте консоль."))
                    .exceptionally(error -> { logCallbackError(error); return null; });
            return;
        }

        rest.interactionCallback(interactionId, interactionToken,
                DiscordPayloads.ephemeral("✅ Ответ сохранён. Игрок **" + result.report.reporterName()
                        + "** получит его в Minecraft сейчас или при следующем входе."))
                .exceptionally(error -> { logCallbackError(error); return null; });
    }

    private static String[] parseReportCustomId(String customId) {
        if (!(customId.startsWith("reportdg:") || customId.startsWith("teamreports:"))) return null;
        String[] parts = customId.split(":", 3);
        return parts.length == 3 ? parts : null;
    }

    private static String findInputValue(Object node, String customId) {
        Map<String, Object> object = Json.asObject(node);
        if (object != null) {
            if (customId.equals(Json.string(object, "custom_id", ""))) {
                String value = Json.string(object, "value", "");
                if (!value.isEmpty()) return value;
            }
            String nested = findInputValue(object.get("component"), customId);
            if (!nested.isEmpty()) return nested;
            nested = findInputValue(object.get("components"), customId);
            if (!nested.isEmpty()) return nested;
        }
        List<Object> array = Json.asArray(node);
        if (array != null) {
            for (Object item : array) {
                String nested = findInputValue(item, customId);
                if (!nested.isEmpty()) return nested;
            }
        }
        return "";
    }

    private Moderator moderator(Map<String, Object> interaction) {
        Map<String, Object> member = Json.asObject(interaction.get("member"));
        Map<String, Object> user = member == null ? Json.asObject(interaction.get("user")) : Json.asObject(member.get("user"));
        String id = Json.string(user, "id", "");
        String name = member == null ? "" : Json.string(member, "nick", "");
        if (name.isBlank() || "null".equals(name)) name = Json.string(user, "global_name", "");
        if (name.isBlank() || "null".equals(name)) name = Json.string(user, "username", "DiscordAdmin");
        java.util.HashSet<String> roles = new java.util.HashSet<String>();
        List<Object> rawRoles = member == null ? null : Json.asArray(member.get("roles"));
        if (rawRoles != null) for (Object role : rawRoles) roles.add(String.valueOf(role));
        return new Moderator(id, name, roles);
    }

    private boolean isAuthorized(Moderator moderator) {
        if (config.allowAnyDiscordUser) return true;
        if (config.staffUserIds.contains(moderator.id)) return true;
        for (String role : moderator.roles) if (config.staffRoleIds.contains(role)) return true;
        return false;
    }

    private void logCallbackError(Throwable error) {
        plugin.getLogger().warning("Не удалось ответить на нажатие кнопки Discord: " + rootMessage(error));
    }

    private void reconnectNow() {
        WebSocket current = socket;
        socket = null;
        ready = false;
        if (current != null) current.abort();
        scheduleReconnect();
    }

    private void scheduleReconnect() {
        if (stopped.get() || !reconnectScheduled.compareAndSet(false, true)) return;
        ready = false;
        executor.schedule(this::connect, 5L, TimeUnit.SECONDS);
    }

    private static String rootMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null) current = current.getCause();
        String message = current.getMessage();
        return message == null ? current.getClass().getSimpleName() : message;
    }

    @Override
    public void close() {
        if (!stopped.compareAndSet(false, true)) return;
        ready = false;
        ScheduledFuture<?> heartbeat = heartbeatTask;
        if (heartbeat != null) heartbeat.cancel(true);
        WebSocket current = socket;
        if (current != null) {
            try { current.sendClose(WebSocket.NORMAL_CLOSURE, "Plugin disabled"); }
            catch (RuntimeException ignored) { current.abort(); }
        }
        executor.shutdownNow();
        rest.close();
    }

    private final class GatewayListener implements WebSocket.Listener {
        private final StringBuilder buffer = new StringBuilder();

        @Override
        public void onOpen(WebSocket webSocket) {
            socket = webSocket;
            webSocket.request(1);
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            synchronized (buffer) {
                buffer.append(data);
                if (last) {
                    String message = buffer.toString();
                    buffer.setLength(0);
                    executor.execute(() -> processGatewayMessage(message));
                }
            }
            webSocket.request(1);
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            socket = null;
            ready = false;
            scheduleReconnect();
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            socket = null;
            ready = false;
            if (!stopped.get()) plugin.getLogger().warning("Discord Gateway отключён: " + rootMessage(error));
            scheduleReconnect();
        }
    }

    private static final class Moderator {
        final String id;
        final String name;
        final Set<String> roles;
        Moderator(String id, String name, Set<String> roles) {
            this.id = id;
            this.name = name;
            this.roles = roles;
        }
    }
}
