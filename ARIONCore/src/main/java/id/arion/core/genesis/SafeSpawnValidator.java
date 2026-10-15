package id.arion.core.genesis;

import java.util.*;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.data.Waterlogged;
import static id.arion.core.genesis.GenesisSession.Point;

public final class SafeSpawnValidator {
    private final GenesisConfig config;
    private final SpawnPolicy policy;
    public SafeSpawnValidator(GenesisConfig config,SpawnPolicy policy) { this.config=config; this.policy=policy; }
    private static final Set<String> HAZARDS=Set.of("LAVA","WATER","POWDER_SNOW","FIRE","SOUL_FIRE","MAGMA_BLOCK","CACTUS",
            "CAMPFIRE","SOUL_CAMPFIRE","SWEET_BERRY_BUSH","WITHER_ROSE","POINTED_DRIPSTONE","COBWEB","END_PORTAL","NETHER_PORTAL",
            "SCULK_SHRIEKER","SCULK_SENSOR","TNT","SPAWNER","TRIAL_SPAWNER","INFESTED_STONE","INFESTED_DEEPSLATE");
    public static boolean hazardous(String material) { return HAZARDS.contains(material) || material.startsWith("INFESTED_"); }
    public static boolean border(double x,double z,double centerX,double centerZ,double size,double margin) {
        return Math.abs(x-centerX)+margin<size/2 && Math.abs(z-centerZ)+margin<size/2;
    }
    public boolean loaded(World world,int x,int z,int radius) {
        for(int cx=(x-radius)>>4;cx<=(x+radius)>>4;cx++) for(int cz=(z-radius)>>4;cz<=(z+radius)>>4;cz++)
            if(!world.isChunkLoaded(cx,cz)) return false;
        return true;
    }
    public Point surface(World world,int x,int z) {
        if(!loaded(world,x,z,3)) return null;
        int y=world.getHighestBlockYAt(x,z,HeightMap.MOTION_BLOCKING_NO_LEAVES)+1;
        Point point=new Point(x+0.5,y,z+0.5);
        return safe(world,point) ? point : null;
    }
    public boolean safe(World world,Point p) {
        if(!Bukkit.isPrimaryThread()) throw new IllegalStateException("Block inspection requires main thread");
        int x=(int)Math.floor(p.x()),y=(int)Math.floor(p.y()),z=(int)Math.floor(p.z());
        if(y<world.getMinHeight()+4 || y>=world.getMaxHeight()-3 || !loaded(world,x,z,3)) return false;
        var border=world.getWorldBorder(); var center=border.getCenter();
        if(!border(p.x(),p.z(),center.getX(),center.getZ(),border.getSize(),config.borderMargin())) return false;
        // No canopy, cave or overhang: player's column must be the actual highest surface.
        if(world.getHighestBlockYAt(x,z,HeightMap.MOTION_BLOCKING)!=y-1) return false;
        Block ground=world.getBlockAt(x,y-1,z);
        if(!ground.getType().isOccluding() || hazardous(ground.getType().name()) || ground.getType().hasGravity()
                || ground.getType().name().endsWith("_LEAVES") || ground.getType().name().contains("ICE")) return false;
        if(!world.getBlockAt(x,y,z).getType().isAir() || !world.getBlockAt(x,y+1,z).getType().isAir()
                || !world.getBlockAt(x,y+2,z).getType().isAir()) return false;
        if(config.deniedBiomes().contains(world.getBiome(x,y,z).getKey().toString())) return false;
        // Fixed-size bounded halo; no chunk loading hidden in block access.
        for(int dx=-2;dx<=2;dx++) for(int dz=-2;dz<=2;dz++) {
            int top=world.getHighestBlockYAt(x+dx,z+dz,HeightMap.MOTION_BLOCKING);
            if(Math.abs(top-(y-1))>2) return false;
            for(int dy=-2;dy<=2;dy++) {
                Block block=world.getBlockAt(x+dx,y+dy,z+dz);
                if(hazardous(block.getType().name()) || block.getBlockData() instanceof Waterlogged w && w.isWaterlogged()) return false;
            }
            if(!policy.allowed(new Location(world,x+dx+0.5,y,z+dz+0.5))) return false;
        }
        return true;
    }
    /** Incremental connected surface flood-fill. One step inspects at most one column. */
    public static final class GroupSearch {
        private final java.util.function.BiFunction<Integer,Integer,Point> surface;
        private final int x,z,count,radius; private final GenesisConfig.Region region;
        private final ArrayDeque<int[]> queue=new ArrayDeque<>(); private final Set<Long> seen=new HashSet<>();
        private final List<Point> points=new ArrayList<>();
        public GroupSearch(java.util.function.BiFunction<Integer,Integer,Point> surface,int x,int z,int count,GenesisConfig.Region region,int radius) {
            if(count<1 || count>10 || radius<1 || radius>16) throw new IllegalArgumentException("Invalid group size/radius");
            this.surface=surface;this.x=x;this.z=z;this.count=count;this.region=region;this.radius=radius;queue.add(new int[]{x,z,Integer.MIN_VALUE});
        }
        public void step() {
            if(done()) return;
            int[] next=queue.removeFirst(); int nx=next[0],nz=next[1];
            long key=((long)nx<<32) ^ (nz&0xffffffffL);
            if(!seen.add(key) || !region.contains(nx+0.5,nz+0.5) || (nx-x)*(nx-x)+(nz-z)*(nz-z)>radius*radius) return;
            Point p=surface.apply(nx,nz);
            if(p==null || next[2]!=Integer.MIN_VALUE && Math.abs(p.y()-next[2])>1) return;
            if(points.stream().allMatch(v->v.distanceSquared(p)>=4)) points.add(p);
            for(int[] d:new int[][]{{1,0},{-1,0},{0,1},{0,-1}}) queue.add(new int[]{nx+d[0],nz+d[1],(int)p.y()});
        }
        public boolean done() { return points.size()==count || queue.isEmpty(); }
        public List<Point> result() { return points.size()==count ? List.copyOf(points) : List.of(); }
    }
}
