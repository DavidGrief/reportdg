package ru.teamworld.reports;

import java.time.Instant;
import java.util.List;
import java.util.Map;

final class DiscordPayloads {
    private DiscordPayloads() {}

    static Map<String, Object> newReport(ReportRecord report, PluginConfig config) {
        Map<String, Object> payload = baseMessage();
        List<Object> embeds = Json.array();
        embeds.add(reportEmbed(report, config, ReportStatus.PENDING));
        payload.put("embeds", embeds);
        payload.put("components", buttons(report, false));
        return payload;
    }

    static Map<String, Object> handledUpdate(ReportRecord report, PluginConfig config) {
        Map<String, Object> data = baseMessage();
        List<Object> embeds = Json.array();
        embeds.add(reportEmbed(report, config, report.status()));
        data.put("embeds", embeds);
        data.put("components", buttons(report, true));

        Map<String, Object> callback = Json.object();
        callback.put("type", Integer.valueOf(7));
        callback.put("data", data);
        return callback;
    }

    static Map<String, Object> replyModal(ReportRecord report, PluginConfig config) {
        Map<String, Object> input = Json.object();
        input.put("type", Integer.valueOf(4));
        input.put("custom_id", "reply_text");
        input.put("style", Integer.valueOf(2));
        input.put("min_length", Integer.valueOf(1));
        input.put("max_length", Integer.valueOf(config.maxReplyLength));
        input.put("required", Boolean.TRUE);
        input.put("placeholder", "Напишите ответ, который увидит игрок в Minecraft...");

        Map<String, Object> label = Json.object();
        label.put("type", Integer.valueOf(18));
        label.put("label", "Ответ игроку " + Text.truncate(report.reporterName(), 60));
        label.put("description", "Репорт #" + report.number() + " на игрока " + Text.truncate(report.targetName(), 50));
        label.put("component", input);

        List<Object> components = Json.array();
        components.add(label);

        Map<String, Object> data = Json.object();
        data.put("custom_id", "reportdg:reply:" + report.id());
        data.put("title", Text.truncate("Ответ на репорт #" + report.number(), 45));
        data.put("components", components);

        Map<String, Object> callback = Json.object();
        callback.put("type", Integer.valueOf(9));
        callback.put("data", data);
        return callback;
    }

    static Map<String, Object> ephemeral(String text) {
        Map<String, Object> data = Json.object();
        data.put("content", text);
        data.put("flags", Integer.valueOf(64));
        Map<String, Object> allowed = Json.object();
        allowed.put("parse", Json.array());
        data.put("allowed_mentions", allowed);
        Map<String, Object> callback = Json.object();
        callback.put("type", Integer.valueOf(4));
        callback.put("data", data);
        return callback;
    }

    static Map<String, Object> rejectedLog(ReportRecord report, PluginConfig config) {
        Map<String, Object> payload = baseMessage();
        Map<String, Object> embed = Json.object();
        embed.put("title", "Репорт отклонён • #" + report.number());
        embed.put("description", "Администратор **" + safe(report.moderatorName()) + "** отклонил репорт.");
        embed.put("color", Integer.valueOf(config.rejectedEmbedColor));
        embed.put("timestamp", Instant.ofEpochMilli(report.handledAt()).toString());
        List<Object> fields = Json.array();
        fields.add(field("Отправитель", safe(report.reporterName()), true));
        fields.add(field("Игрок", safe(report.targetName()), true));
        fields.add(field("Причина", Text.truncate(safe(report.reason()), 1024), false));
        fields.add(field("Администратор", safe(report.moderatorName()) + " (`" + report.moderatorDiscordId() + "`)", false));
        fields.add(field("Сервер", safe(config.serverName), true));
        embed.put("fields", fields);
        List<Object> embeds = Json.array();
        embeds.add(embed);
        payload.put("embeds", embeds);
        return payload;
    }

    private static Map<String, Object> reportEmbed(ReportRecord report, PluginConfig config, ReportStatus status) {
        Map<String, Object> embed = Json.object();
        int color;
        String title;
        String statusText;
        if (status == ReportStatus.ACCEPTED) {
            color = config.acceptedEmbedColor;
            title = "Репорт принят • #" + report.number();
            statusText = "✅ Принят администратором **" + safe(report.moderatorName()) + "**";
        } else if (status == ReportStatus.REJECTED) {
            color = config.rejectedEmbedColor;
            title = "Репорт отклонён • #" + report.number();
            statusText = "❌ Отклонён администратором **" + safe(report.moderatorName()) + "**";
        } else {
            color = config.reportEmbedColor;
            title = "Новый репорт • #" + report.number();
            statusText = "⏳ Ожидает рассмотрения";
        }
        embed.put("title", title);
        embed.put("description", "Игрок **" + safe(report.reporterName()) + "** отправил репорт на **" + safe(report.targetName()) + "**.");
        embed.put("color", Integer.valueOf(color));
        embed.put("timestamp", Instant.ofEpochMilli(report.createdAt()).toString());

        List<Object> fields = Json.array();
        fields.add(field("Отправитель", safe(report.reporterName()), true));
        fields.add(field("Игрок", safe(report.targetName()), true));
        fields.add(field("Причина", Text.truncate(safe(report.reason()), 1024), false));
        fields.add(field("Статус", statusText, false));
        fields.add(field("Сервер", safe(config.serverName), true));
        embed.put("fields", fields);

        Map<String, Object> footer = Json.object();
        footer.put("text", "ReportDG • внутренний ID " + report.id());
        embed.put("footer", footer);
        return embed;
    }

    private static List<Object> buttons(ReportRecord report, boolean decisionsDisabled) {
        Map<String, Object> accept = Json.object();
        accept.put("type", Integer.valueOf(2));
        accept.put("style", Integer.valueOf(3));
        accept.put("label", "Принять");
        accept.put("emoji", emoji("✅"));
        accept.put("custom_id", "reportdg:accept:" + report.id());
        accept.put("disabled", Boolean.valueOf(decisionsDisabled));

        Map<String, Object> reject = Json.object();
        reject.put("type", Integer.valueOf(2));
        reject.put("style", Integer.valueOf(4));
        reject.put("label", "Отклонить");
        reject.put("emoji", emoji("❌"));
        reject.put("custom_id", "reportdg:reject:" + report.id());
        reject.put("disabled", Boolean.valueOf(decisionsDisabled));

        Map<String, Object> reply = Json.object();
        reply.put("type", Integer.valueOf(2));
        reply.put("style", Integer.valueOf(1));
        reply.put("label", "Ответить");
        reply.put("emoji", emoji("💬"));
        reply.put("custom_id", "reportdg:reply:" + report.id());
        reply.put("disabled", Boolean.FALSE);

        List<Object> components = Json.array();
        components.add(accept);
        components.add(reject);
        components.add(reply);
        Map<String, Object> row = Json.object();
        row.put("type", Integer.valueOf(1));
        row.put("components", components);
        List<Object> rows = Json.array();
        rows.add(row);
        return rows;
    }

    private static Map<String, Object> emoji(String name) {
        Map<String, Object> emoji = Json.object();
        emoji.put("name", name);
        return emoji;
    }

    private static Map<String, Object> field(String name, String value, boolean inline) {
        Map<String, Object> field = Json.object();
        field.put("name", name);
        field.put("value", value == null || value.isBlank() ? "—" : value);
        field.put("inline", Boolean.valueOf(inline));
        return field;
    }

    private static Map<String, Object> baseMessage() {
        Map<String, Object> payload = Json.object();
        Map<String, Object> allowed = Json.object();
        allowed.put("parse", Json.array());
        payload.put("allowed_mentions", allowed);
        return payload;
    }

    private static String safe(String text) {
        if (text == null) return "—";
        return text.replace("`", "ˋ").replace("@", "＠").replace("*", "∗").replace("_", "﹏");
    }
}
