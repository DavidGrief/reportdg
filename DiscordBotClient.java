package ru.teamworld.reports;

import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

final class ReportReplyListener implements Listener {
    private final ReportDGPlugin plugin;

    ReportReplyListener(ReportDGPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        plugin.reportService().deliverPendingReplies(event.getPlayer());
    }
}
