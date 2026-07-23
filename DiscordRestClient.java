package ru.teamworld.reports;

import java.io.IOException;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

final class PluginConfig {
    final boolean discordEnabled;
    final String botToken;
    final String reportChannelId;
    final String rejectedChannelId;
    final Set<String> staffRoleIds;
    final Set<String> staffUserIds;
    final boolean allowAnyDiscordUser;
    final String serverName;
    final int reportEmbedColor;
    final int acceptedEmbedColor;
    final int rejectedEmbedColor;
    final int sameTargetCooldownSeconds;
    final int minReasonLength;
    final int maxReasonLength;
    final int maxReplyLength;
    final boolean requireTargetOnline;
    final boolean allowSelfReport;
    final boolean notifyMinecraftStaff;
    final String notifyPermission;
    final String messagePrefix;
    final String messageUsage;
    final String messageNoPermission;
    final String messagePlayersOnly;
    final String messageCooldown;
    final String messageTargetOffline;
    final String messageSelfReport;
    final String messageReasonShort;
    final String messageReasonLong;
    final String messageSending;
    final String messageSent;
    final String messageFailed;
    final String messageAccepted;
    final String messageRejected;
    final String messageAdminReply;
    final String messageStaffNewReport;
    final String messageReloaded;

    private PluginConfig(SimpleYaml yaml) {
        discordEnabled = yaml.getBoolean("discord.enabled", true);
        botToken = yaml.getString("discord.bot-token", "").trim();
        reportChannelId = yaml.getString("discord.report-channel-id", "").trim();
        rejectedChannelId = yaml.getString("discord.rejected-channel-id", "").trim();
        staffRoleIds = cleanIds(yaml.getStringList("discord.staff-role-ids"));
        staffUserIds = cleanIds(yaml.getStringList("discord.staff-user-ids"));
        allowAnyDiscordUser = yaml.getBoolean("discord.allow-any-discord-user", false);
        serverName = yaml.getString("discord.server-name", "Minecraft Server");
        reportEmbedColor = Text.discordColor(yaml.getString("discord.colors.new-report", "#FCD05C"), 0xFCD05C);
        acceptedEmbedColor = Text.discordColor(yaml.getString("discord.colors.accepted", "#57F287"), 0x57F287);
        rejectedEmbedColor = Text.discordColor(yaml.getString("discord.colors.rejected", "#ED4245"), 0xED4245);

        sameTargetCooldownSeconds = Math.max(0, yaml.getInt("report.same-target-cooldown-seconds", 10_800));
        minReasonLength = Math.max(1, yaml.getInt("report.min-reason-length", 3));
        maxReasonLength = Math.max(minReasonLength, yaml.getInt("report.max-reason-length", 300));
        maxReplyLength = Math.max(1, Math.min(4000, yaml.getInt("report.max-reply-length", 1000)));
        requireTargetOnline = yaml.getBoolean("report.require-target-online", false);
        allowSelfReport = yaml.getBoolean("report.allow-self-report", false);

        notifyMinecraftStaff = yaml.getBoolean("minecraft-staff-notify.enabled", true);
        notifyPermission = yaml.getString("minecraft-staff-notify.permission", "reportdg.notify");

        messagePrefix = yaml.getString("messages.prefix", "&#FCD05C⛨ &#FFFFFF| ");
        messageUsage = yaml.getString("messages.usage", "{prefix}&#FFFFFFИспользование: &#AE67F6/report <ник> <причина>");
        messageNoPermission = yaml.getString("messages.no-permission", "{prefix}&#FF5555У вас нет прав.");
        messagePlayersOnly = yaml.getString("messages.players-only", "{prefix}&#FF5555Команда доступна только игрокам.");
        messageCooldown = yaml.getString("messages.cooldown", "{prefix}&#FFFFFFВы уже отправляли репорт на &#AE67F6{target}&#FFFFFF. Повторно можно через &#FCD05C{time}&#FFFFFF.");
        messageTargetOffline = yaml.getString("messages.target-offline", "{prefix}&#FF5555Игрок {target} не найден на сервере.");
        messageSelfReport = yaml.getString("messages.self-report", "{prefix}&#FF5555Нельзя отправить репорт на самого себя.");
        messageReasonShort = yaml.getString("messages.reason-short", "{prefix}&#FF5555Причина слишком короткая.");
        messageReasonLong = yaml.getString("messages.reason-long", "{prefix}&#FF5555Причина слишком длинная. Максимум: {max} символов.");
        messageSending = yaml.getString("messages.sending", "{prefix}&#FFFFFFОтправляю репорт в Discord...");
        messageSent = yaml.getString("messages.sent", "{prefix}&#FFFFFFРепорт &#FCD05C#{number} &#FFFFFFна игрока &#AE67F6{target} &#FFFFFFотправлен.");
        messageFailed = yaml.getString("messages.failed", "{prefix}&#FF5555Не удалось отправить репорт в Discord. Сообщите администрации.");
        messageAccepted = yaml.getString("messages.accepted", "{prefix}&#FFFFFFВаш репорт &#FCD05C#{number} &#FFFFFFпринял администратор &#AE67F6{admin}&#FFFFFF.");
        messageRejected = yaml.getString("messages.rejected", "{prefix}&#FFFFFFВаш репорт &#FCD05C#{number} &#FFFFFFотклонил администратор &#AE67F6{admin}&#FFFFFF.");
        messageAdminReply = yaml.getString("messages.admin-reply", "{prefix}&#FCD05CОтвет администратора &#AE67F6{admin} &#FFFFFFпо репорту &#FCD05C#{number}&#FFFFFF: &#AE67F6{reply}");
        messageStaffNewReport = yaml.getString("messages.staff-new-report", "{prefix}&#FCD05C{reporter} &#FFFFFFотправил репорт на &#AE67F6{target} &#FFFFFFпо причине: &#FCD05C{reason}");
        messageReloaded = yaml.getString("messages.reloaded", "{prefix}&#55FF55Конфигурация ReportDG перезагружена.");
    }

    static PluginConfig load(Path path) throws IOException {
        return new PluginConfig(SimpleYaml.load(path));
    }

    boolean hasDiscordCredentials() {
        return discordEnabled && !isPlaceholder(botToken) && isSnowflake(reportChannelId);
    }

    private static boolean isPlaceholder(String token) {
        return token == null || token.isBlank() || token.contains("PASTE_") || token.contains("ВСТАВЬТЕ");
    }

    static boolean isSnowflake(String value) {
        if (value == null || value.length() < 15 || value.length() > 22) return false;
        for (int i = 0; i < value.length(); i++) if (!Character.isDigit(value.charAt(i))) return false;
        return true;
    }

    private static Set<String> cleanIds(List<String> ids) {
        Set<String> result = new HashSet<String>();
        for (String id : ids) {
            String clean = id == null ? "" : id.trim();
            if (isSnowflake(clean)) result.add(clean);
        }
        return result;
    }
}
