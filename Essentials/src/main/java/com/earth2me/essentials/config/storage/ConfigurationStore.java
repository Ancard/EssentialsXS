package com.earth2me.essentials.config.storage;

import org.spongepowered.configurate.CommentedConfigurationNode;
import org.spongepowered.configurate.ConfigurateException;

/** Persists detached configuration snapshots without accessing Bukkit objects. */
public interface ConfigurationStore {
    CommentedConfigurationNode load() throws ConfigurateException;

    void save(CommentedConfigurationNode snapshot) throws ConfigurateException;

    CommentedConfigurationNode createNode();
}
