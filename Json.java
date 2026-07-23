package ru.teamworld.reports;

import org.bukkit.Bukkit;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.logging.Level;

public final class ReportDGPlugin extends JavaPlugin {
    private volatile PluginConfig config;
    private ReportStore store;
    private ReportService reportService;
    private volatile DiscordBotClient discord;

    @Override
    public void onEnable() {
        try {
            Files.createDirectories(getDataFolder().toPath());
            migrateLegacyData();
            copyDefaultConfig();
            config = PluginConfig.load(getDataFolder().toPath().resolve("config.yml"));
            store = new ReportStore(getDataFolder().toPath().resolve("reports.json"));
            store.load();
            reportService = new ReportService(this, store, config);
            registerCommands();
            getServer().getPluginManager().registerEvents(new ReportReplyListener(this), this);
            printBanner();
            startDiscord();
            getLogger().info("ReportDG включён. Java " + System.getProperty("java.version") + ", RGB-цвета активны.");
        } catch (Exception exception) {
            getLogger().log(Level.SEVERE, "Не удалось включить ReportDG", exception);
            getServer().getPluginManager().disablePlugin(this);
        }
    }

    @Override
    public void onDisable() {
        DiscordBotClient current = discord;
        if (current != null) current.close();
        if (store != null) {
            try { store.save(); }
            catch (IOException exception) { getLogger().log(Level.SEVERE, "Не удалось сохранить reports.json", exception); }
        }
    }

    PluginConfig config() { return config; }
    ReportService reportService() { return reportService; }

    synchronized boolean reloadPlugin() {
        try {
            PluginConfig updated = PluginConfig.load(getDataFolder().toPath().resolve("config.yml"));
            DiscordBotClient old = discord;
            discord = null;
            reportService.setDiscord(null);
            if (old != null) old.close();
            config = updated;
            reportService.setConfig(updated);
            startDiscord();
            return true;
        } catch (Exception exception) {
            getLogger().log(Level.SEVERE, "Не удалось перезагрузить config.yml", exception);
            return false;
        }
    }

    private void registerCommands() {
        ReportCommand reportCommand = new ReportCommand(this);
        PluginCommand report = getCommand("report");
        if (report == null) throw new IllegalStateException("Команда report отсутствует в plugin.yml");
        report.setExecutor(reportCommand);
        report.setTabCompleter(reportCommand);

        PluginCommand reload = getCommand("reportdgreload");
        if (reload == null) throw new IllegalStateException("Команда reportdgreload отсутствует в plugin.yml");
        reload.setExecutor(new ReloadCommand(this));
    }

    private synchronized void startDiscord() {
        if (!config.discordEnabled) {
            getLogger().warning("Discord-интеграция отключена в config.yml.");
            return;
        }
        if (!config.hasDiscordCredentials()) {
            getLogger().warning("Discord не настроен: вставьте bot-token и report-channel-id в config.yml.");
            return;
        }
        if (!config.allowAnyDiscordUser && config.staffRoleIds.isEmpty() && config.staffUserIds.isEmpty()) {
            getLogger().warning("Кнопки Discord пока никто не сможет нажать: задайте staff-role-ids/staff-user-ids или allow-any-discord-user.");
        }
        DiscordBotClient client = new DiscordBotClient(this, config, reportService);
        discord = client;
        reportService.setDiscord(client);
        client.start();
    }

    private void printBanner() {
        String[] banner = {
                "&#FCD05C██████╗ &#F7C05F███████╗&#F2B063██████╗ &#EDA066 ██████╗ &#E89069██████╗ &#E3806C████████╗&#DE7070██████╗  &#D96073 ██████╗",
                "&#FCD05C██╔══██╗&#F7C05F██╔════╝&#F2B063██╔══██╗&#EDA066██╔═══██╗&#E89069██╔══██╗&#E3806C╚══██╔══╝&#DE7070██╔══██╗&#D96073██╔════╝",
                "&#FCD05C██████╔╝&#F7C05F█████╗  &#F2B063██████╔╝&#EDA066██║   ██║&#E89069██████╔╝&#E3806C   ██║   &#DE7070██║  ██║&#D96073██║  ███╗",
                "&#FCD05C██╔══██╗&#F7C05F██╔══╝  &#F2B063██╔═══╝ &#EDA066██║   ██║&#E89069██╔══██╗&#E3806C   ██║   &#DE7070██║  ██║&#D96073██║   ██║",
                "&#FCD05C██║  ██║&#F7C05F███████╗&#F2B063██║     &#EDA066╚██████╔╝&#E89069██║  ██║&#E3806C   ██║   &#DE7070██████╔╝&#D96073╚██████╔╝",
                "&#FCD05C╚═╝  ╚═╝&#F7C05F╚══════╝&#F2B063╚═╝      &#EDA066╚═════╝ &#E89069╚═╝  ╚═╝&#E3806C   ╚═╝   &#DE7070╚═════╝ &#D96073 ╚═════╝",
                "&#FF3B3B━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━",
                "&#FF3B3B❤  ПОДПИШИСЬ НА YOUTUBE-КАНАЛ: ДАВИД GRIEF  ❤",
                "&#FF3B3B━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
        };
        for (String line : banner) {
            Bukkit.getConsoleSender().sendMessage(Text.color(line));
        }
    }

    private void migrateLegacyData() throws IOException {
        Path current = getDataFolder().toPath();
        Path pluginsDirectory = current.getParent();
        if (pluginsDirectory == null) return;
        Path legacy = pluginsDirectory.resolve("TeamReports");
        if (!Files.isDirectory(legacy) || legacy.equals(current)) return;

        boolean copied = false;
        for (String fileName : new String[]{"config.yml", "reports.json"}) {
            Path source = legacy.resolve(fileName);
            Path target = current.resolve(fileName);
            if (Files.isRegularFile(source) && !Files.exists(target)) {
                Files.copy(source, target);
                copied = true;
            }
        }
        if (copied) {
            getLogger().info("Настройки и репорты перенесены из plugins/TeamReports в plugins/ReportDG.");
        }
    }

    private void copyDefaultConfig() throws IOException {
        Path target = getDataFolder().toPath().resolve("config.yml");
        if (Files.exists(target)) return;
        try (InputStream input = ReportDGPlugin.class.getClassLoader().getResourceAsStream("config.yml")) {
            if (input == null) throw new IOException("В JAR отсутствует config.yml");
            Files.copy(input, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
