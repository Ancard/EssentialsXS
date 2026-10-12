package com.earth2me.essentials.config;

import com.earth2me.essentials.Essentials;
import com.earth2me.essentials.config.storage.ConfigurationStore;
import com.earth2me.essentials.config.storage.YamlConfigurationStore;

import org.spongepowered.configurate.CommentedConfigurationNode;
import org.spongepowered.configurate.ConfigurateException;
import org.spongepowered.configurate.yaml.YamlConfigurationLoader;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.logging.Level;

public class ConfigurationSaveTask implements Runnable {
    private final ConfigurationStore store;
    private final Supplier<CommentedConfigurationNode> nodeSupplier;
    private final AtomicInteger pendingWrites;
    private final Consumer<Throwable> completion;

    public ConfigurationSaveTask(
            final YamlConfigurationLoader loader,
            final Supplier<CommentedConfigurationNode> nodeSupplier,
            final AtomicInteger pendingWrites) {
        this(new YamlConfigurationStore(loader), nodeSupplier, pendingWrites);
    }

    public ConfigurationSaveTask(
            final ConfigurationStore store,
            final Supplier<CommentedConfigurationNode> nodeSupplier,
            final AtomicInteger pendingWrites) {
        this(store, nodeSupplier, pendingWrites, failure -> {});
    }

    public ConfigurationSaveTask(
            final ConfigurationStore store,
            final Supplier<CommentedConfigurationNode> nodeSupplier,
            final AtomicInteger pendingWrites,
            Consumer<Throwable> completion) {
        this.store = store;
        this.nodeSupplier = nodeSupplier;
        this.pendingWrites = pendingWrites;
        this.completion = completion;
    }

    @Override
    public void run() {
        if (pendingWrites.decrementAndGet() > 0) {
            return;
        }

        synchronized (store) {
            try {
                final CommentedConfigurationNode node = nodeSupplier.get();
                store.save(node);
                completion.accept(null);
            } catch (ConfigurateException | RuntimeException e) {
                completion.accept(e);
                Essentials.getWrappedLogger().log(Level.SEVERE, e.getMessage(), e);
            }
        }
    }
}
