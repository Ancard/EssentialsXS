package com.earth2me.essentials.config.storage;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/** Replaces filesystem assumptions for SQL-managed resources only. */
public final class RepositoryFiles {
    private RepositoryFiles() {
    }

    public static boolean managed(File file) {
        return BackendSession.active()
                && BackendSession.current().managed(BackendSession.current().catalog().key(file));
    }

    public static boolean exists(File file) {
        if (managed(file)) {
            return BackendSession.current().read(BackendSession.current().catalog().key(file))
                    != null;
        }
        return YamlRepositories.exists(file);
    }

    public static boolean isFile(File file) {
        return managed(file) ? exists(file) : file.isFile();
    }

    public static long lastModified(File file) {
        return managed(file)
                ? BackendSession.current().modified(BackendSession.current().catalog().key(file))
                : file.lastModified();
    }

    public static boolean delete(File file) {
        if (managed(file)) {
            BackendSession.current().delete(BackendSession.current().catalog().key(file));
            return true;
        }
        return YamlRepositories.delete(file);
    }

    public static File[] listFiles(File directory) {
        if (!BackendSession.active()) {
            return YamlRepositories.enumerate(directory);
        }
        if (directory.getName().equals("kits")
                && !BackendSession.current().managed("core/kits.yml")) {
            return directory.listFiles();
        }
        try {
            final List<File> files = new ArrayList<>();
            final String base = directory.getCanonicalPath() + File.separator;
            for (String key : BackendSession.current().snapshot().keySet()) {
                if (key.startsWith("core/@")) {
                    continue;
                }
                final File file = BackendSession.current().catalog().file(key);
                if (file.getPath().startsWith(base)
                        && file.getParentFile()
                                .getCanonicalFile()
                                .equals(directory.getCanonicalFile())) {
                    files.add(file);
                }
            }
            return files.toArray(new File[0]);
        } catch (Exception e) {
            throw new IllegalStateException("Cannot enumerate repository resources.", e);
        }
    }
}
