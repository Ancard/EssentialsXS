package com.earth2me.essentials;

import net.ess3.api.IEssentials;
import net.essentialsx.api.v2.services.BalanceTop;

import org.bukkit.plugin.ServicePriority;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public class BalanceTopImpl implements BalanceTop {
    private final IEssentials ess;
    private LinkedHashMap<UUID, BalanceTop.Entry> topCache = new LinkedHashMap<>();
    private BigDecimal balanceTopTotal = BigDecimal.ZERO;
    private long cacheAge = 0;
    private CompletableFuture<Void> cacheLock;

    public BalanceTopImpl(IEssentials ess) {
        this.ess = ess;
        ess.getServer()
                .getServicesManager()
                .register(BalanceTop.class, this, ess, ServicePriority.Normal);
    }

    private void calculateBalanceTopMap() {
        final List<Entry> entries = new LinkedList<>();
        BigDecimal newTotal = BigDecimal.ZERO;
        for (UUID u : ess.getUsers().getAllUserUUIDs()) {
            final User user = ess.getUsers().loadUncachedUser(u);
            if (user != null) {
                if (!ess.getSettings().isNpcsInBalanceRanking() && user.isNPC()) {
                    // Don't list NPCs in output
                    continue;
                }
                if (!user.isBaltopExempt()) {
                    final BigDecimal userMoney = user.getMoney();
                    user.updateMoneyCache(userMoney);
                    newTotal = newTotal.add(userMoney);
                    final String name;
                    if (user.getBase() instanceof OfflinePlayerStub) {
                        name = user.getLastAccountName();
                    } else if (user.isHidden()) {
                        name = user.getName();
                    } else {
                        name = user.getDisplayName();
                    }
                    entries.add(new BalanceTop.Entry(user.getUUID(), name, userMoney));
                }
            }
        }
        final LinkedHashMap<UUID, Entry> sortedMap = new LinkedHashMap<>();
        entries.sort((entry1, entry2) -> entry2.getBalance().compareTo(entry1.getBalance()));
        final int entryLimit = ess.getSettings().getBaltopEntryLimit();
        final int limit = entryLimit == -1 ? entries.size() : Math.min(entryLimit, entries.size());
        for (int i = 0; i < limit; i++) {
            final Entry entry = entries.get(i);
            sortedMap.put(entry.getUuid(), entry);
        }
        topCache = sortedMap;
        balanceTopTotal = newTotal;
        cacheAge = System.currentTimeMillis();
        cacheLock.complete(null);
        cacheLock = null;
    }

    @Override
    public CompletableFuture<Void> calculateBalanceTopMapAsync() {
        if (cacheLock != null) {
            return cacheLock;
        }
        cacheLock = new CompletableFuture<>();
        if (com.earth2me.essentials.config.storage.BackendSession.active()) {
            final CompletableFuture<Void> result = cacheLock;
            final Runnable capture =
                    () -> {
                        final Map<UUID, String> names = new java.util.HashMap<>();
                        for (org.bukkit.entity.Player player : ess.getServer().getOnlinePlayers()) {
                            final User user = ess.getUser(player);
                            names.put(
                                    player.getUniqueId(),
                                    user.isHidden() ? user.getName() : user.getDisplayName());
                        }
                        final Map<String, String> documents =
                                com.earth2me.essentials.config.storage.BackendSession.current()
                                        .snapshot();
                        final boolean includeNpcs = ess.getSettings().isNpcsInBalanceRanking();
                        final BigDecimal starting = ess.getSettings().getStartingBalance();
                        final int entryLimit = ess.getSettings().getBaltopEntryLimit();
                        ess.runTaskAsynchronously(
                                () -> {
                                    try {
                                        final List<Entry> entries = new java.util.ArrayList<>();
                                        BigDecimal total = BigDecimal.ZERO;
                                        for (Map.Entry<String, String> document :
                                                documents.entrySet()) {
                                            if (!document.getKey().startsWith("core/userdata/")) {
                                                continue;
                                            }
                                            final org.spongepowered.configurate
                                                            .CommentedConfigurationNode
                                                    node =
                                                            com.earth2me.essentials.config.storage
                                                                    .BackendSession.decode(
                                                                    document.getValue());
                                            if (node.node("baltop-exempt").getBoolean()
                                                    || (!includeNpcs
                                                            && node.node("npc").getBoolean())) {
                                                continue;
                                            }
                                            final UUID uuid =
                                                    UUID.fromString(
                                                            document.getKey()
                                                                    .substring(
                                                                            14,
                                                                            document.getKey()
                                                                                            .length()
                                                                                    - 4));
                                            final BigDecimal amount =
                                                    new BigDecimal(
                                                            node.node("money")
                                                                    .getString(
                                                                            starting
                                                                                    .toPlainString()));
                                            total = total.add(amount);
                                            entries.add(
                                                    new Entry(
                                                            uuid,
                                                            names.getOrDefault(
                                                                    uuid,
                                                                    node.node("last-account-name")
                                                                            .getString()),
                                                            amount));
                                        }
                                        entries.sort(
                                                (a, b) -> b.getBalance().compareTo(a.getBalance()));
                                        final LinkedHashMap<UUID, Entry> sorted =
                                                new LinkedHashMap<>();
                                        final int limit =
                                                entryLimit < 0
                                                        ? entries.size()
                                                        : Math.min(entryLimit, entries.size());
                                        for (int i = 0; i < limit; i++) {
                                            final Entry entry = entries.get(i);
                                            sorted.put(entry.getUuid(), entry);
                                        }
                                        topCache = sorted;
                                        balanceTopTotal = total;
                                        cacheAge = System.currentTimeMillis();
                                        cacheLock = null;
                                        result.complete(null);
                                    } catch (Exception failure) {
                                        cacheLock = null;
                                        result.completeExceptionally(failure);
                                    }
                                });
                    };
            if (org.bukkit.Bukkit.isPrimaryThread()) {
                capture.run();
            } else {
                ess.getServer().getScheduler().runTask(ess, capture);
            }
            return result;
        }
        ess.runTaskAsynchronously(this::calculateBalanceTopMap);
        return cacheLock;
    }

    @Override
    public Map<UUID, Entry> getBalanceTopCache() {
        return Collections.unmodifiableMap(topCache);
    }

    @Override
    public long getCacheAge() {
        return cacheAge;
    }

    @Override
    public BigDecimal getBalanceTopTotal() {
        return balanceTopTotal;
    }

    public boolean isCacheLocked() {
        return cacheLock != null;
    }
}
