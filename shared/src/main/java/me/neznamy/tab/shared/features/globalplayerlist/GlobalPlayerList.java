package me.neznamy.tab.shared.features.globalplayerlist;

import java.util.*;

import lombok.Getter;
import me.neznamy.tab.shared.TabConstants;
import me.neznamy.tab.shared.chat.TabComponent;
import me.neznamy.tab.shared.config.files.config.GlobalPlayerListConfiguration;
import me.neznamy.tab.shared.cpu.ThreadExecutor;
import me.neznamy.tab.shared.cpu.TimedCaughtTask;
import me.neznamy.tab.shared.features.PlayerList;
import me.neznamy.tab.shared.features.redis.RedisPlayer;
import me.neznamy.tab.shared.features.redis.RedisSupport;
import me.neznamy.tab.shared.platform.TabList;
import me.neznamy.tab.shared.TAB;
import me.neznamy.tab.shared.platform.TabPlayer;
import me.neznamy.tab.shared.features.types.*;
import me.neznamy.tab.shared.util.OnlinePlayers;
import me.neznamy.tab.shared.util.PerformanceUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Feature handler for global PlayerList feature.
 */
public class GlobalPlayerList extends RefreshableFeature implements JoinListener, QuitListener, VanishListener, GameModeListener,
        Loadable, UnLoadable, ServerSwitchListener, TabListClearListener, CustomThreaded, RedisFeature {

    @Getter private final ThreadExecutor customThread = new ThreadExecutor("TAB Global PlayerList Thread");
    @Getter private OnlinePlayers onlinePlayers;
    @Nullable private final RedisSupport redis = TAB.getInstance().getFeatureManager().getFeature(TabConstants.Feature.REDIS_BUNGEE);
    @NotNull private final GlobalPlayerListConfiguration configuration;
    @NotNull  private final Map<String, String> serverToGroupName = new HashMap<>();
    @NotNull private final Map<String, Object> groupNameToGroup = new HashMap<>();
    @NotNull private final Map<String, String> serverToClusterMain = new HashMap<>();
    @Nullable private final PlayerList playerlist = TAB.getInstance().getFeatureManager().getFeature(TabConstants.Feature.PLAYER_LIST);

    /**
     * Constructs new instance and registers new placeholders.
     *
     * @param   configuration
     *          Feature configuration
     */
    public GlobalPlayerList(@NotNull GlobalPlayerListConfiguration configuration) {
        this.configuration = configuration;
        for (Map.Entry<String, List<String>> entry : configuration.sharedServers.entrySet()) {
            TAB.getInstance().getPlaceholderManager().registerServerPlaceholder(TabConstants.Placeholder.globalPlayerListGroup(entry.getKey()), 1000, () -> {
                if (onlinePlayers == null) return "0"; // Not loaded yet
                int count = 0;
                for (TabPlayer player : onlinePlayers.getPlayers()) {
                    if (entry.getValue().contains(player.server) && !player.isVanished()) count++;
                }
                if (redis != null) {
                    for (RedisPlayer player : redis.getRedisPlayers().values()) {
                        if (entry.getValue().contains(player.server) && !player.isVanished()) count++;
                    }
                }
                return PerformanceUtil.toString(count);
            });
        }
    }

    @Override
    public void load() {
        onlinePlayers =  new OnlinePlayers(TAB.getInstance().getOnlinePlayers());
        if (configuration.updateLatency) addUsedPlaceholder(TabConstants.Placeholder.PING);
        for (TabPlayer all : onlinePlayers.getPlayers()) {
            all.globalPlayerListData.serverGroup = getServerGroup(all.server);
            all.globalPlayerListData.onSpyServer = configuration.spyServers.contains(all.server.toLowerCase());
            // MineVN start
            all.globalPlayerListData.clusterMainServerId = getClusterMainServerId(all.server);
            // MineVN end
        }
        for (TabPlayer viewer : onlinePlayers.getPlayers()) {
            for (TabPlayer displayed : onlinePlayers.getPlayers()) {
                if (viewer.server.equals(displayed.server)) continue;
                if (shouldSee(viewer, displayed)) {
                    viewer.getTabList().addEntry(getAddInfoData(displayed, viewer));
                }
            }
        }
    }

    /**
     * Returns {@code true} if viewer should see the target player, {@code false} if not.
     *
     * @param   viewer
     *          Player viewing the tablist
     * @param   displayed
     *          Player who is being displayed
     * @return  {@code true} if viewer should see the target, {@code false} if not
     */
    public boolean shouldSee(@NotNull TabPlayer viewer, @NotNull TabPlayer displayed) {
        if (displayed == viewer) return true;
        if (!TAB.getInstance().getPlatform().canSee(viewer, displayed)) return false;
        if (viewer.globalPlayerListData.onSpyServer) return true;
        // MineVN start
        if (displayed.globalPlayerListData.clusterMainServerId != null && viewer.globalPlayerListData.clusterMainServerId != null) {
            // viewer is on the main server of the cluster group and displayed in part of cluster group
            if (viewer.server.equals(displayed.globalPlayerListData.clusterMainServerId)) {
                TAB.getInstance().debug("Viewer " + viewer.getName() + " is on " + viewer.server + " and displayed " + displayed.getName() + " is part of cluster group " + displayed.globalPlayerListData.clusterMainServerId);
                return true;
            }
        }
        // MineVN end
        return viewer.globalPlayerListData.serverGroup == displayed.globalPlayerListData.serverGroup;
    }

    /**
     * Returns server group of specified server. If not part of any group,
     * returns either default or unique name if isolate unlisted servers is enabled.
     *
     * @param   playerServer
     *          Server to get group of
     * @return  Name of server group for this server
     */
    @NotNull
    public synchronized String getServerGroupName(@NotNull String playerServer) {
        return serverToGroupName.computeIfAbsent(playerServer, this::computeServerGroup);
    }

    /**
     * Returns server group of specified server. The returned object identity is equal for
     * all servers in the same group.
     *
     * @param   playerServer
     *          Server to get group of
     * @return  Server group of specified server
     */
    @NotNull
    private synchronized Object getServerGroup(@NotNull String playerServer) {
        return groupNameToGroup.computeIfAbsent(getServerGroupName(playerServer), n -> new Object());
    }

    @NotNull
    private String computeServerGroup(@NotNull String server) {
        // MineVN start
        for (Map.Entry<String, List<String>> group : configuration.sharedServers.entrySet()) {
            if (serverMatchesGroup(server, group.getValue())) {
                return group.getKey();
            }
        }
        // MineVN end
        return configuration.isolateUnlistedServers ? "isolated:" + server : "DEFAULT";
    }

    // MineVN start
    /**
     * Returns the main server of the cluster group of specified server.
     * @param playerServer server id need to check
     * @return server id of the main server of the cluster group
     */
    private String getClusterMainServerId(String playerServer) {
        return serverToClusterMain.computeIfAbsent(playerServer, this::computeClusterServer);
    }

    /**
     * Returns the main server of the cluster group of specified server.
     * @param server server id need to check
     * @return server id of the main server of the cluster group
     */
    private String computeClusterServer(String server) {
        for (Map.Entry<String, List<String>> cluster : configuration.clusterServers.entrySet()) {
            if (cluster.getKey().equals(server) || serverMatchesGroup(server, cluster.getValue())) {
                return cluster.getKey();
            }
        }
        return null;
    }

    /**
     * Returns the main server of the cluster group of specified server.
     * @param server server id need to check
     * @param groupServers list of servers
     * @return true if server is part of the group, false otherwise
     */
    private boolean serverMatchesGroup(@NotNull String server, @NotNull List<String> groupServers) {
        for (String serverDefinition : groupServers) {
            if (serverDefinition.endsWith("*") && server.toLowerCase().startsWith(serverDefinition.substring(0, serverDefinition.length() - 1).toLowerCase())) {
                return true;
            }
            if (serverDefinition.startsWith("*") && server.toLowerCase().endsWith(serverDefinition.substring(1).toLowerCase())) {
                return true;
            }
            if (server.equalsIgnoreCase(serverDefinition)) {
                return true;
            }
        }
        return false;
    }
    // MineVN end

    @Override
    public void unload() {
        for (TabPlayer displayed : onlinePlayers.getPlayers()) {
            for (TabPlayer viewer : onlinePlayers.getPlayers()) {
                if (!displayed.server.equals(viewer.server)) viewer.getTabList().removeEntry(displayed.getTablistId());
            }
        }
    }

    @Override
    public void onJoin(@NotNull TabPlayer connectedPlayer) {
        onlinePlayers.addPlayer(connectedPlayer);
        connectedPlayer.globalPlayerListData.serverGroup = getServerGroup(connectedPlayer.server);
        connectedPlayer.globalPlayerListData.onSpyServer = configuration.spyServers.contains(connectedPlayer.server.toLowerCase());
        // MineVN start
        connectedPlayer.globalPlayerListData.clusterMainServerId = getClusterMainServerId(connectedPlayer.server);
        // MineVN end
        for (TabPlayer all : onlinePlayers.getPlayers()) {
            if (connectedPlayer.server.equals(all.server)) continue;
            if (shouldSee(all, connectedPlayer)) {
                all.getTabList().addEntry(getAddInfoData(connectedPlayer, all));
            }
            if (shouldSee(connectedPlayer, all)) {
                connectedPlayer.getTabList().addEntry(getAddInfoData(all, connectedPlayer));
            }
        }
        if (redis != null) {
            for (RedisPlayer redis : redis.getRedisPlayers().values()) {
                if (!redis.server.equals(connectedPlayer.server) && shouldSee(connectedPlayer, redis)) {
                    connectedPlayer.getTabList().addEntry(getEntry(redis));
                }
            }
        }
    }

    @Override
    public void onQuit(@NotNull TabPlayer disconnectedPlayer) {
        onlinePlayers.removePlayer(disconnectedPlayer);
        for (TabPlayer all : onlinePlayers.getPlayers()) {
            all.getTabList().removeEntry(disconnectedPlayer.getTablistId());
        }
    }

    @Override
    public void onServerChange(@NotNull TabPlayer changed, @NotNull String from, @NotNull String to) {
        changed.globalPlayerListData.serverGroup = getServerGroup(changed.server);
        changed.globalPlayerListData.onSpyServer = configuration.spyServers.contains(changed.server.toLowerCase());
        // MineVN start
        changed.globalPlayerListData.clusterMainServerId = getClusterMainServerId(changed.server);
        // MineVN end
        // TODO fix players potentially not appearing on rapid server switching (if anyone reports it)
        // Player who switched server is removed from tablist of other players in ~70-110ms (depending on online count), re-add with a delay
        customThread.executeLater(new TimedCaughtTask(TAB.getInstance().getCpu(), () -> {
            for (TabPlayer all : onlinePlayers.getPlayers()) {
                // Remove for everyone and add back if visible, easy solution to display-others-as-spectators option
                // Also do not remove/add players from the same server, let backend handle it
                if (!all.server.equals(changed.server)) {
                    all.getTabList().removeEntry(changed.getTablistId());
                    if (shouldSee(all, changed)) {
                        all.getTabList().addEntry(getAddInfoData(changed, all));
                    }
                }
            }
        }, getFeatureName(), TabConstants.CpuUsageCategory.SERVER_SWITCH), 200);
    }

    @Override
    public void onTabListClear(@NotNull TabPlayer player) {
        for (TabPlayer all : onlinePlayers.getPlayers()) {
            // Ignore players on the same server, since the server already sends add packet
            if (!all.server.equals(player.server) && shouldSee(player, all)) {
                player.getTabList().addEntry(getAddInfoData(all, player));
            }
        }
        if (redis != null) {
            for (RedisPlayer redis : redis.getRedisPlayers().values()) {
                if (!redis.server.equals(player.server) && shouldSee(player, redis)) {
                    player.getTabList().addEntry(getEntry(redis));
                }
            }
        }
    }

    /**
     * Creates new entry of given target player for viewer.
     *
     * @param   p
     *          Displayed player
     * @param   viewer
     *          Player viewing the tablist
     * @return  Entry of target for viewer
     */
    @NotNull
    public TabList.Entry getAddInfoData(@NotNull TabPlayer p, @NotNull TabPlayer viewer) {
        TabComponent format = null;
        if (playerlist != null && !p.tablistData.disabled.get()) {
            format = playerlist.getTabFormat(p, viewer);
        }
        int gameMode = (configuration.othersAsSpectators && !p.server.equals(viewer.server)) ||
                (configuration.vanishedAsSpectators && p.isVanished()) ? 3 : p.getGamemode();
        return new TabList.Entry(
                p.getTablistId(),
                p.getNickname(),
                p.getSkin(),
                true,
                configuration.updateLatency ? p.getPing() : 0,
                gameMode,
                viewer.getVersion().getMinorVersion() >= 8 ? format : null,
                0
        );
    }

    @Override
    public void onGameModeChange(@NotNull TabPlayer player) {
        for (TabPlayer viewer : onlinePlayers.getPlayers()) {
            if (!player.server.equals(viewer.server)) {
                viewer.getTabList().updateGameMode(player.getTablistId(), configuration.othersAsSpectators ? 3 : player.getGamemode());
            }
        }
    }

    @Override
    public void onVanishStatusChange(@NotNull TabPlayer p) {
        if (p.isVanished()) {
            for (TabPlayer all : onlinePlayers.getPlayers()) {
                if (all == p) continue;
                if (!shouldSee(all, p)) {
                    all.getTabList().removeEntry(p.getTablistId());
                }
            }
        } else {
            for (TabPlayer viewer : onlinePlayers.getPlayers()) {
                if (viewer == p) continue;
                if (shouldSee(viewer, p)) {
                    viewer.getTabList().addEntry(getAddInfoData(p, viewer));
                }
            }
        }
    }

    @NotNull
    @Override
    public String getRefreshDisplayName() {
        return "Updating latency";
    }

    @Override
    public void refresh(@NotNull TabPlayer refreshed, boolean force) {
        //player ping changed, must manually update latency for players on other servers
        for (TabPlayer viewer : onlinePlayers.getPlayers()) {
            if (viewer.globalPlayerListData.serverGroup == refreshed.globalPlayerListData.serverGroup && !refreshed.server.equals(viewer.server)) {
                viewer.getTabList().updateLatency(refreshed.getTablistId(), refreshed.getPing());
            }
        }
    }

    private boolean shouldSee(@NotNull TabPlayer viewer, @NotNull RedisPlayer target) {
        if (target.isVanished() && !viewer.hasPermission(TabConstants.Permission.SEE_VANISHED)) return false;
        if (viewer.globalPlayerListData.onSpyServer) return true;
        return viewer.globalPlayerListData.serverGroup == target.serverGroup;
    }

    @NotNull
    private TabList.Entry getEntry(@NotNull RedisPlayer player) {
        return new TabList.Entry(player.getUniqueId(), player.getNickname(), player.getSkin(), true, 0, 0, player.getTabFormat(), 0);
    }

    // ------------------
    // RedisBungee
    // ------------------

    @Override
    public void onJoin(@NotNull RedisPlayer player) {
        player.serverGroup = getServerGroup(player.server);
        for (TabPlayer viewer : onlinePlayers.getPlayers()) {
            if (shouldSee(viewer, player) && !viewer.server.equals(player.server)) {
                viewer.getTabList().addEntry(getEntry(player));
            }
        }
    }

    @Override
    public void onServerSwitch(@NotNull RedisPlayer player) {
        player.serverGroup = getServerGroup(player.server);
        for (TabPlayer viewer : onlinePlayers.getPlayers()) {
            if (viewer.server.equals(player.server)) continue;
            if (shouldSee(viewer, player)) {
                viewer.getTabList().addEntry(getEntry(player));
            } else {
                viewer.getTabList().removeEntry(player.getUniqueId());
            }
        }
    }

    @Override
    public void onQuit(@NotNull RedisPlayer player) {
        for (TabPlayer viewer : onlinePlayers.getPlayers()) {
            if (!player.server.equals(viewer.server)) {
                viewer.getTabList().removeEntry(player.getUniqueId());
            }
        }
    }

    @Override
    public void onVanishStatusChange(@NotNull RedisPlayer player) {
        if (player.isVanished()) {
            for (TabPlayer all : onlinePlayers.getPlayers()) {
                if (!shouldSee(all, player)) {
                    all.getTabList().removeEntry(player.getUniqueId());
                }
            }
        } else {
            for (TabPlayer viewer : onlinePlayers.getPlayers()) {
                if (shouldSee(viewer, player)) {
                    viewer.getTabList().addEntry(getEntry(player));
                }
            }
        }
    }

    @NotNull
    @Override
    public String getFeatureName() {
        return "Global PlayerList";
    }

    /**
     * Class holding global playerlist data for players.
     */
    public static class PlayerData {

        /** Server group of server the player is connected to */
        private Object serverGroup;

        /** Flag tracking whether the player is on spy server or not */
        private boolean onSpyServer;

        // MineVN start
        /** Cluster group of server the player is connected to */
        public Object clusterMainServerId;
        // MineVN end
    }
}