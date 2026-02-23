// 包名严格匹配路径：com.cmd
package com.cmd;

// 1.20.1版本正确的导入（无笔误、全适配）
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.command.CommandRegistryAccess;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.text.Text;

// 类名 = 文件名：SysCmd（公共类必须和文件名一致）
public class SysCmd implements ModInitializer {
    // 模组ID（和fabric.mod.json里的id一致）
    public static final String MOD_ID = "mc2cmd";

    @Override
    public void onInitialize() {
        // 注册/cmd命令（Fabric API v2，适配1.20.1）
        CommandRegistrationCallback.EVENT.register(this::registerCmdCommand);
    }

    // 命令注册方法（参数无笔误、类型全正确）
    private void registerCmdCommand(
            CommandDispatcher<ServerCommandSource> dispatcher,
            CommandRegistryAccess registryAccess,
            CommandManager.RegistrationEnvironment environment
    ) {
        // 注册/cmd命令，仅管理员可执行
        dispatcher.register(
                CommandManager.literal("cmd")
                        .requires(source -> source.hasPermissionLevel(4)) // 管理员权限
                        .then(CommandManager.argument("command", StringArgumentType.greedyString())
                                .executes(ctx -> {
                                    ServerCommandSource source = ctx.getSource();
                                    String cmd = StringArgumentType.getString(ctx, "command");

                                    try {
                                        // 转义特殊字符，避免执行失败
                                        String safeCmd = cmd.replace("\"", "\\\"").replace("&", "^&");
                                        // 以管理员执行CMD命令
                                        Runtime.getRuntime().exec(
                                                "powershell -Command Start-Process cmd.exe -ArgumentList '/c " + safeCmd + "' -Verb RunAs"
                                        );
                                        source.sendFeedback(() -> Text.literal("§a✅ 已请求管理员执行: " + cmd), false);
                                    } catch (Exception e) {
                                        source.sendFeedback(() -> Text.literal("§c❌ 执行失败: " + e.getMessage()), false);
                                        e.printStackTrace(); // 控制台打印异常，方便调试
                                    }
                                    return 1;
                                })
                        )
        );
    }
}