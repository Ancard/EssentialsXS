package net.essentialsx.storage;

import java.util.Map;
import java.util.Set;

/** Performs persistent I/O only on the storage worker. */
public interface StorageEngine extends AutoCloseable {
    default void check() throws Exception { heartbeat(); loadAll(); }
    default long configurationRevision() { return 0; }
    Map<String, String> loadAll() throws Exception;
    void commit(Map<String, String> values, Set<String> deleted) throws Exception;
    void heartbeat() throws Exception;
    String description();
    boolean initialized() throws Exception;
    void markInitialized() throws Exception;
    int beginImport(String manifestHash) throws Exception;
    void advanceImport(int progress, boolean completed) throws Exception;
    @Override
    void close() throws Exception;
}
