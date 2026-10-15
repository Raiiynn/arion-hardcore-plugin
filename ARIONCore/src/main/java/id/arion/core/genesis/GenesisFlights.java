package id.arion.core.genesis;

import java.time.Duration;
import java.util.*;

/** Main-thread ledger. A timeout expires protection, but never pretends the engine operation was cancelled. */
public final class GenesisFlights {
    private record Flight(long generation,long started) {}
    private final Map<UUID,Flight> active=new LinkedHashMap<>();
    private final long timeout;
    public GenesisFlights(Duration timeout) {
        if(timeout.isNegative() || timeout.isZero()) throw new IllegalArgumentException("Positive timeout required");
        this.timeout=timeout.toNanos();
    }
    public void begin(UUID player,long generation,long now) {
        if(active.containsKey(player) || active.size()>=5) throw new IllegalStateException("Duplicate or excessive in-flight teleport");
        active.put(player,new Flight(generation,now));
    }
    public boolean finish(UUID player,long generation) {
        Flight f=active.get(player);
        if(f==null || f.generation()!=generation) return false;
        active.remove(player);return true;
    }
    public Optional<UUID> expired(long now) {
        return active.entrySet().stream().filter(e->now-e.getValue().started()>=timeout).map(Map.Entry::getKey).findFirst();
    }
    public boolean protecting(UUID player,long now) {
        Flight f=active.get(player);return f!=null && now-f.started()<timeout;
    }
    public boolean isEmpty() { return active.isEmpty(); }
    public int size() { return active.size(); }
}
