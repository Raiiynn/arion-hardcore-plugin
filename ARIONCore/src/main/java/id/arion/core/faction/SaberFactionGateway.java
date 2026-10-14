package id.arion.core.faction;

import com.massivecraft.factions.FPlayers;
import java.util.Optional;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

/** Verified against the 4.1.9-STABLE release JAR; see docs/DEPENDENCIES.md. */
public final class SaberFactionGateway implements FactionGateway {
    @Override
    public Optional<FactionSnapshot> factionOf(Player player) {
        if (!Bukkit.isPrimaryThread()) throw new IllegalStateException("Saber API requires the server thread");
        var faction = FPlayers.getInstance().getByPlayer(player).getFaction();
        if (faction == null || !faction.isNormal()) return Optional.empty();
        return Optional.of(new FactionSnapshot(faction.getId(), faction.getTag(), faction.getFPlayers().size()));
    }
}
