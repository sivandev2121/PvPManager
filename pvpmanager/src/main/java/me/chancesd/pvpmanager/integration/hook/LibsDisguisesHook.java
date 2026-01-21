package me.chancesd.pvpmanager.integration.hook;

import org.bukkit.entity.Player;
import me.chancesd.pvpmanager.integration.BaseDependency;
import me.chancesd.pvpmanager.integration.Hook;
import me.chancesd.pvpmanager.integration.type.DisguiseDependency;

public class LibsDisguisesHook extends BaseDependency implements DisguiseDependency {

	public LibsDisguisesHook(final Hook hook) {
		super(hook);
	}

	@Override
	public boolean isDisguised(final Player player) {
		return false; // Kütüphane silindiği için her zaman false döndürür
	}

	@Override
	public void unDisguise(final Player player) {
		// Kütüphane silindiği için işlem yapmaz
	}

	@Override
	public String onEnableMessage() {
		return "LibsDisguises integration disabled to fix build errors.";
	}

}
