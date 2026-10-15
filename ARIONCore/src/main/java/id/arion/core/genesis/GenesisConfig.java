package id.arion.core.genesis;

import java.util.*;
import org.bukkit.configuration.ConfigurationSection;

public record GenesisConfig(boolean enabled, String openingWorld, String destinationWorld,
        List<String> factions, Set<UUID> excludedPlayers, int maxMembers, double distance, int radius,
        int borderMargin, int spawnExclusion, List<Region> regions, List<Exclusion> exclusions,
        int attempts, int prepareSeconds, int concurrency, boolean generate, int budgetMs,
        int readySeconds, int delayTicks, int teleportSeconds, int protectionSeconds,
        Set<String> deniedBiomes, String title, String subtitle, boolean debug, boolean protectionReviewed) {
    public record Region(String id, int minX, int maxX, int minZ, int maxZ, GenesisSession.Point fallback) {
        public Region {
            if (id.isBlank() || minX>maxX || minZ>maxZ || Math.abs((long)minX)>29_000_000
                    || Math.abs((long)maxX)>29_000_000 || Math.abs((long)minZ)>29_000_000 || Math.abs((long)maxZ)>29_000_000)
                throw new IllegalArgumentException("Invalid candidate region: " + id);
        }
        public boolean contains(double x, double z) { return x>=minX && x<=maxX && z>=minZ && z<=maxZ; }
    }
    public record Exclusion(String world, int minX, int maxX, int minY, int maxY, int minZ, int maxZ) {
        public Exclusion {
            if (world.isBlank() || minX>maxX || minY>maxY || minZ>maxZ)
                throw new IllegalArgumentException("Invalid excluded volume");
        }
        public boolean contains(String name, int x, int y, int z) {
            return world.equals(name) && x>=minX && x<=maxX && y>=minY && y<=maxY && z>=minZ && z<=maxZ;
        }
    }
    public static GenesisConfig read(ConfigurationSection root) {
        var c=root.getConfigurationSection("genesis");
        if (c==null) return null;
        if (!c.isBoolean("enabled")) throw new IllegalArgumentException("genesis.enabled must be boolean");
        if (!c.getBoolean("enabled")) return null;
        var factions=strings(c,"faction-ids");
        int count=number(c,"faction-count",3,1,16);
        if (factions.size()!=count || new HashSet<>(factions).size()!=count || factions.stream().anyMatch(String::isBlank))
            throw new IllegalArgumentException("Provide exactly faction-count unique Saber faction IDs");
        var regions=new ArrayList<Region>();
        if (!c.isList("candidate-regions") || !c.isList("excluded-regions")) throw new IllegalArgumentException("Genesis regions must be lists");
        for (var m:c.getMapList("candidate-regions")) {
            GenesisSession.Point fallback=null;
            if (m.get("fallback-anchor") instanceof Map<?,?> f)
                fallback=new GenesisSession.Point(integer(f,"x"),integer(f,"y"),integer(f,"z"));
            regions.add(new Region(string(m,"id"),integer(m,"min-x"),integer(m,"max-x"),integer(m,"min-z"),integer(m,"max-z"),fallback));
        }
        for (var r:regions) if(r.fallback()!=null && !r.contains(r.fallback().x(),r.fallback().z()))
            throw new IllegalArgumentException("Fallback must be inside its candidate region: "+r.id());
        if (regions.size()!=count || regions.stream().map(Region::id).distinct().count()!=count)
            throw new IllegalArgumentException("Provide faction-count unique candidate regions");
        var exclusions=new ArrayList<Exclusion>();
        for (var m:c.getMapList("excluded-regions")) exclusions.add(new Exclusion(string(m,"world"),integer(m,"min-x"),integer(m,"max-x"),
                integer(m,"min-y"),integer(m,"max-y"),integer(m,"min-z"),integer(m,"max-z")));
        var excluded=new HashSet<UUID>();
        for (String id:strings(c,"excluded-player-uuids")) excluded.add(UUID.fromString(id));
        Set<String> denied=bool(c,"biome-restrictions.enabled",false) ? Set.copyOf(strings(c,"biome-restrictions.denied")) : Set.of();
        if (denied.stream().anyMatch(s -> !s.matches("[a-z0-9_.-]+:[a-z0-9_./-]+")))
            throw new IllegalArgumentException("Biome IDs must be namespaced keys");
        return new GenesisConfig(true,required(c,"opening-world"),required(c,"destination-world"),List.copyOf(factions),Set.copyOf(excluded),
                number(c,"max-players-per-faction",10,1,10),number(c,"minimum-faction-distance",2000,1,1_000_000),
                number(c,"spawn-group-radius",12,8,16),number(c,"world-border-margin",64,4,10000),
                number(c,"destination-spawn-exclusion-radius",256,0,10000),List.copyOf(regions),List.copyOf(exclusions),
                number(c,"maximum-search-attempts",200,1,10000),number(c,"maximum-preparation-duration-seconds",120,10,600),
                number(c,"chunk-loading-concurrency",2,1,4),bool(c,"allow-chunk-generation",false),
                number(c,"preparation-budget-ms-per-tick",2,1,5),number(c,"ready-timeout-seconds",900,30,1800),
                number(c,"lightning-to-teleport-delay-ticks",40,1,200),number(c,"teleport-timeout-seconds",15,3,60),
                number(c,"arrival-protection-duration-seconds",5,1,15),denied,
                c.getString("title","ARION — The Beginning"),c.getString("subtitle","Yōkoso, Sakasama no Sekai."),
                bool(c,"debug",false),bool(c,"protection-review-confirmed",false));
    }
    private static List<String> strings(ConfigurationSection c,String key) {
        if (!c.isList(key)) throw new IllegalArgumentException("Expected list: genesis."+key);
        var list=c.getList(key);
        if (list.stream().anyMatch(x -> !(x instanceof String))) throw new IllegalArgumentException("Expected strings: "+key);
        return c.getStringList(key);
    }
    private static String required(ConfigurationSection c,String key) {
        if (!c.isString(key) || c.getString(key).isBlank()) throw new IllegalArgumentException("Required: genesis."+key);
        return c.getString(key);
    }
    private static boolean bool(ConfigurationSection c,String key,boolean fallback) {
        if (c.contains(key) && !c.isBoolean(key)) throw new IllegalArgumentException("Expected boolean: "+key);
        return c.getBoolean(key,fallback);
    }
    private static int number(ConfigurationSection c,String key,int fallback,int min,int max) {
        if (c.contains(key) && !c.isInt(key)) throw new IllegalArgumentException("Expected integer: "+key);
        int value=c.getInt(key,fallback);
        if (value<min || value>max) throw new IllegalArgumentException(key+" must be "+min+".."+max);
        return value;
    }
    private static int integer(Map<?,?> map,String key) {
        Object value=map.get(key);
        if (!(value instanceof Integer i)) throw new IllegalArgumentException("Expected integer region field: "+key);
        return i;
    }
    private static String string(Map<?,?> map,String key) {
        if (!(map.get(key) instanceof String value) || value.isBlank()) throw new IllegalArgumentException("Missing region field: "+key);
        return value;
    }
}
