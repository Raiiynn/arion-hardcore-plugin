package id.arion.core.deathban;

import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

public final class DeathBanService {
    private final DeathBanRepository repository;
    private final Clock clock;
    private final Duration duration;
    private final String world;

    public DeathBanService(DeathBanRepository repository, Clock clock, Duration duration, String world) {
        if (duration.isNegative() || duration.isZero()) throw new IllegalArgumentException("Positive duration required");
        this.repository = repository;
        this.clock = clock;
        this.duration = duration;
        this.world = world;
    }

    public Optional<DeathBan> onDeath(UUID player, String deathWorld, boolean bypass) throws SQLException {
        if (!world.equals(deathWorld) || bypass) return Optional.empty();
        return Optional.of(apply(player, duration, "death", "death in " + world));
    }

    public DeathBan apply(UUID player, Duration length, String actor, String reason) throws SQLException {
        if (length.isNegative() || length.isZero() || length.compareTo(Duration.ofDays(365)) > 0) {
            throw new IllegalArgumentException("Duration must be between 1 second and 365 days");
        }
        var now = clock.instant();
        repository.extend(player, now.plus(length), now, actor, reason);
        return repository.find(player).orElseThrow(() -> new SQLException("Ban missing after commit"));
    }

    public Optional<DeathBan> active(UUID player) throws SQLException {
        return repository.find(player).filter(ban -> ban.activeAt(clock.instant()));
    }

    public boolean revoke(UUID player, String actor) throws SQLException {
        return repository.revoke(player, clock.instant(), actor);
    }

    public String message(DeathBan ban) {
        long seconds = ban.remainingSeconds(clock.instant());
        return "ARIONCore: DeathBan aktif. Sisa " + (seconds / 60) + " menit " + (seconds % 60)
                + " detik. Berakhir: " + ban.expiresAt() + " (UTC).";
    }
}
