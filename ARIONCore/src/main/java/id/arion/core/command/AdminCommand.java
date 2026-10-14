package id.arion.core.command;

import id.arion.core.ARIONCorePlugin;
import java.sql.SQLException;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;

public final class AdminCommand implements CommandExecutor, TabCompleter {
    private final ARIONCorePlugin plugin;
    public AdminCommand(ARIONCorePlugin plugin) { this.plugin = plugin; }

    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("arion.admin")) { sender.sendMessage("Tidak punya izin arion.admin."); return true; }
        if (args.length == 1 && args[0].equalsIgnoreCase("status")) {
            sender.sendMessage("ARIONCore M1: " + plugin.health() + "; Factions: " + plugin.factionStatus());
            return true;
        }
        if (!plugin.ready()) { sender.sendMessage(ARIONCorePlugin.UNAVAILABLE); return true; }
        try {
            if (args.length == 2 && args[0].equalsIgnoreCase("faction")) {
                var player = plugin.getServer().getPlayerExact(args[1]);
                if (player == null) throw new IllegalArgumentException("Pemain harus online.");
                var gateway = plugin.factions().orElseThrow(() -> new IllegalArgumentException("SaberFactions tidak tersedia."));
                sender.sendMessage(gateway.factionOf(player)
                        .map(f -> "Faction " + f.tag() + " [" + f.id() + "]; anggota=" + f.members())
                        .orElse("Pemain tidak berada di faction normal."));
                return true;
            }
            if (args.length < 3 || !args[0].equalsIgnoreCase("deathban")) { usage(sender); return true; }
            UUID playerId = resolve(args[2]);
            String actor = sender instanceof org.bukkit.entity.Player player
                    ? player.getUniqueId().toString() : sender.getName();
            switch (args[1].toLowerCase(Locale.ROOT)) {
                case "check" -> {
                    if (args.length != 3) { usage(sender); break; }
                    sender.sendMessage(plugin.deathBans().active(playerId).map(plugin.deathBans()::message)
                            .orElse("Tidak ada DeathBan aktif untuk " + playerId));
                }
                case "clear" -> {
                    if (args.length != 3) { usage(sender); break; }
                    sender.sendMessage(plugin.deathBans().revoke(playerId, actor)
                            ? "DeathBan dihapus: " + playerId : "Tidak ada record ban: " + playerId);
                }
                case "apply" -> {
                    if (args.length != 4) { usage(sender); break; }
                    long seconds = Long.parseLong(args[3]);
                    if (seconds < 1 || seconds > 31536000) throw new IllegalArgumentException("Detik harus 1..31536000.");
                    var ban = plugin.deathBans().apply(playerId, Duration.ofSeconds(seconds), actor, "admin command");
                    sender.sendMessage("DeathBan tersimpan: " + playerId + "; " + plugin.deathBans().message(ban));
                    var online = plugin.getServer().getPlayer(playerId);
                    if (online != null) online.kick(Component.text(plugin.deathBans().message(ban)));
                }
                default -> usage(sender);
            }
        } catch (IllegalArgumentException failure) {
            sender.sendMessage("Input tidak valid: " + failure.getMessage());
        } catch (SQLException failure) {
            plugin.storageFailure("admin command by " + sender.getName() + ": " + String.join(" ", args), failure);
            sender.sendMessage(ARIONCorePlugin.UNAVAILABLE);
        } catch (LinkageError | IllegalStateException failure) {
            plugin.getLogger().log(java.util.logging.Level.SEVERE, "Faction adapter failed", failure);
            sender.sendMessage("Adapter faction gagal; lihat console.");
        }
        return true;
    }

    private UUID resolve(String value) {
        var online = plugin.getServer().getPlayerExact(value);
        if (online != null) return online.getUniqueId();
        if (!value.matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")) {
            throw new IllegalArgumentException("Gunakan nama pemain online atau UUID lengkap pemain offline.");
        }
        return UUID.fromString(value);
    }

    private void usage(CommandSender sender) {
        sender.sendMessage("/arion status | faction <online-player>");
        sender.sendMessage("/arion deathban <check|clear> <online-player|uuid>");
        sender.sendMessage("/arion deathban apply <online-player|uuid> <seconds>");
    }

    @Override public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!sender.hasPermission("arion.admin")) return List.of();
        List<String> options = args.length == 1 ? List.of("status", "deathban", "faction")
                : args.length == 2 && args[0].equalsIgnoreCase("deathban") ? List.of("check", "clear", "apply") : List.of();
        String prefix = args.length == 0 ? "" : args[args.length - 1].toLowerCase(Locale.ROOT);
        return options.stream().filter(option -> option.startsWith(prefix)).toList();
    }
}
