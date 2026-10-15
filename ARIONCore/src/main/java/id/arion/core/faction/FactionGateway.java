package id.arion.core.faction;

import java.util.Optional;
import java.util.Set;
import org.bukkit.Location;
import org.bukkit.entity.Player;

/** Paper main-thread boundary. Never call SaberFactions from async login or database code. */
public interface FactionGateway {
    Optional<FactionSnapshot> factionOf(Player player);
    Set<String> memberIds(String factionId);
    boolean wilderness(Location location);

    record FactionSnapshot(String id, String tag, int members) {}
}
