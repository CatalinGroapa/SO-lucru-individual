import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public class BlockedSite {
    private String id;
    private String title;
    private String urlPattern;
    private boolean enabled;

    public BlockedSite() {
        this.id = UUID.randomUUID().toString();
        this.enabled = true;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getUrlPattern() {
        return urlPattern;
    }

    public void setUrlPattern(String urlPattern) {
        this.urlPattern = urlPattern;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getDisplayDomain() {
        String host = extractHost(urlPattern);
        return host == null ? urlPattern : host;
    }

    public List<String> getHostsForBlocking() {
        List<String> hosts = new ArrayList<>();
        String host = extractHost(urlPattern);
        if (host != null && !host.isBlank()) {
            hosts.add(host);
            if (host.startsWith("www.")) {
                hosts.add(host.substring(4));
            } else {
                hosts.add("www." + host);
            }
        }
        return hosts;
    }

    private String extractHost(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.trim().toLowerCase();
        if (normalized.startsWith("http://") || normalized.startsWith("https://")) {
            int idx = normalized.indexOf("://");
            normalized = normalized.substring(idx + 3);
        }
        int slash = normalized.indexOf('/');
        if (slash >= 0) {
            normalized = normalized.substring(0, slash);
        }
        int portIdx = normalized.indexOf(':');
        if (portIdx >= 0) {
            normalized = normalized.substring(0, portIdx);
        }
        normalized = normalized.replaceAll("[^a-z0-9.-]", "");
        if (normalized.isBlank()) {
            return null;
        }
        return normalized;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        BlockedSite that = (BlockedSite) o;
        return Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }
}

