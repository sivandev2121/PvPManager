package me.chancesd.pvpmanager.listener;

import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.util.Vector;
import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import me.chancesd.pvpmanager.manager.DependencyManager;
import me.chancesd.pvpmanager.manager.PlayerManager;
import me.chancesd.pvpmanager.player.CombatPlayer;
import me.chancesd.pvpmanager.setting.Conf;
import me.chancesd.pvpmanager.setting.Lang;

public class MoveListener implements Listener {

    private final PlayerManager ph;
    private final DependencyManager depManager;
    private final Cache<UUID, Player> cache = CacheBuilder.newBuilder().weakValues().expireAfterWrite(1, TimeUnit.SECONDS).build();
    private final double pushbackForce = Conf.PUSHBACK_FORCE.asDouble();

    public MoveListener(final PlayerManager ph) {
        this.ph = ph;
        this.depManager = ph.getPlugin().getDependencyManager();
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public final void onPlayerMove(final PlayerMoveEvent event) {
        final Location locTo = event.getTo();
        if (locTo == null) return;
        
        final Location locFrom = event.getFrom();

        // AGIR OPTIMIZASYON: Oyuncu blok bazında yer degistirmediyse (kafasini cevirdiyse) aninda dur!
        if (locFrom.getBlockX() == locTo.getBlockX() && 
            locFrom.getBlockZ() == locTo.getBlockZ() && 
            locFrom.getBlockY() == locTo.getBlockY()) {
            return;
        }

        // AGIR OPTIMIZASYON 2: Savasat olmayan oyuncu icin hesaplama yapma (CPU tasarrufu)
        final CombatPlayer pvplayer = ph.get(event.getPlayer());
        if (!pvplayer.isInCombat()) {
            return;
        }

        // Guvenli bolge ve itme (pushback) kontrolü
        if (!depManager.canAttackAt(null, locTo) && depManager.canAttackAt(null, locFrom)) {
            final Player player = event.getPlayer();
            final Vector newVel = locFrom.toVector().subtract(locTo.toVector());
            
            // Itme gucunu hesapla ve uygula
            newVel.setY(newVel.getY() + 0.1).normalize().multiply(pushbackForce);
            player.setVelocity(newVel);

            // Oyuncuya uyari mesajini gonder (Cache ile spam engelli)
            if (!cache.asMap().containsKey(player.getUniqueId())) {
                pvplayer.message(Lang.PUSHBACK_WARNING);
                cache.put(player.getUniqueId(), player);
            }
        }
    }
}
