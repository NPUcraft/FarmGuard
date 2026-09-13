package com.npucraft.farmguard.command;

import com.npucraft.farmguard.FarmGuardRuntime;
import com.npucraft.farmguard.i18n.AdminUi;
import com.npucraft.farmguard.i18n.LanguageManager;
import com.npucraft.farmguard.i18n.LocaleIds;
import com.npucraft.farmguard.i18n.MessageService;
import com.npucraft.farmguard.model.AutomationCluster;
import com.npucraft.farmguard.model.ChunkKey;
import com.npucraft.farmguard.model.OperatingMode;
import com.npucraft.farmguard.model.ProtectionState;
import com.npucraft.farmguard.model.RiskAssessment;
import com.npucraft.farmguard.model.ServerMetrics;
import com.npucraft.farmguard.util.ChunkKeys;
import com.npucraft.farmguard.util.Numbers;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import net.kyori.adventure.text.Component;
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
                msg().send(sender, "command.no-permission");
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
                case "inspect" -> inspect(sender, args, label);
                case "limits" -> limits(sender);
                case "mode" -> mode(sender, args);
                case "whitelist" -> whitelist(sender, args);
                case "reload" -> reload(sender);
                case "language", "lang" -> language(sender, args);
                default -> {
                    msg().send(sender, "command.unknown", Map.of("label", label));
                    yield true;
                }
            };
        } catch (RuntimeException exception) {
            msg().send(sender, "command.failed");
            exception.printStackTrace();
            return true;
        }
    }

    private boolean status(CommandSender sender) {
        if (!sender.hasPermission("farmguard.status") && !sender.hasPermission("farmguard.admin")) {
            msg().send(sender, "command.no-permission");
            return true;
        }
        ServerMetrics metrics = runtime.performance().latest();
        LanguageManager lang = runtime.language();
        msg().send(sender, "command.status.header");
        msg().send(sender, "command.status.mode", Map.of("mode", lang.mode(runtime.mode())));
        msg().send(sender, "command.status.tps", Map.of(
                "tps", Numbers.oneDecimal(metrics.tps()),
                "mspt", Numbers.oneDecimal(metrics.mspt()),
                "avg-mspt", Numbers.oneDecimal(metrics.averageMspt())
        ));
        msg().send(sender, "command.status.pressure", Map.of(
                "pressure", lang.pressure(metrics.pressure()),
                "lag", lang.raw(metrics.lagIncident() ? "common.yes-text" : "common.no-text")
        ));
        msg().send(sender, "command.status.chunks", Map.of(
                "active", String.valueOf(runtime.hotspots().activeChunks()),
                "high-risk", String.valueOf(runtime.hotspots().highRiskChunks())
        ));
        msg().send(sender, "command.status.limits", Map.of(
                "limits", String.valueOf(runtime.protection().restrictingCount()),
                "tracked", String.valueOf(runtime.metrics().size())
        ));
        msg().send(sender, runtime.debugLog().isActive() ? "command.status.debug-on" : "command.status.debug-off");
        if (runtime.debugLog().isActive()) {
            msg().sendComponent(sender, msg().prefixed(Component.text(runtime.debugLog().relativePath())));
        }
        return true;
    }

    private boolean top(CommandSender sender, String[] args) {
        if (!sender.hasPermission("farmguard.top") && !sender.hasPermission("farmguard.admin")) {
            msg().send(sender, "command.no-permission");
            return true;
        }
        int limit = runtime.settings().topLimit();
        if (args.length >= 2) {
            Integer parsed = parseInt(sender, args[1]);
            if (parsed == null) {
                return true;
            }
            limit = Math.max(1, Math.min(50, parsed));
        }
        ui().send(sender, ui().top(
                limit,
                runtime.performance().latest(),
                runtime.hotspots().all(),
                runtime.clusters().current(),
                runtime.protection().activeLimits(),
                runtime.mode(),
                sender instanceof Player && sender.hasPermission("farmguard.inspect")
        ));
        return true;
    }

    private boolean inspect(CommandSender sender, String[] args, String label) {
        if (!sender.hasPermission("farmguard.inspect") && !sender.hasPermission("farmguard.admin")) {
            msg().send(sender, "command.no-permission");
            return true;
        }
        ChunkKey key = resolveInspect(sender, args, label);
        if (key == null) {
            return true;
        }
        RiskAssessment assessment = runtime.inspect(key);
        ProtectionState protection = runtime.protection().stateOf(key);
        AutomationCluster cluster = runtime.clusters().find(key);
        ui().send(sender, ui().inspect(
                key,
                assessment,
                protection,
                cluster,
                runtime.whitelist().contains(key),
                runtime.settings().longWindowSeconds(),
                sender instanceof Player
        ));
        return true;
    }

    private ChunkKey resolveInspect(CommandSender sender, String[] args, String label) {
        if (args.length == 1) {
            if (!(sender instanceof Player player)) {
                msg().send(sender, "command.player-only");
                return null;
            }
            return ChunkKeys.of(player.getLocation());
        }
        if (args.length == 3) {
            if (!(sender instanceof Player player)) {
                msg().send(sender, "command.inspect.console-usage", Map.of("label", label));
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
                msg().send(sender, "command.inspect.world-not-found", Map.of("world", args[1]));
                return null;
            }
            Integer x = parseInt(sender, args[2]);
            Integer z = parseInt(sender, args[3]);
            if (x == null || z == null) {
                return null;
            }
            return ChunkKeys.of(world, x, z);
        }
        msg().send(sender, "command.unknown", Map.of("label", label));
        return null;
    }

    private boolean limits(CommandSender sender) {
        if (!sender.hasPermission("farmguard.status") && !sender.hasPermission("farmguard.admin")) {
            msg().send(sender, "command.no-permission");
            return true;
        }
        msg().send(sender, "command.limits.header");
        List<ProtectionState> limits = runtime.protection().activeLimits();
        if (limits.isEmpty()) {
            msg().send(sender, "command.limits.empty");
            return true;
        }
        LanguageManager lang = runtime.language();
        for (ProtectionState state : limits) {
            msg().sendComponent(sender, msg().prefixed(
                    Component.text(state.chunk().display() + "  ")
                            .append(Component.text(lang.protection(state.applied())))
            ));
        }
        return true;
    }

    private boolean mode(CommandSender sender, String[] args) {
        if (args.length == 1) {
            if (!sender.hasPermission("farmguard.status") && !sender.hasPermission("farmguard.admin") && !sender.hasPermission("farmguard.manage")) {
                msg().send(sender, "command.no-permission");
                return true;
            }
            msg().send(sender, "command.mode.current", Map.of("mode", runtime.language().mode(runtime.mode())));
            return true;
        }
        if (!sender.hasPermission("farmguard.manage") && !sender.hasPermission("farmguard.admin")) {
            msg().send(sender, "command.no-permission");
            return true;
        }
        OperatingMode parsed = OperatingMode.parse(args[1], null);
        if (parsed == null) {
            msg().send(sender, "command.mode.invalid");
            return true;
        }
        runtime.setMode(parsed);
        msg().send(sender, "command.mode.changed", Map.of("mode", runtime.language().mode(parsed)));
        msg().send(sender, parsed == OperatingMode.MONITOR ? "command.mode.monitor-hint" : "command.mode.protect-hint");
        return true;
    }

    private boolean whitelist(CommandSender sender, String[] args) {
        if (!sender.hasPermission("farmguard.manage") && !sender.hasPermission("farmguard.admin")) {
            msg().send(sender, "command.no-permission");
            return true;
        }
        if (args.length < 2) {
            msg().send(sender, "command.unknown", Map.of("label", "fg"));
            return true;
        }
        String action = args[1].toLowerCase(Locale.ROOT);
        if (action.equals("list")) {
            List<ChunkKey> chunks = runtime.whitelist().chunks();
            List<String> clusters = List.copyOf(runtime.whitelist().clusterIds());
            if (chunks.isEmpty() && clusters.isEmpty()) {
                msg().send(sender, "command.whitelist.empty");
                return true;
            }
            msg().send(sender, "command.whitelist.header");
            for (ChunkKey key : chunks) {
                msg().send(sender, "command.whitelist.chunk", Map.of("chunk", key.display()));
            }
            for (String id : clusters) {
                msg().send(sender, "command.whitelist.cluster", Map.of("id", id));
            }
            return true;
        }
        if (action.equals("add") || action.equals("remove")) {
            if (!(sender instanceof Player player)) {
                msg().send(sender, "command.player-only");
                return true;
            }
            ChunkKey key = ChunkKeys.of(player.getLocation());
            if (action.equals("add")) {
                if (runtime.whitelist().add(key)) {
                    runtime.persistState();
                    runtime.recordWhitelist(true, key);
                    msg().send(sender, "command.whitelist.added", Map.of(
                            "world", key.worldName(),
                            "x", String.valueOf(key.x()),
                            "z", String.valueOf(key.z())
                    ));
                } else {
                    msg().send(sender, "command.whitelist.exists");
                }
                return true;
            }
            if (runtime.whitelist().remove(key)) {
                runtime.persistState();
                runtime.recordWhitelist(false, key);
                msg().send(sender, "command.whitelist.removed", Map.of(
                        "world", key.worldName(),
                        "x", String.valueOf(key.x()),
                        "z", String.valueOf(key.z())
                ));
            } else {
                msg().send(sender, "command.whitelist.missing");
            }
            return true;
        }
        msg().send(sender, "command.unknown", Map.of("label", "fg"));
        return true;
    }

    private boolean reload(CommandSender sender) {
        if (!sender.hasPermission("farmguard.reload") && !sender.hasPermission("farmguard.admin")) {
            msg().send(sender, "command.no-permission");
            return true;
        }
        int errors = runtime.reload();
        if (errors > 0) {
            msg().send(sender, "command.reload.partial", Map.of("count", String.valueOf(errors)));
        } else {
            msg().send(sender, "command.reload.success");
        }
        return true;
    }

    private boolean language(CommandSender sender, String[] args) {
        boolean canView = sender.hasPermission("farmguard.status")
                || sender.hasPermission("farmguard.manage")
                || sender.hasPermission("farmguard.admin");
        if (!canView) {
            msg().send(sender, "command.no-permission");
            return true;
        }
        LanguageManager lang = runtime.language();
        if (args.length == 1) {
            msg().send(sender, "command.language.current", Map.of(
                    "name", lang.currentDisplayName(),
                    "locale", lang.currentLocale()
            ));
            msg().send(sender, "command.language.available", Map.of(
                    "languages", String.join(", ", lang.availableLocales())
            ));
            return true;
        }
        if (!sender.hasPermission("farmguard.manage") && !sender.hasPermission("farmguard.admin")) {
            msg().send(sender, "command.language.no-permission-change");
            return true;
        }
        String requested = args[1];
        if (!lang.supports(requested)) {
            msg().send(sender, "command.language.unknown", Map.of("locale", requested));
            msg().send(sender, "command.language.available", Map.of(
                    "languages", String.join(", ", lang.availableLocales())
            ));
            return true;
        }
        runtime.setLanguage(LocaleIds.tryNormalize(requested));
        LanguageManager updated = runtime.language();
        msg().send(sender, "command.language.changed", Map.of(
                "name", updated.currentDisplayName(),
                "locale", updated.currentLocale()
        ));
        return true;
    }

    private void sendHelp(CommandSender sender, String label) {
        Map<String, String> values = Map.of("label", label);
        msg().send(sender, "command.help.header");
        msg().send(sender, "command.help.status", values);
        msg().send(sender, "command.help.top", values);
        msg().send(sender, "command.help.inspect", values);
        msg().send(sender, "command.help.limits", values);
        msg().send(sender, "command.help.mode", values);
        msg().send(sender, "command.help.whitelist", values);
        msg().send(sender, "command.help.reload", values);
        msg().send(sender, "command.help.language", values);
    }

    private Integer parseInt(CommandSender sender, String raw) {
        try {
            return Integer.parseInt(raw);
        } catch (NumberFormatException exception) {
            msg().send(sender, "command.invalid-number");
            return null;
        }
    }

    private boolean hasAny(CommandSender sender) {
        return sender.hasPermission("farmguard.admin")
                || sender.hasPermission("farmguard.status")
                || sender.hasPermission("farmguard.top")
                || sender.hasPermission("farmguard.inspect")
                || sender.hasPermission("farmguard.manage")
                || sender.hasPermission("farmguard.reload");
    }

    private MessageService msg() {
        return runtime.messages();
    }

    private AdminUi ui() {
        return runtime.adminUi();
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> options = new ArrayList<>();
        if (args.length == 1) {
            options.addAll(List.of("status", "top", "inspect", "limits", "mode", "whitelist", "reload", "language", "lang", "help"));
        } else if (args.length == 2 && args[0].equalsIgnoreCase("mode")) {
            options.addAll(List.of("monitor", "protect"));
        } else if (args.length == 2 && args[0].equalsIgnoreCase("whitelist")) {
            options.addAll(List.of("add", "remove", "list"));
        } else if (args.length == 2 && args[0].equalsIgnoreCase("top")) {
            options.addAll(List.of("5", "10", "15"));
        } else if (args.length == 2 && (args[0].equalsIgnoreCase("language") || args[0].equalsIgnoreCase("lang"))) {
            options.addAll(runtime.language().availableLocales());
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
