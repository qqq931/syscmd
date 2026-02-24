package com.cmd;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.command.CommandRegistryAccess;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.text.Text;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class SysCmd implements ModInitializer {
    public static final String MOD_ID = "mc2cmd";
    public static final String MOD_VERSION = "1.10.0";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitialize() {
        LOGGER.info("MC2CMD v{} 已启动 - CMD + PowerShell 双支持", MOD_VERSION);
        CommandRegistrationCallback.EVENT.register(this::registerAllCommands);
    }

    private void registerAllCommands(
            CommandDispatcher<ServerCommandSource> dispatcher,
            CommandRegistryAccess registryAccess,
            CommandManager.RegistrationEnvironment environment
    ) {

        // ======================
        // 原版 CMD 指令（不动）
        // ======================
        dispatcher.register(
            CommandManager.literal("cmd")
                .requires(source -> source.hasPermissionLevel(4))
                .then(CommandManager.argument("command", StringArgumentType.greedyString())
                    .executes(ctx -> {
                        ServerCommandSource source = ctx.getSource();
                        String cmd = StringArgumentType.getString(ctx, "command");
                        try {
                            String safeCmd = cmd.replace("\"", "\\\"").replace("&", "^&");
                            Runtime.getRuntime().exec("cmd.exe /c " + safeCmd);
                            source.sendFeedback(() -> Text.translatable("mc2cmd.cmd.success", cmd), false);
                        } catch (Exception e) {
                            source.sendFeedback(() -> Text.translatable("mc2cmd.cmd.error", e.getMessage()), false);
                            e.printStackTrace();
                        }
                        return 1;
                    })
                )
        );

        dispatcher.register(
            CommandManager.literal("cmdadmin")
                .requires(source -> source.hasPermissionLevel(4))
                .then(CommandManager.argument("command", StringArgumentType.greedyString())
                    .executes(ctx -> {
                        ServerCommandSource source = ctx.getSource();
                        String cmd = StringArgumentType.getString(ctx, "command");
                        try {
                            String safeCmd = cmd.replace("\"", "\\\"").replace("&", "^&");
                            Runtime.getRuntime().exec(
                                "powershell -Command Start-Process cmd.exe -ArgumentList '/c " + safeCmd + "' -Verb RunAs"
                            );
                            source.sendFeedback(() -> Text.translatable("mc2cmd.cmdadmin.success", cmd), false);
                        } catch (Exception e) {
                            source.sendFeedback(() -> Text.translatable("mc2cmd.cmdadmin.error", e.getMessage()), false);
                            e.printStackTrace();
                        }
                        return 1;
                    })
                )
        );

        // ======================
        // 新增：PowerShell 指令
        // ======================
        dispatcher.register(
            CommandManager.literal("ps")
                .requires(source -> source.hasPermissionLevel(4))
                .then(CommandManager.argument("command", StringArgumentType.greedyString())
                    .executes(ctx -> {
                        ServerCommandSource source = ctx.getSource();
                        String cmd = StringArgumentType.getString(ctx, "command");
                        try {
                            String safeCmd = cmd.replace("\"", "\\\"");
                            // 直接用 PowerShell 执行
                            Runtime.getRuntime().exec("powershell.exe -Command " + safeCmd);
                            // 直接复用你现有的多语言键，不用加新翻译！
                            source.sendFeedback(() -> Text.translatable("mc2cmd.cmd.success", cmd), false);
                        } catch (Exception e) {
                            source.sendFeedback(() -> Text.translatable("mc2cmd.cmd.error", e.getMessage()), false);
                            e.printStackTrace();
                        }
                        return 1;
                    })
                )
        );

        dispatcher.register(
            CommandManager.literal("psadmin")
                .requires(source -> source.hasPermissionLevel(4))
                .then(CommandManager.argument("command", StringArgumentType.greedyString())
                    .executes(ctx -> {
                        ServerCommandSource source = ctx.getSource();
                        String cmd = StringArgumentType.getString(ctx, "command");
                        try {
                            String safeCmd = cmd.replace("\"", "\\\"");
                            // 管理员 PowerShell
                            Runtime.getRuntime().exec(
                                "powershell -Command Start-Process powershell.exe -ArgumentList '-Command \"" + safeCmd + "\"' -Verb RunAs"
                            );
                            // 复用现有语言
                            source.sendFeedback(() -> Text.translatable("mc2cmd.cmdadmin.success", cmd), false);
                        } catch (Exception e) {
                            source.sendFeedback(() -> Text.translatable("mc2cmd.cmdadmin.error", e.getMessage()), false);
                            e.printStackTrace();
                        }
                        return 1;
                    })
                )
        );
    }
}