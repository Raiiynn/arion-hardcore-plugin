package id.arion.core.faction;

import com.massivecraft.factions.FPlayers;
import java.util.Optional;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

/** Verified against the 4.1.9-STABLE release JAR; see docs/DEPENDENCIES.md. */
public final class SaberFactionGateway implements FactionGateway {
    @Override public java.util.Set<String> memberIds(String factionId) {
        if (!Bukkit.isPrimaryThread()) throw new IllegalStateException("Saber API requires the server thread");
        var faction = com.massivecraft.factions.Factions.getInstance().getFactionById(factionId);
        if (faction == null || !faction.isNormal()) throw new IllegalArgumentException("Faction missing/disbanded: " + factionId);
        var members = new java.util.HashSet<String>();
        faction.getFPlayers().forEach(p -> members.add(p.getId()));
        faction.getAltPlayers().forEach(p -> members.add(p.getId()));
        return java.util.Set.copyOf(members);
    }
    @Override public boolean wilderness(org.bukkit.Location location) {
        if (!Bukkit.isPrimaryThread()) throw new IllegalStateException("Saber API requires the server thread");
        var faction = com.massivecraft.factions.Board.getInstance().getFactionAt(com.massivecraft.factions.FLocation.wrap(location));
        return faction != null && faction.isWilderness();
    }
    @Override
    public Optional<FactionSnapshot> factionOf(Player player) {
        if (!Bukkit.isPrimaryThread()) throw new IllegalStateException("Saber API requires the server thread");
        var faction = FPlayers.getInstance().getByPlayer(player).getFaction();
        if (faction == null || !faction.isNormal()) return Optional.empty();
        return Optional.of(new FactionSnapshot(faction.getId(), faction.getTag(), faction.getFPlayers().size()));
    }
}
