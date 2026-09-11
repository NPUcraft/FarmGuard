package dev.farmguard;

import java.io.File;
import org.bukkit.plugin.java.JavaPlugin;

public final class FarmGuardPlugin extends JavaPlugin {

    private FarmGuardRuntime runtime;

    @Override
    public void onEnable() {
        getLogger().info("Starting FarmGuard...");
        saveDefaultConfig();
        File messagesFile = new File(getDataFolder(), "messages.yml");
        if (!messagesFile.exists()) {
            saveResource("messages.yml", false);
        }
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
}
