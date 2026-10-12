package com.earth2me.essentials;

import static com.earth2me.essentials.I18n.tlLiteral;

import com.earth2me.essentials.adventure.AdventureUtil;
import com.earth2me.essentials.commands.WarpNotFoundException;
import com.earth2me.essentials.config.EssentialsConfiguration;
import com.earth2me.essentials.utils.StringUtil;

import net.ess3.api.InvalidNameException;
import net.ess3.api.TranslatableException;

import org.bukkit.Location;

import java.io.File;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;

public class Warps implements IConf, net.ess3.api.IWarps {
    private final Map<StringIgnoreCase, EssentialsConfiguration> warpPoints = new HashMap<>();
    private final File warpsFolder;

    public Warps(final File dataFolder) {
        warpsFolder = new File(dataFolder, "warps");
        if (!com.earth2me.essentials.config.storage.BackendSession.active()
                && !warpsFolder.exists()) {
            warpsFolder.mkdirs();
        }
        reloadConfig();
    }

    @Override
    public boolean isEmpty() {
        return warpPoints.isEmpty();
    }

    @Override
    public boolean isWarp(String name) {
        return warpPoints.containsKey(new StringIgnoreCase(name));
    }

    @Override
    public Collection<String> getList() {
        final List<String> keys = new ArrayList<>();
        for (final StringIgnoreCase stringIgnoreCase : warpPoints.keySet()) {
            keys.add(stringIgnoreCase.getString());
        }
        keys.sort(String.CASE_INSENSITIVE_ORDER);
        return keys;
    }

    @Override
    public Location getWarp(final String warp) throws WarpNotFoundException {
        final EssentialsConfiguration conf = warpPoints.get(new StringIgnoreCase(warp));
        if (conf == null) {
            throw new WarpNotFoundException();
        }

        final Location loc = conf.getLocation(null).location();
        if (loc == null) {
            throw new WarpNotFoundException();
        }
        return loc;
    }

    @Override
    public void setWarp(final String name, final Location loc) throws Exception {
        synchronized (com.earth2me.essentials.config.storage.BackendSession.mutationLock()) {
            setWarp(null, name, loc);
        }
    }

    @Override
    public void setWarp(final IUser user, final String name, final Location loc) throws Exception {
        synchronized (com.earth2me.essentials.config.storage.BackendSession.mutationLock()) {
            if (com.earth2me.essentials.config.storage.BackendSession.active()) {
                com.earth2me.essentials.config.storage.BackendSession.current().requireWritable();
            }
            final String filename = StringUtil.sanitizeFileName(name);
            EssentialsConfiguration conf = warpPoints.get(new StringIgnoreCase(name));
            if (conf == null) {
                final File confFile = new File(warpsFolder, filename + ".yml");
                if (com.earth2me.essentials.config.storage.RepositoryFiles.exists(confFile)) {
                    throw new TranslatableException("similarWarpExist");
                }
                conf = new EssentialsConfiguration(confFile);
                conf.load();
                warpPoints.put(new StringIgnoreCase(name), conf);
            }
            conf.setProperty(null, loc);
            conf.setProperty("name", name);
            if (user != null) {
                conf.setProperty("lastowner", user.getBase().getUniqueId().toString());
            }
            conf.save();
        }
    }

    @Override
    public UUID getLastOwner(final String warp) throws WarpNotFoundException {
        final EssentialsConfiguration conf = warpPoints.get(new StringIgnoreCase(warp));
        if (conf == null) {
            throw new WarpNotFoundException();
        }
        UUID uuid = null;
        try {
            uuid = UUID.fromString(conf.getString("lastowner", null));
        } catch (final Exception ignored) {
        }
        return uuid;
    }

    @Override
    public void removeWarp(final String name) throws Exception {
        synchronized (com.earth2me.essentials.config.storage.BackendSession.mutationLock()) {
            final EssentialsConfiguration conf = warpPoints.get(new StringIgnoreCase(name));
            if (conf == null) {
                throw new TranslatableException("warpNotExist");
            }
            if (!com.earth2me.essentials.config.storage.RepositoryFiles.delete(conf.getFile())) {
                throw new TranslatableException("warpDeleteError");
            }
            warpPoints.remove(new StringIgnoreCase(name));
        }
    }

    @Override
    public final void reloadConfig() {
        warpPoints.clear();
        final File[] listOfFiles =
                com.earth2me.essentials.config.storage.RepositoryFiles.listFiles(warpsFolder);
        if (listOfFiles != null) {
            for (final File listOfFile : listOfFiles) {
                final String filename = listOfFile.getName();
                if (com.earth2me.essentials.config.storage.RepositoryFiles.isFile(listOfFile)
                        && filename.endsWith(".yml")) {
                    try {
                        final EssentialsConfiguration conf =
                                new EssentialsConfiguration(listOfFile);
                        conf.load();
                        final String name = conf.getString("name", null);
                        if (name != null && conf.hasProperty("world")) {
                            warpPoints.put(new StringIgnoreCase(name), conf);
                        }
                    } catch (final Exception ex) {
                        Essentials.getWrappedLogger()
                                .log(
                                        Level.WARNING,
                                        AdventureUtil.getAdventureFacet()
                                                .miniToLegacy(tlLiteral("loadWarpError", filename)),
                                        ex);
                    }
                }
            }
        }
    }

    /**
     * @deprecated This method relates to the abandoned 3.x storage refactor and is not implemented.
     */
    @Override
    @Deprecated
    public File getWarpFile(final String name) throws InvalidNameException {
        throw new UnsupportedOperationException("Not supported yet.");
    }

    @Override
    public int getCount() {
        return getList().size();
    }

    private static class StringIgnoreCase {
        private final String string;

        StringIgnoreCase(final String string) {
            this.string = string;
        }

        @Override
        public int hashCode() {
            return getString().toLowerCase(Locale.ENGLISH).hashCode();
        }

        @Override
        public boolean equals(final Object o) {
            if (o instanceof StringIgnoreCase) {
                return getString().equalsIgnoreCase(((StringIgnoreCase) o).getString());
            }
            return false;
        }

        public String getString() {
            return string;
        }
    }
}
