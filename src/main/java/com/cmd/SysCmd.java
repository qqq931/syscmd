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

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.Arrays;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.UUID;

public class SysCmd implements ModInitializer {
    public static final String MOD_ID = "mc2cmd";
    public static final String MOD_VERSION = "1.10.0";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);
    
    private static final String CMD_PREFIX = "cmd.exe /c ";
    private static final String PS_PREFIX = "powershell.exe -Command ";
    private static final int OUTPUT_LIMIT = 10000;
    private static final long RATE_LIMIT_MS = 1000;
    
    // 白名单配置（设置为 true 以启用白名单）
    private static final boolean WHITELIST_ENABLED = true;
    
    private final ConcurrentHashMap<UUID, Long> lastExecutionTime = new ConcurrentHashMap<>();
    
    @Override
    public void onInitialize() {
        LOGGER.info("MC2CMD v{} started - CMD + PowerShell dual support", MOD_VERSION);
        CommandRegistrationCallback.EVENT.register(this::registerAllCommands);
    }

    private void registerAllCommands(
            CommandDispatcher<ServerCommandSource> dispatcher,
            CommandRegistryAccess registryAccess,
            CommandManager.RegistrationEnvironment environment
    ) {
        // 普通 CMD 指令（权限0）
        dispatcher.register(
            CommandManager.literal("cmd")
                .requires(source -> source.hasPermissionLevel(0))
                .then(CommandManager.argument("command", StringArgumentType.greedyString())
                    .executes(ctx -> {
                        ServerCommandSource source = ctx.getSource();
                        String cmd = StringArgumentType.getString(ctx, "command");
                        return executeCommand(source, cmd, false, false);
                    })
                )
        );

        // 管理员 CMD 指令（权限4）
        dispatcher.register(
            CommandManager.literal("cmdadmin")
                .requires(source -> source.hasPermissionLevel(4))
                .then(CommandManager.argument("command", StringArgumentType.greedyString())
                    .executes(ctx -> {
                        ServerCommandSource source = ctx.getSource();
                        String cmd = StringArgumentType.getString(ctx, "command");
                        return executeCommand(source, cmd, false, true);
                    })
                )
        );

        // 普通 PowerShell 指令（权限0）
        dispatcher.register(
            CommandManager.literal("ps")
                .requires(source -> source.hasPermissionLevel(0))
                .then(CommandManager.argument("command", StringArgumentType.greedyString())
                    .executes(ctx -> {
                        ServerCommandSource source = ctx.getSource();
                        String cmd = StringArgumentType.getString(ctx, "command");
                        return executeCommand(source, cmd, true, false);
                    })
                )
        );

        // 管理员 PowerShell 指令（权限4）
        dispatcher.register(
            CommandManager.literal("psadmin")
                .requires(source -> source.hasPermissionLevel(4))
                .then(CommandManager.argument("command", StringArgumentType.greedyString())
                    .executes(ctx -> {
                        ServerCommandSource source = ctx.getSource();
                        String cmd = StringArgumentType.getString(ctx, "command");
                        return executeCommand(source, cmd, true, true);
                    })
                )
        );
    }
    
    private int executeCommand(ServerCommandSource source, String cmd, boolean isPowerShell, boolean isAdmin) {
        try {
            // 白名单检查（仅对非管理员命令生效）
            if (!isAdmin && WHITELIST_ENABLED && !checkWhitelist(source)) {
                source.sendFeedback(() -> Text.translatable("mc2cmd.whitelist.blocked"), false);
                return 0;
            }
            
            if (!checkRateLimit(source)) {
                source.sendFeedback(() -> Text.translatable("mc2cmd.rate.limit"), false);
                return 0;
            }
            
            String safeCmd = sanitizeCommand(cmd);
            String fullCommand = (isPowerShell ? PS_PREFIX : CMD_PREFIX) + safeCmd;

            LOGGER.info("Executing command: {} - Player: {}", fullCommand, source.getName());

            ProcessBuilder processBuilder;
            if (isAdmin) {
                if (isPowerShell) {
                    processBuilder = new ProcessBuilder(
                        "powershell", "-Command",
                        "Start-Process powershell.exe -ArgumentList '-Command \\\"" + safeCmd + "\\\"' -Verb RunAs"
                    );
                } else {
                    processBuilder = new ProcessBuilder(
                        "powershell", "-Command",
                        "Start-Process cmd.exe -ArgumentList '/c \\\"" + safeCmd + "\\\"' -Verb RunAs"
                    );
                }
                source.sendFeedback(() -> Text.translatable("mc2cmd.admin.request", cmd), false);
                return 1;
            } else {
                processBuilder = new ProcessBuilder(isPowerShell ? "powershell" : "cmd",
                    isPowerShell ? "-Command" : "/c", safeCmd);
            }
            
            processBuilder.redirectErrorStream(true);
            Process process = processBuilder.start();
            
            // 动态检测系统编码（Windows CMD 默认 GBK，PowerShell 和 Linux 是 UTF-8）
            String encoding = "UTF-8";
            if (System.getProperty("os.name").toLowerCase().contains("win") && !isPowerShell) {
                encoding = "GBK"; // Windows CMD 默认 GBK 编码
            }
            
            // 读取输出（使用系统检测到的编码）
            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), encoding))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append("\n");
                    
                    if (output.length() >= 2000) {
                        sendOutput(source, output.toString());
                        output = new StringBuilder();
                    }
                }
            }
            
            if (output.length() > 0) {
                sendOutput(source, output.toString());
            }
            
            if (!process.waitFor(30, TimeUnit.SECONDS)) {
                process.destroy();
                source.sendFeedback(() -> Text.translatable("mc2cmd.timeout"), false);
                return 0;
            }
            
            int exitCode = process.exitValue();
            if (exitCode == 0) {
                source.sendFeedback(() -> Text.translatable("mc2cmd.success", cmd), false);
            } else {
                source.sendFeedback(() -> Text.translatable("mc2cmd.exit.code", exitCode), false);
            }
            
            return exitCode == 0 ? 1 : 0;
            
        } catch (Exception e) {
            LOGGER.error("Command execution failed: {} - Error: {}", cmd, e.getMessage(), e);
            source.sendFeedback(() -> Text.translatable("mc2cmd.error", e.getMessage()), false);
            return 0;
        }
    }
    
    private boolean checkWhitelist(ServerCommandSource source) {
        try {
            UUID playerId = source.getPlayer().getUuid();
            // 可以在这里添加具体的白名单检查逻辑
            // 例如：return whitelistUUIDs.contains(playerId);
            return true;
        } catch (Exception e) {
            return true;
        }
    }
    
    private boolean checkRateLimit(ServerCommandSource source) {
        try {
            UUID playerId = source.getPlayer().getUuid();
            long currentTime = System.currentTimeMillis();
            Long lastTime = lastExecutionTime.get(playerId);
            
            if (lastTime != null && (currentTime - lastTime) < RATE_LIMIT_MS) {
                return false;
            }
            
            lastExecutionTime.put(playerId, currentTime);
            return true;
        } catch (Exception e) {
            return true;
        }
    }
    
    private String sanitizeCommand(String cmd) {
        return cmd.replace("\"", "\\\"")
                   .replace("&", "^&")
                   .replace("|", "^|")
                   .replace("<", "^<")
                   .replace(">", "^>")
                   .replace("(", "^(")
                   .replace(")", "^)");
    }
    
    private void sendOutput(ServerCommandSource source, String output) {
        if (output.length() > OUTPUT_LIMIT) {
            output = output.substring(0, OUTPUT_LIMIT) + "\n... (Output truncated, max " + OUTPUT_LIMIT + " characters)";
        }
        
        String[] lines = output.split("\n");
        StringBuilder buffer = new StringBuilder();
        int lineCount = 0;
        
        for (String line : lines) {
            buffer.append(line).append("\n");
            lineCount++;
            
            if (lineCount >= 10) {
                String msg = buffer.toString();
                source.sendFeedback(() -> Text.literal("§e" + msg), false);
                buffer = new StringBuilder();
                lineCount = 0;
            }
        }
        
        if (buffer.length() > 0) {
            String msg = buffer.toString();
            source.sendFeedback(() -> Text.literal("§e" + msg), false);
        }
    }
}
