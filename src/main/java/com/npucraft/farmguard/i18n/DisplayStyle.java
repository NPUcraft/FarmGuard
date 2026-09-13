package com.npucraft.farmguard.i18n;

import com.npucraft.farmguard.model.LagCorrelation;
import com.npucraft.farmguard.model.ProtectionLevel;
import com.npucraft.farmguard.model.RiskLevel;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;

public final class DisplayStyle {

    private DisplayStyle() {
    }

    public static TextColor risk(RiskLevel level) {
        if (level == null) {
            return NamedTextColor.GRAY;
        }
        return switch (level) {
            case NONE -> NamedTextColor.GRAY;
            case LOW -> NamedTextColor.GREEN;
            case MEDIUM -> NamedTextColor.YELLOW;
            case HIGH -> NamedTextColor.GOLD;
            case CRITICAL -> NamedTextColor.DARK_RED;
        };
    }

    public static TextColor correlation(LagCorrelation value) {
        if (value == LagCorrelation.STRONG) {
            return NamedTextColor.GOLD;
        }
        if (value == LagCorrelation.POSSIBLE) {
            return NamedTextColor.YELLOW;
        }
        return NamedTextColor.GRAY;
    }

    public static TextColor protection(ProtectionLevel level) {
        if (level == null) {
            return NamedTextColor.GRAY;
        }
        return switch (level) {
            case NORMAL -> NamedTextColor.GRAY;
            case WARNING -> NamedTextColor.YELLOW;
            case THROTTLE -> NamedTextColor.GOLD;
            case EMERGENCY -> NamedTextColor.RED;
        };
    }

    public static TextColor world() {
        return NamedTextColor.WHITE;
    }

    public static TextColor coordinates() {
        return NamedTextColor.GRAY;
    }

    public static TextColor activity() {
        return NamedTextColor.AQUA;
    }

    public static TextColor reasons() {
        return NamedTextColor.GRAY;
    }

    public static TextColor label() {
        return NamedTextColor.GRAY;
    }

    public static TextColor value() {
        return NamedTextColor.WHITE;
    }
}
