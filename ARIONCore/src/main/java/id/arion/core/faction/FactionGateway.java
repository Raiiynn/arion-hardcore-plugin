package id.arion.core.faction;

import java.util.Optional;
import org.bukkit.entity.Player;

/** Paper main-thread boundary. Never call SaberFactions from async login or database code. */
public interface FactionGateway {
    Optional<FactionSnapshot> factionOf(Player player);

    record FactionSnapshot(String id, String tag, int members) {}
}
