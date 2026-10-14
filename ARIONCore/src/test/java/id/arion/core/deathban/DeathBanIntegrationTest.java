package id.arion.core.deathban;

import static org.junit.jupiter.api.Assertions.*;

import id.arion.core.storage.SqliteStore;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DeathBanIntegrationTest {
    @TempDir Path directory;
    final UUID player = UUID.randomUUID();
    final MutableClock clock = new MutableClock(Instant.parse("2026-10-14T12:00:00Z"));

    DeathBanService service(SqliteStore store) {
        return new DeathBanService(store, clock, Duration.ofMinutes(30), "areus_s1");
    }

    @Test void deathBansExactlyThirtyMinutesAndExpiresAtBoundary() throws Exception {
        try (var store = new SqliteStore(directory.resolve("test.db"))) {
            var service = service(store);
            var ban = service.onDeath(player, "areus_s1", false).orElseThrow();
            assertEquals(clock.instant().plusSeconds(1800), ban.expiresAt());
            clock.advance(Duration.ofMillis(1_799_999));
            assertTrue(service.active(player).isPresent());
            assertEquals(1, ban.remainingSeconds(clock.instant()));
            clock.advance(Duration.ofMillis(1));
            assertTrue(service.active(player).isEmpty());
            assertEquals(0, ban.remainingSeconds(clock.instant()));
        }
    }

    @Test void otherWorldAndBypassDoNotCreateBans() throws Exception {
        try (var store = new SqliteStore(directory.resolve("test.db"))) {
            var service = service(store);
            assertTrue(service.onDeath(player, "world", false).isEmpty());
            assertTrue(service.onDeath(player, "areus_s1_nether", false).isEmpty());
            assertTrue(service.onDeath(player, "areus_s1", true).isEmpty());
            assertTrue(store.find(player).isEmpty());
        }
    }

    @Test void bypassDoesNotClearAnExistingBan() throws Exception {
        try (var store = new SqliteStore(directory.resolve("test.db"))) {
            var service = service(store);
            service.onDeath(player, "areus_s1", false);
            service.onDeath(player, "areus_s1", true);
            assertTrue(service.active(player).isPresent());
        }
    }

    @Test void reopenKeepsAbsoluteExpiryAndRevokeSurvivesAnotherRestart() throws Exception {
        var file = directory.resolve("restart.db");
        Instant expected;
        try (var store = new SqliteStore(file)) {
            expected = service(store).onDeath(player, "areus_s1", false).orElseThrow().expiresAt();
        }
        clock.advance(Duration.ofMinutes(5));
        try (var store = new SqliteStore(file)) {
            var service = service(store);
            assertEquals(expected, service.active(player).orElseThrow().expiresAt());
            assertEquals(1500, service.active(player).orElseThrow().remainingSeconds(clock.instant()));
            assertTrue(service.revoke(player, "console"));
        }
        try (var store = new SqliteStore(file)) {
            assertTrue(service(store).active(player).isEmpty());
            assertFalse(service(store).revoke(player, "console"));
        }
    }

    @Test void shorterBanNeverShortensAnExistingPenalty() throws Exception {
        try (var store = new SqliteStore(directory.resolve("test.db"))) {
            var service = service(store);
            var original = service.apply(player, Duration.ofHours(1), "console", "test");
            assertEquals(original.expiresAt(), service.onDeath(player, "areus_s1", false).orElseThrow().expiresAt());
            assertEquals(original.expiresAt(), service.apply(player, Duration.ofSeconds(30), "console", "test").expiresAt());
        }
    }

    @Test void repeatedDeathAtSameInstantDoesNotStackTime() throws Exception {
        try (var store = new SqliteStore(directory.resolve("test.db"))) {
            var service = service(store);
            var first = service.onDeath(player, "areus_s1", false);
            assertEquals(first, service.onDeath(player, "areus_s1", false));
        }
    }

    @Test void longerBanExtendsAndPlayersAreIsolated() throws Exception {
        try (var store = new SqliteStore(directory.resolve("test.db"))) {
            var service = service(store);
            service.onDeath(player, "areus_s1", false);
            var extended = service.apply(player, Duration.ofHours(2), "console", "test");
            assertEquals(clock.instant().plusSeconds(7200), extended.expiresAt());
            assertTrue(service.active(UUID.randomUUID()).isEmpty());
        }
    }

    @Test void rejectedDurationsDoNotWrite() throws Exception {
        try (var store = new SqliteStore(directory.resolve("test.db"))) {
            var service = service(store);
            for (var bad : new Duration[]{Duration.ZERO, Duration.ofSeconds(-1), Duration.ofDays(366)}) {
                assertThrows(IllegalArgumentException.class, () -> service.apply(player, bad, "console", "test"));
            }
            assertTrue(store.find(player).isEmpty());
        }
    }

    @Test void auditFailureRollsBackBanAndDoesNotReportSuccess() throws Exception {
        var file = directory.resolve("rollback.db");
        try (var store = new SqliteStore(file);
             var connection = DriverManager.getConnection("jdbc:sqlite:" + file);
             var statement = connection.createStatement()) {
            statement.execute("CREATE TRIGGER reject_audit BEFORE INSERT ON audit_log BEGIN SELECT RAISE(ABORT, 'test failure'); END");
            assertThrows(SQLException.class, () -> service(store).onDeath(player, "areus_s1", false));
            assertTrue(store.find(player).isEmpty());
            statement.execute("DROP TRIGGER reject_audit");
            assertTrue(service(store).onDeath(player, "areus_s1", false).isPresent());
        }
    }

    @Test void auditFailureAlsoRollsBackRevoke() throws Exception {
        var file = directory.resolve("rollback-clear.db");
        try (var store = new SqliteStore(file);
             var connection = DriverManager.getConnection("jdbc:sqlite:" + file);
             var statement = connection.createStatement()) {
            service(store).onDeath(player, "areus_s1", false);
            statement.execute("CREATE TRIGGER reject_audit BEFORE INSERT ON audit_log BEGIN SELECT RAISE(ABORT, 'test failure'); END");
            assertThrows(SQLException.class, () -> service(store).revoke(player, "console"));
            assertTrue(service(store).active(player).isPresent());
        }
    }

    @Test void banAndAdminActorAreCommittedTogether() throws Exception {
        var file = directory.resolve("audit.db");
        try (var store = new SqliteStore(file)) {
            service(store).apply(player, Duration.ofSeconds(60), "console", "quote ' and unicode uji");
        }
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + file);
             var statement = connection.createStatement();
             var result = statement.executeQuery("SELECT actor,action,player_uuid FROM audit_log")) {
            assertTrue(result.next());
            assertEquals("console", result.getString(1));
            assertEquals("BAN_EXTEND", result.getString(2));
            assertEquals(player.toString(), result.getString(3));
            assertFalse(result.next());
        }
    }

    @Test void concurrentPreLoginReadsAndAdminWritesKeepLongestExpiry() throws Exception {
        try (var store = new SqliteStore(directory.resolve("concurrent.db"));
             var executor = Executors.newFixedThreadPool(6)) {
            var tasks = IntStream.rangeClosed(1, 60).<java.util.concurrent.Callable<Void>>mapToObj(seconds -> () -> {
                service(store).apply(player, Duration.ofSeconds(seconds), "concurrent-test", "test");
                service(store).active(player);
                return null;
            }).toList();
            for (var future : executor.invokeAll(tasks)) future.get();
            assertEquals(clock.instant().plusSeconds(60), store.find(player).orElseThrow().expiresAt());
        }
    }

    @Test void rejectsNewerSchemaWithoutDowngradingIt() throws Exception {
        var file = directory.resolve("future.db");
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + file);
             var statement = connection.createStatement()) { statement.execute("PRAGMA user_version=2"); }
        assertThrows(SQLException.class, () -> new SqliteStore(file));
    }

    @Test void closedDatabaseFailurePropagatesInsteadOfAllowingLogin() throws Exception {
        var store = new SqliteStore(directory.resolve("closed.db"));
        var service = service(store);
        store.close();
        assertThrows(SQLException.class, () -> service.active(player));
    }

    static final class MutableClock extends Clock {
        private Instant now;
        MutableClock(Instant now) { this.now = now; }
        void advance(Duration duration) { now = now.plus(duration); }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return Clock.fixed(now, zone); }
        @Override public Instant instant() { return now; }
    }
}
