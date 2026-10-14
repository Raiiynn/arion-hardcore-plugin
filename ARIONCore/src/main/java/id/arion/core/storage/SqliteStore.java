package id.arion.core.storage;

import id.arion.core.deathban.DeathBan;
import id.arion.core.deathban.DeathBanRepository;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** One serialized connection: safe across async pre-login and the Paper server thread. */
public final class SqliteStore implements DeathBanRepository, AutoCloseable {
    private final Connection connection;

    public SqliteStore(Path file) throws SQLException {
        // A plugin classloader may not be visible to DriverManager's initial service discovery.
        try { Class.forName("org.sqlite.JDBC", true, SqliteStore.class.getClassLoader()); }
        catch (ClassNotFoundException missingDriver) { throw new SQLException("Bundled SQLite driver is missing", missingDriver); }
        connection = DriverManager.getConnection("jdbc:sqlite:" + file.toAbsolutePath());
        try {
            try (var statement = connection.createStatement()) {
                statement.execute("PRAGMA busy_timeout=3000");
                statement.execute("PRAGMA journal_mode=WAL");
                statement.execute("PRAGMA synchronous=FULL");
                statement.execute("PRAGMA foreign_keys=ON");
            }
            migrate();
        } catch (SQLException failure) {
            try { connection.close(); } catch (SQLException closeFailure) { failure.addSuppressed(closeFailure); }
            throw failure;
        }
    }

    private void migrate() throws SQLException {
        int version;
        try (var statement = connection.createStatement(); var result = statement.executeQuery("PRAGMA user_version")) {
            if (!result.next()) throw new SQLException("Cannot read schema version");
            version = result.getInt(1);
        }
        if (version > 1) throw new SQLException("Database schema is newer than this plugin: " + version);
        if (version == 1) return;
        transaction(() -> {
            try (var statement = connection.createStatement()) {
                statement.executeUpdate("""
                    CREATE TABLE death_bans (
                        player_uuid TEXT PRIMARY KEY,
                        expires_at_ms INTEGER NOT NULL,
                        updated_at_ms INTEGER NOT NULL,
                        reason TEXT NOT NULL
                    )
                    """);
                statement.executeUpdate("""
                    CREATE TABLE audit_log (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        at_ms INTEGER NOT NULL,
                        actor TEXT NOT NULL,
                        action TEXT NOT NULL,
                        player_uuid TEXT NOT NULL,
                        detail TEXT NOT NULL
                    )
                    """);
                statement.execute("PRAGMA user_version=1");
            }
        });
    }

    @Override
    public synchronized Optional<DeathBan> find(UUID player) throws SQLException {
        try (var statement = connection.prepareStatement("SELECT expires_at_ms FROM death_bans WHERE player_uuid=?")) {
            statement.setString(1, player.toString());
            try (var result = statement.executeQuery()) {
                return result.next() ? Optional.of(new DeathBan(player, Instant.ofEpochMilli(result.getLong(1))))
                        : Optional.empty();
            }
        }
    }

    @Override
    public synchronized void extend(UUID player, Instant expires, Instant now, String actor, String reason) throws SQLException {
        transaction(() -> {
            try (var statement = connection.prepareStatement("""
                INSERT INTO death_bans(player_uuid, expires_at_ms, updated_at_ms, reason) VALUES(?,?,?,?)
                ON CONFLICT(player_uuid) DO UPDATE SET
                  expires_at_ms=MAX(death_bans.expires_at_ms, excluded.expires_at_ms),
                  updated_at_ms=excluded.updated_at_ms, reason=excluded.reason
                """)) {
                statement.setString(1, player.toString());
                statement.setLong(2, expires.toEpochMilli());
                statement.setLong(3, now.toEpochMilli());
                statement.setString(4, reason);
                statement.executeUpdate();
            }
            audit(now, actor, "BAN_EXTEND", player, expires + " " + reason);
        });
    }

    @Override
    public synchronized boolean revoke(UUID player, Instant now, String actor) throws SQLException {
        boolean[] removed = {false};
        transaction(() -> {
            try (var statement = connection.prepareStatement("DELETE FROM death_bans WHERE player_uuid=?")) {
                statement.setString(1, player.toString());
                removed[0] = statement.executeUpdate() > 0;
            }
            audit(now, actor, "BAN_REVOKE", player, "removed=" + removed[0]);
        });
        return removed[0];
    }

    private void audit(Instant at, String actor, String action, UUID player, String detail) throws SQLException {
        try (var statement = connection.prepareStatement(
                "INSERT INTO audit_log(at_ms,actor,action,player_uuid,detail) VALUES(?,?,?,?,?)")) {
            statement.setLong(1, at.toEpochMilli());
            statement.setString(2, actor);
            statement.setString(3, action);
            statement.setString(4, player.toString());
            statement.setString(5, detail);
            statement.executeUpdate();
        }
    }

    private void transaction(SqlWork work) throws SQLException {
        connection.setAutoCommit(false);
        try {
            work.run();
            connection.commit();
        } catch (SQLException | RuntimeException failure) {
            try { connection.rollback(); } catch (SQLException rollbackFailure) { failure.addSuppressed(rollbackFailure); }
            throw failure;
        } finally {
            connection.setAutoCommit(true);
        }
    }

    @FunctionalInterface private interface SqlWork { void run() throws SQLException; }
    @Override public synchronized void close() throws SQLException { connection.close(); }
}
