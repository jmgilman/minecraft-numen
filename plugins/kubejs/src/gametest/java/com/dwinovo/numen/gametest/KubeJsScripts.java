package com.dwinovo.numen.gametest;

import dev.latvian.mods.kubejs.KubeJSPaths;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import net.minecraft.server.MinecraftServer;

/** 脚本用例共用:把要上线的那一份脚本(目录由 {@code numen.kubejs.scripts} 给,即 {@code plugins/kubejs/scripts})装进运行目录再重载。 */
final class KubeJsScripts {

    private KubeJsScripts() {
    }

    /** 把 {@code name} 拷进运行目录的 {@code kubejs/server_scripts},{@code /reload};用例接着等它的函数登记上。 */
    static void install(MinecraftServer server, String name) {
        try {
            Path source = Path.of(System.getProperty("numen.kubejs.scripts")).resolve(name);
            Path target = KubeJSPaths.SERVER_SCRIPTS.resolve(name);
            Files.createDirectories(target.getParent());
            Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), "reload");
    }
}
