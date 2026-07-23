package ru.teamworld.reports;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;

final class ReportService {
    enum HandleCode { SUCCESS, NOT_FOUND, ALREADY_HANDLED, SAVE_FAILED }
    enum ReplyCode { SUCCESS, NOT_FOUND, SAVE_FAILED }

    static final class HandleResult {
        final HandleCode code;
        final ReportRecord report;
        HandleResult(HandleCode code, ReportRecord report) { this.code = code; this.report = report; }
    }

    static final class ReplyResult {
        final ReplyCode code;
        final ReportRecord report;
        final ReportReply reply;
        ReplyResult(ReplyCode code, ReportRecord report, ReportReply reply) {
            this.code = code;
            this.report = report;
            this.reply = reply;
        }
    }

    private final ReportDGPlugin plugin;
    private final ReportStore store;
    private volatile PluginConfig config;
    private volatile DiscordBotClient discord;

    ReportService(ReportDGPlugin plugin, ReportStore store, PluginConfig config) {
        this.plugin = plugin;
        this.store = store;
        this.config = config;
    }

    void setConfig(PluginConfig config) { this.config = config; }
    void setDiscord(DiscordBotClient discord) { this.discord = discord; }

    boolean isDiscordConfigured() {
        return config.hasDiscordCredentials() && discord != null;
    }

    ReportRecord createReport(Player reporter, String target, String reason) throws IOException {
        return store.create(reporter.getName(), reporter.getUniqueId(), target, reason);
    }

    long remainingSameTargetCooldown(Player reporter, String target, long now) {
        long cooldownMillis = config.sameTargetCooldownSeconds * 1000L;
        return store.remainingSameTargetCooldown(reporter.getUniqueId(), reporter.getName(), target, cooldownMillis, now);
    }

    ReportRecord findReport(String reportId) {
        return store.get(reportId);
    }

    CompletableFuture<Void> deliverReport(ReportRecord report) {
        DiscordBotClient client = discord;
        if (client == null) {
            CompletableFuture<Void> future = new CompletableFuture<Void>();
            future.completeExceptionally(new IllegalStateException("Discord client is not configured"));
            return future;
        }
        return client.sendReport(report).thenAccept(messageId -> {
            report.markDelivered(messageId);
            try { store.save(); }
            catch (IOException exception) { plugin.getLogger().log(Level.SEVERE, "Не удалось сохранить ID Discord-сообщения репорта #" + report.number(), exception); }
            notifyMinecraftStaff(report);
        }).exceptionally(error -> {
            String message = rootMessage(error);
            report.markDeliveryError(message);
            try { store.save(); }
            catch (IOException exception) { plugin.getLogger().log(Level.SEVERE, "Не удалось сохранить ошибку доставки репорта #" + report.number(), exception); }
            throw new java.util.concurrent.CompletionException(error);
        });
    }

    HandleResult handleFromDiscord(String reportId, ReportStatus status, String moderatorName, String moderatorId) {
        ReportRecord report = store.get(reportId);
        if (report == null) return new HandleResult(HandleCode.NOT_FOUND, null);
        if (!report.handle(status, moderatorName, moderatorId)) return new HandleResult(HandleCode.ALREADY_HANDLED, report);
        try {
            store.save();
            return new HandleResult(HandleCode.SUCCESS, report);
        } catch (IOException exception) {
            plugin.getLogger().log(Level.SEVERE, "Не удалось сохранить обработанный репорт #" + report.number(), exception);
            return new HandleResult(HandleCode.SAVE_FAILED, report);
        }
    }

    ReplyResult replyFromDiscord(String reportId, String moderatorName, String moderatorId, String text) {
        ReportRecord report = store.get(reportId);
        if (report == null) return new ReplyResult(ReplyCode.NOT_FOUND, null, null);
        ReportReply reply = report.addReply(moderatorName, moderatorId, text);
        try {
            store.save();
        } catch (IOException exception) {
            plugin.getLogger().log(Level.SEVERE, "Не удалось сохранить ответ по репорту #" + report.number(), exception);
            return new ReplyResult(ReplyCode.SAVE_FAILED, report, reply);
        }
        notifyReporterReply(report, reply);
        return new ReplyResult(ReplyCode.SUCCESS, report, reply);
    }

    void notifyReporterHandled(ReportRecord report) {
        PluginConfig current = config;
        Bukkit.getScheduler().runTask(plugin, () -> {
            Player player = Bukkit.getPlayerExact(report.reporterName());
            if (player == null || !player.isOnline()) return;
            Map<String, String> placeholders = basePlaceholders(current);
            placeholders.put("number", String.valueOf(report.number()));
            placeholders.put("target", report.targetName());
            placeholders.put("reason", report.reason());
            placeholders.put("admin", report.moderatorName());
            String template = report.status() == ReportStatus.ACCEPTED ? current.messageAccepted : current.messageRejected;
            player.sendMessage(Text.format(template, placeholders));
        });
    }

    void deliverPendingReplies(Player player) {
        if (player == null || !player.isOnline()) return;
        for (ReportRecord report : store.all()) {
            if (!report.matchesReporter(player.getUniqueId(), player.getName())) continue;
            for (ReportReply reply : report.pendingReplies()) {
                deliverReplyToPlayer(player, report, reply);
            }
        }
    }

    private void notifyReporterReply(ReportRecord report, ReportReply reply) {
        Bukkit.getScheduler().runTask(plugin, () -> {
            Player player = Bukkit.getPlayerExact(report.reporterName());
            if (player == null || !player.isOnline()) return;
            deliverReplyToPlayer(player, report, reply);
        });
    }

    private void deliverReplyToPlayer(Player player, ReportRecord report, ReportReply reply) {
        if (reply.delivered()) return;
        PluginConfig current = config;
        Map<String, String> placeholders = basePlaceholders(current);
        placeholders.put("number", String.valueOf(report.number()));
        placeholders.put("target", report.targetName());
        placeholders.put("reason", report.reason());
        placeholders.put("admin", reply.moderatorName());
        placeholders.put("reply", reply.text());
        player.sendMessage(Text.format(current.messageAdminReply, placeholders));
        if (reply.markDelivered()) {
            try { store.save(); }
            catch (IOException exception) {
                plugin.getLogger().log(Level.SEVERE, "Не удалось отметить доставленным ответ по репорту #" + report.number(), exception);
            }
        }
    }

    void notifyDeliveryResult(String playerName, ReportRecord report, boolean success) {
        PluginConfig current = config;
        Bukkit.getScheduler().runTask(plugin, () -> {
            Player player = Bukkit.getPlayerExact(playerName);
            if (player == null || !player.isOnline()) return;
            Map<String, String> placeholders = basePlaceholders(current);
            placeholders.put("number", String.valueOf(report.number()));
            placeholders.put("target", report.targetName());
            placeholders.put("reason", report.reason());
            player.sendMessage(Text.format(success ? current.messageSent : current.messageFailed, placeholders));
        });
    }

    private void notifyMinecraftStaff(ReportRecord report) {
        PluginConfig current = config;
        if (!current.notifyMinecraftStaff) return;
        Map<String, String> placeholders = basePlaceholders(current);
        placeholders.put("number", String.valueOf(report.number()));
        placeholders.put("reporter", report.reporterName());
        placeholders.put("target", report.targetName());
        placeholders.put("reason", report.reason());
        String message = Text.format(current.messageStaffNewReport, placeholders);
        Bukkit.getScheduler().runTask(plugin, () -> {
            for (Player player : Bukkit.getOnlinePlayers()) {
                if (player.hasPermission(current.notifyPermission)) player.sendMessage(message);
            }
        });
    }

    static Map<String, String> basePlaceholders(PluginConfig config) {
        Map<String, String> placeholders = new HashMap<String, String>();
        placeholders.put("prefix", config.messagePrefix);
        return placeholders;
    }

    private static String rootMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null) current = current.getCause();
        return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
    }
}
