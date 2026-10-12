package com.earth2me.essentials.config.storage;

import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerLoginEvent;

/** Prevents player admission while a maintenance migration is capturing source data. */
public final class BackendMaintenanceListener implements Listener {
    @EventHandler
    public void onPreLogin(AsyncPlayerPreLoginEvent event) {
        if (BackendMigration.busy()
                || (BackendSession.active() && !BackendSession.current().available())) {
            event.disallow(
                    AsyncPlayerPreLoginEvent.Result.KICK_OTHER,
                    "Essentials storage maintenance is in progress.");
        }
    }

    @EventHandler
    public void onLogin(PlayerLoginEvent event) {
        if (BackendMigration.busy()
                || (BackendSession.active() && !BackendSession.current().available())) {
            event.disallow(
                    PlayerLoginEvent.Result.KICK_OTHER,
                    "Essentials storage maintenance is in progress.");
        }
    }
}
