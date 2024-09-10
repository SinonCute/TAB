package me.neznamy.tab.shared.config.files.config;

import me.neznamy.tab.shared.config.file.ConfigurationFile;
import me.neznamy.tab.shared.config.files.ConfigurationSection;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Arrays;
import java.util.List;

public class TablistFormattingConfiguration extends ConfigurationSection {

    private final String SECTION = "tablist-name-formatting";
    public final boolean antiOverride = getBoolean(SECTION + ".anti-override", true);
    @Nullable public final String disableCondition = getString(SECTION + ".disable-condition", "%world%=disabledworld");
    // MineVN start
    public final List<String> viewerFormatCheck = getStringList(SECTION + ".viewer-format-check", Arrays.asList("server_a", "server_b"));

    public TablistFormattingConfiguration(@NotNull ConfigurationFile config) {
        super(config);
        checkForUnknownKey(SECTION, Arrays.asList("enabled", "anti-override", "disable-condition", "viewer-format-check"));
        printStartupWarns();
    }

    private void printStartupWarns() {
        if (!antiOverride) {
            startupWarn("anti-override for tablist-name-formatting is disabled in config. This is usually a mistake. If you notice the" +
                    " feature randomly breaking, enable it back.");
        }
    }
}
