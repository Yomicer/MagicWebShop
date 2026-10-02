package com.magic.webshop.update;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.magic.webshop.config.PluginConfig;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;

/**
 * Auto-update: checks the GitHub repository's latest release and, when
 * auto-update is enabled in config, downloads the new jar next to the running
 * plugin so a restart picks it up.
 *
 * <p>Only stable releases are considered (the GitHub API's {@code /releases/latest}
 * skips pre-releases by design). Version comparison is simple dotted-numeric.
 */
public class UpdateChecker {

    private final JavaPlugin plugin;
    private final PluginConfig config;

    public UpdateChecker(JavaPlugin plugin, PluginConfig config) {
        this.plugin = plugin;
        this.config = config;
    }

    public static int compareVersions(String a, String b) {
        String[] pa = a.replaceFirst("^[vV]", "").split("[.\\-+]");
        String[] pb = b.replaceFirst("^[vV]", "").split("[.\\-+]");
        for (int i = 0; i < Math.max(pa.length, pb.length); i++) {
            int va = i < pa.length ? parseInt(pa[i]) : 0;
            int vb = i < pb.length ? parseInt(pb[i]) : 0;
            if (va != vb) return Integer.compare(va, vb);
        }
        return 0;
    }

    private static int parseInt(String s) {
        try { return Integer.parseInt(s); } catch (Exception e) { return 0; }
    }

    /** Result of a check: latest tag/url if a newer stable release exists. */
    public static final class Latest {
        public final String tag;
        public final String name;
        public final String assetUrl;
        public Latest(String tag, String name, String assetUrl) {
            this.tag = tag; this.name = name; this.assetUrl = assetUrl;
        }
    }

    /** Prefix a URL with the configured mirror (empty = 直连 GitHub).
     *  Mirror proxies typically take the full original URL, e.g.
     *  https://ghproxy.net/https://api.github.com/...
     */
    private String resolve(String url) {
        String m = config.getUpdateMirror();
        if (m == null || m.isBlank()) return url;
        return m.endsWith("/") ? m + url : m + "/" + url;
    }

    /** Query the GitHub API for the latest stable release. Null if unavailable. */
    public Latest latest() {
        try {
            String repo = config.getUpdateRepo()
                    .replaceFirst("^https?://github\\.com/", "")
                    .replaceAll("/$", "");
            if (!repo.matches("[^/]+/[^/]+")) return null;
            String apiUrl = resolve("https://api.github.com/repos/" + repo + "/releases/latest");
            URI uri = URI.create(apiUrl);
            HttpURLConnection c = (HttpURLConnection) uri.toURL().openConnection();
            c.setConnectTimeout(8000);
            c.setReadTimeout(8000);
            c.setRequestProperty("Accept", "application/vnd.github+json");
            c.setRequestProperty("User-Agent", "MagicWebShop-updater");
            int code = c.getResponseCode();
            if (code != 200) return null;
            try (InputStream in = c.getInputStream()) {
                JsonObject o = JsonParser.parseReader(new java.io.InputStreamReader(in)).getAsJsonObject();
                String tag = o.get("tag_name").getAsString();
                String name = o.has("name") && !o.get("name").isJsonNull()
                        ? o.get("name").getAsString() : tag;
                JsonArray assets = o.has("assets") ? o.getAsJsonArray("assets") : new JsonArray();
                String assetUrl = null;
                for (JsonElement e : assets) {
                    JsonObject a = e.getAsJsonObject();
                    String fn = a.has("name") ? a.get("name").getAsString() : "";
                    if (fn.endsWith(".jar") && !fn.contains("sources")) {
                        assetUrl = a.get("browser_download_url").getAsString();
                        break;
                    }
                }
                return new Latest(tag, name, assetUrl);
            }
        } catch (Exception e) {
            String m = e.getMessage();
            String hint = (m != null && (m.contains("PKIX") || m.contains("cert") || m.contains("SSL")))
                    ? "TLS 证书校验失败：服务器网络可能拦截了 GitHub 的 HTTPS（可尝试在 config.yml 配置 update-mirror 镜像）。"
                    : "无法连接 GitHub，可尝试配置 update-mirror 镜像。";
            plugin.getLogger().warning("MagicWebShop: update check failed: " + m + " (" + hint + ")");
            return null;
        }
    }

    /** Download the latest jar over the running plugin's own jar file so a restart
     *  replaces it in place (keeps a single jar, avoiding double-load conflicts). */
    public boolean download(String assetUrl) {
        try {
            File runningJar = new File(plugin.getClass().getProtectionDomain()
                    .getCodeSource().getLocation().toURI());
            File dir = runningJar.getParentFile();
            if (dir == null) dir = plugin.getDataFolder().getParentFile();
            URI uri = URI.create(resolve(assetUrl));
            HttpURLConnection c = (HttpURLConnection) uri.toURL().openConnection();
            c.setConnectTimeout(15000);
            c.setReadTimeout(15000);
            c.setInstanceFollowRedirects(true);
            c.setRequestProperty("User-Agent", "MagicWebShop-updater");
            if (c.getResponseCode() != 200) return false;
            File tmp = new File(dir, runningJar.getName() + ".part");
            try (InputStream in = c.getInputStream(); FileOutputStream out = new FileOutputStream(tmp)) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
            }
            Files.move(tmp.toPath(), runningJar.toPath(), StandardCopyOption.REPLACE_EXISTING);
            return true;
        } catch (Exception e) {
            plugin.getLogger().warning("MagicWebShop: update download failed: " + e.getMessage());
            return false;
        }
    }

    /** One full check: returns a message describing the outcome. */
    public String checkAndMaybeUpdate() {
        Latest latest = latest();
        String local = plugin.getDescription().getVersion();
        if (latest == null) {
            plugin.getLogger().info("MagicWebShop: update check failed (network) → local=" + local);
            return "无法获取 GitHub 最新版本（服务器可能无法访问外网）。";
        }
        plugin.getLogger().info("MagicWebShop: update check → local=" + local + ", latest=" + latest.tag
                + (config.isAutoUpdate() ? ", auto-update: on" : ", auto-update: off"));
        if (compareVersions(latest.tag, local) <= 0) {
            return "已是最新版本（" + local + "）。";
        }
        String msg = "发现新版本 " + latest.tag + "（当前 " + local + "）";
        if (!config.isAutoUpdate()) {
            return msg + "。可在 config.yml 开启 auto-update: true 后自动下载。";
        }
        if (latest.assetUrl == null) {
            return msg + "，但该 Release 未附带 .jar 附件，请手动更新。";
        }
        boolean ok = download(latest.assetUrl);
        return ok
                ? msg + "，已下载到 plugins/ 目录，重启服务器后生效。"
                : msg + "，但下载失败，请手动更新。";
    }
}
