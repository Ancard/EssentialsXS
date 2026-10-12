package com.earth2me.essentials.commands.essentials;

import com.earth2me.essentials.CommandSource;
import com.earth2me.essentials.commands.EssentialsTreeNode;

public class ReloadCommand extends EssentialsTreeNode {

    public ReloadCommand() {
        super("reload");
    }

    @Override
    protected void run(final CommandSource sender, final String commandLabel, final String[] args)
            throws Exception {
        if (com.earth2me.essentials.config.storage.BackendSession.active()
                && com.earth2me.essentials.config.storage.BackendSession.current()
                        .managed("core/config.yml")) {
            com.earth2me.essentials.config.storage.BackendSession.current()
                    .reloadConfiguration()
                    .whenComplete(
                            (result, failure) ->
                                    ess.getServer()
                                            .getScheduler()
                                            .runTask(
                                                    ess,
                                                    () -> {
                                                        if (failure != null) {
                                                            sender.sendMessage(
                                                                    "Configuration reload failed;"
                                                                        + " the active revision was"
                                                                        + " retained.");
                                                        } else {
                                                            ess.reload();
                                                            sender.sendTl(
                                                                    "essentialsReload",
                                                                    ess.getDescription()
                                                                            .getVersion());
                                                        }
                                                    }));
            return;
        }
        ess.reload();
        sender.sendTl("essentialsReload", ess.getDescription().getVersion());
    }
}
