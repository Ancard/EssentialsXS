package com.earth2me.essentials.config.storage;

import org.spongepowered.configurate.CommentedConfigurationNode;
import org.spongepowered.configurate.ConfigurateException;
import org.spongepowered.configurate.yaml.YamlConfigurationLoader;

import java.io.BufferedReader;
import java.io.StringReader;

/** Adapts existing Configurate serializers to the SQL session's in-memory snapshots. */
public final class CachedConfigurationStore implements ConfigurationStore {
    private final BackendSession session;
    private final String key;
    private final ConfigurationStore yaml;
    private final long generation;

    public CachedConfigurationStore(BackendSession session, String key, ConfigurationStore yaml) {
        this.session = session;
        this.key = key;
        this.yaml = yaml;
        generation = session.generation(key);
    }

    @Override
    public CommentedConfigurationNode load() throws ConfigurateException {
        final String value = session.read(key);
        final CommentedConfigurationNode node =
                value == null
                        ? createNode()
                        : YamlConfigurationLoader.builder()
                                .defaultOptions(o -> yaml.createNode().options())
                                .source(() -> new BufferedReader(new StringReader(value)))
                                .build()
                                .load();
        if (key.equals("core/config.yml")) {
            try {
                node.node("backend").set(session.bootstrap().node("backend"));
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }
        return node;
    }

    @Override
    public void save(CommentedConfigurationNode snapshot) {
        try {
            final CommentedConfigurationNode copy = snapshot.copy();
            if (key.equals("core/config.yml")) {
                copy.removeChild("backend");
            }
            session.write(key, BackendSession.encode(copy), generation);
        } catch (Exception e) {
            throw new IllegalStateException("Persistent snapshot was rejected.", e);
        }
    }

    @Override
    public CommentedConfigurationNode createNode() {
        return yaml.createNode();
    }

    public void requireWritable() {
        session.requireWritable(key, generation);
    }
}
