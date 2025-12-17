import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

public class WebsiteBlocker {
    private static final String START_MARKER = "# BEGIN PARENTAL_CONTROL";
    private static final String END_MARKER = "# END PARENTAL_CONTROL";
    private static final String HOSTS_PATH = "C:/Windows/System32/drivers/etc/hosts";
    private final Consumer<String> logger;

    public WebsiteBlocker(Consumer<String> logger) {
        this.logger = logger;
    }

    public void apply(List<BlockedSite> sites) throws IOException {
        Path hosts = Path.of(HOSTS_PATH);
        Path backup = hosts.resolveSibling("hosts.parental.bak");
        if (!Files.exists(backup)) {
            Files.copy(hosts, backup, StandardCopyOption.REPLACE_EXISTING);
        }
        List<String> lines = Files.readAllLines(hosts, StandardCharsets.UTF_8);
        int start = findMarker(lines, START_MARKER);
        int end = findMarker(lines, END_MARKER);
        if (start >= 0 && end > start) {
            lines.subList(start, end + 1).clear();
        }
        lines.add(START_MARKER);
        Set<String> uniqueHosts = new HashSet<>();
        for (BlockedSite site : sites) {
            if (!site.isEnabled()) continue;
            for (String host : site.getHostsForBlocking()) {
                if (uniqueHosts.add(host)) {
                    lines.add("127.0.0.1 " + host);
                    lines.add("0.0.0.0 " + host);
                }
            }
        }
        lines.add(END_MARKER);
        Files.write(hosts, lines, StandardCharsets.UTF_8);
        log("Fișier hosts actualizat pentru " + uniqueHosts.size() + " domenii.");
    }

    public void removeAll(List<BlockedSite> sites) throws IOException {
        Path hosts = Path.of(HOSTS_PATH);
        if (!Files.exists(hosts)) return;
        List<String> lines = Files.readAllLines(hosts, StandardCharsets.UTF_8);
        int start = findMarker(lines, START_MARKER);
        int end = findMarker(lines, END_MARKER);
        if (start >= 0 && end > start) {
            lines.subList(start, end + 1).clear();
            Files.write(hosts, lines, StandardCharsets.UTF_8);
            log("Secțiunea de blocare a fost eliminată din hosts.");
        }
    }

    private int findMarker(List<String> lines, String marker) {
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).trim().equals(marker)) {
                return i;
            }
        }
        return -1;
    }

    private void log(String msg) {
        try {
            logger.accept(msg);
        } catch (Exception ignored) {
        }
    }
}

