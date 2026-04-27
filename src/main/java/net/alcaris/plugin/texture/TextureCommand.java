package net.alcaris.plugin.texture;

import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.ProxyServer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

import java.util.List;

public class TextureCommand implements SimpleCommand {

    private final ProxyServer server;
    private final Object pluginInstance;
    private final ResourcePackManager packManager;
    private final Runnable sendToAll;

    public TextureCommand(ProxyServer server, Object pluginInstance, ResourcePackManager packManager, Runnable sendToAll) {
        this.server = server;
        this.pluginInstance = pluginInstance;
        this.packManager = packManager;
        this.sendToAll = sendToAll;
    }

    @Override
    public void execute(Invocation invocation) {
        CommandSource source = invocation.source();
        String[] args = invocation.arguments();

        if (args.length == 0) {
            sendHelp(source);
            return;
        }

        switch (args[0].toLowerCase()) {
            case "status" -> {
                String version = packManager.getCurrentVersion();
                if (version == null) {
                    source.sendMessage(Component.text("[TextureUpdater] パックはまだ読み込まれていません。", NamedTextColor.RED));
                } else {
                    source.sendMessage(Component.text("[TextureUpdater] 現在のバージョン: " + version, NamedTextColor.GREEN));
                }
            }
            case "reload" -> {
                source.sendMessage(Component.text("[TextureUpdater] GitHubから最新版を確認中...", NamedTextColor.YELLOW));
                server.getScheduler().buildTask(pluginInstance, () -> {
                    boolean updated = packManager.fetchLatest();
                    if (updated) {
                        sendToAll.run();
                        source.sendMessage(Component.text("[TextureUpdater] 更新を検出し全プレイヤーに送信しました。", NamedTextColor.GREEN));
                    } else {
                        source.sendMessage(Component.text("[TextureUpdater] リロード完了。更新はありません（バージョン: " + packManager.getCurrentVersion() + "）", NamedTextColor.YELLOW));
                    }
                }).schedule();
            }
            case "force" -> {
                sendToAll.run();
                int count = server.getAllPlayers().size();
                source.sendMessage(Component.text("[TextureUpdater] " + count + " 人のプレイヤーにパックを強制送信しました。", NamedTextColor.GREEN));
            }
            default -> sendHelp(source);
        }
    }

    private void sendHelp(CommandSource source) {
        source.sendMessage(Component.text("[TextureUpdater] コマンド一覧:", NamedTextColor.YELLOW));
        source.sendMessage(Component.text("  /textureupdater status  - 現在のバージョンを表示", NamedTextColor.GRAY));
        source.sendMessage(Component.text("  /textureupdater reload  - GitHubから最新版を確認・取得", NamedTextColor.GRAY));
        source.sendMessage(Component.text("  /textureupdater force   - 全プレイヤーにパックを強制送信", NamedTextColor.GRAY));
    }

    @Override
    public boolean hasPermission(Invocation invocation) {
        return invocation.source().hasPermission("textureupdater.admin");
    }

    @Override
    public List<String> suggest(Invocation invocation) {
        if (invocation.arguments().length <= 1) {
            return List.of("status", "reload", "force");
        }
        return List.of();
    }
}
