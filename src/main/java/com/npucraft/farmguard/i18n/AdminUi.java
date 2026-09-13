package com.npucraft.farmguard.i18n;

import com.npucraft.farmguard.model.AutomationCluster;
import com.npucraft.farmguard.model.ChunkKey;
import com.npucraft.farmguard.model.OperatingMode;
import com.npucraft.farmguard.model.ProtectionLevel;
import com.npucraft.farmguard.model.ProtectionState;
import com.npucraft.farmguard.model.RiskAssessment;
import com.npucraft.farmguard.model.RiskReason;
import com.npucraft.farmguard.model.ServerMetrics;
import com.npucraft.farmguard.util.Numbers;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/**
 * Renders administrator-facing hotspot and inspect views. No scoring lives here.
 */
public final class AdminUi {

    private final LanguageManager language;
    private final MessageService messages;

    public AdminUi(LanguageManager language, MessageService messages) {
        this.language = language;
        this.messages = messages;
    }

    public List<Component> top(
            int limit,
            ServerMetrics metrics,
            List<RiskAssessment> rankedHotspots,
            List<AutomationCluster> clusters,
            List<ProtectionState> protections,
            OperatingMode mode,
            boolean interactive
    ) {
        List<Component> lines = new ArrayList<>();
        Map<String, String> titleValues = Map.of("limit", String.valueOf(limit));
        lines.add(messages.prefixed(messages.deserialize(language.raw("command.top.title", titleValues))));
        lines.add(messages.deserialize(language.raw("command.top.metrics", Map.of(
                "tps", Numbers.oneDecimal(metrics == null ? 20.0 : metrics.tps()),
                "mspt", Numbers.oneDecimal(metrics == null ? 0.0 : metrics.mspt()),
                "pressure", language.pressure(metrics == null ? null : metrics.pressure())
        ))));
        List<TopDisplay.Entry> entries = TopDisplay.of(rankedHotspots, clusters, protections, limit);
        if (entries.isEmpty()) {
            lines.add(messages.deserialize(language.raw("command.top.empty")));
            return lines;
        }
        int attention = TopDisplay.attentionCount(entries);
        if (attention == 0) {
            lines.add(messages.deserialize(language.raw("command.top.no-high-risk")));
        } else {
            lines.add(messages.deserialize(language.raw("command.top.attention-count", Map.of(
                    "count", String.valueOf(attention)
            ))));
        }
        boolean showProtection = mode != null && mode.allowsProtection();
        int rank = 1;
        for (TopDisplay.Entry entry : entries) {
            lines.add(topEntry(rank, entry, showProtection, interactive));
            lines.add(topDetail(entry));
            rank++;
        }
        return lines;
    }

    public List<Component> inspect(
            ChunkKey key,
            RiskAssessment assessment,
            ProtectionState protection,
            AutomationCluster cluster,
            boolean whitelisted,
            int windowSeconds,
            boolean interactive
    ) {
        List<Component> lines = new ArrayList<>();
        lines.add(messages.prefixed(messages.deserialize(language.raw("command.inspect.title"))));
        lines.add(messages.deserialize(language.raw("command.inspect.location", Map.of(
                "world", key.worldName(),
                "x", String.valueOf(key.x()),
                "z", String.valueOf(key.z())
        ))));
        if (assessment == null) {
            lines.add(messages.deserialize(language.raw("command.inspect.missing")));
            return lines;
        }
        ProtectionLevel applied = protection == null ? ProtectionLevel.NORMAL : protection.applied();
        String protectionLabel = applied.restrictsGameplay()
                ? language.protection(applied)
                : language.raw("command.inspect.protection-none");
        lines.add(labeled("command.inspect.risk-level", language.riskLevel(assessment.level()), DisplayStyle.risk(assessment.level())));
        lines.add(labeled("command.inspect.activity-score", Numbers.oneDecimal(assessment.activityScore()), DisplayStyle.activity()));
        lines.add(labeled("command.inspect.risk-score", Numbers.oneDecimal(assessment.riskScore()), DisplayStyle.value()));
        lines.add(labeled(
                "command.inspect.correlation",
                language.correlation(assessment.correlation()),
                DisplayStyle.correlation(assessment.correlation())
        ));
        lines.add(labeled("command.inspect.protection", protectionLabel, DisplayStyle.protection(applied)));
        lines.add(Component.empty());
        lines.add(section("command.inspect.activity-title"));
        for (ActivityHighlights.Line line : ActivityHighlights.inspectLines(assessment.snapshot(), 3)) {
            String count = line.estimated()
                    ? language.raw("format.estimated", Map.of("count", String.valueOf((int) Math.round(line.count()))))
                    : language.raw("format.per-second", Map.of("rate", formatRate(line.count())));
            lines.add(Component.text("  ")
                    .append(Component.text(pad(language.activityKey(line.key()), 16), DisplayStyle.label()))
                    .append(Component.text(count, DisplayStyle.activity())));
        }
        lines.add(Component.empty());
        lines.add(section("command.inspect.reason-title"));
        if (assessment.reasons().isEmpty()) {
            lines.add(Component.text("  ").append(Component.text(language.raw("common.none"), DisplayStyle.reasons())));
        } else {
            for (RiskReason reason : assessment.reasons()) {
                Component bullet = Component.text("  • " + language.reason(reason), DisplayStyle.reasons());
                if (interactive) {
                    bullet = bullet.hoverEvent(HoverEvent.showText(
                            Component.text(language.reason(reason) + "\n" + language.reasonDetail(reason))
                    ));
                }
                lines.add(bullet);
            }
        }
        lines.add(Component.empty());
        lines.add(section("command.inspect.cluster-title"));
        if (cluster == null) {
            lines.add(Component.text("  ").append(Component.text(language.raw("command.inspect.no-cluster"), DisplayStyle.reasons())));
        } else {
            Component typeValue = Component.text(language.clusterType(cluster.type()), DisplayStyle.value());
            if (interactive) {
                typeValue = typeValue.hoverEvent(HoverEvent.showText(
                        Component.text(language.raw("command.inspect.cluster-type-hover"))
                ));
            }
            lines.add(Component.text("  ")
                    .append(Component.text(pad(language.raw("command.inspect.cluster-type"), 18), DisplayStyle.label()))
                    .append(typeValue));
            lines.add(labeledIndented("command.inspect.cluster-id", cluster.id()));
            lines.add(labeledIndented("command.inspect.cluster-chunks", String.valueOf(cluster.chunks().size())));
        }
        lines.add(labeled("command.inspect.whitelist", language.raw(whitelisted ? "common.yes-text" : "common.no-text"), DisplayStyle.value()));
        return lines;
    }

    public void send(CommandSender sender, List<Component> lines) {
        boolean interactive = sender instanceof Player;
        for (Component line : lines) {
            if (!interactive) {
                messages.sendComponent(sender, line);
            } else {
                sender.sendMessage(line);
            }
        }
    }

    public List<String> plain(List<Component> lines) {
        List<String> out = new ArrayList<>();
        for (Component line : lines) {
            out.add(MessageService.plainText(line));
        }
        return out;
    }

    private Component topEntry(int rank, TopDisplay.Entry entry, boolean showProtection, boolean interactive) {
        Component badge = Component.text("[" + language.riskLevel(entry.level()) + "]", DisplayStyle.risk(entry.level()));
        Component line = Component.text("#" + rank + "  ").append(badge).append(Component.text("  "));
        if (entry.clustered()) {
            line = line.append(Component.text(language.clusterType(entry.cluster().type()), NamedTextColor.WHITE))
                    .append(Component.text("  "));
        }
        line = line.append(chunkName(entry.location(), interactive));
        if (showProtection && entry.protection() == ProtectionLevel.THROTTLE) {
            line = line.append(badgeText(language.raw("command.top.throttled"), DisplayStyle.protection(entry.protection())));
        } else if (showProtection && entry.protection() == ProtectionLevel.EMERGENCY) {
            line = line.append(badgeText(language.raw("command.top.emergency"), DisplayStyle.protection(entry.protection())));
        }
        return line;
    }

    private Component topDetail(TopDisplay.Entry entry) {
        String activity = Numbers.oneDecimal(entry.activityScore());
        String reasons = language.truncatedReasons(entry.reasons(), 2);
        if (reasons.isEmpty() && !entry.clustered()) {
            String primaryKey = ActivityHighlights.primaryActivityKey(entry.representative().snapshot());
            reasons = primaryKey == null ? "" : language.activityKey(primaryKey);
        }
        Component line = Component.text("    ");
        if (entry.clustered()) {
            line = line.append(Component.text(language.raw("command.top.chunks-count", Map.of(
                    "count", String.valueOf(entry.memberChunks())
            )), DisplayStyle.label()))
                    .append(Component.text(" | ", NamedTextColor.DARK_GRAY));
        }
        line = line.append(Component.text(language.raw("command.top.activity-label") + " " + activity, DisplayStyle.activity()));
        if (!reasons.isEmpty()) {
            line = line.append(Component.text(" | " + reasons, DisplayStyle.reasons()));
        }
        return line;
    }

    private Component chunkName(ChunkKey key, boolean interactive) {
        Component world = Component.text(key.worldName(), DisplayStyle.world());
        Component coords = Component.text("  (" + key.x() + "," + key.z() + ")", DisplayStyle.coordinates());
        Component combined = world.append(coords);
        if (!interactive) {
            return combined;
        }
        String command = "/fg inspect " + key.worldName() + " " + key.x() + " " + key.z();
        return combined
                .clickEvent(ClickEvent.runCommand(command))
                .hoverEvent(HoverEvent.showText(Component.text(language.raw("command.top.hover-inspect"))))
                .decoration(TextDecoration.UNDERLINED, false);
    }

    private static Component badgeText(String label, net.kyori.adventure.text.format.TextColor color) {
        return Component.text("  [").color(NamedTextColor.GRAY)
                .append(Component.text(label, color))
                .append(Component.text("]", NamedTextColor.GRAY));
    }

    private Component labeled(String labelKey, String value, net.kyori.adventure.text.format.TextColor color) {
        return Component.text(pad(language.raw(labelKey), 18), DisplayStyle.label())
                .append(Component.text(value, color));
    }

    private Component labeledIndented(String labelKey, String value) {
        return Component.text("  ")
                .append(Component.text(pad(language.raw(labelKey), 18), DisplayStyle.label()))
                .append(Component.text(value, DisplayStyle.value()));
    }

    private Component section(String key) {
        return Component.text(language.raw(key), NamedTextColor.GOLD);
    }

    private static String formatRate(double rate) {
        if (rate >= 10.0) {
            return String.valueOf(Math.round(rate));
        }
        return Numbers.oneDecimal(rate);
    }

    private static String pad(String text, int width) {
        String label = text == null ? "" : text;
        int target = Math.max(width, displayWidth(label) + 2);
        StringBuilder builder = new StringBuilder(label);
        while (displayWidth(builder.toString()) < target) {
            builder.append(' ');
        }
        return builder.toString();
    }

    private static int displayWidth(String text) {
        int width = 0;
        for (int i = 0; i < text.length(); i++) {
            width += text.charAt(i) > 127 ? 2 : 1;
        }
        return width;
    }
}
