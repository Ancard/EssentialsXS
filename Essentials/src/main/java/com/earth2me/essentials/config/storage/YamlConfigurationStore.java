package com.earth2me.essentials.config.storage;

import org.spongepowered.configurate.CommentedConfigurationNode;
import org.spongepowered.configurate.ConfigurateException;
import org.spongepowered.configurate.yaml.YamlConfigurationLoader;

import java.util.Objects;

/** Retains the existing YAML loader, serializers and file format behind a store contract. */
public final class YamlConfigurationStore implements ConfigurationStore {
    private final YamlConfigurationLoader loader;

    public YamlConfigurationStore(final YamlConfigurationLoader loader) {
        this.loader = Objects.requireNonNull(loader, "loader");
    }

    @Override
    public CommentedConfigurationNode load() throws ConfigurateException {
        synchronized (loader) {
            return loader.load();
        }
    }

    @Override
    public void save(final CommentedConfigurationNode snapshot) throws ConfigurateException {
        // Legacy callers may wrap the same loader more than once; retain its shared lock.
        synchronized (loader) {
            loader.save(snapshot);
        }
    }

    @Override
    public CommentedConfigurationNode createNode() {
        return loader.createNode();
    }
}
