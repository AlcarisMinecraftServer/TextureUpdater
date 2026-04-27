package net.alcaris.plugin.texture;

import com.moandjiezana.toml.Toml;
import com.google.inject.Inject;
import com.velocitypowered.api.command.CommandManager;
import com.velocitypowered.api.command.CommandMeta;
import com.velocitypowered.api.event.ResultedEvent;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.connection.PostLoginEvent;
import com.velocitypowered.api.event.player.PlayerResourcePackStatusEvent;
import com.velocitypowered.api.event.player.ServerPreConnectEvent;
import com.velocitypowered.api.event.player.ServerResourcePackSendEvent;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

@Plugin(id = "texture_updater")
public final class TextureUpdater {

    private final ProxyServer server;
    private final Logger logger;
    private final Path dataDirectory;

    private ResourcePackManager packManager;
    private boolean mandatory;

    private final Set<UUID> loadedPlayers = ConcurrentHashMap.newKeySet();
    private final Set<UUID> pendingPlayers = ConcurrentHashMap.newKeySet();
    private final Map<UUID, RegisteredServer> pendingServerJoin = new ConcurrentHashMap<>();

    @Inject
    public TextureUpdater(ProxyServer server, Logger logger, @DataDirectory Path dataDirectory) {
        this.server = server;
        this.logger = logger;
        this.dataDirectory = dataDirectory;
    }

    @Subscribe
    public void onProxyInitialization(ProxyInitializeEvent event) {
        PluginConfig config = loadConfig();
        this.packManager = new ResourcePackManager(server, logger, config);
        this.mandatory = config.mandatory;

        packManager.fetchLatest();

        server.getScheduler()
                .buildTask(this, this::checkForUpdates)
                .delay(config.checkIntervalMinutes, TimeUnit.MINUTES)
                .repeat(config.checkIntervalMinutes, TimeUnit.MINUTES)
                .schedule();

        CommandManager cm = server.getCommandManager();
        CommandMeta meta = cm.metaBuilder("textureupdater").aliases("tu").build();
        cm.register(meta, new TextureCommand(server, this, packManager, this::sendToAllPlayers));
    }

    private void sendToAllPlayers() {
        Component msg = Component.text(
                "[TextureUpdater] リソースパックが更新されました。新しいパックを送信します...",
                NamedTextColor.YELLOW
        );
        for (Player player : server.getAllPlayers()) {
            player.sendMessage(msg);
            sendPackOffer(player);
        }
    }

    private PluginConfig loadConfig() {
        Path configFile = dataDirectory.resolve("config.toml");
        if (!Files.exists(configFile)) {
            try {
                Files.createDirectories(dataDirectory);
                try (InputStream in = getClass().getResourceAsStream("/config.toml")) {
                    Files.copy(in, configFile);
                }
            } catch (IOException e) {
                logger.error("[TextureUpdater] Failed to copy default config: {}", e.getMessage());
                return new PluginConfig();
            }
        }
        try {
            return new Toml().read(configFile.toFile()).to(PluginConfig.class);
        } catch (Exception e) {
            logger.error("[TextureUpdater] Failed to read config.toml: {}", e.getMessage());
            return new PluginConfig();
        }
    }

    private void checkForUpdates() {
        if (!packManager.fetchLatest()) return;
        int count = server.getAllPlayers().size();
        sendToAllPlayers();
        logger.info("[TextureUpdater] Sent updated resource pack to {} online player(s)", count);
    }

    private void sendPackOffer(Player player) {
        packManager.getCurrentPack().ifPresent(pack -> {
            loadedPlayers.remove(player.getUniqueId());
            pendingPlayers.add(player.getUniqueId());
            player.sendResourcePackOffer(pack);
        });
    }

    @Subscribe
    public void onPostLogin(PostLoginEvent event) {
        sendPackOffer(event.getPlayer());
    }

    @Subscribe
    public void onServerPreConnect(ServerPreConnectEvent event) {
        if (!mandatory) return;

        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();

        if (loadedPlayers.contains(uuid)) return;

        if (packManager.getCurrentPack().isEmpty()) return;

        event.getResult().getServer().ifPresent(s ->
                pendingServerJoin.putIfAbsent(uuid, s)
        );

        event.setResult(ServerPreConnectEvent.ServerResult.denied());

        if (pendingPlayers.contains(uuid)) {
            if (event.getPreviousServer() == null) {
                player.sendMessage(Component.text(
                        "リソースパックをダウンロード中です。完了後に自動的に接続します...",
                        NamedTextColor.YELLOW
                ));
            } else {
                player.sendMessage(Component.text(
                        "リソースパックの適用が完了するまでサーバーを移動できません。",
                        NamedTextColor.RED
                ));
            }
        } else {
            sendPackOffer(player);
            player.sendMessage(Component.text(
                    "サーバーに参加するにはリソースパックが必要です。適用してください。",
                    NamedTextColor.YELLOW
            ));
        }
    }

    @Subscribe
    public void onResourcePackStatus(PlayerResourcePackStatusEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();
        PlayerResourcePackStatusEvent.Status status = event.getStatus();

        if (status.isIntermediate()) return;

        switch (status) {
            case SUCCESSFUL -> {
                pendingPlayers.remove(uuid);
                loadedPlayers.add(uuid);

                RegisteredServer target = pendingServerJoin.remove(uuid);
                if (target != null) {
                    player.createConnectionRequest(target).fireAndForget();
                }

                logger.info("[TextureUpdater] {} loaded the resource pack", player.getUsername());
            }

            case DECLINED, FAILED_DOWNLOAD, INVALID_URL, FAILED_RELOAD -> {
                pendingPlayers.remove(uuid);
                pendingServerJoin.remove(uuid);

                if (mandatory) {
                    if (player.getCurrentServer().isPresent()) {
                        player.disconnect(Component.text(
                                "このサーバーではリソースパックの適用が必須です。\n" +
                                "設定でリソースパックを許可してから再接続してください。",
                                NamedTextColor.RED
                        ));
                    } else {
                        player.sendMessage(Component.text(
                                "リソースパックを適用しないとサーバーに接続できません。サーバーを選択すると再度送信されます。",
                                NamedTextColor.RED
                        ));
                    }
                }

                logger.info("[TextureUpdater] {} resource pack status: {}", player.getUsername(), status);
            }

            case DISCARDED -> {}
        }
    }

    @Subscribe
    public void onServerResourcePackSend(ServerResourcePackSendEvent event) {
        event.setResult(ResultedEvent.GenericResult.denied());
    }

    @Subscribe
    public void onDisconnect(DisconnectEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        loadedPlayers.remove(uuid);
        pendingPlayers.remove(uuid);
        pendingServerJoin.remove(uuid);
    }
}
