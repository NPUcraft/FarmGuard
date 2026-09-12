package com.npucraft.farmguard.command;

import com.npucraft.farmguard.FarmGuardRuntime;
import com.npucraft.farmguard.config.Whitelist;
import com.npucraft.farmguard.model.AutomationCluster;
import com.npucraft.farmguard.model.ChunkKey;
import com.npucraft.farmguard.model.MetricType;
import com.npucraft.farmguard.model.OperatingMode;
import com.npucraft.farmguard.model.ProtectionState;
import com.npucraft.farmguard.model.RiskAssessment;
import com.npucraft.farmguard.model.ScoreContribution;
import com.npucraft.farmguard.model.ServerMetrics;
import com.npucraft.farmguard.util.ChunkKeys;
import com.npucraft.farmguard.util.Numbers;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;

public final class FarmGuardCommand implements TabExecutor {

    private final FarmGuardRuntime runtime;

    public FarmGuardCommand(FarmGuardRuntime runtime) {
        this.runtime = runtime;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0 || args[0].equalsIgnoreCase("help")) {
            if (!hasAny(sender)) {
                runtime.messages().send(sender, "no-permission");
                return true;
            }
            sendHelp(sender, label);
            return true;
        }
        String sub = args[0].toLowerCase(Locale.ROOT);
        try {
            return switch (sub) {
                case "status" -> status(sender);
                case "top" -> top(sender, args);
                case "inspect" -> inspect(sender, args);
                case "limits" -> limits(sender);
                case "mode" -> mode(sender, args);
                case "whitelist" -> whitelist(sender, args);
                case "reload" -> reload(sender);
                default -> {
                    runtime.messages().send(sender, "invalid-command");
                    yield true;
                }
            };
        } catch (RuntimeException exception) {
            sender.sendMessage(runtime.messages().plainPrefixed("<red>命令执行失败，请查看控制台。</red>"));
            exception.printStackTrace();
            return true;
        }
    }

    private boolean status(CommandSender sender) {
        if (!sender.hasPermission("farmguard.status") && !sender.hasPermission("farmguard.admin")) {
            runtime.messages().send(sender, "no-permission");
            return true;
        }
        ServerMetrics metrics = runtime.performance().latest();
        runtime.messages().send(sender, "status-header");
        send(sender, "模式: <white>" + runtime.mode().name() + "</white>");
        send(sender, "TPS: <white>" + Numbers.oneDecimal(metrics.tps()) + "</white>  MSPT: <white>"
                + Numbers.oneDecimal(metrics.mspt()) + "</white>  平均MSPT: <white>"
                + Numbers.oneDecimal(metrics.averageMspt()) + "</white>");
        send(sender, "服务器压力: <white>" + metrics.pressure().name() + "</white>  卡顿事件: <white>"
                + (metrics.lagIncident() ? "是" : "否") + "</white>");
        send(sender, "活跃区块: <white>" + runtime.hotspots().activeChunks() + "</white>  高风险区块: <white>"
                + runtime.hotspots().highRiskChunks() + "</white>");
        send(sender, "正在限制: <white>" + runtime.protection().restrictingCount() + "</white>  跟踪区块: <white>"
                + runtime.metrics().size() + "</white>");
        if (runtime.debugLog().isActive()) {
            send(sender, "Debug log: <white>ON</white>");
            send(sender, runtime.debugLog().relativePath());
        } else {
            send(sender, "Debug log: <white>OFF</white>");
        }
        return true;
    }

    private boolean top(CommandSender sender, String[] args) {
        if (!sender.hasPermission("farmguard.top") && !sender.hasPermission("farmguard.admin")) {
            runtime.messages().send(sender, "no-permission");
            return true;
        }
        int limit = runtime.settings().topLimit();
        if (args.length >= 2) {
            try {
                limit = Math.max(1, Math.min(50, Integer.parseInt(args[1])));
            } catch (NumberFormatException exception) {
                runtime.messages().send(sender, "invalid-number");
                return true;
            }
        }
        runtime.messages().send(sender, "top-header", Map.of("limit", String.valueOf(limit)));
        List<RiskAssessment> top = runtime.hotspots().top(limit);
        if (top.isEmpty()) {
            runtime.messages().send(sender, "top-empty");
            return true;
        }
        int rank = 1;
        for (RiskAssessment assessment : top) {
            AutomationCluster cluster = runtime.clusters().find(assessment.chunk());
            String extra = cluster == null ? "" : "  Cluster " + cluster.type().name();
            send(sender, "#" + rank + " <white>" + assessment.chunk().display() + "</white>  "
                    + assessment.level().name() + "  Score <white>" + Numbers.oneDecimal(assessment.riskScore())
                    + "</white>  " + String.join(" / ", assessment.primaryActivityLabels(3)) + extra);
            rank++;
        }
        return true;
    }

    private boolean inspect(CommandSender sender, String[] args) {
        if (!sender.hasPermission("farmguard.inspect") && !sender.hasPermission("farmguard.admin")) {
            runtime.messages().send(sender, "no-permission");
            return true;
        }
        ChunkKey key = resolveInspect(sender, args);
        if (key == null) {
            return true;
        }
        runtime.messages().send(sender, "inspect-header", Map.of(
                "world", key.worldName(),
                "x", String.valueOf(key.x()),
                "z", String.valueOf(key.z())
        ));
        RiskAssessment assessment = runtime.inspect(key);
        if (assessment == null) {
            runtime.messages().send(sender, "inspect-missing");
            return true;
        }
        ProtectionState protection = runtime.protection().stateOf(key);
        AutomationCluster cluster = runtime.clusters().find(key);
        send(sender, "Activity: <white>" + Numbers.oneDecimal(assessment.activityScore())
                + "</white>  Risk: <white>" + Numbers.oneDecimal(assessment.riskScore())
                + "</white> / " + assessment.level().name());
        send(sender, "Lag correlation: <white>" + assessment.correlation().name() + "</white>  (疑似同步，不是绝对因果)");
        send(sender, "Protection: <white>"
                + (protection == null ? "NORMAL" : protection.applied().name())
                + "</white>  recommended: <white>"
                + (protection == null ? "NORMAL" : protection.recommended().name())
                + "</white>");
        send(sender, "Reasons: <white>" + assessment.reasonsSummary() + "</white>");
        if (cluster != null) {
            send(sender, "Cluster: <white>" + cluster.id() + "</white>  type: <white>" + cluster.type().name()
                    + "</white>  chunks: <white>" + cluster.chunks().size() + "</white>");
        }
        send(sender, "Activity rates /s (short):");
        for (MetricType type : MetricType.values()) {
            double rate = assessment.snapshot().shortPerSecond(type);
            if (rate >= 0.05) {
                send(sender, "  " + type.name() + ": <white>" + Numbers.oneDecimal(rate) + "</white>");
            }
        }
        send(sender, "估算实体密度（会因传送/卸载/转化产生漂移，不是精确计数）: 村民 <white>"
                + assessment.snapshot().villagers()
                + "</white>  掉落物 <white>" + assessment.snapshot().items()
                + "</white>  矿车 <white>" + assessment.snapshot().minecarts()
                + "</white>  生物 <white>" + assessment.snapshot().livingEntities() + "</white>");
        send(sender, "Score breakdown:");
        for (ScoreContribution contribution : assessment.contributions()) {
            if (contribution.points() == 0.0 && contribution.label().contains("multiplier")) {
                send(sender, "  " + contribution.label());
            } else if (contribution.points() >= 0.5) {
                send(sender, "  " + contribution);
            }
        }
        send(sender, "Whitelist: <white>" + (runtime.whitelist().contains(key) ? "是" : "否") + "</white>");
        return true;
    }

    private ChunkKey resolveInspect(CommandSender sender, String[] args) {
        if (args.length == 1) {
            if (!(sender instanceof Player player)) {
                runtime.messages().send(sender, "player-only");
                return null;
            }
            return ChunkKeys.of(player.getLocation());
        }
        if (args.length == 3) {
            if (!(sender instanceof Player player)) {
                runtime.messages().send(sender, "inspect-console-usage");
                return null;
            }
            Integer x = parseInt(sender, args[1]);
            Integer z = parseInt(sender, args[2]);
            if (x == null || z == null) {
                return null;
            }
            return ChunkKeys.of(player.getWorld(), x, z);
        }
        if (args.length >= 4) {
            World world = Bukkit.getWorld(args[1]);
            if (world == null) {
                runtime.messages().send(sender, "world-not-found", Map.of("world", args[1]));
                return null;
            }
            Integer x = parseInt(sender, args[2]);
            Integer z = parseInt(sender, args[3]);
            if (x == null || z == null) {
                return null;
            }
            return ChunkKeys.of(world, x, z);
        }
        runtime.messages().send(sender, "invalid-command");
        return null;
    }

    private boolean limits(CommandSender sender) {
        if (!sender.hasPermission("farmguard.status") && !sender.hasPermission("farmguard.admin")) {
            runtime.messages().send(sender, "no-permission");
            return true;
        }
        runtime.messages().send(sender, "limits-header");
        List<ProtectionState> limits = runtime.protection().activeLimits();
        if (limits.isEmpty()) {
            runtime.messages().send(sender, "limits-empty");
            return true;
        }
        for (ProtectionState state : limits) {
            send(sender, state.chunk().display() + "  <white>" + state.applied().name() + "</white>");
        }
        return true;
    }

    private boolean mode(CommandSender sender, String[] args) {
        if (args.length == 1) {
            if (!sender.hasPermission("farmguard.status") && !sender.hasPermission("farmguard.admin") && !sender.hasPermission("farmguard.manage")) {
                runtime.messages().send(sender, "no-permission");
                return true;
            }
            runtime.messages().send(sender, "mode-current", Map.of("mode", runtime.mode().name()));
            return true;
        }
        if (!sender.hasPermission("farmguard.manage") && !sender.hasPermission("farmguard.admin")) {
            runtime.messages().send(sender, "no-permission");
            return true;
        }
        OperatingMode parsed = OperatingMode.parse(args[1], null);
        if (parsed == null) {
            runtime.messages().send(sender, "mode-invalid");
            return true;
        }
        runtime.setMode(parsed);
        runtime.messages().send(sender, "mode-changed", Map.of("mode", parsed.name()));
        if (parsed == OperatingMode.MONITOR) {
            send(sender, "已关闭自动限制。监控与排行仍然工作。");
        } else {
            send(sender, "已允许在服务器压力足够时对高风险区域做临时限流。");
        }
        return true;
    }

    private boolean whitelist(CommandSender sender, String[] args) {
        if (!sender.hasPermission("farmguard.manage") && !sender.hasPermission("farmguard.admin")) {
            runtime.messages().send(sender, "no-permission");
            return true;
        }
        if (args.length < 2) {
            runtime.messages().send(sender, "invalid-command");
            return true;
        }
        String action = args[1].toLowerCase(Locale.ROOT);
        if (action.equals("list")) {
            List<ChunkKey> chunks = runtime.whitelist().chunks();
            List<String> clusters = List.copyOf(runtime.whitelist().clusterIds());
            if (chunks.isEmpty() && clusters.isEmpty()) {
                runtime.messages().send(sender, "whitelist-empty");
                return true;
            }
            runtime.messages().send(sender, "whitelist-header");
            for (ChunkKey key : chunks) {
                send(sender, "Chunk " + key.display());
            }
            for (String id : clusters) {
                send(sender, "Cluster " + id);
            }
            return true;
        }
        if (action.equals("add") || action.equals("remove")) {
            if (!(sender instanceof Player player)) {
                runtime.messages().send(sender, "player-only");
                return true;
            }
            ChunkKey key = ChunkKeys.of(player.getLocation());
            if (action.equals("add")) {
                if (runtime.whitelist().add(key)) {
                    runtime.persistState();
                    runtime.recordWhitelist(true, key);
                    runtime.messages().send(sender, "whitelist-added", Map.of(
                            "world", key.worldName(),
                            "x", String.valueOf(key.x()),
                            "z", String.valueOf(key.z())
                    ));
                } else {
                    runtime.messages().send(sender, "whitelist-exists");
                }
                return true;
            }
            if (runtime.whitelist().remove(key)) {
                runtime.persistState();
                runtime.recordWhitelist(false, key);
                runtime.messages().send(sender, "whitelist-removed", Map.of(
                        "world", key.worldName(),
                        "x", String.valueOf(key.x()),
                        "z", String.valueOf(key.z())
                ));
            } else {
                runtime.messages().send(sender, "whitelist-missing");
            }
            return true;
        }
        runtime.messages().send(sender, "invalid-command");
        return true;
    }

    private boolean reload(CommandSender sender) {
        if (!sender.hasPermission("farmguard.reload") && !sender.hasPermission("farmguard.admin")) {
            runtime.messages().send(sender, "no-permission");
            return true;
        }
        int errors = runtime.reload();
        if (errors > 0) {
            runtime.messages().send(sender, "reload-partial", Map.of("count", String.valueOf(errors)));
        } else {
            runtime.messages().send(sender, "reload-success");
        }
        return true;
    }

    private void sendHelp(CommandSender sender, String label) {
        runtime.messages().send(sender, "help-header");
        send(sender, "/" + label + " status");
        send(sender, "/" + label + " top [n]");
        send(sender, "/" + label + " inspect [x z | world x z]");
        send(sender, "/" + label + " limits");
        send(sender, "/" + label + " mode [monitor|protect]");
        send(sender, "/" + label + " whitelist <add|remove|list>");
        send(sender, "/" + label + " reload");
    }

    private Integer parseInt(CommandSender sender, String raw) {
        try {
            return Integer.parseInt(raw);
        } catch (NumberFormatException exception) {
            runtime.messages().send(sender, "invalid-number");
            return null;
        }
    }

    private void send(CommandSender sender, String mini) {
        sender.sendMessage(runtime.messages().plainPrefixed(mini));
    }

    private boolean hasAny(CommandSender sender) {
        return sender.hasPermission("farmguard.admin")
                || sender.hasPermission("farmguard.status")
                || sender.hasPermission("farmguard.top")
                || sender.hasPermission("farmguard.inspect")
                || sender.hasPermission("farmguard.manage")
                || sender.hasPermission("farmguard.reload");
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> options = new ArrayList<>();
        if (args.length == 1) {
            options.addAll(List.of("status", "top", "inspect", "limits", "mode", "whitelist", "reload", "help"));
        } else if (args.length == 2 && args[0].equalsIgnoreCase("mode")) {
            options.addAll(List.of("monitor", "protect"));
        } else if (args.length == 2 && args[0].equalsIgnoreCase("whitelist")) {
            options.addAll(List.of("add", "remove", "list"));
        } else if (args.length == 2 && args[0].equalsIgnoreCase("top")) {
            options.addAll(List.of("5", "10", "15"));
        } else if (args.length == 2 && args[0].equalsIgnoreCase("inspect")) {
            for (World world : Bukkit.getWorlds()) {
                options.add(world.getName());
            }
        }
        String prefix = args[args.length - 1].toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<>();
        for (String option : options) {
            if (option.toLowerCase(Locale.ROOT).startsWith(prefix)) {
                out.add(option);
            }
        }
        return out;
    }
}
