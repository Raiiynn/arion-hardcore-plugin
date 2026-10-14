package id.arion.core;

import id.arion.core.command.AdminCommand;
import id.arion.core.config.CoreConfig;
import id.arion.core.deathban.DeathBanListener;
import id.arion.core.deathban.DeathBanService;
import id.arion.core.faction.FactionGateway;
import id.arion.core.faction.SaberFactionGateway;
import id.arion.core.storage.SqliteStore;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;
import net.kyori.adventure.text.Component;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

public final class ARIONCorePlugin extends JavaPlugin {
    public static final String UNAVAILABLE = "ARIONCore sedang terkunci karena gangguan data/config. Hubungi admin.";
    private final AtomicReference<String> health = new AtomicReference<>("STARTING");
    private volatile DeathBanService deathBans;
    private SqliteStore database;
    private CoreConfig settings;
    private FactionGateway factions;
    private Plugin saberPlugin;
    private String factionStatus = "unavailable";

    @Override public void onEnable() {
        // Register the login guard first; initialization errors must not silently allow banned users in.
        getServer().getPluginManager().registerEvents(new DeathBanListener(this), this);
        var command = new AdminCommand(this);
        Objects.requireNonNull(getCommand("arion")).setExecutor(command);
        Objects.requireNonNull(getCommand("arion")).setTabCompleter(command);
        try {
            Files.createDirectories(getDataFolder().toPath());
            if (Files.exists(failureMarker())) throw new IOException("STORAGE-FAILURE.txt needs administrator review");
            saveDefaultConfig();
            settings = CoreConfig.read(getConfig());
            database = new SqliteStore(getDataFolder().toPath().resolve("arion.db"));
            deathBans = new DeathBanService(database, Clock.systemUTC(), settings.deathBanDuration(), settings.world());
            setupFactions();
            health.set("READY");
            if (getServer().getWorld(settings.world()) == null) {
                getLogger().warning("World " + settings.world() + " is not loaded. DeathBan triggers only in that exact world.");
            }
            getLogger().info("Milestone 1 READY. DeathBan=" + settings.deathBanDuration() + ", Saber=" + factionStatus);
        } catch (Exception | LinkageError failure) {
            fail("initialization", failure, false);
        }
    }

    @SuppressWarnings("deprecation")
    private void setupFactions() {
        saberPlugin = getServer().getPluginManager().getPlugin("Factions");
        if (saberPlugin != null && saberPlugin.isEnabled()
                && saberPlugin.getDescription().getMain().equals("com.massivecraft.factions.FactionsPlugin")
                && saberPlugin.getDescription().getVersion().equals("1.6.9.5-4.1.9")) {
            factions = new SaberFactionGateway();
            factionStatus = "SaberFactions 1.6.9.5-4.1.9 (read-only adapter)";
        } else {
            factionStatus = "missing, disabled, or unsupported version; faction features unavailable";
            if (settings.requireSaber()) throw new IllegalStateException(factionStatus);
            getLogger().warning(factionStatus);
        }
    }

    public boolean ready() { return health.get().equals("READY"); }
    public String health() { return health.get(); }
    public DeathBanService deathBans() { return Objects.requireNonNull(deathBans); }
    public Optional<FactionGateway> factions() {
        return saberPlugin != null && saberPlugin.isEnabled() ? Optional.ofNullable(factions) : Optional.empty();
    }
    public String factionStatus() { return factions().isPresent() ? factionStatus : "unavailable"; }

    public void storageFailure(String context, Throwable failure) { fail(context, failure, true); }

    private synchronized void fail(String context, Throwable failure, boolean persist) {
        boolean first = !health.get().startsWith("FAILED");
        health.set("FAILED (see console)");
        if (!first) return;
        getLogger().log(Level.SEVERE, "ARIONCore fail-closed: " + context, failure);
        if (persist) {
            try {
                Files.writeString(failureMarker(), "At: " + Instant.now() + "\nContext: " + context
                        + "\nFailure: " + failure + "\nInspect database and reconcile bans before deleting this marker.\n");
            } catch (IOException markerFailure) {
                getLogger().log(Level.SEVERE, "Cannot persist failure marker; disk needs repair before restart", markerFailure);
            }
        }
        // May be called by async pre-login; player operations always run on the server thread.
        getServer().getScheduler().runTask(this, () -> getServer().getOnlinePlayers()
                .forEach(player -> player.kick(Component.text(UNAVAILABLE))));
    }

    private Path failureMarker() { return getDataFolder().toPath().resolve("STORAGE-FAILURE.txt"); }

    @Override public void onDisable() {
        health.set("STOPPED");
        if (database != null) {
            try { database.close(); }
            catch (Exception failure) { getLogger().log(Level.SEVERE, "Failed to close SQLite", failure); }
        }
    }
}
