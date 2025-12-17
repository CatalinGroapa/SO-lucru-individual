import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

// Format multi-tip:
// APP|id|displayName|exeName|enabled|exePath|dailyLimit|usageMillis|usageDate|allowedIntervals
// WEB|id|title|urlPattern|enabled
public class BlockedListStore {
    private final Path dataFile;

    public BlockedListStore() {
        String appDir = System.getenv("APPDATA");
        if (appDir == null || appDir.isEmpty()) {
            appDir = System.getProperty("user.home");
        }
        Path dir = Path.of(appDir, "ParentalControlApp");
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            // ignore
        }
        dataFile = dir.resolve("blocked_apps.txt");
    }

    public List<BlockedApp> loadApps() throws IOException {
        List<BlockedApp> list = new ArrayList<>();
        if (!Files.exists(dataFile)) return list;
        try (BufferedReader r = Files.newBufferedReader(dataFile, StandardCharsets.UTF_8)) {
            String line;
            while ((line = r.readLine()) != null) {
                String[] parts = line.split("\\|", -1);
                if (parts.length < 1) continue;
                if ("WEB".equals(parts[0])) continue; // skip, handled in loadSites
                BlockedApp b = parseApp(parts);
                if (b != null) {
                    b.resetDailyUsageIfNeeded();
                    list.add(b);
                }
            }
        }
        return list;
    }

    public List<BlockedSite> loadSites() throws IOException {
        List<BlockedSite> list = new ArrayList<>();
        if (!Files.exists(dataFile)) return list;
        try (BufferedReader r = Files.newBufferedReader(dataFile, StandardCharsets.UTF_8)) {
            String line;
            while ((line = r.readLine()) != null) {
                String[] parts = line.split("\\|", -1);
                if (parts.length < 1) continue;
                if (!"WEB".equals(parts[0])) continue;
                if (parts.length < 5) continue;
                BlockedSite s = new BlockedSite();
                s.setId(parts[1]);
                s.setTitle(parts[2]);
                s.setUrlPattern(parts[3]);
                s.setEnabled("true".equalsIgnoreCase(parts[4]));
                list.add(s);
            }
        }
        return list;
    }

    public void save(List<BlockedApp> apps, List<BlockedSite> sites) throws IOException {
        Path tmp = dataFile.resolveSibling("blocked_apps.txt.tmp");
        try (BufferedWriter w = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
             for (BlockedApp b : apps) {
                String line = String.join("|",
                        "APP",
                        nullToEmpty(b.getId()),
                        nullToEmpty(b.getDisplayName()),
                        nullToEmpty(b.getExeName()),
                        Boolean.toString(b.isEnabled()),
                        nullToEmpty(b.getExePath()),
                        Integer.toString(b.getDailyLimitMinutes()),
                        Long.toString(b.getUsageMillisToday()),
                        nullToEmpty(currentUsageDate(b)),
                        nullToEmpty(b.getAllowedIntervals()));
                w.write(line);
                w.newLine();
            }
            for (BlockedSite s : sites) {
                String line = String.join("|",
                        "WEB",
                        nullToEmpty(s.getId()),
                        nullToEmpty(s.getTitle()),
                        nullToEmpty(s.getUrlPattern()),
                        Boolean.toString(s.isEnabled()));
                w.write(line);
                w.newLine();
            }
        }
        Files.move(tmp, dataFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    private String nullToEmpty(String s) {
        return s == null ? "" : s.replaceAll("\\r|\\n|\\|", " ");
    }

    private String emptyToNull(String s) {
        return (s == null || s.isEmpty()) ? null : s;
    }

    private int parseIntSafe(String val) {
        try {
            return Integer.parseInt(val);
        } catch (NumberFormatException ex) {
            return 0;
        }
    }

    private long parseLongSafe(String val) {
        try {
            return Long.parseLong(val);
        } catch (NumberFormatException ex) {
            return 0L;
        }
    }

    private String currentUsageDate(BlockedApp app) {
        app.resetDailyUsageIfNeeded();
        return app.getUsageDateIso();
    }

    private BlockedApp parseApp(String[] parts) {
        BlockedApp b = new BlockedApp();
        if ("APP".equals(parts[0])) {
            if (parts.length < 5) return null;
            b.setId(parts[1]);
            b.setDisplayName(parts[2]);
            b.setExeName(parts[3]);
            b.setEnabled("true".equalsIgnoreCase(parts[4]));
            if (parts.length > 5) b.setExePath(emptyToNull(parts[5]));
            if (parts.length > 6) b.setDailyLimitMinutes(parseIntSafe(parts[6]));
            if (parts.length > 7) b.setUsageMillisToday(parseLongSafe(parts[7]));
            if (parts.length > 8) b.setUsageDateIso(emptyToNull(parts[8]));
            if (parts.length > 9) b.setAllowedIntervals(emptyToNull(parts[9]));
            return b;
        }
        // Fallback vechi: id|display|exe|enabled
        if (parts.length < 4) return null;
        b.setId(parts[0]);
        b.setDisplayName(parts[1]);
        b.setExeName(parts[2]);
        b.setEnabled("true".equalsIgnoreCase(parts[3]));
        return b;
    }
}
