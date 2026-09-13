package com.npucraft.farmguard;

import java.util.logging.Logger;
import org.bukkit.plugin.java.JavaPlugin;

public final class FarmGuardPlugin extends JavaPlugin {

    private FarmGuardRuntime runtime;

    @Override
    public void onEnable() {
        printBanner();
        saveDefaultConfig();
        runtime = new FarmGuardRuntime(this);
        runtime.enable();
    }

    @Override
    public void onDisable() {
        if (runtime != null) {
            runtime.disable();
        }
    }

    public FarmGuardRuntime runtime() {
        return runtime;
    }

    private void printBanner() {
        Logger log = getLogger();
        String version = getPluginMeta().getVersion();
        for (String line : new String[] {
            "",
            "  ______                    _____                     _",
            " |  ____|                  / ____|                   | |",
            " | |__ __ _ _ __ _ __ ___ | |  __ _   _  __ _ _ __ __| |",
            " |  __/ _` | '__| '_ ` _ \\| | |_ | | | |/ _` | '__/ _` |",
            " | | | (_| | |  | | | | | | |__| | |_| | (_| | | | (_| |",
            " |_|  \\__,_|_|  |_| |_| |_|\\_____|\\__,_|\\__,_|_|  \\__,_|",
            "                    FarmGuard by NPUcraft",
            "                    v" + version,
            ""
        }) {
            log.info(line);
        }
    }
}
