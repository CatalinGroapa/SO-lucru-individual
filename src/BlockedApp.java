import java.nio.file.InvalidPathException;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;

public class BlockedApp {
    private String id;
    private String displayName;
    private String exeName;
    private boolean enabled;
    private String exePath;
    private int dailyLimitMinutes;
    private long usageMillisToday;
    private String usageDateIso;
    private String allowedIntervals;

    public BlockedApp() {
        this.id = UUID.randomUUID().toString();
    }

    public BlockedApp(String displayName, String exeName, boolean enabled) {
        this.id = UUID.randomUUID().toString();
        this.displayName = displayName;
        this.exeName = exeName;
        this.enabled = enabled;
        this.dailyLimitMinutes = 0;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getDisplayName() {
        return displayName;
    }

    public void setDisplayName(String displayName) {
        this.displayName = displayName;
    }

    public String getExeName() {
        return exeName;
    }

    public void setExeName(String exeName) {
        this.exeName = exeName;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getExePath() {
        return exePath;
    }

    public void setExePath(String exePath) {
        this.exePath = exePath;
        if ((exeName == null || exeName.isBlank()) && exePath != null && !exePath.isBlank()) {
            try {
                var fileName = Paths.get(exePath).getFileName();
                if (fileName != null) {
                    this.exeName = fileName.toString();
                }
            } catch (InvalidPathException ignored) {
            }
        }
    }

    public int getDailyLimitMinutes() {
        return dailyLimitMinutes;
    }

    public void setDailyLimitMinutes(int dailyLimitMinutes) {
        this.dailyLimitMinutes = Math.max(0, dailyLimitMinutes);
    }

    public long getUsageMillisToday() {
        return usageMillisToday;
    }

    public double getUsageMinutesToday() {
        return usageMillisToday / 60000d;
    }

    public void setUsageMillisToday(long usageMillisToday) {
        this.usageMillisToday = Math.max(0, usageMillisToday);
    }

    public String getUsageDateIso() {
        return usageDateIso;
    }

    public void setUsageDateIso(String usageDateIso) {
        this.usageDateIso = usageDateIso;
    }

    public String getAllowedIntervals() {
        return allowedIntervals;
    }

    public void setAllowedIntervals(String allowedIntervals) {
        this.allowedIntervals = allowedIntervals;
    }

    public boolean hasDailyLimit() {
        return dailyLimitMinutes > 0;
    }

    public boolean hasReachedDailyLimit() {
        return hasDailyLimit() && getUsageMinutesToday() >= dailyLimitMinutes;
    }

    public void addUsageMillis(long millis) {
        resetDailyUsageIfNeeded();
        usageMillisToday += Math.max(0, millis);
    }

    public void resetDailyUsageIfNeeded() {
        LocalDate today = LocalDate.now();
        if (usageDateIso == null || usageDateIso.isBlank() || !today.toString().equals(usageDateIso)) {
            usageDateIso = today.toString();
            usageMillisToday = 0;
        }
    }

    public boolean isScheduleAllowed(LocalTime now) {
        if (allowedIntervals == null || allowedIntervals.isBlank()) {
            return true;
        }
        DateTimeFormatter fmt = DateTimeFormatter.ofPattern("HH:mm");
        return Arrays.stream(allowedIntervals.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .anyMatch(interval -> {
                    String[] bounds = interval.split("-");
                    if (bounds.length != 2) return false;
                    try {
                        LocalTime start = LocalTime.parse(bounds[0].trim(), fmt);
                        LocalTime end = LocalTime.parse(bounds[1].trim(), fmt);
                        if (start.equals(end)) {
                            return true;
                        }
                        if (start.isBefore(end)) {
                            return !now.isBefore(start) && !now.isAfter(end);
                        }
                        // interval peste miezul nopții
                        return !now.isBefore(start) || !now.isAfter(end);
                    } catch (DateTimeParseException ex) {
                        return false;
                    }
                });
    }

    public boolean matchesExecutable(String command) {
        if (command == null || command.isBlank()) {
            return false;
        }
        String lower = command.toLowerCase();
        if (exePath != null && !exePath.isBlank()) {
            try {
                String normalized = Paths.get(exePath).toAbsolutePath().normalize().toString().toLowerCase();
                if (lower.equals(normalized) || lower.endsWith(normalized)) {
                    return true;
                }
            } catch (InvalidPathException ignored) {
            }
        }
        if (exeName != null && !exeName.isBlank()) {
            String exeLower = exeName.toLowerCase();
            return lower.endsWith("\\" + exeLower) || lower.endsWith("/" + exeLower) || lower.equals(exeLower);
        }
        return false;
    }

    public String getUsageSummary() {
        if (hasDailyLimit()) {
            return String.format("%.1f / %d min", getUsageMinutesToday(), dailyLimitMinutes);
        }
        return String.format("%.1f min", getUsageMinutesToday());
    }

    public String getScheduleSummary() {
        return allowedIntervals == null || allowedIntervals.isBlank() ? "Oricând" : allowedIntervals;
    }

    public String getFriendlyName() {
        if (displayName != null && !displayName.isBlank()) {
            return displayName;
        }
        if (exeName != null && !exeName.isBlank()) {
            return exeName;
        }
        return "aplicație necunoscută";
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        BlockedApp that = (BlockedApp) o;
        return Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }

    @Override
    public String toString() {
        return displayName + " (" + exeName + ")";
    }
}
