package ru.teamworld.reports;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

final class ReportRecord {
    private final String id;
    private final long number;
    private final String reporterName;
    private final String reporterUuid;
    private final String targetName;
    private final String reason;
    private final long createdAt;
    private volatile ReportStatus status;
    private volatile String moderatorName;
    private volatile String moderatorDiscordId;
    private volatile long handledAt;
    private volatile String discordMessageId;
    private volatile String deliveryError;
    private final List<ReportReply> replies;

    ReportRecord(long number, String reporterName, UUID reporterUuid, String targetName, String reason) {
        this(UUID.randomUUID().toString(), number, reporterName, reporterUuid == null ? "" : reporterUuid.toString(),
                targetName, reason, Instant.now().toEpochMilli(), ReportStatus.PENDING, "", "", 0L, "", "",
                new ArrayList<ReportReply>());
    }

    private ReportRecord(String id, long number, String reporterName, String reporterUuid, String targetName,
                         String reason, long createdAt, ReportStatus status, String moderatorName,
                         String moderatorDiscordId, long handledAt, String discordMessageId, String deliveryError,
                         List<ReportReply> replies) {
        this.id = id;
        this.number = number;
        this.reporterName = reporterName;
        this.reporterUuid = reporterUuid;
        this.targetName = targetName;
        this.reason = reason;
        this.createdAt = createdAt;
        this.status = status;
        this.moderatorName = moderatorName;
        this.moderatorDiscordId = moderatorDiscordId;
        this.handledAt = handledAt;
        this.discordMessageId = discordMessageId;
        this.deliveryError = deliveryError;
        this.replies = replies == null ? new ArrayList<ReportReply>() : replies;
    }

    String id() { return id; }
    long number() { return number; }
    String reporterName() { return reporterName; }
    String reporterUuid() { return reporterUuid; }
    String targetName() { return targetName; }
    String reason() { return reason; }
    long createdAt() { return createdAt; }
    ReportStatus status() { return status; }
    String moderatorName() { return moderatorName; }
    String moderatorDiscordId() { return moderatorDiscordId; }
    long handledAt() { return handledAt; }
    String discordMessageId() { return discordMessageId; }
    String deliveryError() { return deliveryError; }

    synchronized boolean handle(ReportStatus newStatus, String moderatorName, String moderatorDiscordId) {
        if (status != ReportStatus.PENDING) return false;
        if (newStatus != ReportStatus.ACCEPTED && newStatus != ReportStatus.REJECTED) return false;
        this.status = newStatus;
        this.moderatorName = moderatorName == null ? "" : moderatorName;
        this.moderatorDiscordId = moderatorDiscordId == null ? "" : moderatorDiscordId;
        this.handledAt = Instant.now().toEpochMilli();
        return true;
    }

    synchronized ReportReply addReply(String moderatorName, String moderatorDiscordId, String text) {
        ReportReply reply = new ReportReply(moderatorName, moderatorDiscordId, text);
        replies.add(reply);
        return reply;
    }

    synchronized List<ReportReply> replies() {
        return new ArrayList<ReportReply>(replies);
    }

    synchronized List<ReportReply> pendingReplies() {
        List<ReportReply> pending = new ArrayList<ReportReply>();
        for (ReportReply reply : replies) if (!reply.delivered()) pending.add(reply);
        return pending;
    }

    boolean matchesReporter(UUID uuid, String name) {
        if (uuid != null && !reporterUuid.isBlank() && reporterUuid.equals(uuid.toString())) return true;
        return name != null && reporterName.equalsIgnoreCase(name);
    }

    synchronized void markDelivered(String messageId) {
        this.discordMessageId = messageId == null ? "" : messageId;
        this.deliveryError = "";
    }

    synchronized void markDeliveryError(String error) {
        this.deliveryError = Text.truncate(error == null ? "Неизвестная ошибка" : error, 500);
    }

    Map<String, Object> toJson() {
        Map<String, Object> map = new LinkedHashMap<String, Object>();
        map.put("id", id);
        map.put("number", Long.valueOf(number));
        map.put("reporterName", reporterName);
        map.put("reporterUuid", reporterUuid);
        map.put("targetName", targetName);
        map.put("reason", reason);
        map.put("createdAt", Long.valueOf(createdAt));
        map.put("status", status.name());
        map.put("moderatorName", moderatorName);
        map.put("moderatorDiscordId", moderatorDiscordId);
        map.put("handledAt", Long.valueOf(handledAt));
        map.put("discordMessageId", discordMessageId);
        map.put("deliveryError", deliveryError);
        List<Object> replyList = Json.array();
        synchronized (this) {
            for (ReportReply reply : replies) replyList.add(reply.toJson());
        }
        map.put("replies", replyList);
        return map;
    }

    static ReportRecord fromJson(Map<String, Object> map) {
        String statusRaw = Json.string(map, "status", "PENDING");
        ReportStatus status;
        try { status = ReportStatus.valueOf(statusRaw); }
        catch (IllegalArgumentException ignored) { status = ReportStatus.PENDING; }

        List<ReportReply> replies = new ArrayList<ReportReply>();
        List<Object> rawReplies = Json.asArray(map.get("replies"));
        if (rawReplies != null) {
            for (Object item : rawReplies) {
                Map<String, Object> replyMap = Json.asObject(item);
                if (replyMap != null) replies.add(ReportReply.fromJson(replyMap));
            }
        }

        return new ReportRecord(
                Json.string(map, "id", UUID.randomUUID().toString()),
                Json.longValue(map, "number", 0L),
                Json.string(map, "reporterName", "unknown"),
                Json.string(map, "reporterUuid", ""),
                Json.string(map, "targetName", "unknown"),
                Json.string(map, "reason", ""),
                Json.longValue(map, "createdAt", System.currentTimeMillis()),
                status,
                Json.string(map, "moderatorName", ""),
                Json.string(map, "moderatorDiscordId", ""),
                Json.longValue(map, "handledAt", 0L),
                Json.string(map, "discordMessageId", ""),
                Json.string(map, "deliveryError", ""),
                replies
        );
    }
}
