import java.io.IOException;
import java.time.Instant;
import java.time.LocalTime;
import java.time.Duration;
import java.util.Map;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

public class ProcessMonitor {
    private final List<BlockedApp> blockedList;
    private final Consumer<String> logger;
    private ScheduledExecutorService executor;
    private volatile boolean running = false;
    private final Map<Long, Instant> startTimes = new ConcurrentHashMap<>();

    public ProcessMonitor(List<BlockedApp> blockedList, Consumer<String> logger) {
        this.blockedList = blockedList;
        this.logger = logger;
    }

    public synchronized void start() {
        if (running) return;
        running = true;
        executor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "ProcessMonitor");
            t.setDaemon(true);
            return t;
        });
        executor.scheduleWithFixedDelay(this::poll, 0, 2, TimeUnit.SECONDS);
    }

    public synchronized void stop() {
        if (!running) return;
        running = false;
        if (executor != null) {
            executor.shutdownNow();
            executor = null;
        }
    }

    public boolean isRunning() {
        return running;
    }

    public void blockNow(BlockedApp target) {
        enforceImmediate(target);
    }

    private void enforceImmediate(BlockedApp target) {
        if (target == null) {
            return;
        }
        try {
            for (ProcessHandle ph : ProcessHandle.allProcesses().toArray(ProcessHandle[]::new)) {
                if (!ph.isAlive()) {
                    continue;
                }
                String cmd = ph.info().command().orElse("");
                if (cmd.isBlank()) {
                    continue;
                }
                if (target.matchesExecutable(cmd)) {
                    tryTerminate(ph.pid(), cmd, target);
                }
            }
        } catch (Throwable t) {
            log("Blocare manuală eșuată: " + t.getMessage());
        }
    }

    private void poll() {
        try {
            Instant nowInstant = Instant.now();
            LocalTime nowTime = LocalTime.now();
            for (ProcessHandle ph : ProcessHandle.allProcesses().toArray(ProcessHandle[]::new)) {
                if (!ph.isAlive()) continue;
                String cmd = ph.info().command().orElse("");
                if (cmd.isBlank()) continue;
                synchronized (blockedList) {
                    for (BlockedApp b : blockedList) {
                        if (!b.isEnabled()) continue;
                        if (!b.matchesExecutable(cmd)) continue;
                        b.resetDailyUsageIfNeeded();
                        if (!b.isScheduleAllowed(nowTime)) {
                            tryTerminate(ph.pid(), cmd, b);
                            continue;
                        }
                        if (b.hasDailyLimit()) {
                            trackUsage(ph.pid(), nowInstant, b);
                            if (b.hasReachedDailyLimit()) {
                                tryTerminate(ph.pid(), cmd, b);
                            }
                        } else {
                            tryTerminate(ph.pid(), cmd, b);
                        }
                    }
                }
            }
        } catch (Throwable t) {
            log("Monitor error: " + t.getMessage());
        }
    }

    private void trackUsage(long pid, Instant now, BlockedApp app) {
        Instant previous = startTimes.put(pid, now);
        if (previous != null) {
            long delta = Duration.between(previous, now).toMillis();
            if (delta > 0) {
                app.addUsageMillis(delta);
            }
        }
    }

    private void tryTerminate(long pid, String cmd, BlockedApp app) {
        String exeName = app.getExeName() != null && !app.getExeName().isBlank() ? app.getExeName() : cmd;
        log("Aplicatie blocata: " + exeName + " (pid=" + pid + ") cmd=" + cmd);
        // First try polite termination
        try {
            ProcessHandle.of(pid).ifPresent(ph -> ph.destroy());
            // wait briefly and check
            try { Thread.sleep(300); } catch (InterruptedException ignored) {}
            if (ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false)) {
                boolean killed = killByTaskkill(exeName);
                if (killed) log("Proces terminat fortat (taskkill): " + exeName + " pid=" + pid);
                else log("Nu pot termina " + exeName + ", pid=" + pid + ". Ruleaza aplicatia ca Administrator.");
            } else {
                log("Proces terminat: " + exeName + " pid=" + pid);
            }
        } catch (Throwable t) {
            log("Eroare la terminare " + exeName + ": " + t.getMessage());
        }
        startTimes.remove(pid);
    }

    private boolean killByTaskkill(String exeName) {
        try {
            ProcessBuilder pb = new ProcessBuilder("taskkill", "/F", "/IM", exeName);
            Process p = pb.start();
            int rc = p.waitFor();
            return rc == 0;
        } catch (IOException | InterruptedException e) {
            return false;
        }
    }

    private void log(String message) {
        try {
            logger.accept(message);
        } catch (Throwable t) {
            // ignore
        }
    }
}
