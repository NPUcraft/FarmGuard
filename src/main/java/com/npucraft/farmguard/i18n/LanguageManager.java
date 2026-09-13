package com.npucraft.farmguard.i18n;

import com.npucraft.farmguard.model.ClusterType;
import com.npucraft.farmguard.model.LagCorrelation;
import com.npucraft.farmguard.model.MetricType;
import com.npucraft.farmguard.model.OperatingMode;
import com.npucraft.farmguard.model.ProtectionLevel;
import com.npucraft.farmguard.model.RiskLevel;
import com.npucraft.farmguard.model.RiskReason;
import com.npucraft.farmguard.model.ServerPressure;
import com.npucraft.farmguard.util.Placeholders;
import com.npucraft.farmguard.util.Quarantine;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;

/**
 * In-memory language catalog. YAML is read only on start / reload.
 */
public final class LanguageManager {

    public static final String BUNDLED_ZH = "lang/zh_CN.yml";
    public static final String BUNDLED_EN = "lang/en_US.yml";

    private final Logger logger;
    private final ClassLoader classLoader;
    private final AtomicReference<Catalog> catalog = new AtomicReference<>(Catalog.empty());
    private final Set<String> warnedKeys = ConcurrentHashMap.newKeySet();
    private volatile boolean migratedLegacy;

    public LanguageManager(Logger logger) {
        this(logger, LanguageManager.class.getClassLoader());
    }

    public LanguageManager(Logger logger, ClassLoader classLoader) {
        this.logger = logger;
        this.classLoader = classLoader == null ? LanguageManager.class.getClassLoader() : classLoader;
    }

    public synchronized LoadResult load(File dataFolder, String requestedLocale) {
        File langDir = new File(dataFolder, "lang");
        if (!langDir.exists() && !langDir.mkdirs() && !langDir.isDirectory()) {
            logger.warning("[FarmGuard] Could not create lang directory: " + langDir.getAbsolutePath());
        }
        YamlConfiguration bundledZh = loadBundled(BUNDLED_ZH);
        YamlConfiguration bundledEn = loadBundled(BUNDLED_EN);
        boolean migrated = migrateLegacyIfNeeded(dataFolder, langDir, bundledZh);
        installBundled(langDir, LocaleIds.ZH_CN, bundledZh);
        installBundled(langDir, LocaleIds.EN_US, bundledEn);

        Map<String, LanguagePack> packs = new LinkedHashMap<>();
        addPack(packs, loadOrRepairUser(langDir, LocaleIds.ZH_CN, bundledZh, "zh_CN"));
        addPack(packs, loadOrRepairUser(langDir, LocaleIds.EN_US, bundledEn, "en_US"));
        File[] extras = langDir.listFiles((dir, name) -> name.endsWith(".yml"));
        if (extras != null) {
            for (File extra : extras) {
                String id = LocaleIds.tryNormalize(extra.getName().substring(0, extra.getName().length() - 4));
                if (id == null || packs.containsKey(id)) {
                    continue;
                }
                LanguagePack pack = loadUserPack(extra, id, bundledFor(id, bundledZh, bundledEn));
                addPack(packs, pack);
            }
        }
        if (!packs.containsKey(LocaleIds.ZH_CN)) {
            addPack(packs, packFromYaml(LocaleIds.ZH_CN, bundledZh));
        }
        String selected = LocaleIds.tryNormalize(requestedLocale);
        if (selected == null) {
            logger.warning("[FarmGuard] Invalid language '" + requestedLocale + "', falling back to " + LocaleIds.CANONICAL);
            selected = LocaleIds.CANONICAL;
        } else if (!packs.containsKey(selected)) {
            logger.warning("[FarmGuard] Language file for '" + selected + "' is missing, falling back to " + LocaleIds.CANONICAL);
            selected = LocaleIds.CANONICAL;
        }
        Catalog next = new Catalog(selected, Map.copyOf(packs));
        catalog.set(next);
        this.migratedLegacy = migrated;
        return new LoadResult(selected, packs.size(), migrated);
    }

    public synchronized void select(String locale) {
        Catalog current = catalog.get();
        String normalized = LocaleIds.tryNormalize(locale);
        if (normalized == null || !current.packs.containsKey(normalized)) {
            return;
        }
        catalog.set(new Catalog(normalized, current.packs));
    }

    public String currentLocale() {
        return catalog.get().selected;
    }

    public String currentDisplayName() {
        LanguagePack pack = currentPack();
        return pack == null ? currentLocale() : pack.displayName();
    }

    public List<String> availableLocales() {
        return List.copyOf(catalog.get().packs.keySet());
    }

    public List<String> availableDisplay() {
        List<String> out = new ArrayList<>();
        for (LanguagePack pack : catalog.get().packs.values()) {
            out.add(pack.locale());
        }
        return List.copyOf(out);
    }

    public String displayName(String locale) {
        LanguagePack pack = catalog.get().packs.get(LocaleIds.tryNormalize(locale));
        return pack == null ? locale : pack.displayName();
    }

    public boolean supports(String locale) {
        String normalized = LocaleIds.tryNormalize(locale);
        return normalized != null && catalog.get().packs.containsKey(normalized);
    }

    public boolean migratedLegacy() {
        return migratedLegacy;
    }

    public Set<String> keys(String locale) {
        LanguagePack pack = catalog.get().packs.get(locale);
        return pack == null ? Set.of() : pack.keys();
    }

    public String raw(String key) {
        return raw(key, Map.of());
    }

    public String raw(String key, Map<String, String> placeholders) {
        String template = lookup(key);
        return Placeholders.apply(template, placeholders);
    }

    public String lookup(String key) {
        if (key == null || key.isBlank()) {
            return "";
        }
        Catalog current = catalog.get();
        LanguagePack selected = current.packs.get(current.selected);
        String value = selected == null ? null : selected.get(key);
        if (value != null) {
            return value;
        }
        LanguagePack canonical = current.packs.get(LocaleIds.CANONICAL);
        String fallback = canonical == null ? null : canonical.get(key);
        if (fallback != null) {
            warnMissing(current.selected, key);
            return fallback;
        }
        warnMissing(current.selected, key);
        String missing = selected != null && selected.get("format.missing") != null
                ? selected.get("format.missing")
                : "<missing:{key}>";
        return Placeholders.apply(missing, Map.of("key", key));
    }

    public String riskLevel(RiskLevel level) {
        return raw("risk.level." + LocaleIds.kebab(level == null ? RiskLevel.NONE : level));
    }

    public String protection(ProtectionLevel level) {
        return raw("protection." + LocaleIds.kebab(level == null ? ProtectionLevel.NORMAL : level));
    }

    public String correlation(LagCorrelation value) {
        return raw("correlation." + LocaleIds.kebab(value == null ? LagCorrelation.NONE : value));
    }

    public String pressure(ServerPressure value) {
        return raw("pressure." + LocaleIds.kebab(value == null ? ServerPressure.NORMAL : value));
    }

    public String mode(OperatingMode value) {
        return raw("mode." + LocaleIds.kebab(value == null ? OperatingMode.MONITOR : value));
    }

    public String clusterType(ClusterType type) {
        return raw("cluster." + LocaleIds.kebab(type == null ? ClusterType.UNKNOWN_AUTOMATION : type));
    }

    public String activity(MetricType type) {
        return raw("activity." + LocaleIds.kebab(type));
    }

    public String activityKey(String key) {
        return raw("activity." + key);
    }

    public String reason(RiskReason reason) {
        return raw("risk-reason." + LocaleIds.kebab(reason));
    }

    public String reasonDetail(RiskReason reason) {
        return raw("risk-reason." + LocaleIds.kebab(reason) + "-detail");
    }

    public String listSeparator() {
        return raw("format.list-separator");
    }

    public String reasons(Iterable<RiskReason> reasons) {
        if (reasons == null) {
            return raw("common.none");
        }
        String separator = listSeparator();
        StringBuilder builder = new StringBuilder();
        int count = 0;
        for (RiskReason reason : reasons) {
            if (count > 0) {
                builder.append(separator);
            }
            builder.append(reason(reason));
            count++;
        }
        return count == 0 ? raw("common.none") : builder.toString();
    }

    public String truncatedReasons(List<RiskReason> reasons, int keep) {
        if (reasons == null || reasons.isEmpty()) {
            return "";
        }
        int shown = Math.min(keep, reasons.size());
        List<RiskReason> head = reasons.subList(0, shown);
        String text = reasons(head);
        int extra = reasons.size() - shown;
        if (extra > 0) {
            text = text + " " + raw("format.reason-more", Map.of("count", String.valueOf(extra)));
        }
        return text;
    }

    public int warnedKeyCount() {
        return warnedKeys.size();
    }

    private void warnMissing(String locale, String key) {
        String token = locale + ":" + key;
        if (warnedKeys.add(token)) {
            logger.warning("[FarmGuard] Missing language key '" + key + "' (locale=" + locale + ")");
        }
    }

    private LanguagePack currentPack() {
        Catalog current = catalog.get();
        return current.packs.get(current.selected);
    }

    private void addPack(Map<String, LanguagePack> packs, LanguagePack pack) {
        if (pack != null && pack.locale() != null && !pack.locale().isBlank()) {
            packs.put(pack.locale(), pack);
        }
    }

    private boolean migrateLegacyIfNeeded(File dataFolder, File langDir, YamlConfiguration bundledZh) {
        File legacy = new File(dataFolder, "messages.yml");
        File zhFile = new File(langDir, LocaleIds.ZH_CN + ".yml");
        if (!legacy.exists() || zhFile.exists()) {
            return false;
        }
        YamlConfiguration userLegacy = loadFile(legacy, "messages.yml");
        YamlConfiguration target = copyYaml(bundledZh);
        int applied = LegacyMessageMigration.overlay(target, userLegacy);
        try {
            target.save(zhFile);
            logger.info("[FarmGuard] Migrated messages.yml to lang/zh_CN.yml (" + applied
                    + " customized key(s)). The old messages.yml file was kept as a backup.");
            return true;
        } catch (IOException exception) {
            logger.warning("[FarmGuard] Could not write migrated lang/zh_CN.yml: " + exception.getMessage());
            return false;
        }
    }

    private void installBundled(File langDir, String locale, YamlConfiguration bundled) {
        File file = new File(langDir, locale + ".yml");
        if (file.exists() || bundled == null) {
            return;
        }
        try (InputStream in = classLoader.getResourceAsStream("lang/" + locale + ".yml")) {
            if (in != null) {
                Files.copy(in, file.toPath());
                return;
            }
        } catch (IOException ignored) {
            // Fall through to YAML save.
        }
        try {
            bundled.save(file);
        } catch (IOException exception) {
            logger.warning("[FarmGuard] Could not install lang/" + locale + ".yml: " + exception.getMessage());
        }
    }

    private LanguagePack loadOrRepairUser(File langDir, String locale, YamlConfiguration bundled, String label) {
        File file = new File(langDir, locale + ".yml");
        YamlConfiguration user = loadFileOrQuarantine(file, label);
        if (user == null) {
            return packFromYaml(locale, bundled);
        }
        int added = YamlMessages.mergeMissing(user, bundled);
        if (added > 0) {
            try {
                user.save(file);
                logger.info("[FarmGuard] Added " + added + " missing language key(s) to lang/" + locale + ".yml");
            } catch (IOException exception) {
                logger.warning("[FarmGuard] Could not update lang/" + locale + ".yml: " + exception.getMessage());
            }
        }
        return packFromYaml(locale, user);
    }

    private LanguagePack loadUserPack(File file, String locale, YamlConfiguration bundled) {
        YamlConfiguration user = loadFileOrQuarantine(file, file.getName());
        if (user == null) {
            return bundled == null ? null : packFromYaml(locale, bundled);
        }
        if (bundled != null) {
            YamlMessages.mergeMissing(user, bundled);
        }
        return packFromYaml(locale, user);
    }

    private YamlConfiguration bundledFor(String locale, YamlConfiguration zh, YamlConfiguration en) {
        if (LocaleIds.ZH_CN.equals(locale)) {
            return zh;
        }
        if (LocaleIds.EN_US.equals(locale)) {
            return en;
        }
        return null;
    }

    private LanguagePack packFromYaml(String locale, YamlConfiguration yaml) {
        String id = YamlMessages.metaLocale(yaml, locale);
        String name = YamlMessages.metaName(yaml, id);
        return new LanguagePack(id, name, YamlMessages.flatten(yaml));
    }

    private YamlConfiguration loadBundled(String classpath) {
        try (InputStream in = classLoader.getResourceAsStream(classpath)) {
            if (in == null) {
                logger.warning("[FarmGuard] Missing bundled language resource " + classpath);
                return new YamlConfiguration();
            }
            String text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            YamlConfiguration yaml = new YamlConfiguration();
            yaml.loadFromString(text);
            return yaml;
        } catch (IOException | InvalidConfigurationException exception) {
            logger.warning("[FarmGuard] Could not read bundled " + classpath + ": " + exception.getMessage());
            return new YamlConfiguration();
        }
    }

    private YamlConfiguration loadFile(File file, String label) {
        try {
            String text = Files.readString(file.toPath(), StandardCharsets.UTF_8);
            YamlConfiguration yaml = new YamlConfiguration();
            yaml.loadFromString(text);
            return yaml;
        } catch (IOException | InvalidConfigurationException exception) {
            logger.warning("[FarmGuard] Failed to read " + label + ": " + exception.getMessage());
            return new YamlConfiguration();
        }
    }

    private YamlConfiguration loadFileOrQuarantine(File file, String label) {
        if (!file.exists()) {
            return new YamlConfiguration();
        }
        try {
            String text = Files.readString(file.toPath(), StandardCharsets.UTF_8);
            YamlConfiguration yaml = new YamlConfiguration();
            yaml.loadFromString(text);
            return yaml;
        } catch (IOException | InvalidConfigurationException exception) {
            logger.warning("[FarmGuard] Language file " + label + " is invalid: " + exception.getMessage());
            Quarantine.move(file, logger, label);
            return null;
        }
    }

    private static YamlConfiguration copyYaml(YamlConfiguration source) {
        YamlConfiguration copy = new YamlConfiguration();
        if (source == null) {
            return copy;
        }
        try {
            copy.loadFromString(source.saveToString());
        } catch (InvalidConfigurationException exception) {
            for (String key : source.getKeys(true)) {
                if (!source.isConfigurationSection(key)) {
                    copy.set(key, source.get(key));
                }
            }
        }
        return copy;
    }

    public record LoadResult(String locale, int packCount, boolean migratedLegacy) {
    }

    private record Catalog(String selected, Map<String, LanguagePack> packs) {
        static Catalog empty() {
            return new Catalog(LocaleIds.CANONICAL, Map.of());
        }
    }
}
