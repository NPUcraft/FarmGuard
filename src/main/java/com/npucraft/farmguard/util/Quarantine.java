package dev.farmguard.util;

import java.io.File;
import java.util.Arrays;
import java.util.Comparator;
import java.util.logging.Logger;

/**
 * Moves a damaged YAML aside and keeps only a bounded number of those backups.
 */
public final class Quarantine {

    public static final int MAX_CORRUPT_BACKUPS = 8;

    private Quarantine() {
    }

    public static File move(File file, Logger logger, String label) {
        File parent = file.getParentFile();
        File backup = new File(parent, file.getName() + ".corrupt-" + System.currentTimeMillis());
        if (file.renameTo(backup)) {
            logger.warning("[FarmGuard] Corrupt " + label + " moved to " + backup.getName());
            prune(parent, file.getName() + ".corrupt-", MAX_CORRUPT_BACKUPS, logger);
            return backup;
        }
        logger.warning("[FarmGuard] Could not rename corrupt " + label + ": " + file.getAbsolutePath());
        return null;
    }

    static void prune(File parent, String prefix, int maxKeep, Logger logger) {
        if (parent == null || maxKeep < 1) {
            return;
        }
        File[] matches = parent.listFiles((dir, name) -> name.startsWith(prefix));
        if (matches == null || matches.length <= maxKeep) {
            return;
        }
        Arrays.sort(matches, Comparator.comparingLong(File::lastModified));
        int extra = matches.length - maxKeep;
        for (int i = 0; i < extra; i++) {
            if (!matches[i].delete()) {
                logger.warning("[FarmGuard] Could not delete old corrupt backup: " + matches[i].getName());
            }
        }
    }
}
