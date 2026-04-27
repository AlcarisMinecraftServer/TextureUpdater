package net.alcaris.plugin.texture;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.player.ResourcePackInfo;
import org.slf4j.Logger;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Optional;

public class ResourcePackManager {

    private static final String USER_AGENT = "TextureUpdater-VelocityPlugin";

    private final ProxyServer server;
    private final Logger logger;
    private final PluginConfig config;
    private final HttpClient http;
    private final HttpClient httpNoRedirect;

    private volatile ResourcePackInfo currentPack;
    private volatile String currentVersion;

    public ResourcePackManager(ProxyServer server, Logger logger, PluginConfig config) {
        this.server = server;
        this.logger = logger;
        this.config = config;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        this.httpNoRedirect = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    public boolean fetchLatest() {
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(config.url))
                    .header("Accept", "application/vnd.github.v3+json")
                    .header("User-Agent", USER_AGENT)
                    .timeout(Duration.ofSeconds(15))
                    .build();

            HttpResponse<String> response = http.send(req, HttpResponse.BodyHandlers.ofString());
            String body = response.body();
            JsonObject release = JsonParser.parseString(body).getAsJsonObject();

            if (release.get("tag_name") == null) {
                String apiMessage = release.has("message") ? release.get("message").getAsString() : "unknown error";
                logger.error("[TextureUpdater] GitHub API returned unexpected response (HTTP {}): {}",
                        response.statusCode(), apiMessage);
                return false;
            }
            String version = release.get("tag_name").getAsString();

            if (version.equals(currentVersion)) return false;

            JsonArray assets = release.getAsJsonArray("assets");
            if (assets == null) {
                logger.error("[TextureUpdater] GitHub release {} has no assets array", version);
                return false;
            }
            String downloadUrl = findAsset(assets);
            if (downloadUrl == null) {
                logger.error("[TextureUpdater] No matching asset found in GitHub release {}", version);
                return false;
            }

            String resolvedUrl = resolveRedirect(downloadUrl);
            logger.info("[TextureUpdater] Downloading resource pack {} ...", version);
            byte[] hash = downloadAndHash(resolvedUrl);

            ResourcePackInfo newPack = server.createResourcePackBuilder(resolvedUrl)
                    .setHash(hash)
                    .build();

            boolean isUpdate = currentVersion != null;
            currentPack = newPack;
            currentVersion = version;

            if (isUpdate) {
                logger.info("[TextureUpdater] Resource pack updated to {}", version);
            } else {
                logger.info("[TextureUpdater] Resource pack loaded: {}", version);
            }
            return isUpdate;

        } catch (Exception e) {
            logger.error("[TextureUpdater] Failed to fetch resource pack from GitHub: {}", e.getMessage());
            return false;
        }
    }

    private String resolveRedirect(String url) throws Exception {
        String current = url;
        for (int i = 0; i < 10; i++) {
            HttpRequest req = HttpRequest.newBuilder()
                    .method("HEAD", HttpRequest.BodyPublishers.noBody())
                    .uri(URI.create(current))
                    .header("User-Agent", USER_AGENT)
                    .timeout(Duration.ofSeconds(10))
                    .build();
            HttpResponse<Void> resp = httpNoRedirect.send(req, HttpResponse.BodyHandlers.discarding());
            int status = resp.statusCode();
            if (status >= 300 && status < 400) {
                current = resp.headers().firstValue("Location").orElse(current);
            } else {
                break;
            }
        }
        return current;
    }

    private String findAsset(JsonArray assets) {
        String targetName = config.file;
        for (var element : assets) {
            JsonObject asset = element.getAsJsonObject();
            if (asset.get("name") == null || asset.get("browser_download_url") == null) continue;
            String name = asset.get("name").getAsString();
            if (!targetName.isEmpty() ? name.equals(targetName) : name.endsWith(".zip")) {
                return asset.get("browser_download_url").getAsString();
            }
        }
        return null;
    }

    private byte[] downloadAndHash(String url) throws Exception {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("User-Agent", USER_AGENT)
                .timeout(Duration.ofMinutes(2))
                .build();

        HttpResponse<InputStream> response = http.send(req, HttpResponse.BodyHandlers.ofInputStream());
        long contentLength = response.headers().firstValueAsLong("Content-Length").orElse(-1);

        MessageDigest digest = MessageDigest.getInstance("SHA-1");
        byte[] buffer = new byte[8192];
        long downloaded = 0;
        int lastReported = -1;

        try (InputStream in = response.body()) {
            int read;
            while ((read = in.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
                downloaded += read;
                if (contentLength > 0) {
                    int percent = (int) (downloaded * 100 / contentLength);
                    int step = percent / 10;
                    if (step != lastReported) {
                        logger.info("[TextureUpdater] Downloading... {}% ({}/{}KB)",
                                percent, downloaded / 1024, contentLength / 1024);
                        lastReported = step;
                    }
                }
            }
        }

        return digest.digest();
    }

    public Optional<ResourcePackInfo> getCurrentPack() {
        return Optional.ofNullable(currentPack);
    }

    public String getCurrentVersion() {
        return currentVersion;
    }
}
