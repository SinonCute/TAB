package me.neznamy.tab.shared.placeholders;

import java.util.ArrayList;
import java.util.List;

import lombok.Getter;
import lombok.NonNull;
import me.neznamy.tab.shared.chat.EnumChatFormat;
import me.neznamy.tab.shared.chat.rgb.RGBUtils;
import me.neznamy.tab.shared.TAB;
import me.neznamy.tab.shared.TabConstants;
import me.neznamy.tab.shared.config.files.animations.AnimationConfiguration;
import me.neznamy.tab.shared.config.files.animations.AnimationConfiguration.AnimationDefinition;
import me.neznamy.tab.shared.features.PlaceholderManagerImpl;
import org.jetbrains.annotations.NotNull;

/**
 * A class representing an animation from animations.yml
 */
public class Animation {

    /** Placeholder manager for fast access */
    private final PlaceholderManagerImpl placeholderManager;

    /** Animation's name defined in configuration */
    @Getter private final String name;
    
    /** All defined frames of the animation */
    private final String[] messages;
    
    /** Configured change interval for animation to jump to the next frame */
    private final int interval;

    /**
     * Refresh interval of placeholder created from this animation.
     * It may be lower than change interval due to nested placeholders,
     * which may need to refresh faster for any reason,
     * such as this being a slow animation with a fast nested animation.
     */
    @Getter private final int refresh;
    
    /**
     * Constructs new instance with given arguments which are fixed if necessary, such as when
     * refresh is not divisible by {@link TabConstants.Placeholder#MINIMUM_REFRESH_INTERVAL}
     *
     * @param   placeholderManager
     *          Placeholder manager
     * @param   name
     *          animation's name
     * @param   configuration
     *          Animation configuration
     */
    public Animation(@NotNull PlaceholderManagerImpl placeholderManager, @NonNull String name, @NotNull AnimationDefinition configuration) {
        this.placeholderManager = placeholderManager;
        this.name = name;
        messages = configuration.texts.toArray(new String[0]);
        interval = configuration.changeInterval;
        int refresh = interval;
        List<String> nestedPlaceholders = new ArrayList<>();
        for (int i=0; i<messages.length; i++) {
            messages[i] = RGBUtils.getInstance().applyCleanGradients(messages[i]);
            messages[i] = EnumChatFormat.color(messages[i]);
            nestedPlaceholders.addAll(PlaceholderManagerImpl.detectPlaceholders(messages[i]));
        }
        for (String placeholder : nestedPlaceholders) {
            int localRefresh;
            if (placeholder.startsWith("%animation:")) {
                AnimationConfiguration cfg = TAB.getInstance().getConfiguration().getAnimations().getAnimations();
                localRefresh = cfg.animations.containsKey(placeholder) ? cfg.animations.get(placeholder).changeInterval : interval;
            } else {
                localRefresh = placeholderManager.getPlaceholder(placeholder).getRefresh();
            }
            if (localRefresh != -1 && localRefresh < refresh) {
                refresh = localRefresh;
            }
        }
        this.refresh = refresh;
    }

    /**
     * Returns current up-to-date message depending on current system time
     *
     * @return  current message
     */
    public @NotNull String getMessage() {
        return messages[(((placeholderManager.getLoopTime())%(messages.length*interval))/interval)];
    }
}