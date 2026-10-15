package id.arion.core.genesis;

import id.arion.core.ARIONCorePlugin;
import org.bukkit.*;

/** Conservative optional WorldGuard public-API bridge. Any adapter error denies preparation. */
public final class SpawnPolicy {
    private final ARIONCorePlugin plugin;
    private final GenesisConfig config;
    public SpawnPolicy(ARIONCorePlugin plugin,GenesisConfig config) { this.plugin=plugin; this.config=config; }
    public void available() {
        if (!config.protectionReviewed()) throw new IllegalStateException("Set protection-review-confirmed only after reviewing all protection/territory exclusions; see docs/GENESIS.md");
        if (plugin.factions().isEmpty()) throw new IllegalStateException("Supported SaberFactions required for Genesis");
        var wg=Bukkit.getPluginManager().getPlugin("WorldGuard");
        if (wg!=null && !wg.isEnabled()) throw new IllegalStateException("WorldGuard is installed but disabled");
    }
    public boolean allowed(Location at) {
        if (!Bukkit.isPrimaryThread()) throw new IllegalStateException("Spawn policy requires main thread");
        for(var e:config.exclusions()) if(e.contains(at.getWorld().getName(),at.getBlockX(),at.getBlockY(),at.getBlockZ())) return false;
        if (!plugin.factions().orElseThrow().wilderness(at)) return false;
        var spawn=at.getWorld().getSpawnLocation();
        double dx=at.getX()-spawn.getX(), dz=at.getZ()-spawn.getZ();
        if (dx*dx+dz*dz < (double)config.spawnExclusion()*config.spawnExclusion()) return false;
        var wg=Bukkit.getPluginManager().getPlugin("WorldGuard");
        if(wg==null) return true;
        if(!wg.isEnabled()) throw new IllegalStateException("WorldGuard disabled during session");
        try {
            ClassLoader loader=wg.getClass().getClassLoader();
            Class<?> api=Class.forName("com.sk89q.worldguard.WorldGuard",true,loader);
            Object instance=api.getMethod("getInstance").invoke(null);
            Object platform=api.getMethod("getPlatform").invoke(instance);
            Object container=platform.getClass().getMethod("getRegionContainer").invoke(platform);
            Class<?> weWorld=Class.forName("com.sk89q.worldedit.world.World",true,loader);
            Class<?> adapter=Class.forName("com.sk89q.worldedit.bukkit.BukkitAdapter",true,loader);
            Object world=adapter.getMethod("adapt",World.class).invoke(null,at.getWorld());
            Object manager=container.getClass().getMethod("get",weWorld).invoke(container,world);
            if(manager==null) throw new IllegalStateException("WorldGuard region manager unavailable");
            if(manager.getClass().getMethod("getRegion",String.class).invoke(manager,"__global__")!=null) return false;
            Class<?> vector=Class.forName("com.sk89q.worldedit.math.BlockVector3",true,loader);
            Object pos=vector.getMethod("at",int.class,int.class,int.class).invoke(null,at.getBlockX(),at.getBlockY(),at.getBlockZ());
            Object result=manager.getClass().getMethod("getApplicableRegions",vector).invoke(manager,pos);
            // Reject every region, regardless of bypass or flags. Global regions are deliberately conservative.
            Class<?> applicable=Class.forName("com.sk89q.worldguard.protection.ApplicableRegionSet",true,loader);
            return ((Number)applicable.getMethod("size").invoke(result)).intValue()==0;
        } catch(ReflectiveOperationException | LinkageError e) { throw new IllegalStateException("Cannot verify WorldGuard protection",e); }
    }
}
