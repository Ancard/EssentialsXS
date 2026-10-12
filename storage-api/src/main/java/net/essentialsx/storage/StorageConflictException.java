package net.essentialsx.storage;

/** Identifies optimistic revision conflicts separately from absent data and connection failures. */
public final class StorageConflictException extends Exception {
    public StorageConflictException(String message) { super(message); }
}
