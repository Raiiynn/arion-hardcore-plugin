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
        if (args.length == 0 || args[0].equalsIgnoreCase("help")) {
            if (args.length == 2 && args[1].equalsIgnoreCase("genesis")) genesisHelp(sender);
            else usage(sender);
            return true;
        }
        if (args.length > 0 && args[0].equalsIgnoreCase("genesis")) {
            if (!sender.hasPermission("arion.admin.genesis")) { sender.sendMessage("Tidak punya izin arion.admin.genesis."); return true; }
            if (args.length == 2 && args[1].equalsIgnoreCase("help")) { genesisHelp(sender); return true; }
            plugin.genesis().ifPresentOrElse(service -> service.command(sender, args),
                    () -> sender.sendMessage("Genesis: " + plugin.genesisStatus()));
            return true;
        }
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
        sender.sendMessage("ARIONCore — Bantuan admin");
        sender.sendMessage("/arion help — Tampilkan bantuan ini.");
        sender.sendMessage("/arion status — Lihat kesehatan plugin dan integrasi faction.");
        sender.sendMessage("/arion faction <nama-online> — Lihat ID, tag, dan jumlah anggota faction.");
        sender.sendMessage("/arion deathban check <nama-online|uuid> — Periksa ban aktif.");
        sender.sendMessage("/arion deathban clear <nama-online|uuid> — Cabut DeathBan.");
        sender.sendMessage("/arion deathban apply <nama-online|uuid> <detik> — Beri/perpanjang ban dan keluarkan pemain.");
        if (sender.hasPermission("arion.admin.genesis")) sender.sendMessage("/arion help genesis — Lihat command event Genesis.");
    }

    private void genesisHelp(CommandSender sender) {
        if (!sender.hasPermission("arion.admin.genesis")) { sender.sendMessage("Tidak punya izin arion.admin.genesis."); return; }
        sender.sendMessage("Genesis — Bantuan admin (juga tersedia: /arion genesis help)");
        sender.sendMessage("/arion genesis validate — Periksa world, konfigurasi, dan peserta; belum memeriksa terrain.");
        sender.sendMessage("/arion genesis prepare — Cari spawn aman dan simpan assignment faction.");
        sender.sendMessage("/arion genesis preview — Lihat assignment, koordinat, dan status peserta.");
        sender.sendMessage("/arion genesis start — Mulai petir dan teleport; hanya saat READY.");
        sender.sendMessage("/arion genesis status — Lihat state, progress, dan error session.");
        sender.sendMessage("/arion genesis abort — Hentikan event; pemain yang sudah tiba tidak ditarik kembali.");
        sender.sendMessage("/arion genesis reset [token] — Arsipkan session yang berhenti dengan konfirmasi.");
        sender.sendMessage("/arion genesis recover inspect — Periksa ledger setelah gangguan/restart.");
        sender.sendMessage("/arion genesis recover resolve <uuid> <arrived|pending> [token] — Rekonsiliasi hasil teleport secara manual.");
        sender.sendMessage("/arion genesis recover resume [token] — Lanjutkan assignment lama tanpa mengulang petir.");
    }

    @Override public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!sender.hasPermission("arion.admin")) return List.of();
        if (args.length == 2 && args[0].equalsIgnoreCase("help")) {
            return sender.hasPermission("arion.admin.genesis") && "genesis".startsWith(args[1].toLowerCase(Locale.ROOT)) ? List.of("genesis") : List.of();
        }
        if (args.length > 1 && args[0].equalsIgnoreCase("genesis")) {
            if (!sender.hasPermission("arion.admin.genesis")) return List.of();
            String prefix = args[args.length - 1].toLowerCase(Locale.ROOT);
            var options = new java.util.ArrayList<>(plugin.genesis().map(service -> service.completions(args)).orElse(List.of()));
            if (args.length == 2) options.add("help");
            return options.stream().filter(s -> s.startsWith(prefix)).toList();
        }
        List<String> options = args.length == 1 ? (sender.hasPermission("arion.admin.genesis") ? List.of("help", "status", "deathban", "faction", "genesis") : List.of("help", "status", "deathban", "faction"))
                : args.length == 2 && args[0].equalsIgnoreCase("deathban") ? List.of("check", "clear", "apply") : List.of();
        String prefix = args.length == 0 ? "" : args[args.length - 1].toLowerCase(Locale.ROOT);
        return options.stream().filter(option -> option.startsWith(prefix)).toList();
    }
}
