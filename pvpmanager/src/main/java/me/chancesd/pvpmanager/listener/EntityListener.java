package me.chancesd.pvpmanager.listener;

import java.util.concurrent.TimeUnit;
import org.bukkit.Effect;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.*;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockIgniteEvent;
import org.bukkit.event.block.BlockIgniteEvent.IgniteCause;
import org.bukkit.event.entity.EntityCombustByEntityEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.PotionSplashEvent;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.event.weather.LightningStrikeEvent;
import org.bukkit.event.weather.LightningStrikeEvent.Cause;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.projectiles.ProjectileSource;
import org.jetbrains.annotations.Nullable;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;

import me.chancesd.pvpmanager.integration.Hook;
import me.chancesd.pvpmanager.integration.type.WorldGuardDependency;
import me.chancesd.pvpmanager.manager.PlayerManager;
import me.chancesd.pvpmanager.player.CombatPlayer;
import me.chancesd.pvpmanager.player.ProtectionResult;
import me.chancesd.pvpmanager.player.ProtectionType;
import me.chancesd.pvpmanager.setting.Lang;
import me.chancesd.pvpmanager.setting.Permissions;
import me.chancesd.pvpmanager.setting.Conf;
import me.chancesd.pvpmanager.utils.CombatUtils;
import me.chancesd.sdutils.utils.MCVersion;

public class EntityListener implements Listener {

	private final PlayerManager playerHandler;
	private final WorldGuardDependency wg;
	private final Cache<LightningStrike, Location> lightningCache = CacheBuilder.newBuilder().expireAfterWrite(2, TimeUnit.SECONDS).build();

	public EntityListener(final PlayerManager ph) {
		this.playerHandler = ph;
		this.wg = (WorldGuardDependency) ph.getPlugin().getDependencyManager().getDependency(Hook.WORLDGUARD);
	}

	@EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
	public final void onPlayerDamage(final EntityDamageByEntityEvent event) {
		// OPTİMİZASYON: Hasar alan veya veren oyuncu/ok değilse anında dur (CPU Dostu)
		final Entity victim = event.getEntity();
		final Entity damager = event.getDamager();
		if (!(victim instanceof Player) && !(damager instanceof Player) && !(damager instanceof Projectile)) return;

		if (CombatUtils.isWorldExcluded(victim.getWorld().getName())) return;

		if (!CombatUtils.isPvP(event)) {
			if (!(victim instanceof Player playerVictim)) return;

			final CombatPlayer attacked = playerHandler.get(playerVictim);
			if (attacked.isNewbie() && Conf.NEWBIE_GODMODE.asBool()) {
				event.setCancelled(true);
			} else if (damager instanceof final LightningStrike lightning) {
				if (!lightningCache.asMap().containsKey(lightning)) return;
				if (!attacked.hasPvPEnabled() || attacked.isNewbie() || attacked.hasRespawnProtection()) {
					event.setCancelled(true);
				}
			}
			return;
		}

		final Player attacker = getAttacker(damager);
		if (attacker == null) return;
		final Player attacked = (Player) victim;

		final ProtectionResult result = playerHandler.checkProtection(attacker, attacked);
		if (result.isProtected()) {
			event.setCancelled(true);
			Lang.messageProtection(result, attacker, attacked);
		}
	}

	@EventHandler(priority = EventPriority.HIGHEST)
	public final void onPlayerDamageOverride(final EntityDamageByEntityEvent event) {
		if (!event.isCancelled() || !CombatUtils.isPvP(event)) return;
		if (CombatUtils.isWorldExcluded(event.getEntity().getWorld().getName())) return;

		final Player attacker = getAttacker(event.getDamager());
		if (attacker == null) return;
		
		if (playerHandler.checkProtection(attacker, (Player) event.getEntity()).type() == ProtectionType.FAIL_OVERRIDE) {
			event.setCancelled(false);
		}
	}

	@EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
	public final void onPlayerDamageMonitor(final EntityDamageByEntityEvent event) {
		if (!CombatUtils.isPvP(event)) return;
		if (CombatUtils.isWorldExcluded(event.getEntity().getWorld().getName())) return;
		
		final Player attacker = getAttacker(event.getDamager());
		if (attacker == null) return;
		
		processDamage(attacker, (Player) event.getEntity());
	}

	@EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
	public final void onEntityCombust(final EntityCombustByEntityEvent event) {
		final Entity victim = event.getEntity();
		if (CombatUtils.isWorldExcluded(victim.getWorld().getName())) return;
		
		if (!CombatUtils.isPvP(event)) {
			if (victim instanceof final Player player && playerHandler.get(player).isNewbie() && Conf.NEWBIE_GODMODE.asBool()) {
				event.setCancelled(true);
			}
			return;
		}

		final Player attacker = getAttacker(event.getCombuster());
		if (attacker == null) return;

		if (!playerHandler.canAttack(attacker, (Player) victim)) {
			event.setCancelled(true);
		}
	}

	public void processDamage(final Player attacker, final Player defender) {
		final CombatPlayer pvpAttacker = playerHandler.get(attacker);
		final CombatPlayer pvpDefender = playerHandler.get(defender);

		if (Conf.PVP_BLOOD.asBool()) {
			defender.getWorld().playEffect(defender.getLocation(), Effect.STEP_SOUND, Material.REDSTONE_BLOCK);
		}

		disableActions(attacker, defender, pvpAttacker, pvpDefender);
		
		if (Conf.COMBAT_TAG_ENABLED.asBool()) {
			if (Conf.VULNERABLE_ENABLED.asBool() && wg != null && !Conf.VULNERABLE_RENEW_TAG.asBool() 
				&& wg.hasDenyPvPFlag(attacker) && wg.hasDenyPvPFlag(defender)) {
				return;
			}
			pvpAttacker.tag(true, pvpDefender);
			pvpDefender.tag(false, pvpAttacker);
		}
	}

	private void disableActions(final Player attacker, final Player defender, final CombatPlayer pvpAttacker, final CombatPlayer pvpDefender) {
		final boolean hasExemptPerm = pvpAttacker.hasPerm(Permissions.EXEMPT_DISABLE_ACTIONS);
		
		if (Conf.DISABLE_FLY.asBool()) {
			if (CombatUtils.canFly(attacker) && !hasExemptPerm) pvpAttacker.disableFly();
			if (CombatUtils.canFly(defender) && !pvpDefender.hasPerm(Permissions.EXEMPT_DISABLE_ACTIONS)) pvpDefender.disableFly();
		}
		
		if (Conf.DISABLE_ELYTRA.asBool()) {
			if (!hasExemptPerm) CombatUtils.checkGlide(pvpAttacker);
			if (!pvpDefender.hasPerm(Permissions.EXEMPT_DISABLE_ACTIONS)) CombatUtils.checkGlide(pvpDefender);
		}

		if (hasExemptPerm) return;
		
		if (Conf.DISABLE_GAMEMODE.asBool() && attacker.getGameMode() != GameMode.SURVIVAL) {
			attacker.setGameMode(GameMode.SURVIVAL);
		}
		if (Conf.DISABLE_DISGUISE.asBool()) {
			playerHandler.getPlugin().getDependencyManager().disableDisguise(attacker);
		}
		if (Conf.DISABLE_INVISIBILITY.asBool() && attacker.hasPotionEffect(PotionEffectType.INVISIBILITY)) {
			attacker.removePotionEffect(PotionEffectType.INVISIBILITY);
		}
		if (Conf.DISABLE_GODMODE.asBool()) {
			playerHandler.getPlugin().getDependencyManager().disableGodMode(attacker);
		}
	}

	@EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
	public final void onPotionSplash(final PotionSplashEvent event) {
		if (!shouldCheckPotionEvent(event)) return;

		final Player player = (Player) event.getPotion().getShooter();
		if (player == null) return;

		for (final LivingEntity e : event.getAffectedEntities()) {
			if (e instanceof Player attacked && !e.equals(player)) {
				final ProtectionResult result = playerHandler.checkProtection(player, attacked);
				if (result.isProtected()) {
					event.setIntensity(attacked, 0);
					Lang.messageProtection(result, player, attacked);
				}
			}
		}
	}

	@EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
	public final void onPotionSplashMonitor(final PotionSplashEvent event) {
		if (!shouldCheckPotionEvent(event)) return;

		final Player player = (Player) event.getPotion().getShooter();
		for (final LivingEntity e : event.getAffectedEntities()) {
			if (e instanceof Player attacked && !e.equals(player)) {
				processDamage(player, attacked);
			}
		}
	}

	private boolean shouldCheckPotionEvent(final PotionSplashEvent event) {
		if (CombatUtils.isWorldExcluded(event.getEntity().getWorld().getName())) return false;

		final ThrownPotion potion = event.getPotion();
		if (event.getAffectedEntities().isEmpty() || potion.getEffects().isEmpty() || !(potion.getShooter() instanceof Player)) return false;

		for (final PotionEffect effect : potion.getEffects()) {
			if (CombatUtils.isHarmfulPotion(effect.getType())) return true;
		}
		return false;
	}

	@EventHandler(ignoreCancelled = true)
	public void onLightningStrike(final LightningStrikeEvent event) {
		if (MCVersion.isAtLeast(MCVersion.V1_13_1) && event.getCause() == Cause.TRIDENT) {
			if (!CombatUtils.isWorldExcluded(event.getLightning().getWorld().getName())) {
				lightningCache.put(event.getLightning(), event.getLightning().getLocation());
			}
		}
	}

	@EventHandler(ignoreCancelled = true)
	public void onBlockIgnite(final BlockIgniteEvent event) {
		if (event.getCause() != IgniteCause.LIGHTNING) return;
		if (CombatUtils.isWorldExcluded(event.getBlock().getWorld().getName())) return;

		final Entity igniter = event.getIgnitingEntity();
		if (igniter instanceof final LightningStrike lightning && lightningCache.asMap().containsKey(igniter)) {
			// OPTİMİZASYON: distanceSquared ile yarıçap kontrolü (2^2 = 4)
			for (final Entity entity : igniter.getNearbyEntities(2, 2, 2)) {
				if (entity instanceof final Player player) {
					final CombatPlayer attacked = playerHandler.get(player);
					if (!attacked.hasPvPEnabled() || attacked.isNewbie() || attacked.hasRespawnProtection()) {
						event.setCancelled(true);
						return;
					}
				}
			}
		}
	}

	@EventHandler(ignoreCancelled = true)
	public void onProjectileHitEvent(final ProjectileHitEvent event) {
		if (!(event.getEntity().getShooter() instanceof final Player player)) return;

		final CombatPlayer pvPlayer = playerHandler.get(player);
		if (pvPlayer.isInCombat()) {
			final EntityType type = event.getEntity().getType();
			if ((Conf.PEARL_RENEW_TAG.asBool() && type == EntityType.ENDER_PEARL)
					|| (Conf.WIND_CHARGE_RENEW_TAG.asBool() && type == EntityType.WIND_CHARGE)) {
				final CombatPlayer enemy = pvPlayer.getEnemy();
				pvPlayer.tag(true, enemy != null ? enemy : pvPlayer);
			}
		}
	}

	@Nullable
	private Player getAttacker(final Entity damager) {
		if (damager instanceof Player player) return player;
		if (damager instanceof Projectile proj) return proj.getShooter() instanceof Player s ? s : null;
		if (damager instanceof TNTPrimed tnt) return tnt.getSource() instanceof Player s ? s : null;
		if (damager instanceof AreaEffectCloud aec) return aec.getSource() instanceof Player s ? s : null;
		return null;
	}
}
