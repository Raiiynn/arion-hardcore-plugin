package id.arion.core.deathban;

import java.sql.SQLException;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface DeathBanRepository {
    Optional<DeathBan> find(UUID playerId) throws SQLException;
    void extend(UUID playerId, Instant expiresAt, Instant now, String actor, String reason) throws SQLException;
    boolean revoke(UUID playerId, Instant now, String actor) throws SQLException;
}
