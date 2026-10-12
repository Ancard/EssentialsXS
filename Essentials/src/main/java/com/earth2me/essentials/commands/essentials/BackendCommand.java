package com.earth2me.essentials.commands.essentials;

import com.earth2me.essentials.CommandSource;
import com.earth2me.essentials.commands.EssentialsTreeNode;
import com.earth2me.essentials.config.storage.BackendMigration;
import com.earth2me.essentials.config.storage.BackendSession;
import com.earth2me.essentials.config.storage.ConfigurationRevision;

import java.util.concurrent.CompletableFuture;

/** Runs storage administration without performing JDBC on the command thread. */
public final class BackendCommand extends EssentialsTreeNode {
    public BackendCommand() {
        super("backend");
    }

    @Override
    protected void run(CommandSource sender, String label, String[] args) throws Exception {
        if (!sender.isAuthorized("essentials.backend")) {
            throw new IllegalStateException("Missing essentials.backend permission.");
        }
        final String action = args.length == 0 ? "status" : args[0];
        final BackendSession session = BackendSession.current();
        final CompletableFuture<?> operation;
        if (action.equals("status")) {
            sender.sendMessage(
                    (session == null ? "YAML backend; SQL is inactive." : session.status())
                            + "; migration="
                            + BackendMigration.status());
            return;
        }
        if (action.equals("import") || action.equals("export")) {
            if (!ess.getServer().getOnlinePlayers().isEmpty() || sender.isPlayer()) {
                throw new IllegalStateException(
                        "Migration requires console maintenance with no connected players.");
            }
            if (action.equals("import") && session != null) {
                throw new IllegalStateException(
                        "Import must run while the YAML backend is selected.");
            }
            operation =
                    BackendMigration.execute(
                            ess.getDataFolder(),
                            action,
                            java.util.Arrays.asList(args).contains("--dry-run"));
        } else if (action.equals("flush") || action.equals("check")) {
            if (session == null) {
                throw new IllegalStateException("SQL backend is inactive.");
            }
            operation = action.equals("check") ? session.check() : session.flush();
        } else if (action.equals("config") && args.length > 1) {
            if (session == null) {
                throw new IllegalStateException("SQL backend is inactive.");
            }
            if (args[1].equals("reload")) {
                operation = session.reloadConfiguration();
            } else if (args[1].equals("stage")) {
                operation = session.stageConfiguration();
            } else if (args[1].equals("validate") || args[1].equals("publish")) {
                final String mode = args[1];
                operation =
                        session.configurationTask(
                                        () -> {
                                            final java.util.Map<String, String> revision =
                                                    ConfigurationRevision.loadStage(session);
                                            if (mode.equals("publish")) {
                                                return new ConfigurationRevision.Draft(
                                                        revision,
                                                        ConfigurationRevision.expectedRevision(
                                                                session));
                                            }
                                            return "Configuration draft validated.";
                                        })
                                .thenCompose(
                                        value -> {
                                            if (value instanceof ConfigurationRevision.Draft) {
                                                final ConfigurationRevision.Draft draft =
                                                        (ConfigurationRevision.Draft) value;
                                                return session.publishConfiguration(
                                                                draft.documents,
                                                                draft.expectedRevision)
                                                        .thenApply(
                                                                ignored ->
                                                                        "Configuration revision"
                                                                                + " published.");
                                            }
                                            return CompletableFuture.completedFuture(value);
                                        });
            } else {
                throw new IllegalArgumentException("Use config stage|validate|publish|reload.");
            }
        } else {
            throw new IllegalArgumentException(
                    "Use backend status|check|flush|import|export|config"
                            + " stage|validate|publish|reload.");
        }
        sender.sendMessage("Backend operation started.");
        operation.whenComplete(
                (result, failure) ->
                        ess.getServer()
                                .getScheduler()
                                .runTask(
                                        ess,
                                        () -> {
                                            if (failure != null) {
                                                ess.getLogger()
                                                        .warning(
                                                                "Backend operation failed: "
                                                                        + action);
                                                sender.sendMessage(
                                                        "Backend operation failed; no backend"
                                                            + " switch was performed. Review the"
                                                            + " operation and connection"
                                                            + " settings.");
                                            } else {
                                                ess.getLogger()
                                                        .info(
                                                                "Backend operation completed: "
                                                                        + action);
                                                if (action.equals("config")
                                                        && (args[1].equals("reload")
                                                                || args[1].equals("publish"))) {
                                                    ess.reload();
                                                }
                                                sender.sendMessage(
                                                        result == null
                                                                ? "Backend operation completed."
                                                                : result.toString());
                                            }
                                        }));
    }
}
