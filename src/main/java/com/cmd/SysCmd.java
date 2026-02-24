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
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.UUID;

public class SysCmd implements ModInitializer {
    public static final String MOD_ID = "mc2cmd";
    public static final String MOD_VERSION = "1.10.0";

    // Logger 实例声明 - 新增这行
    private static final Logger LOGGER = LoggerFactory.getLogger(SysCmd.class);

    // 常量定义
    private static final String CMD_PREFIX = "cmd.exe /c ";
    private static final String PS_PREFIX = "powershell.exe -Command ";
    private static final int OUTPUT_LIMIT = 10000;
    private static final long RATE_LIMIT_MS = 1000;

    // 命令白名单（普通用户）
    private static final Set<String> CMD_WHITELIST = Set.of(
        "dir", "ipconfig", "ping", "tracert", "nslookup", "cls", "ver", "hostname",
        "echo", "type", "find", "more", "sort", "date", "time", "timeout", "pause",
        "tree", "cd", "path", "set"
    );

    private static final Set<String> PS_WHITELIST = Set.of(
        "Get-Process", "Get-Service", "Get-ComputerInfo", "Test-Connection",
        "Get-Date", "Get-Host", "Get-Content", "Select-Object", "Where-Object",
        "Foreach-Object", "Get-Location", "Set-Location", "Get-ChildItem",
        "Measure-Object", "Format-Table", "Format-List"
    );

    // 频率限制记录
    private final ConcurrentHashMap<UUID, Long> lastExecutionTime = new ConcurrentHashMap<>();

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
        // 普通 CMD 指令（权限0）
        // ======================
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

        // ======================
        // 管理员 CMD 指令（权限4）
        // ======================
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

        // ======================
        // 普通 PowerShell 指令（权限0）
        // ======================
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

        // ======================
        // 管理员 PowerShell 指令（权限4）
        // ======================
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

    /**
     * 执行命令的核心方法
     * @param source 命令源
     * @param cmd 要执行的命令
     * @param isPowerShell 是否为PowerShell命令
     * @param isAdmin 是否为管理员命令
     * @return 执行结果
     */
    private int executeCommand(ServerCommandSource source, String cmd, boolean isPowerShell, boolean isAdmin) {
        try {
            // 频率限制检查
            if (!checkRateLimit(source)) {
                source.sendFeedback(() -> Text.translatable("mc2cmd.rate.limit"), false);
                return 0;
            }

            // 普通用户白名单检查
            if (!isAdmin && !checkWhitelist(cmd, isPowerShell)) {
                source.sendFeedback(() -> Text.translatable("mc2cmd.whitelist.blocked"), false);
                return 0;
            }

            // 准备命令
            String safeCmd = sanitizeCommand(cmd);
            String fullCommand = (isPowerShell ? PS_PREFIX : CMD_PREFIX) + safeCmd;

            LOGGER.info("执行命令: {} - 玩家: {}", fullCommand, source.getName());

            // 构建进程
            ProcessBuilder processBuilder;
            if (isAdmin) {
                // 管理员权限：启动新进程
                if (isPowerShell) {
                    processBuilder = new ProcessBuilder(
                        "powershell", "-Command",
                        "Start-Process powershell.exe -ArgumentList '-Command \"" + safeCmd + "\"' -Verb RunAs"
                    );
                } else {
                    processBuilder = new ProcessBuilder(
                        "powershell", "-Command",
                        "Start-Process cmd.exe -ArgumentList '/c \"" + safeCmd + "\"' -Verb RunAs"
                    );
                }
                source.sendFeedback(() -> Text.translatable("mc2cmd.admin.request", cmd), false);
                return 1;
            } else {
                // 普通权限：直接执行
                processBuilder = new ProcessBuilder(
                    isPowerShell ? "powershell" : "cmd",
                    isPowerShell ? "-Command" : "/c",
                    safeCmd
                );
            }

            processBuilder.redirectErrorStream(true);
            Process process = processBuilder.start();

            // 读取输出
            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), "UTF-8"))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append("\n");

                    // 实时发送输出（避免消息过长）
                    if (output.length() >= 2000) {
                        sendOutput(source, output.toString());
                        output = new StringBuilder();
                    }
                }
            }

            // 发送剩余输出
            if (output.length() > 0) {
                sendOutput(source, output.toString());
            }

            // 等待进程完成（最多30秒）
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
            LOGGER.error("命令执行失败: {} - 错误: {}", cmd, e.getMessage(), e);
            source.sendFeedback(() -> Text.translatable("mc2cmd.error", e.getMessage()), false);
            return 0;
        }
    }

    /**
     * 检查频率限制
     */
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
            return true; // 如果无法获取玩家ID，不限制
        }
    }

    /**
     * 检查命令白名单
     */
    private boolean checkWhitelist(String cmd, boolean isPowerShell) {
        String baseCommand = cmd.trim().split("\\s+")[0].toLowerCase();

        if (isPowerShell) {
            // PowerShell 命令可能包含连字符
            return PS_WHITELIST.stream().anyMatch(allowed ->
                baseCommand.startsWith(allowed.toLowerCase().replace("-", "-"))
            );
        } else {
            return CMD_WHITELIST.contains(baseCommand);
        }
    }

    /**
     * 命令安全过滤
     */
    private String sanitizeCommand(String cmd) {
        return cmd.replace("\"", "\\\"")
            .replace("&", "^&")
            .replace("|", "^|")
            .replace("<", "^<")
            .replace(">", "^>")
            .replace("(", "^(")
            .replace(")", "^)");
    }

    /**
     * 发送输出到玩家
     */
    private void sendOutput(ServerCommandSource source, String output) {
        // 限制输出长度
        if (output.length() > OUTPUT_LIMIT) {
            output = output.substring(0, OUTPUT_LIMIT) +
                "\n... (输出已截断，最多显示" + OUTPUT_LIMIT + "字符)";
        }

        // 分行发送（避免单条消息过长）
        String[] lines = output.split("\n");
        StringBuilder buffer = new StringBuilder();
        int lineCount = 0;

        for (String line : lines) {
            buffer.append(line).append("\n");
            lineCount++;

            if (lineCount >= 10) { // 每10行发送一次
                String messageToSend = buffer.toString(); // 创建 effectively final 变量
                source.sendFeedback(() -> Text.literal("§e" + messageToSend), false);
                buffer = new StringBuilder();
                lineCount = 0;
            }
        }

        // 发送剩余内容
        if (buffer.length() > 0) {
            String finalMessage = buffer.toString(); // 创建 effectively final 变量
            source.sendFeedback(() -> Text.literal("§e" + finalMessage), false);
        }
    }
}
