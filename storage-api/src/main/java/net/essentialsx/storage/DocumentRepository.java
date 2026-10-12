package net.essentialsx.storage;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/** Exposes document operations with explicit absence and asynchronous failures. */
public interface DocumentRepository {
    CompletableFuture<Optional<String>> read(String key);
    CompletableFuture<Map<String, String>> enumerate();
    CompletableFuture<Void> write(String key, String snapshot);
    CompletableFuture<Void> delete(String key);
    CompletableFuture<Void> flush();
}
