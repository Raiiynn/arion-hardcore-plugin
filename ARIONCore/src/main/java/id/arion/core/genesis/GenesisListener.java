package id.arion.core.genesis;

import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.*;
import org.bukkit.event.block.*;
import org.bukkit.event.entity.*;
import org.bukkit.event.player.*;
import org.bukkit.event.world.WorldUnloadEvent;

/** Session-scoped protection; never changes invulnerability flags, gamemode or DeathBan permissions. */
public final class GenesisListener implements Listener {
    private final GenesisService genesis;
    public GenesisListener(GenesisService genesis) { this.genesis=genesis; }
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)
    public void move(PlayerMoveEvent event) {
        if(event instanceof PlayerTeleportEvent || !genesis.frozen(event.getPlayer().getUniqueId()) || event.getTo()==null) return;
        var from=event.getFrom();var to=event.getTo();
        if(from.getX()!=to.getX() || from.getY()!=to.getY() || from.getZ()!=to.getZ()) {
            var fixed=from.clone();fixed.setYaw(to.getYaw());fixed.setPitch(to.getPitch());event.setTo(fixed);
        }
    }
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)
    public void teleport(PlayerTeleportEvent event) {
        if(genesis.frozen(event.getPlayer().getUniqueId()) && !genesis.permitTeleport(event.getPlayer().getUniqueId(),event.getTo())) event.setCancelled(true);
    }
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)
    public void damage(EntityDamageEvent event) {
        if(event.getEntity() instanceof Player p && genesis.protectedPlayer(p.getUniqueId())) event.setCancelled(true);
        if(event instanceof EntityDamageByEntityEvent by) {
            Player attacker=by.getDamager() instanceof Player p?p:by.getDamager() instanceof Projectile projectile && projectile.getShooter() instanceof Player p?p:null;
            if(attacker!=null && genesis.protectedPlayer(attacker.getUniqueId()))event.setCancelled(true);
        }
    }
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)
    public void combust(EntityCombustEvent event) {
        if(event.getEntity() instanceof Player p && genesis.protectedPlayer(p.getUniqueId()))event.setCancelled(true);
    }
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)
    public void hunger(FoodLevelChangeEvent event) {
        if(event.getEntity() instanceof Player p && genesis.frozen(p.getUniqueId()))event.setCancelled(true);
    }
    @EventHandler(priority=EventPriority.HIGHEST)
    public void interact(PlayerInteractEvent event) { if(genesis.frozen(event.getPlayer().getUniqueId()))event.setCancelled(true); }
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)
    public void interactEntity(PlayerInteractEntityEvent event) { if(genesis.frozen(event.getPlayer().getUniqueId()))event.setCancelled(true); }
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)
    public void breakBlock(BlockBreakEvent event) { if(genesis.frozen(event.getPlayer().getUniqueId()))event.setCancelled(true); }
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)
    public void placeBlock(BlockPlaceEvent event) { if(genesis.frozen(event.getPlayer().getUniqueId()))event.setCancelled(true); }
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)
    public void drop(PlayerDropItemEvent event) { if(genesis.frozen(event.getPlayer().getUniqueId()))event.setCancelled(true); }
    @EventHandler public void quit(PlayerQuitEvent event) { genesis.departed(event.getPlayer().getUniqueId()); }
    @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true)
    public void unload(WorldUnloadEvent event) { genesis.worldUnloaded(event.getWorld().getUID()); }
}
