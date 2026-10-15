package id.arion.core.genesis;

import java.util.*;

/** Mutable only on the server thread; copy() is the persistence boundary. */
public final class GenesisSession {
    public enum State { PREPARING, READY, THUNDER, TELEPORTING, COMPLETED, FAILED, ABORTED }
    public enum Delivery { PENDING, IN_FLIGHT, ARRIVED, OFFLINE, FAILED, NEEDS_REVIEW }
    public record Point(double x, double y, double z) {
        public Point {
            if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z))
                throw new IllegalArgumentException("Non-finite spawn");
        }
        public double distanceSquared(Point other) {
            return (x-other.x)*(x-other.x) + (z-other.z)*(z-other.z);
        }
    }
    public record Participant(UUID id, String faction, Point spawn, Delivery delivery, int attempts) {
        public Participant at(Point point) { return new Participant(id, faction, point, delivery, attempts); }
        public Participant delivered(Delivery value) { return new Participant(id, faction, spawn, value, attempts); }
        public Participant attempt() {
            if(delivery!=Delivery.PENDING || attempts>=2) throw new IllegalStateException("Delivery is not pending or retry limit reached");
            return new Participant(id, faction, spawn, Delivery.IN_FLIGHT, attempts + 1);
        }
    }
    public final UUID id;
    public final UUID world;
    public final long seed;
    public final String fingerprint;
    public State state;
    public long revision;
    public long readyAt;
    public long releasedAt;
    public boolean startIntent;
    public String detail = "";
    public final Map<UUID, Participant> participants = new LinkedHashMap<>();
    public final Map<String, String> regions = new LinkedHashMap<>();
    public final Map<String, Set<String>> rosters = new LinkedHashMap<>();

    public GenesisSession(UUID id, UUID world, long seed, String fingerprint, State state) {
        this.id=id; this.world=world; this.seed=seed; this.fingerprint=fingerprint; this.state=state;
    }
    public void transition(State next) {
        boolean allowed = switch (state) {
            case PREPARING -> next == State.READY;
            case READY -> next == State.THUNDER || next == State.TELEPORTING;
            case THUNDER -> next == State.TELEPORTING;
            case TELEPORTING -> next == State.COMPLETED;
            default -> false;
        };
        if (!allowed && next != State.FAILED && next != State.ABORTED)
            throw new IllegalStateException("Illegal Genesis transition " + state + " -> " + next);
        if (state == State.COMPLETED && next != State.ABORTED)
            throw new IllegalStateException("Completed session is immutable");
        state=next; revision++;
    }
    public GenesisSession copy() {
        var copy = new GenesisSession(id, world, seed, fingerprint, state);
        copy.revision=revision; copy.readyAt=readyAt; copy.releasedAt=releasedAt;
        copy.startIntent=startIntent; copy.detail=detail;
        copy.participants.putAll(participants); copy.regions.putAll(regions);
        rosters.forEach((key,value) -> copy.rosters.put(key, Set.copyOf(value)));
        return copy;
    }
    public void armStart(boolean recovery) {
        if(state!=State.READY || startIntent && !recovery) throw new IllegalStateException("Start requires an unstarted READY session");
        startIntent=true;revision++;
    }
    public void quarantineInFlight() {
        participants.replaceAll((id,p)->p.delivery()==Delivery.IN_FLIGHT?p.delivered(Delivery.NEEDS_REVIEW):p);
    }
    public static Map<String,String> assignRegions(List<String> factions,List<String> regions,long seed) {
        if(factions.isEmpty() || factions.size()!=regions.size() || new HashSet<>(factions).size()!=factions.size()
                || new HashSet<>(regions).size()!=regions.size()) throw new IllegalArgumentException("Faction/region assignment must be one-to-one");
        var shuffled=new ArrayList<>(regions);Collections.shuffle(shuffled,new Random(seed));
        Map<String,String> mapping=new LinkedHashMap<>();
        for(int i=0;i<factions.size();i++) mapping.put(factions.get(i),shuffled.get(i));
        return Collections.unmodifiableMap(mapping);
    }
    public boolean allArrived() {
        return !participants.isEmpty() && participants.values().stream().allMatch(p -> p.delivery()==Delivery.ARRIVED);
    }
    public static boolean separated(Collection<Participant> players, double distance) {
        if(players.isEmpty() || players.stream().anyMatch(p->p.spawn()==null)) return false;
        var list = List.copyOf(players);
        for (int i=0; i<list.size(); i++) for (int j=i+1; j<list.size(); j++) {
            var a=list.get(i); var b=list.get(j);
            if (a.spawn()==null || b.spawn()==null) return false;
            if (!a.faction().equals(b.faction()) && a.spawn().distanceSquared(b.spawn()) < distance*distance) return false;
        }
        return true;
    }
}
