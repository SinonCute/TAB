package me.neznamy.tab.shared.features.nametags;

import lombok.Getter;
import lombok.NonNull;
import me.neznamy.tab.api.nametag.NameTagManager;
import me.neznamy.tab.shared.Property;
import me.neznamy.tab.shared.TAB;
import me.neznamy.tab.shared.TabConstants;
import me.neznamy.tab.shared.chat.TabComponent;
import me.neznamy.tab.shared.cpu.ThreadExecutor;
import me.neznamy.tab.shared.features.redis.RedisSupport;
import me.neznamy.tab.shared.features.types.*;
import me.neznamy.tab.shared.placeholders.conditions.Condition;
import me.neznamy.tab.shared.platform.Scoreboard;
import me.neznamy.tab.shared.platform.decorators.SafeScoreboard;
import me.neznamy.tab.shared.platform.Scoreboard.CollisionRule;
import me.neznamy.tab.shared.platform.Scoreboard.NameVisibility;
import me.neznamy.tab.shared.platform.TabPlayer;
import me.neznamy.tab.shared.util.OnlinePlayers;
import me.neznamy.tab.shared.util.cache.StringToComponentCache;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

public class NameTag extends RefreshableFeature implements NameTagManager, JoinListener, QuitListener,
        Loadable, UnLoadable, WorldSwitchListener, ServerSwitchListener, VanishListener, CustomThreaded {

    /** Name of the property used in configuration */
    public static final String TAGPREFIX = "tagprefix";

    /** Name of the property used in configuration */
    public static final String TAGSUFFIX = "tagsuffix";

    @Getter private final ThreadExecutor customThread = new ThreadExecutor("TAB NameTag Thread");

    @Getter private OnlinePlayers onlinePlayers;
    
    protected final boolean invisibleNameTags = config().getBoolean("scoreboard-teams.invisible-nametags", false);
    private final boolean canSeeFriendlyInvisibles = config().getBoolean("scoreboard-teams.can-see-friendly-invisibles", false);
    private final boolean antiOverride = config().getBoolean("scoreboard-teams.anti-override", true);

    @Getter private final StringToComponentCache cache = new StringToComponentCache("NameTags", 1000);
    @Getter private final CollisionManager collisionManager = new CollisionManager(this);
    @Getter private final int teamOptions = canSeeFriendlyInvisibles ? 2 : 0;
    @Getter private final DisableChecker disableChecker;
    @Nullable private RedisSupport redis;

    public NameTag() {
        super("NameTags", "Updating prefix/suffix");
        Condition disableCondition = Condition.getCondition(config().getString("scoreboard-teams.disable-condition"));
        disableChecker = new DisableChecker(getFeatureName(), disableCondition, this::onDisableConditionChange, p -> p.teamData.disabled);
        TAB.getInstance().getFeatureManager().registerFeature(TabConstants.Feature.NAME_TAGS + "-Condition", disableChecker);
        if (!antiOverride) TAB.getInstance().getConfigHelper().startup().teamAntiOverrideDisabled();
    }

    @Override
    public void load() {
        onlinePlayers = new OnlinePlayers(TAB.getInstance().getOnlinePlayers());
        TAB.getInstance().getFeatureManager().registerFeature(TabConstants.Feature.NAME_TAGS_COLLISION, collisionManager);
        collisionManager.load();
        // RedisSupport is instantiated after NameTags, so must be loaded after
        redis = TAB.getInstance().getFeatureManager().getFeature(TabConstants.Feature.REDIS_BUNGEE);
        TAB.getInstance().getFeatureManager().registerFeature(TabConstants.Feature.NAME_TAGS_VISIBILITY, new VisibilityRefresher(this));
        for (TabPlayer all : onlinePlayers.getPlayers()) {
            ((SafeScoreboard<?>)all.getScoreboard()).setAntiOverrideTeams(antiOverride);
            loadProperties(all);
            all.teamData.teamName = all.sortingData.shortTeamName; // Sorting is loaded sync before nametags
            if (disableChecker.isDisableConditionMet(all)) {
                all.teamData.disabled.set(true);
                continue;
            }
            TAB.getInstance().getPlaceholderManager().getTabExpansion().setNameTagVisibility(all, true);
        }
        for (TabPlayer viewer : onlinePlayers.getPlayers()) {
            for (TabPlayer target : onlinePlayers.getPlayers()) {
                if (target.isVanished() && !TAB.getInstance().getPlatform().canSee(viewer, target)) {
                    target.teamData.vanishedFor.add(viewer.getUniqueId());
                }
                if (!target.teamData.disabled.get()) registerTeam(target, viewer);
            }
        }
    }

    @Override
    public void unload() {
        for (TabPlayer viewer : onlinePlayers.getPlayers()) {
            for (TabPlayer target : onlinePlayers.getPlayers()) {
                if (hasTeamHandlingPaused(target) || target.teamData.disabled.get()) continue;
                viewer.getScoreboard().unregisterTeam(target.teamData.teamName);
            }
        }
    }

    @Override
    public void refresh(@NotNull TabPlayer refreshed, boolean force) {
        if (refreshed.teamData.disabled.get()) return;
        boolean refresh;
        if (force) {
            updateProperties(refreshed);
            refresh = true;
        } else {
            boolean prefix = refreshed.teamData.prefix.update();
            boolean suffix = refreshed.teamData.suffix.update();
            refresh = prefix || suffix;
        }
        if (refresh) updatePrefixSuffix(refreshed);
    }

    @Override
    public void onJoin(@NotNull TabPlayer connectedPlayer) {
        onlinePlayers.addPlayer(connectedPlayer);
        ((SafeScoreboard<?>)connectedPlayer.getScoreboard()).setAntiOverrideTeams(antiOverride);
        loadProperties(connectedPlayer);
        connectedPlayer.teamData.teamName = connectedPlayer.sortingData.shortTeamName; // Sorting is loaded sync before nametags
        for (TabPlayer all : onlinePlayers.getPlayers()) {
            if (all == connectedPlayer) continue; //avoiding double registration
            if (connectedPlayer.isVanished() && !TAB.getInstance().getPlatform().canSee(all, connectedPlayer)) {
                connectedPlayer.teamData.vanishedFor.add(all.getUniqueId());
            }
            if (all.isVanished() && !TAB.getInstance().getPlatform().canSee(connectedPlayer, all)) {
                all.teamData.vanishedFor.add(connectedPlayer.getUniqueId());
            }
            if (!all.teamData.disabled.get()) {
                registerTeam(all, connectedPlayer);
            }
        }
        TAB.getInstance().getPlaceholderManager().getTabExpansion().setNameTagVisibility(connectedPlayer, true);
        if (disableChecker.isDisableConditionMet(connectedPlayer)) {
            connectedPlayer.teamData.disabled.set(true);
            return;
        }
        registerTeam(connectedPlayer);
    }

    @Override
    public void onQuit(@NotNull TabPlayer disconnectedPlayer) {
        onlinePlayers.removePlayer(disconnectedPlayer);
        if (!disconnectedPlayer.teamData.disabled.get() && !hasTeamHandlingPaused(disconnectedPlayer)) {
            String teamName = disconnectedPlayer.teamData.teamName;
            for (TabPlayer viewer : onlinePlayers.getPlayers()) {
                if (((SafeScoreboard<?>)viewer.getScoreboard()).containsTeam(teamName)) {
                    viewer.getScoreboard().unregisterTeam(teamName);
                }
            }
        }
    }

    @Override
    public void onServerChange(@NonNull TabPlayer p, @NonNull String from, @NonNull String to) {
        if (updateProperties(p) && !p.teamData.disabled.get()) updatePrefixSuffix(p);
    }

    @Override
    public void onWorldChange(@NotNull TabPlayer changed, @NotNull String from, @NotNull String to) {
        if (updateProperties(changed) && !changed.teamData.disabled.get()) updatePrefixSuffix(changed);
    }

    @Override
    public void onVanishStatusChange(@NotNull TabPlayer player) {
        if (player.isVanished()) {
            for (TabPlayer viewer : onlinePlayers.getPlayers()) {
                if (viewer == player) continue;
                if (!TAB.getInstance().getPlatform().canSee(viewer, player)) {
                    player.teamData.vanishedFor.add(viewer.getUniqueId());
                    viewer.getScoreboard().unregisterTeam(player.teamData.teamName);
                }
            }
        } else {
            for (UUID id : player.teamData.vanishedFor) {
                TabPlayer viewer = TAB.getInstance().getPlayer(id);
                if (viewer != null) registerTeam(player, viewer);
            }
            player.teamData.vanishedFor.clear();
        }
    }

    /**
     * Loads properties from config.
     *
     * @param   player
     *          Player to load properties for
     */
    private void loadProperties(@NotNull TabPlayer player) {
        player.teamData.prefix = player.loadPropertyFromConfig(this, TAGPREFIX, "");
        player.teamData.suffix = player.loadPropertyFromConfig(this, TAGSUFFIX, "");
    }

    /**
     * Loads all properties from config and returns {@code true} if at least
     * one of them either wasn't loaded or changed value, {@code false} otherwise.
     *
     * @param   p
     *          Player to update properties of
     * @return  {@code true} if at least one property changed, {@code false} if not
     */
    private boolean updateProperties(@NotNull TabPlayer p) {
        boolean changed = p.updatePropertyFromConfig(p.teamData.prefix, TAGPREFIX, "");
        if (p.updatePropertyFromConfig(p.teamData.suffix, TAGSUFFIX, "")) changed = true;
        return changed;
    }

    public void onDisableConditionChange(TabPlayer p, boolean disabledNow) {
        customThread.execute(() -> {
            if (disabledNow) {
                unregisterTeam(p, p.teamData.teamName);
            } else {
                registerTeam(p);
            }
        }, getFeatureName(), TabConstants.CpuUsageCategory.DISABLE_CONDITION_CHANGE);
    }

    /**
     * Updates team prefix and suffix of given player.
     *
     * @param   player
     *          Player to update prefix/suffix of
     */
    private void updatePrefixSuffix(@NonNull TabPlayer player) {
        for (TabPlayer viewer : onlinePlayers.getPlayers()) {
            if (!((SafeScoreboard<?>)viewer.getScoreboard()).containsTeam(player.teamData.teamName)) continue;
            TabComponent prefix = cache.get(player.teamData.prefix.getFormat(viewer));
            viewer.getScoreboard().updateTeam(
                    player.teamData.teamName,
                    prefix,
                    cache.get(player.teamData.suffix.getFormat(viewer)),
                    prefix.getLastColor().getLegacyColor()
            );
        }
        if (redis != null) redis.updateTeam(player, player.teamData.teamName,
                player.teamData.prefix.get(),
                player.teamData.suffix.get(),
                getTeamVisibility(player, player) ? NameVisibility.ALWAYS : NameVisibility.NEVER);
    }

    /**
     * Updates collision of a player for everyone.
     *
     * @param   player
     *          Player to update collision of
     * @param   moveToThread
     *          Whether task should be moved to feature thread or not, because it already is
     */
    public void updateCollision(@NonNull TabPlayer player, boolean moveToThread) {
        if (moveToThread) {
            customThread.execute(() -> {
                for (TabPlayer viewer : onlinePlayers.getPlayers()) {
                    if (!((SafeScoreboard<?>)viewer.getScoreboard()).containsTeam(player.teamData.teamName)) continue;
                    viewer.getScoreboard().updateTeam(
                            player.teamData.teamName,
                            player.teamData.getCollisionRule() ? CollisionRule.ALWAYS : CollisionRule.NEVER
                    );
                }
            }, getFeatureName(), "Updating collision");
        } else {
            for (TabPlayer viewer : onlinePlayers.getPlayers()) {
                if (!((SafeScoreboard<?>)viewer.getScoreboard()).containsTeam(player.teamData.teamName)) continue;
                viewer.getScoreboard().updateTeam(
                        player.teamData.teamName,
                        player.teamData.getCollisionRule() ? CollisionRule.ALWAYS : CollisionRule.NEVER
                );
            }
        }
    }

    /**
     * Updates visibility of a player for everyone.
     *
     * @param   player
     *          Player to update visibility of
     */
    public void updateVisibility(@NonNull TabPlayer player) {
        customThread.execute(() -> {
            for (TabPlayer viewer : onlinePlayers.getPlayers()) {
                if (!((SafeScoreboard<?>)viewer.getScoreboard()).containsTeam(player.teamData.teamName)) continue;
                viewer.getScoreboard().updateTeam(
                        player.teamData.teamName,
                        getTeamVisibility(player, viewer) ? NameVisibility.ALWAYS : NameVisibility.NEVER
                );
            }
            if (redis != null) redis.updateTeam(player, player.teamData.teamName,
                    player.teamData.prefix.get(),
                    player.teamData.suffix.get(),
                    getTeamVisibility(player, player) ? NameVisibility.ALWAYS : NameVisibility.NEVER);
        }, getFeatureName(), "Updating visibility");
    }

    /**
     * Updates visibility of a player for specified player.
     *
     * @param   player
     *          Player to update visibility of
     * @param   viewer
     *          Viewer to send update to
     */
    public void updateVisibility(@NonNull TabPlayer player, @NonNull TabPlayer viewer) {
        if (!((SafeScoreboard<?>)viewer.getScoreboard()).containsTeam(player.teamData.teamName)) return;
        viewer.getScoreboard().updateTeam(
                player.teamData.teamName,
                getTeamVisibility(player, viewer) ? NameVisibility.ALWAYS : NameVisibility.NEVER
        );
    }

    public void unregisterTeam(@NonNull TabPlayer p, @NonNull String teamName) {
        if (hasTeamHandlingPaused(p)) return;
        for (TabPlayer viewer : onlinePlayers.getPlayers()) {
            if (((SafeScoreboard<?>)viewer.getScoreboard()).containsTeam(teamName)) {
                viewer.getScoreboard().unregisterTeam(teamName);
            }
        }
    }

    public void registerTeam(@NonNull TabPlayer p) {
        for (TabPlayer viewer : onlinePlayers.getPlayers()) {
            registerTeam(p, viewer);
        }
    }

    private void registerTeam(@NonNull TabPlayer p, @NonNull TabPlayer viewer) {
        if (hasTeamHandlingPaused(p)) return;
        if (!TAB.getInstance().getPlatform().canSee(viewer, p) && p != viewer) return;
        TabComponent prefix = cache.get(p.teamData.prefix.getFormat(viewer));
        viewer.getScoreboard().registerTeam(
                p.teamData.teamName,
                prefix,
                cache.get(p.teamData.suffix.getFormat(viewer)),
                getTeamVisibility(p, viewer) ? NameVisibility.ALWAYS : NameVisibility.NEVER,
                p.teamData.getCollisionRule() ? CollisionRule.ALWAYS : CollisionRule.NEVER,
                Collections.singletonList(p.getNickname()),
                teamOptions,
                prefix.getLastColor().getLegacyColor()
        );
    }

    public boolean getTeamVisibility(@NonNull TabPlayer p, @NonNull TabPlayer viewer) {
        if (viewer.getVersion().getMinorVersion() == 8 && p.hasInvisibilityPotion()) return false;
        return !hasHiddenNameTag(p) && !hasHiddenNameTag(p, viewer) && !invisibleNameTags && !viewer.teamData.invisibleNameTagView;
    }

    /**
     * Updates team name for a specified player to everyone.
     *
     * @param   player
     *          Player to change team name of
     * @param   newTeamName
     *          New team name to use
     */
    public void updateTeamName(@NonNull TabPlayer player, @NonNull String newTeamName) {
        customThread.execute(() -> {
            if (hasTeamHandlingPaused(player) || player.teamData.disabled.get()) {
                player.teamData.teamName = newTeamName;
                return;
            }
            unregisterTeam(player, player.teamData.teamName);
            player.teamData.teamName = newTeamName;
            registerTeam(player);
            if (redis != null) redis.updateTeam(player, player.teamData.teamName, player.teamData.prefix.get(), player.teamData.suffix.get(),
                    getTeamVisibility(player, player) ? Scoreboard.NameVisibility.ALWAYS : Scoreboard.NameVisibility.NEVER);
        }, getFeatureName(), "Updating team name");
    }

    // ------------------
    // API Implementation
    // ------------------

    @Override
    public void hideNameTag(@NonNull me.neznamy.tab.api.TabPlayer player) {
        ensureActive();
        TabPlayer p = (TabPlayer) player;
        p.ensureLoaded();
        if (!p.teamData.hiddenNameTag) {
            p.teamData.hiddenNameTag = true;
            updateVisibility(p);
        }
    }

    @Override
    public void hideNameTag(@NonNull me.neznamy.tab.api.TabPlayer player, @NonNull me.neznamy.tab.api.TabPlayer viewer) {
        ensureActive();
        TabPlayer p = (TabPlayer) player;
        p.ensureLoaded();
        if (!p.teamData.hiddenNameTagFor.add((TabPlayer) viewer)) return;
        updateVisibility(p, (TabPlayer) viewer);
    }

    @Override
    public void showNameTag(@NonNull me.neznamy.tab.api.TabPlayer player) {
        ensureActive();
        TabPlayer p = (TabPlayer) player;
        p.ensureLoaded();
        if (p.teamData.hiddenNameTag) {
            p.teamData.hiddenNameTag = false;
            updateVisibility(p);
        }
    }

    @Override
    public void showNameTag(@NonNull me.neznamy.tab.api.TabPlayer player, @NonNull me.neznamy.tab.api.TabPlayer viewer) {
        ensureActive();
        TabPlayer p = (TabPlayer) player;
        p.ensureLoaded();
        if (!p.teamData.hiddenNameTagFor.remove((TabPlayer) viewer)) return;
        updateVisibility(p, (TabPlayer) viewer);
    }

    @Override
    public boolean hasHiddenNameTag(@NonNull me.neznamy.tab.api.TabPlayer player) {
        ensureActive();
        return ((TabPlayer)player).teamData.hiddenNameTag;
    }

    @Override
    public boolean hasHiddenNameTag(@NonNull me.neznamy.tab.api.TabPlayer player, @NonNull me.neznamy.tab.api.TabPlayer viewer) {
        ensureActive();
        return ((TabPlayer)player).teamData.hiddenNameTagFor.contains((TabPlayer) viewer);
    }

    @Override
    public void pauseTeamHandling(@NonNull me.neznamy.tab.api.TabPlayer player) {
        ensureActive();
        TabPlayer p = (TabPlayer) player;
        p.ensureLoaded();
        if (p.teamData.teamHandlingPaused) return;
        if (!p.teamData.disabled.get()) unregisterTeam(p, p.teamData.teamName);
        p.teamData.teamHandlingPaused = true; //setting after, so unregisterTeam method runs
    }

    @Override
    public void resumeTeamHandling(@NonNull me.neznamy.tab.api.TabPlayer player) {
        ensureActive();
        TabPlayer p = (TabPlayer) player;
        p.ensureLoaded();
        if (!p.teamData.teamHandlingPaused) return;
        p.teamData.teamHandlingPaused = false; //setting before, so registerTeam method runs
        if (!p.teamData.disabled.get()) registerTeam(p);
    }

    @Override
    public boolean hasTeamHandlingPaused(@NonNull me.neznamy.tab.api.TabPlayer player) {
        return ((TabPlayer)player).teamData.teamHandlingPaused;
    }

    @Override
    public void setCollisionRule(@NonNull me.neznamy.tab.api.TabPlayer player, Boolean collision) {
        ensureActive();
        TabPlayer p = (TabPlayer) player;
        p.ensureLoaded();
        if (Objects.equals(p.teamData.forcedCollision, collision)) return;
        p.teamData.forcedCollision = collision;
        updateCollision(p, true);
    }

    @Override
    public Boolean getCollisionRule(@NonNull me.neznamy.tab.api.TabPlayer player) {
        ensureActive();
        TabPlayer p = (TabPlayer) player;
        p.ensureLoaded();
        return p.teamData.forcedCollision;
    }

    @Override
    public void setPrefix(@NonNull me.neznamy.tab.api.TabPlayer player, @Nullable String prefix) {
        ensureActive();
        TabPlayer p = (TabPlayer) player;
        p.ensureLoaded();
        customThread.execute(() -> {
            p.teamData.prefix.setTemporaryValue(prefix);
            updatePrefixSuffix(p);
        }, getFeatureName(), "Updating prefix");
    }

    @Override
    public void setSuffix(@NonNull me.neznamy.tab.api.TabPlayer player, @Nullable String suffix) {
        ensureActive();
        TabPlayer p = (TabPlayer) player;
        p.ensureLoaded();
        customThread.execute(() -> {
            p.teamData.suffix.setTemporaryValue(suffix);
            updatePrefixSuffix(p);
        }, getFeatureName(), "Updating suffix");
    }

    @Override
    public String getCustomPrefix(@NonNull me.neznamy.tab.api.TabPlayer player) {
        ensureActive();
        TabPlayer p = (TabPlayer) player;
        p.ensureLoaded();
        return p.teamData.prefix.getTemporaryValue();
    }

    @Override
    public String getCustomSuffix(@NonNull me.neznamy.tab.api.TabPlayer player) {
        ensureActive();
        TabPlayer p = (TabPlayer) player;
        p.ensureLoaded();
        return p.teamData.suffix.getTemporaryValue();
    }

    @Override
    @NonNull
    public String getOriginalPrefix(@NonNull me.neznamy.tab.api.TabPlayer player) {
        ensureActive();
        TabPlayer p = (TabPlayer) player;
        p.ensureLoaded();
        return p.teamData.prefix.getOriginalRawValue();
    }

    @Override
    @NonNull
    public String getOriginalSuffix(@NonNull me.neznamy.tab.api.TabPlayer player) {
        ensureActive();
        TabPlayer p = (TabPlayer) player;
        p.ensureLoaded();
        return p.teamData.suffix.getOriginalRawValue();
    }

    @Override
    public void toggleNameTagVisibilityView(@NonNull me.neznamy.tab.api.TabPlayer p, boolean sendToggleMessage) {
        ensureActive();
        TabPlayer player = (TabPlayer) p;
        if (player.teamData.invisibleNameTagView) {
            player.teamData.invisibleNameTagView = false;
            if (sendToggleMessage) player.sendMessage(TAB.getInstance().getConfiguration().getMessages().getNameTagsShown(), true);
        } else {
            player.teamData.invisibleNameTagView = true;
            if (sendToggleMessage) player.sendMessage(TAB.getInstance().getConfiguration().getMessages().getNameTagsHidden(), true);
        }
        TAB.getInstance().getPlaceholderManager().getTabExpansion().setNameTagVisibility(player, !player.teamData.invisibleNameTagView);
        for (TabPlayer all : onlinePlayers.getPlayers()) {
            updateVisibility(all, player);
        }
    }

    @Override
    public boolean hasHiddenNameTagVisibilityView(@NonNull me.neznamy.tab.api.TabPlayer player) {
        ensureActive();
        return ((TabPlayer)player).teamData.invisibleNameTagView;
    }

    /**
     * Class holding team data for players.
     */
    public static class PlayerData {

        /** Team name used for sorting */
        public String teamName;

        /** Player's tagprefix */
        public Property prefix;

        /** Player's tagsuffix */
        public Property suffix;

        /** Flag tracking whether this feature is disabled for the player with condition or not */
        public final AtomicBoolean disabled = new AtomicBoolean();
        
        /** Flag tracking whether this player has name tag hidden globally using API or not */
        public boolean hiddenNameTag;

        /** Players who should not see this player's name tag */
        public final Set<TabPlayer> hiddenNameTagFor = Collections.newSetFromMap(new WeakHashMap<>());

        /** Flag tracking whether team handling is paused or not */
        public boolean teamHandlingPaused;

        /** Flag tracking whether this player disabled nametags on all players or not */
        public boolean invisibleNameTagView;

        /** Players who this player is vanished for */
        public final Set<UUID> vanishedFor = new HashSet<>();
        
        /** Currently used collision rule */
        public boolean collisionRule;

        /** Forced collision rule using API */
        @Nullable
        public Boolean forcedCollision;

        /**
         * Returns current collision rule. If forced using API, the forced value is returned.
         * Otherwise, value assigned internally based on configuration is returned.
         *
         * @return  Current collision rule to use
         */
        public boolean getCollisionRule() {
            return forcedCollision != null ? forcedCollision : collisionRule;
        }
    }
}