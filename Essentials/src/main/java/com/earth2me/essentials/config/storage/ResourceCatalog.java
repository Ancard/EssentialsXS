package com.earth2me.essentials.config.storage;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;

/** Maps only the resources owned by the included modules to stable repository keys. */
public final class ResourceCatalog {
    private final File core;
    private final File geoip;

    public ResourceCatalog(File core) throws IOException {
        this.core = core.getCanonicalFile();
        this.geoip = new File(core.getParentFile(), "EssentialsGeoIP").getCanonicalFile();
        if (!this.core.equals(core.getAbsoluteFile().toPath().normalize().toFile())
                || !this.geoip.equals(
                        new File(this.core.getParentFile(), "EssentialsGeoIP")
                                .getAbsoluteFile()
                                .toPath()
                                .normalize()
                                .toFile())) {
            throw new IOException("Storage roots cannot contain symbolic links or junctions.");
        }
    }

    public String key(File file) {
        try {
            final String path = file.getCanonicalPath();
            final String lexical = file.getAbsoluteFile().toPath().normalize().toString();
            if (lexical.startsWith(core.getPath() + File.separator)
                    && !path.startsWith(core.getPath() + File.separator)) {
                throw new IOException("Storage resource escaped its root.");
            }
            if (path.equals(new File(geoip, "config.yml").getPath())) {
                return "geoip/config.yml";
            }
            final String root = core.getPath() + File.separator;
            if (!path.startsWith(root)) {
                return null;
            }
            final String relative = path.substring(root.length()).replace(File.separatorChar, '/');
            if (relative.indexOf('/') < 0 && relative.endsWith(".txt")) {
                return "core/" + relative;
            }
            if (relative.startsWith("userdata/") && relative.endsWith(".yml")) {
                java.util.UUID.fromString(relative.substring(9, relative.length() - 4));
                return "core/" + relative;
            }
            if (relative.startsWith("warps/") && relative.endsWith(".yml")) {
                return "core/" + relative;
            }
            if (relative.startsWith("kits/") && relative.endsWith(".yml")) {
                return "core/" + relative;
            }
            if (relative.startsWith("messages/") && relative.endsWith(".properties")) {
                return "core/" + relative;
            }
            if (Arrays.asList(
                            "config.yml",
                            "kits.yml",
                            "worth.yml",
                            "tpr.yml",
                            "custom_items.yml",
                            "jail.yml",
                            "spawn.yml",
                            "motd.txt",
                            "rules.txt",
                            "info.txt",
                            "help.txt")
                    .contains(relative)) {
                return "core/" + relative;
            }
            return null;
        } catch (IOException | IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid storage resource path.", e);
        }
    }

    public File file(String key) throws IOException {
        final File root = key.startsWith("geoip/") ? geoip : core;
        final String relative = key.substring(key.indexOf('/') + 1);
        final File file = new File(root, relative).getCanonicalFile();
        if (!file.getPath().startsWith(root.getPath() + File.separator) || !key.equals(key(file))) {
            throw new IOException("Invalid storage resource key.");
        }
        return file;
    }

    public File core() {
        return core;
    }

    public File local(String relative) throws IOException {
        final File candidate = new File(core, relative), resolved = candidate.getCanonicalFile();
        if (!resolved.getPath().startsWith(core.getPath() + File.separator)
                || !resolved.equals(candidate.getAbsoluteFile().toPath().normalize().toFile())) {
            throw new IOException("Local backend path escaped its root.");
        }
        return resolved;
    }
}
