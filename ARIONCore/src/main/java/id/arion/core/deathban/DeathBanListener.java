package id.arion.core.deathban;

import id.arion.core.ARIONCorePlugin;
import java.sql.SQLException;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerRespawnEvent;

public final class DeathBanListener implements Listener {
    private final ARIONCorePlugin plugin;
    public DeathBanListener(ARIONCorePlugin plugin) { this.plugin = plugin; }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDeath(PlayerDeathEvent event) {
        if (!plugin.ready()) return;
        Player player = event.getEntity();
        try {
            if (plugin.deathBans().onDeath(player.getUniqueId(), player.getWorld().getName(),
                    player.hasPermission("arion.deathban.bypass")).isPresent()) {
                // Commit completes before scheduling the kick; immediate reconnect cannot escape the ban.
                plugin.getServer().getScheduler().runTask(plugin, () -> enforce(player));
            }
        } catch (SQLException failure) {
            plugin.storageFailure("death for " + player.getUniqueId(), failure);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPreLogin(AsyncPlayerPreLoginEvent event) {
        if (!plugin.ready()) {
            event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER, Component.text(ARIONCorePlugin.UNAVAILABLE));
            return;
        }
        try {
            var ban = plugin.deathBans().active(event.getUniqueId());
            if (ban.isPresent()) event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_BANNED,
                    Component.text(plugin.deathBans().message(ban.get())));
        } catch (SQLException failure) {
            plugin.storageFailure("pre-login for " + event.getUniqueId(), failure);
            event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER, Component.text(ARIONCorePlugin.UNAVAILABLE));
        }
    }

    // Covers a ban applied after async pre-login and before the player joins.
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onJoin(PlayerJoinEvent event) { enforce(event.getPlayer()); }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onRespawn(PlayerRespawnEvent event) {
        plugin.getServer().getScheduler().runTask(plugin, () -> enforce(event.getPlayer()));
    }

    public void enforce(Player player) {
        if (!player.isOnline()) return;
        if (!plugin.ready()) { player.kick(Component.text(ARIONCorePlugin.UNAVAILABLE)); return; }
        try {
            plugin.deathBans().active(player.getUniqueId()).ifPresent(ban ->
                    player.kick(Component.text(plugin.deathBans().message(ban))));
        } catch (SQLException failure) {
            plugin.storageFailure("join/respawn for " + player.getUniqueId(), failure);
            player.kick(Component.text(ARIONCorePlugin.UNAVAILABLE));
        }
    }
}
