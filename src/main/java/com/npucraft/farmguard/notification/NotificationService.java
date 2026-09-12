package dev.farmguard.notification;

import dev.farmguard.config.FarmGuardSettings;
import dev.farmguard.config.Messages;
import dev.farmguard.model.ProtectionState;
import dev.farmguard.model.RiskAssessment;
import dev.farmguard.model.RiskLevel;
import dev.farmguard.model.ServerMetrics;
import dev.farmguard.protection.ProtectionManager;
import dev.farmguard.util.Numbers;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

public final class NotificationService {

    private final Logger logger;
    private final Messages messages;
    private final NotificationLimiter limiter = new NotificationLimiter();
    private boolean lagNotified;

    public NotificationService(Logger logger, Messages messages) {
        this.logger = logger;
        this.messages = messages;
    }

    public void onServerMetrics(ServerMetrics metrics, FarmGuardSettings settings, long nowMs) {
        if (metrics.lagIncident()) {
            if (limiter.allow("server-lag", nowMs, settings.notificationCooldownSeconds())) {
                Map<String, String> values = Map.of(
                        "tps", Numbers.oneDecimal(metrics.tps()),
                        "mspt", Numbers.oneDecimal(metrics.mspt()),
                        "pressure", metrics.pressure().name()
                );
                broadcast(settings, messages.component("notify-server-lag", values));
                lagNotified = true;
            }
        } else if (lagNotified && metrics.pressure() == dev.farmguard.model.ServerPressure.NORMAL) {
            if (limiter.allow("server-recover", nowMs, settings.notificationCooldownSeconds())) {
                Map<String, String> values = Map.of(
                        "tps", Numbers.oneDecimal(metrics.tps()),
                        "mspt", Numbers.oneDecimal(metrics.mspt())
                );
                broadcast(settings, messages.component("notify-lag-recovered", values));
                lagNotified = false;
                limiter.clear("server-lag");
            }
        }
        limiter.cleanup(nowMs, 10 * 60 * 1000L);
    }

    public int onRisks(List<RiskAssessment> assessments, FarmGuardSettings settings, long nowMs) {
        RiskNotificationPlanner.Plan plan = RiskNotificationPlanner.plan(
                assessments,
                settings.maxRiskNotificationsPerCycle(),
                settings.notificationCooldownSeconds(),
                limiter,
                nowMs
        );
        for (RiskAssessment assessment : plan.send()) {
            emitRisk(assessment, settings, nowMs);
        }
        if (plan.suppressed() > 0) {
            broadcast(settings, messages.component("notify-risk-suppressed", Map.of(
                    "count", String.valueOf(plan.suppressed())
            )));
        }
        return plan.send().size();
    }

    public void onRisk(RiskAssessment assessment, FarmGuardSettings settings, long nowMs) {
        onRisks(List.of(assessment), settings, nowMs);
    }

    public void clear() {
        limiter.clearAll();
        lagNotified = false;
    }

    private void emitRisk(RiskAssessment assessment, FarmGuardSettings settings, long nowMs) {
        String key = "risk:" + assessment.chunk().compact();
        int seconds = Math.max(1, limiter.secondsSinceFirst(key, nowMs));
        Map<String, String> values = Map.of(
                "world", assessment.chunk().worldName(),
                "x", String.valueOf(assessment.chunk().x()),
                "z", String.valueOf(assessment.chunk().z()),
                "seconds", String.valueOf(seconds),
                "level", assessment.level().name(),
                "reasons", assessment.reasonsSummary(),
                "correlation", assessment.correlation().name()
        );
        String messageKey = assessment.level() == RiskLevel.CRITICAL ? "notify-critical-risk" : "notify-high-risk";
        broadcast(settings, messages.component(messageKey, values));
    }

    public void onProtection(ProtectionManager.Change change, FarmGuardSettings settings) {
        ProtectionState state = change.state();
        Map<String, String> values = Map.of(
                "world", state.chunk().worldName(),
                "x", String.valueOf(state.chunk().x()),
                "z", String.valueOf(state.chunk().z()),
                "level", state.applied().name(),
                "reasons", reasons(state)
        );
        String key = switch (change.type()) {
            case STARTED -> "protection-start";
            case ESCALATED -> "protection-escalated";
            case RECOVERED -> "protection-end";
            case NONE -> null;
        };
        if (key != null) {
            broadcast(settings, messages.component(key, values));
        }
    }

    private void broadcast(FarmGuardSettings settings, Component component) {
        if (settings.notifyConsole()) {
            logger.info(net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(component));
        }
        if (settings.notifyAdmins()) {
            for (Player player : Bukkit.getOnlinePlayers()) {
                if (player.hasPermission(settings.adminPermission()) || player.hasPermission("farmguard.admin")) {
                    player.sendMessage(component);
                }
            }
        }
    }

    private static String reasons(ProtectionState state) {
        if (state.reasons().isEmpty()) {
            return "-";
        }
        StringBuilder builder = new StringBuilder();
        boolean first = true;
        for (dev.farmguard.model.RiskReason reason : state.reasons()) {
            if (!first) {
                builder.append(" / ");
            }
            builder.append(RiskAssessment.formatReason(reason));
            first = false;
        }
        return builder.toString();
    }
}
