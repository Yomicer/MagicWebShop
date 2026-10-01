package com.magic.webshop.net;

import com.magic.webshop.config.PluginConfig;

import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Enumeration;
import java.util.logging.Logger;

/**
 * Figures out the address players should use to reach the web shop, so the
 * server owner only has to set a port. Auto-detects the public IP (falling back
 * to the LAN IP), or uses a domain / full URL if one is configured.
 */
public class NetworkInfo {

    private final Logger logger;
    private volatile String publicIp;   // detected external IP, may be null
    private volatile String localIp;
    private volatile int port;          // actual bound web port (set after startup)

    public NetworkInfo(Logger logger) {
        this.logger = logger;
        this.localIp = detectLocalIp();
    }

    /** Record the port the web server actually bound to (may differ if auto-selected). */
    public void setPort(int port) {
        this.port = port;
    }

    public int getPort() {
        return port;
    }

    /** Query an external service for the public IP, off-thread. */
    public void detectAsync() {
        Thread t = new Thread(() -> {
            String[] services = {
                    "https://api.ipify.org", "https://ifconfig.me/ip", "https://icanhazip.com"
            };
            HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(6)).build();
            for (String svc : services) {
                try {
                    HttpRequest req = HttpRequest.newBuilder()
                            .uri(URI.create(svc)).timeout(Duration.ofSeconds(6)).GET().build();
                    HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
                    if (resp.statusCode() == 200) {
                        String ip = resp.body().trim();
                        if (!ip.isEmpty() && ip.length() < 60) {
                            publicIp = ip;
                            logger.info("Detected public IP: " + ip);
                            return;
                        }
                    }
                } catch (Exception ignored) { }
            }
            logger.info("Could not detect public IP; using LAN IP " + localIp
                    + ". Set web.public-url manually if needed.");
        }, "MagicWebShop-IP-Detect");
        t.setDaemon(true);
        t.start();
    }

    /** Best-effort host (public IP if known, else LAN IP). */
    public String bestHost() {
        return publicIp != null ? publicIp : localIp;
    }

    /** Full base URL, e.g. http://1.2.3.4:8085 — honours a configured domain/URL. */
    public String resolveBaseUrl(PluginConfig config) {
        String pu = config.getPublicUrl();
        int p = port > 0 ? port : config.getWebPort();
        if (pu != null && !pu.isBlank() && !pu.equalsIgnoreCase("auto")) {
            String v = pu.trim();
            if (v.startsWith("http://") || v.startsWith("https://")) {
                return v.endsWith("/") ? v.substring(0, v.length() - 1) : v;
            }
            // a bare host or domain, maybe with its own port
            return v.contains(":") ? "http://" + v : "http://" + v + ":" + p;
        }
        return "http://" + bestHost() + ":" + p;
    }

    private String detectLocalIp() {
        try {
            String host = InetAddress.getLocalHost().getHostAddress();
            if (host != null && !host.startsWith("127.")) return host;
        } catch (Exception ignored) { }
        try {
            Enumeration<NetworkInterface> ifaces = NetworkInterface.getNetworkInterfaces();
            while (ifaces.hasMoreElements()) {
                NetworkInterface ni = ifaces.nextElement();
                if (!ni.isUp() || ni.isLoopback() || ni.isVirtual()) continue;
                Enumeration<InetAddress> addrs = ni.getInetAddresses();
                while (addrs.hasMoreElements()) {
                    InetAddress a = addrs.nextElement();
                    if (a.isSiteLocalAddress() && a.getHostAddress().indexOf(':') < 0) {
                        return a.getHostAddress();
                    }
                }
            }
        } catch (Exception ignored) { }
        return "127.0.0.1";
    }
}
