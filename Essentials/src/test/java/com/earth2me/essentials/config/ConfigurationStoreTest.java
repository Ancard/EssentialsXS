package com.earth2me.essentials.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.earth2me.essentials.config.storage.YamlConfigurationStore;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.spongepowered.configurate.CommentedConfigurationNode;
import org.spongepowered.configurate.yaml.YamlConfigurationLoader;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;

class ConfigurationStoreTest {
    @TempDir Path directory;

    @Test
    void preservesNestedValuesAndUnknownKeys() throws Exception {
        final YamlConfigurationStore store = store("profile.yml");
        final CommentedConfigurationNode node = store.createNode();
        node.node("money").set("123456789.1234567890123456789");
        node.node("enabled").set(false);
        node.node("homes", "dotted.home", "world").set("unloaded-world");
        node.node("extension", "values").setList(String.class, Arrays.asList("first", "second"));
        store.save(node);

        final CommentedConfigurationNode loaded = store.load();
        assertEquals(node.raw(), loaded.raw());
        assertEquals("unloaded-world", loaded.node("homes", "dotted.home", "world").getString());
    }

    @Test
    void coalescesPendingWritesToTheLastDetachedSnapshot() throws Exception {
        final YamlConfigurationStore store = store("queued.yml");
        final AtomicInteger pending = new AtomicInteger(2);
        final CommentedConfigurationNode live = store.createNode();
        live.node("value").set(1);
        final CommentedConfigurationNode first = live.copy();
        live.node("value").set(2);
        final CommentedConfigurationNode last = live.copy();
        live.node("value").set(3);

        new ConfigurationSaveTask(store, () -> first, pending).run();
        assertFalse(Files.exists(directory.resolve("queued.yml")));
        new ConfigurationSaveTask(store, () -> last, pending).run();

        assertEquals(0, pending.get());
        assertEquals(2, store.load().node("value").getInt());
        assertEquals(3, live.node("value").getInt());
    }

    @Test
    void creatingAnEmptyNodeDoesNotWriteAFile() {
        final YamlConfigurationStore store = store("new.yml");
        assertTrue(store.createNode().empty());
        assertFalse(Files.exists(directory.resolve("new.yml")));
    }

    private YamlConfigurationStore store(final String name) {
        return new YamlConfigurationStore(
                YamlConfigurationLoader.builder().path(directory.resolve(name)).build());
    }
}
