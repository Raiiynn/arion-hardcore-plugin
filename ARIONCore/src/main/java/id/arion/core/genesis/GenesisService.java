package id.arion.core.genesis;

import id.arion.core.ARIONCorePlugin;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.logging.Level;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.title.Title;
import io.papermc.paper.registry.RegistryAccess;
import io.papermc.paper.registry.RegistryKey;
import org.bukkit.*;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerTeleportEvent.TeleportCause;
import org.bukkit.scheduler.BukkitTask;
import static id.arion.core.genesis.GenesisSession.*;

/** All mutable event state is main-thread owned. Workers receive detached persistence snapshots only. */
public final class GenesisService implements AutoCloseable {
    private final ARIONCorePlugin plugin;
    public final GenesisConfig config;
    private final GenesisRepository repository;
    private final SpawnPolicy policy;
    private final SafeSpawnValidator validator;
    private final String fingerprint;
    private final Queue<Runnable> io=new ConcurrentLinkedQueue<>();
    private final AtomicBoolean ioRunning=new AtomicBoolean();
    private final AtomicBoolean ioFailed=new AtomicBoolean();
    private volatile boolean closed;
    private boolean initialized, storageBroken, busy, recovering, archiving;
    private GenesisSession session;
    private BukkitTask ticker;
    private long epoch, deadline, ticks, thunderAt, sequenceDeadline;
    private String lastError="Loading Genesis database";
    private final Set<Long> tickets=new HashSet<>();
    private final Set<Long> retained=new HashSet<>();
    private final Map<UUID,Long> protections=new HashMap<>();
    private final GenesisFlights flights;
    private final Set<UUID> teleportPermits=new HashSet<>();
    private final Map<String,Confirmation> confirmations=new HashMap<>();
    private record Confirmation(String token,UUID session,long revision,long expires,String action) {}
    private LoadJob load;
    private SafeSpawnValidator.GroupSearch search;
    private Random random;
    private int factionIndex,attempts,validationIndex;
    private List<GenesisConfig.Region> shuffled;
    private List<Participant> checks=List.of();
    private Runnable afterValidation;

    public GenesisService(ARIONCorePlugin plugin,GenesisConfig config) {
        this.plugin=plugin;this.config=config;
        flights=new GenesisFlights(Duration.ofSeconds(config.teleportSeconds()));
        repository=new GenesisRepository(plugin.getDataFolder().toPath().resolve("genesis.db"));
        policy=new SpawnPolicy(plugin,config);validator=new SafeSpawnValidator(config,policy);
        try { fingerprint=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(
                plugin.getConfig().getConfigurationSection("genesis").getValues(true).toString().getBytes(StandardCharsets.UTF_8))); }
        catch(java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
        submit(() -> { repository.initialize(); return repository.load(); }, existing -> {
            session=existing.orElse(null); initialized=true; lastError="";
            if(session!=null) {
                recovering=true;
                session.quarantineInFlight();
                lastError="Persisted session requires recover inspect; no automatic replay";
            }
        });
        ticker=Bukkit.getScheduler().runTaskTimer(plugin,()->{
            try { tick(); } catch(Exception | LinkageError e) { fail("Genesis tick: "+e.getMessage(),e); }
        },1,1);
    }
    @FunctionalInterface private interface Work<T> { T run() throws Exception; }
    private <T> void submit(Work<T> work,Consumer<T> success) {
        if(closed) return;
        io.add(()->{
            try { T result=work.run(); main(()->{ if(!storageBroken) success.accept(result); }); }
            catch(Exception | LinkageError e) { ioFailed.set(true);io.clear();main(()->{storageBroken=true;fail("Genesis storage unavailable; restart after repair",e);}); }
        });
        drain();
    }
    private void drain() {
        if(closed || ioFailed.get() || !ioRunning.compareAndSet(false,true)) return;
        Bukkit.getScheduler().runTaskAsynchronously(plugin,()->{
            try { Runnable task; while(!closed && !ioFailed.get() && (task=io.poll())!=null) task.run(); }
            finally { ioRunning.set(false); if(!closed && !ioFailed.get() && !io.isEmpty()) main(this::drain); }
        });
    }
    private void main(Runnable action) {
        if(closed) return;
        try { Bukkit.getScheduler().runTask(plugin,()->{
            if(closed) return;
            try { action.run(); } catch(Exception | LinkageError e) { fail("Genesis callback: "+e.getMessage(),e); }
        }); } catch(org.bukkit.plugin.IllegalPluginAccessException ignored) { /* shutdown: persisted intent remains */ }
    }
    private void save(String reason,Runnable done) {
        if(storageBroken) return;
        session.revision++;
        GenesisSession copy=session.copy(); long token=epoch;
        submit(()->{repository.save(copy,reason);return true;},ok->{if(token==epoch) done.run();});
    }
    public String status() {
        if(!initialized) return storageBroken ? lastError : "Genesis loading";
        if(session==null) return "Genesis IDLE"+(lastError.isBlank()?"":"; "+lastError);
        long arrived=session.participants.values().stream().filter(p->p.delivery()==Delivery.ARRIVED).count();
        return "Genesis "+session.state+"; session="+session.id+"; arrived="+arrived+"/"+session.participants.size()
                +"; busy="+busy+"; recovery="+recovering+"; "+session.detail+(lastError.isBlank()?"":"; "+lastError);
    }
    public List<String> completions(String[] args) {
        if(args.length==2) return List.of("validate","prepare","preview","start","status","abort","reset","recover");
        if(args.length==3 && args[1].equalsIgnoreCase("recover")) return List.of("inspect","resume","resolve");
        return List.of();
    }
    public void command(CommandSender sender,String[] args) {
        if(!sender.hasPermission("arion.admin.genesis")) { sender.sendMessage("Tidak punya izin arion.admin.genesis.");return; }
        try {
            String verb=args.length>1?args[1].toLowerCase(Locale.ROOT):"status";
            if(verb.equals("status")) { sender.sendMessage(status());return; }
            require(initialized && !storageBroken,"Genesis database not ready: "+lastError);
            require(!archiving,"Session archive in progress");
            switch(verb) {
                case "validate" -> { environment(); snapshot(); sender.sendMessage("World/faction/config valid. Terrain belum diperiksa; jalankan prepare."); }
                case "prepare" -> prepare(sender);
                case "preview" -> preview(sender);
                case "start" -> start(sender,false);
                case "abort" -> {
                    require(session!=null,"No session");
                    require(session.state!=State.COMPLETED && session.state!=State.ABORTED && session.state!=State.FAILED,"Session already stopped; inspect/reset instead");
                    fail("ABORT by "+actor(sender),null,true);sender.sendMessage(status());
                }
                case "reset" -> reset(sender,args);
                case "recover" -> recovery(sender,args);
                default -> sender.sendMessage("/arion genesis <validate|prepare|preview|start|status|abort|reset|recover>");
            }
        } catch(IllegalArgumentException | IllegalStateException e) { sender.sendMessage("Genesis: "+e.getMessage()); }
        catch(LinkageError e) { fail("Genesis integration incompatible",e);sender.sendMessage(lastError); }
    }
    private static String actor(CommandSender sender) { return sender instanceof Player p?p.getUniqueId().toString():sender.getName(); }
    private static void require(boolean condition,String message) { if(!condition) throw new IllegalStateException(message); }
    private World destination() {
        World world=Bukkit.getWorld(config.destinationWorld());
        require(world!=null,"Destination world not loaded: "+config.destinationWorld());
        if(session!=null) require(world.getUID().equals(session.world),"Destination world UUID changed");
        return world;
    }
    private void environment() {
        require(plugin.ready(),"ARIONCore unavailable"); policy.available();
        require(Bukkit.getWorld(config.openingWorld())!=null,"Opening world not loaded");
        require(destination().getEnvironment()==World.Environment.NORMAL,"Genesis MVP requires an Overworld destination");
        for(String biome:config.deniedBiomes()) require(RegistryAccess.registryAccess()
                .getRegistry(RegistryKey.BIOME).get(NamespacedKey.fromString(biome))!=null,"Unknown biome: "+biome);
    }
    private Map<UUID,Participant> snapshot() {
        var gateway=plugin.factions().orElseThrow(); var result=new LinkedHashMap<UUID,Participant>();
        for(String faction:config.factions()) require(gateway.memberIds(faction).size()<=config.maxMembers(),"Faction exceeds member limit: "+faction);
        for(Player p:Bukkit.getWorld(config.openingWorld()).getPlayers()) {
            if(config.excludedPlayers().contains(p.getUniqueId())) continue;
            eligible(p);
            String faction=gateway.factionOf(p).orElseThrow(()->new IllegalStateException("Factionless: "+p.getName())).id();
            require(config.factions().contains(faction),"Unexpected faction for "+p.getName()+": "+faction);
            result.put(p.getUniqueId(),new Participant(p.getUniqueId(),faction,null,Delivery.PENDING,0));
        }
        for(String faction:config.factions()) require(result.values().stream().anyMatch(p->p.faction().equals(faction)),"No online participant for faction "+faction);
        return result;
    }
    private void eligible(Player p) {
        require(p.isOnline() && !p.isDead() && p.getGameMode()==GameMode.SURVIVAL && !p.isInsideVehicle()
                && p.getPassengers().isEmpty() && !p.isGliding() && p.getFireTicks()<=0 && p.getFreezeTicks()==0,
                "Participant must be alive, survival, unmounted, not burning/freezing: "+p.getName());
    }
    private void rosterCheck(boolean requireAllOnline) {
        environment(); var gateway=plugin.factions().orElseThrow();
        for(var e:session.rosters.entrySet()) require(e.getValue().equals(gateway.memberIds(e.getKey())),"Faction roster changed: "+e.getKey());
        for(var p:session.participants.values()) {
            if(p.delivery()==Delivery.ARRIVED || p.delivery()==Delivery.IN_FLIGHT) continue;
            Player player=Bukkit.getPlayer(p.id());
            if(!requireAllOnline && player==null) continue;
            require(player!=null,"Participant offline: "+p.id()); eligible(player);
            require(player.getWorld().getName().equals(config.openingWorld()),"Participant left opening world: "+player.getName());
            require(gateway.factionOf(player).map(f->f.id().equals(p.faction())).orElse(false),"Faction changed: "+player.getName());
        }
    }
    private void prepare(CommandSender sender) {
        require(session==null,"Session exists; inspect then reset before preparing again"); environment();
        Map<UUID,Participant> players=snapshot();
        session=new GenesisSession(UUID.randomUUID(),destination().getUID(),new java.security.SecureRandom().nextLong(),fingerprint,State.PREPARING);
        session.participants.putAll(players);
        var gateway=plugin.factions().orElseThrow();
        for(String f:config.factions()) session.rosters.put(f,gateway.memberIds(f));
        random=new Random(session.seed);
        session.regions.putAll(GenesisSession.assignRegions(config.factions(),config.regions().stream().map(GenesisConfig.Region::id).toList(),session.seed));
        shuffled=config.factions().stream().map(f->config.regions().stream().filter(r->r.id().equals(session.regions.get(f))).findFirst().orElseThrow()).toList();
        epoch++;busy=true;deadline=System.nanoTime()+Duration.ofSeconds(config.prepareSeconds()).toNanos();factionIndex=0;attempts=0;
        save("PREPARE by "+actor(sender),()->{busy=false;});
        sender.sendMessage("Genesis PREPARING: "+session.id+"; peserta="+players.size());
        for(String f:config.factions()) sender.sendMessage("Faction "+f+": roster="+session.rosters.get(f).size()+", online="+players.values().stream().filter(p->p.faction().equals(f)).count());
    }
    private void preview(CommandSender sender) {
        require(session!=null,"No session"); sender.sendMessage(status());
        session.regions.forEach((f,r)->sender.sendMessage("Faction "+f+" → "+r));
        session.participants.values().forEach(p->sender.sendMessage(p.id()+" ["+p.faction()+"] "+p.delivery()+" → "+p.spawn()));
    }
    private void tick() {
        if(closed || !initialized || storageBroken) return;
        ticks++; protections.entrySet().removeIf(e->System.nanoTime()>e.getValue());
        if(session==null || recovering || archiving) return;
        if(session.state==State.THUNDER || session.state==State.TELEPORTING || session.state==State.COMPLETED && busy)
            require(System.nanoTime()<sequenceDeadline,"Sequence timeout; inspect recovery ledger");
        if(session.state==State.COMPLETED || session.state==State.FAILED || session.state==State.ABORTED) return;
        if(!plugin.ready()) { fail("ARIONCore became unavailable",null);return; }
        if(session.state==State.PREPARING) require(System.nanoTime()<deadline,"Preparation exceeded time limit");
        if(session.state==State.READY) require(System.currentTimeMillis()-session.readyAt<=config.readySeconds()*1000L,"READY expired");
        if(ticks%20==0) rosterCheck(session.state!=State.TELEPORTING);
        if(load!=null) { load.pump();return; }
        if(afterValidation!=null) {
            require(System.nanoTime()<deadline,"Validation timed out");
            long until=System.nanoTime()+config.budgetMs()*1_000_000L;
            while(validationIndex<checks.size() && System.nanoTime()<until) {
                Participant p=checks.get(validationIndex++);
                require(p.spawn()!=null && validator.safe(destination(),p.spawn()),"Prepared spawn is no longer safe: "+p.id());
                Player player=Bukkit.getPlayer(p.id());
                if(player!=null && p.delivery()!=Delivery.ARRIVED) require(playerDestinationSafe(player,p.spawn()),"Spawn collision or player border: "+p.id());
            }
            if(validationIndex==checks.size()) { Runnable done=afterValidation;afterValidation=null;done.run(); }
            return;
        }
        if(busy) return;
        switch(session.state) {
            case PREPARING -> preparationTick();
            case READY -> { if(System.currentTimeMillis()-session.readyAt>config.readySeconds()*1000L) fail("READY expired",null); }
            case THUNDER -> { if(ticks>=thunderAt) {
                session.transition(State.TELEPORTING);busy=true;save("TELEPORTING intent",()->busy=false);
            }}
            case TELEPORTING -> teleportTick();
            default -> { }
        }
    }
    private void preparationTick() {
        require(System.nanoTime()<deadline,"Preparation exceeded time limit");
        if(factionIndex==config.factions().size()) {
            require(GenesisSession.separated(session.participants.values(),config.distance()),"Faction separation failed");
            rosterCheck(true);session.transition(State.READY);session.readyAt=System.currentTimeMillis();busy=true;
            save("READY assignment committed",()->{busy=false;plugin.getLogger().info(status());});return;
        }
        String faction=config.factions().get(factionIndex);GenesisConfig.Region region=shuffled.get(factionIndex);
        if(search!=null) {
            long until=System.nanoTime()+config.budgetMs()*1_000_000L;
            while(!search.done() && System.nanoTime()<until) search.step();
            if(!search.done()) return;
            List<Point> points=search.result();search=null;
            if(!points.isEmpty() && separated(points,faction)) {
                var players=session.participants.values().stream().filter(p->p.faction().equals(faction)).toList();
                for(int i=0;i<players.size();i++) session.participants.put(players.get(i).id(),players.get(i).at(points.get(i)));
                retained.addAll(tickets);factionIndex++;attempts=0;
            } else releaseUnused();
            return;
        }
        require(attempts<=config.attempts(),"No safe spawn for faction "+faction);
        int x,z;boolean fallback=attempts==config.attempts();
        if(attempts==config.attempts()) {
            require(region.fallback()!=null,"No safe spawn and no validated fallback for "+faction);
            x=(int)region.fallback().x();z=(int)region.fallback().z();
        } else { x=random.nextInt(region.minX(),region.maxX()+1);z=random.nextInt(region.minZ(),region.maxZ()+1); }
        attempts++;
        if(config.debug()) plugin.getLogger().info("Genesis candidate faction="+faction+", attempt="+attempts+", x="+x+", z="+z);
        int count=(int)session.participants.values().stream().filter(p->p.faction().equals(faction)).count();
        int fx=x,fz=z;
        load=new LoadJob(footprint(x,z,config.radius()+3),()->{
            if(fallback && !validator.safe(destination(),new Point(fx+0.5,region.fallback().y(),fz+0.5))) {
                releaseUnused();return;
            }
            World world=destination();
            search=new SafeSpawnValidator.GroupSearch((sx,sz)->validator.surface(world,sx,sz),fx,fz,count,region,config.radius());
        },this::releaseUnused);
    }
    private boolean separated(List<Point> points,String faction) {
        for(var p:session.participants.values()) if(p.spawn()!=null && !p.faction().equals(faction))
            for(var point:points) if(point.distanceSquared(p.spawn())<config.distance()*config.distance()) return false;
        return true;
    }
    private static long key(int x,int z) { return ((long)x<<32)^(z&0xffffffffL); }
    private static Set<Long> footprint(int x,int z,int radius) {
        Set<Long> result=new HashSet<>();
        for(int cx=(x-radius)>>4;cx<=(x+radius)>>4;cx++) for(int cz=(z-radius)>>4;cz<=(z+radius)>>4;cz++) result.add(key(cx,cz));
        return result;
    }
    private final class LoadJob {
        private final ArrayDeque<Long> pending;private final Runnable done,failed;private int active;private boolean bad;
        private final long token=epoch;private final long expires=System.nanoTime()+Duration.ofSeconds(15).toNanos();
        LoadJob(Set<Long> chunks,Runnable done,Runnable failed) { pending=new ArrayDeque<>(chunks);this.done=done;this.failed=failed; }
        void pump() {
            require(System.nanoTime()<deadline && System.nanoTime()<expires,"Chunk loading timed out");
            while(!bad && active<config.concurrency() && !pending.isEmpty()) {
                long chunk=pending.removeFirst();int x=(int)(chunk>>32),z=(int)chunk;
                if(tickets.contains(chunk)) continue;
                require(tickets.size()+active<128,"Genesis chunk ticket budget exceeded");active++;
                destination().getChunkAtAsync(x,z,config.generate()).whenComplete((loaded,error)->main(()->{
                    if(token!=epoch || load!=this) return;
                    active--;
                    if(error!=null || loaded==null) {bad=true;return;}
                    // The callback may have been marshalled to a later tick. Never let addTicket synchronously reload.
                    if(!destination().isChunkLoaded(x,z)) {bad=true;return;}
                    destination().addPluginChunkTicket(x,z,plugin);tickets.add(chunk);
                }));
            }
            if(active==0 && (bad || pending.isEmpty())) { load=null;if(bad)failed.run();else done.run(); }
        }
    }
    private void releaseUnused() {
        World world=session==null?null:Bukkit.getWorld(session.world);
        for(long k:new HashSet<>(tickets)) if(!retained.contains(k)) {
            if(world!=null)world.removePluginChunkTicket((int)(k>>32),(int)k,plugin);tickets.remove(k);
        }
    }
    private void releaseTickets() { retained.clear();releaseUnused(); }
    private Location location(Point p) { return new Location(destination(),p.x(),p.y(),p.z()); }
    private void validateSaved(Runnable done) {
        checks=List.copyOf(session.participants.values()); validationIndex=0;afterValidation=done;
        deadline=System.nanoTime()+Duration.ofSeconds(config.prepareSeconds()).toNanos();
    }
    private void start(CommandSender sender,boolean recovery) {
        require(session!=null && session.state==State.READY && !recovering && !busy,"Start requires READY and idle");
        require(!session.startIntent || recovery,"Start already accepted");rosterCheck(true);
        require(System.currentTimeMillis()-session.readyAt<=config.readySeconds()*1000L,"READY expired");
        busy=true;
        validateSaved(()->{
            rosterCheck(true);session.armStart(recovery);
            save("START intent by "+actor(sender),()->{
                rosterCheck(true);
                sequenceDeadline=System.nanoTime()+Duration.ofSeconds(120).toNanos();
                if(recovery) {session.transition(State.TELEPORTING);busy=false;return;}
                session.transition(State.THUNDER);thunderAt=ticks+config.delayTicks();busy=false;
                // One pass, visual-only API; no real lightning or per-player repeating tasks.
                for(var p:session.participants.values()) if(p.delivery()!=Delivery.ARRIVED) {
                    Player player=Bukkit.getPlayer(p.id());player.getWorld().strikeLightningEffect(player.getLocation());
                }
                plugin.getLogger().info("Genesis THUNDER "+session.id);
            });
        });
        sender.sendMessage("Start accepted; validating saved locations before cinematic.");
    }
    private void teleportTick() {
        long now=System.nanoTime();
        var expired=flights.expired(now);
        if(expired.isPresent()) {
            session.participants.computeIfPresent(expired.get(),(id,p)->p.delivered(Delivery.NEEDS_REVIEW));
            fail("Teleport outcome ambiguous: "+expired.get()+"; no automatic retry",null);return;
        }
        // One inspected dispatch per tick; at most five teleports may be in-flight.
        List<Participant> batch=session.participants.values().stream().filter(p->p.delivery()==Delivery.PENDING).limit(flights.size()<5?1:0).toList();
        if(!batch.isEmpty()) {
            for(var p:batch) {
                require(p.attempts()<2,"Teleport retry limit reached for "+p.id());
                Player player=Bukkit.getPlayer(p.id());
                if(player==null) {session.participants.put(p.id(),p.delivered(Delivery.OFFLINE));fail("Participant disconnected",null);return;}
                require(validator.safe(destination(),p.spawn()) && playerDestinationSafe(player,p.spawn()),"Arrival location changed: "+p.id());
                session.participants.put(p.id(),p.attempt());
            }
            busy=true;save("BATCH delivery intent",()->{
                busy=false;
                for(var old:batch) dispatch(session.participants.get(old.id()));
            });return;
        }
        if(session.allArrived() && flights.isEmpty()) {
            busy=true;session.releasedAt=System.currentTimeMillis();session.transition(State.COMPLETED);
            save("COMPLETED",()->{
                busy=false;long until=System.nanoTime()+Duration.ofSeconds(config.protectionSeconds()).toNanos();
                session.participants.keySet().forEach(id->protections.put(id,until));releaseTickets();plugin.getLogger().info(status());
            });
        }
    }
    private void dispatch(Participant p) {
        Player player=Bukkit.getPlayer(p.id());
        if(player==null) {session.participants.put(p.id(),p.delivered(Delivery.OFFLINE));fail("Participant disconnected before dispatch",null);return;}
        if(session.state!=State.TELEPORTING) return;
        rosterCheck(false);
        require(validator.safe(destination(),p.spawn()) && playerDestinationSafe(player,p.spawn()),"Spawn changed before dispatch");
        long token=epoch;flights.begin(p.id(),token,System.nanoTime());teleportPermits.add(p.id());
        player.setFallDistance(0);
        player.teleportAsync(location(p.spawn()),TeleportCause.PLUGIN).whenComplete((ok,error)->main(()->{
            if(!flights.finish(p.id(),token)) return;
            teleportPermits.remove(p.id());
            if(token!=epoch) return; // Persisted IN_FLIGHT remains ambiguous after abort.
            Player current=Bukkit.getPlayer(p.id());
            if(error!=null || !Boolean.TRUE.equals(ok) || current==null || !current.getWorld().getUID().equals(session.world)
                    || current.getLocation().distanceSquared(location(p.spawn()))>1) {
                session.participants.put(p.id(),p.delivered(Delivery.FAILED));fail("Teleport failed/cancelled: "+p.id(),error);return;
            }
            current.setFallDistance(0);
            protections.put(p.id(),System.nanoTime()+Duration.ofSeconds(config.protectionSeconds()).toNanos());
            current.showTitle(Title.title(Component.text(config.title()),Component.text(config.subtitle()),
                    Title.Times.times(Duration.ofMillis(500),Duration.ofSeconds(3),Duration.ofSeconds(1))));
            session.participants.put(p.id(),p.delivered(Delivery.ARRIVED));save("ARRIVED "+p.id(),()->{});
        }));
    }
    public boolean protectedPlayer(UUID id) {
        return frozen(id) || protections.getOrDefault(id,0L)>System.nanoTime()
                || flights.protecting(id,System.nanoTime());
    }
    private boolean playerDestinationSafe(Player player,Point point) {
        var border=player.getWorldBorder();
        if(border!=null && !SafeSpawnValidator.border(point.x(),point.z(),border.getCenter().getX(),border.getCenter().getZ(),border.getSize(),config.borderMargin())) return false;
        return !player.collidesAt(location(point));
    }
    public boolean frozen(UUID id) {
        return !closed && !recovering && !archiving && session!=null && session.participants.containsKey(id)
                && (session.state==State.READY || session.state==State.THUNDER || session.state==State.TELEPORTING
                    || session.state==State.COMPLETED && busy);
    }
    public boolean permitTeleport(UUID id,Location to) {
        if(!teleportPermits.contains(id) || session==null || to==null) return false;
        Point p=session.participants.get(id).spawn();
        return to.getWorld().getUID().equals(session.world) && Math.abs(to.getX()-p.x())<0.01 && Math.abs(to.getY()-p.y())<0.01 && Math.abs(to.getZ()-p.z())<0.01;
    }
    public void departed(UUID id) {
        if(frozen(id)) fail("Participant disconnected/left opening: "+id,null);
    }
    public void worldUnloaded(UUID id) {
        if(session==null || recovering || archiving || session.state==State.COMPLETED || session.state==State.FAILED || session.state==State.ABORTED) return;
        if(session.world.equals(id) || Optional.ofNullable(Bukkit.getWorld(config.openingWorld())).map(w->w.getUID().equals(id)).orElse(false))
            fail("Event world unloaded",null);
    }
    private void fail(String reason,Throwable cause) { fail(reason,cause,false); }
    private void fail(String reason,Throwable cause,boolean abort) {
        if(closed) return;
        boolean pendingCompletion=busy;
        lastError=reason;epoch++;load=null;search=null;afterValidation=null;busy=false;teleportPermits.clear();
        if(session!=null && (session.state!=State.COMPLETED || pendingCompletion)) {
            session.state=abort?State.ABORTED:State.FAILED;session.detail=reason;
            session.quarantineInFlight();
            if(!storageBroken)save(reason,()->{});
        }
        releaseTickets();
        if(cause!=null)plugin.getLogger().log(Level.SEVERE,reason,cause);else plugin.getLogger().warning(reason);
    }
    private boolean confirmed(CommandSender sender,String action,String token) {
        String actor=actor(sender); Confirmation c=confirmations.get(actor);
        if(c!=null && c.action().equals(action) && c.session().equals(session.id) && c.revision()==session.revision
                && c.expires()>System.nanoTime() && c.token().equals(token)) {confirmations.remove(actor);return true;}
        String next=UUID.randomUUID().toString().substring(0,8);
        confirmations.put(actor,new Confirmation(next,session.id,session.revision,System.nanoTime()+Duration.ofSeconds(30).toNanos(),action));
        sender.sendMessage("Konfirmasi dalam 30 detik: /arion genesis "+action+" "+next);return false;
    }
    private void reset(CommandSender sender,String[] args) {
        require(session!=null && !busy && flights.isEmpty(),"Session missing/busy or teleport still in-flight; restart and inspect if unresolved");
        require(recovering || session.state==State.FAILED || session.state==State.ABORTED || session.state==State.COMPLETED,"Abort active session first");
        if(!confirmed(sender,"reset",args.length>2?args[2]:"")) return;
        busy=true;archiving=true;UUID id=session.id;String administrator=actor(sender);
        submit(()->{repository.archive(id,administrator);return true;},ok->{epoch++;releaseTickets();session=null;recovering=false;busy=false;archiving=false;lastError="";sender.sendMessage("Session archived. Genesis IDLE.");});
    }
    private void recovery(CommandSender sender,String[] args) {
        require(session!=null,"No persisted session");
        if(args.length<3 || args[2].equalsIgnoreCase("inspect")) {preview(sender);return;}
        require(!busy && flights.isEmpty(),"Busy/in-flight; cannot reconcile yet");
        require(recovering || session.state==State.FAILED || session.state==State.ABORTED,"Recovery requires stopped session");
        if(args[2].equalsIgnoreCase("resolve")) {
            require(args.length>=5,"Usage: recover resolve <uuid> <arrived|pending> [token]");
            UUID id=UUID.fromString(args[3]);Participant p=session.participants.get(id);require(p!=null,"Unknown participant");
            require(args[4].equals("arrived") || args[4].equals("pending"),"Expected arrived or pending");
            String action="recover resolve "+id+" "+args[4];
            if(!confirmed(sender,action,args.length>5?args[5]:"")) return;
            session.participants.put(id,p.delivered(args[4].equals("arrived")?Delivery.ARRIVED:Delivery.PENDING));busy=true;
            save("ADMIN RESOLVE by "+actor(sender)+": "+id+" "+args[4],()->{busy=false;sender.sendMessage("Resolution saved");});return;
        }
        require(args[2].equalsIgnoreCase("resume"),"Expected recover inspect|resolve|resume");
        require(session.fingerprint.equals(fingerprint),"Config changed; restore original configuration or archive and prepare explicitly");
        require(session.participants.values().stream().allMatch(p->p.spawn()!=null),"Preparation incomplete; archive and prepare explicitly");
        require(session.participants.values().stream().noneMatch(p->p.delivery()==Delivery.NEEDS_REVIEW || p.delivery()==Delivery.IN_FLIGHT),"Resolve ambiguous participants first");
        require(session.participants.values().stream().allMatch(p->p.delivery()==Delivery.ARRIVED || p.attempts()<2),"Retry limit reached; inspect and resolve manually");
        if(!confirmed(sender,"recover resume",args.length>3?args[3]:"")) return;
        rosterCheck(true);epoch++;recovering=false;busy=true;session.state=State.READY;session.readyAt=System.currentTimeMillis();
        session.participants.replaceAll((id,p)->p.delivery()==Delivery.ARRIVED?p:p.delivered(Delivery.PENDING));
        Set<Long> chunks=new HashSet<>();for(var p:session.participants.values()) chunks.addAll(footprint((int)Math.floor(p.spawn().x()),(int)Math.floor(p.spawn().z()),3));
        deadline=System.nanoTime()+Duration.ofSeconds(config.prepareSeconds()).toNanos();
        load=new LoadJob(chunks,()->{retained.addAll(tickets);busy=false;start(sender,true);},()->fail("Recovery chunk load failed",null));
        sender.sendMessage("Recovery loading saved assignments; thunder will not replay.");
    }
    @Override public void close() {
        closed=true;epoch++;if(ticker!=null)ticker.cancel();io.clear();load=null;afterValidation=null;
        releaseTickets();protections.clear();teleportPermits.clear();
        // No blocking wait: each worker closes its own connection. Durable intents drive restart recovery.
    }
}
