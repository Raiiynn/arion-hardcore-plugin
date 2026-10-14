package id.arion.core.deathban;

import java.time.Instant;
import java.util.UUID;

public record DeathBan(UUID playerId, Instant expiresAt) {
    public boolean activeAt(Instant now) { return expiresAt.isAfter(now); }

    public long remainingSeconds(Instant now) {
        long millis = expiresAt.toEpochMilli() - now.toEpochMilli();
        return millis <= 0 ? 0 : (millis + 999) / 1000;
    }
}
