package com.earth2me.essentials.config.storage;

import org.spongepowered.configurate.ConfigurationNode;

/** Validates the local backend selector before storage-dependent services become available. */
public final class BackendBootstrap {
    private BackendBootstrap() {
    }

    public static void requireAvailableBackend(final ConfigurationNode root) {
        final ConfigurationNode backend = root.node("backend");
        if (!backend.virtual() && !backend.isMap()) {
            throw new IllegalArgumentException("backend must be a configuration section.");
        }
        final ConfigurationNode typeNode = backend.node("type");
        if (typeNode.virtual()) {
            return;
        }
        final Object type = typeNode.raw();
        if (!(type instanceof String)) {
            throw new IllegalArgumentException("backend.type must be yaml or mysql.");
        }
        if ("yaml".equals(type)) {
            return;
        }
        if ("mysql".equals(type)) {
            if (BackendSession.active()) {
                return;
            }
            throw new IllegalStateException("The SQL backend has not reached its ready state.");
        }
        throw new IllegalArgumentException("backend.type must be yaml or mysql.");
    }
}
