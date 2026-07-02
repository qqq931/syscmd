package com.cmd;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.api.EnvType;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.command.CommandRegistryAccess;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.text.ClickEvent;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.io.*;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.RuntimeMXBean;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class SysCmd implements ModInitializer {
    public static final String MOD_ID = "mc2cmd";

    // ===== 服务端检测标记 =====
    private static boolean DISABLED_ON_SERVER = false;

    // 命令历史记录（内存中，最多保存20条）
    private static final List<String> CMD_HISTORY = new ArrayList<>();
    private static final int MAX_HISTORY = 20;

    // ===== 危险指令二次确认系统 =====
    private static final Map<UUID, PendingConfirmation> PENDING_CONFIRMATIONS = new ConcurrentHashMap<>();
    private static final long CONFIRM_TIMEOUT_MS = 30_000; // 30秒

    // ===== PowerShell 路径缓存 =====
    private static String psExecutable = null; // 缓存检测到的 PowerShell 路径

    /**
     * 待确认操作的数据结构
     */
    private static class PendingConfirmation {
        final String command;
        final Runnable action;
        final long expireTime;

        PendingConfirmation(String command, Runnable action) {
            this.command = command;
            this.action = action;
            this.expireTime = System.currentTimeMillis() + CONFIRM_TIMEOUT_MS;
        }

        boolean isExpired() {
            return System.currentTimeMillis() > expireTime;
        }
    }

    @Override
    public void onInitialize() {
        if (FabricLoader.getInstance().getEnvironmentType() == EnvType.SERVER) {
            DISABLED_ON_SERVER = true;
            System.out.println("[MC2CMD] ⚠ 本模组仅限客户端使用，服务端安装无效。模组已禁用。");
            return;
        }

        // 检测 PowerShell 可用路径
        detectPowerShell();

        CommandRegistrationCallback.EVENT.register(this::registerCommands);
    }

    /**
     * 检测系统中可用的 PowerShell
     * 优先 pwsh.exe (PowerShell 7+)，回退 powershell.exe (Windows PowerShell 5.1)
     */
    private static void detectPowerShell() {
        // 优先检测 PowerShell 7+
        for (String candidate : new String[]{"pwsh.exe", "powershell.exe"}) {
            try {
                Process p = Runtime.getRuntime().exec(new String[]{candidate, "-NoProfile", "-Command", "echo ok"});
                String out = new String(p.getInputStream().readAllBytes()).trim();
                int code = p.waitFor();
                if (code == 0 && out.contains("ok")) {
                    psExecutable = candidate;
                    System.out.println("[MC2CMD] 检测到 PowerShell: " + candidate);
                    return;
                }
            } catch (Exception ignored) {}
        }
        // 都没找到，降级为 powershell.exe（执行时会报错提示用户）
        psExecutable = "powershell.exe";
        System.out.println("[MC2CMD] 未检测到 PowerShell，将使用默认路径: " + psExecutable);
    }

    /**
     * 获取当前系统的 PowerShell 可执行文件名
     */
    private static String getPsExec() {
        return psExecutable != null ? psExecutable : "powershell.exe";
    }

    private void registerCommands(
            CommandDispatcher<ServerCommandSource> dispatcher,
            CommandRegistryAccess registryAccess,
            CommandManager.RegistrationEnvironment environment
    ) {
        if (environment == CommandManager.RegistrationEnvironment.DEDICATED) {
            System.out.println("[MC2CMD] ⚠ 检测到专用服务端注册环境，命令注册已跳过。");
            return;
        }

        dispatcher.register(
                CommandManager.literal("cmd")
                        .requires(source -> source.hasPermissionLevel(4))
                        // ===== 基础命令 =====
                        .then(CommandManager.literal("help").executes(this::showHelp))
                        .then(CommandManager.literal("exec")
                                .then(CommandManager.argument("command", StringArgumentType.greedyString())
                                        .executes(ctx -> executeCommand(ctx, false))))
                        .then(CommandManager.literal("raw")
                                .then(CommandManager.argument("command", StringArgumentType.greedyString())
                                        .executes(ctx -> executeCommand(ctx, true))))

                        // ===== PowerShell 命令 =====
                        .then(CommandManager.literal("pwsh")
                                .then(CommandManager.argument("command", StringArgumentType.greedyString())
                                        .executes(ctx -> executePowerShell(ctx, false))))
                        .then(CommandManager.literal("pwsh-raw")
                                .then(CommandManager.argument("command", StringArgumentType.greedyString())
                                        .executes(ctx -> executePowerShell(ctx, true))))
                        .then(CommandManager.literal("pwsh-admin")
                                .then(CommandManager.argument("command", StringArgumentType.greedyString())
                                        .executes(this::executePowerShellAdmin)))
                        .then(CommandManager.literal("pwsh-info").executes(this::showPowerShellInfo))

                        // 兼容旧用法
                        .then(CommandManager.argument("command", StringArgumentType.greedyString())
                                .executes(ctx -> executeCommand(ctx, false)))

                        // ===== 系统信息 =====
                        .then(CommandManager.literal("sysinfo").executes(this::showSysInfo))
                        .then(CommandManager.literal("mem").executes(this::showMemory))
                        .then(CommandManager.literal("disk").executes(this::showDisk))
                        .then(CommandManager.literal("uptime").executes(this::showUptime))
                        .then(CommandManager.literal("env")
                                .executes(this::showAllEnv)
                                .then(CommandManager.argument("key", StringArgumentType.word())
                                        .executes(this::showEnvByKey)))
                        .then(CommandManager.literal("net").executes(this::showNetwork))
                        .then(CommandManager.literal("ping")
                                .then(CommandManager.argument("host", StringArgumentType.word())
                                        .executes(this::pingHost)))

                        // ===== 进程管理 =====
                        .then(CommandManager.literal("ps")
                                .executes(ctx -> listProcesses(ctx, null))
                                .then(CommandManager.argument("filter", StringArgumentType.greedyString())
                                        .executes(ctx -> listProcesses(ctx, StringArgumentType.getString(ctx, "filter")))))
                        .then(CommandManager.literal("kill")
                                .then(CommandManager.argument("pid", IntegerArgumentType.integer(1))
                                        .executes(this::killProcessConfirm)))

                        // ===== 文件操作 =====
                        .then(CommandManager.literal("ls")
                                .executes(ctx -> listDir(ctx, "."))
                                .then(CommandManager.argument("path", StringArgumentType.greedyString())
                                        .executes(ctx -> listDir(ctx, StringArgumentType.getString(ctx, "path")))))
                        .then(CommandManager.literal("cat")
                                .then(CommandManager.argument("path", StringArgumentType.greedyString())
                                        .executes(this::readFile)))
                        .then(CommandManager.literal("write")
                                .then(CommandManager.argument("path", StringArgumentType.word())
                                        .then(CommandManager.argument("content", StringArgumentType.greedyString())
                                                .executes(this::writeFile))))
                        .then(CommandManager.literal("del")
                                .then(CommandManager.argument("path", StringArgumentType.greedyString())
                                        .executes(this::deleteFileConfirm)))
                        .then(CommandManager.literal("mkdir")
                                .then(CommandManager.argument("path", StringArgumentType.greedyString())
                                        .executes(this::makeDir)))
                        .then(CommandManager.literal("find")
                                .then(CommandManager.argument("path", StringArgumentType.word())
                                        .then(CommandManager.argument("pattern", StringArgumentType.greedyString())
                                                .executes(ctx -> findFiles(ctx,
                                                        StringArgumentType.getString(ctx, "path"),
                                                        StringArgumentType.getString(ctx, "pattern"))))))

                        // ===== 实用工具 =====
                        .then(CommandManager.literal("open")
                                .then(CommandManager.argument("target", StringArgumentType.greedyString())
                                        .executes(this::openTarget)))
                        .then(CommandManager.literal("clip")
                                .then(CommandManager.argument("text", StringArgumentType.greedyString())
                                        .executes(this::copyToClip)))
                        .then(CommandManager.literal("echo")
                                .then(CommandManager.argument("text", StringArgumentType.greedyString())
                                        .executes(this::echoText)))
                        .then(CommandManager.literal("shutdown")
                                .executes(ctx -> scheduleShutdownConfirm(ctx, 60))
                                .then(CommandManager.argument("seconds", IntegerArgumentType.integer(0, 3600))
                                        .executes(ctx -> scheduleShutdownConfirm(ctx, IntegerArgumentType.getInteger(ctx, "seconds")))))
                        .then(CommandManager.literal("cancel-shutdown").executes(this::cancelShutdown))
                        .then(CommandManager.literal("restart")
                                .executes(ctx -> scheduleRestartConfirm(ctx, 60))
                                .then(CommandManager.argument("seconds", IntegerArgumentType.integer(0, 3600))
                                        .executes(ctx -> scheduleRestartConfirm(ctx, IntegerArgumentType.getInteger(ctx, "seconds")))))

                        // ===== 危险指令确认系统 =====
                        .then(CommandManager.literal("confirm").executes(this::confirmDanger))
                        .then(CommandManager.literal("cancel").executes(this::cancelDanger))

                        // ===== 历史记录 =====
                        .then(CommandManager.literal("history").executes(this::showHistory))
                        .then(CommandManager.literal("clear-history").executes(this::clearHistory))
        );
    }

    // ========== 帮助 ==========
    private int showHelp(CommandContext<ServerCommandSource> ctx) {
        send(ctx, "§6╔══════ MC2CMD v1.2 帮助 ══════╗");
        send(ctx, "§e▌ CMD 命令执行");
        send(ctx, "  §f/cmd exec <命令> §7- 安全执行（自动转义）");
        send(ctx, "  §f/cmd raw <命令> §7- 原样执行");
        send(ctx, "§e▌ PowerShell 命令执行");
        send(ctx, "  §f/cmd pwsh <命令> §7- PowerShell 执行（推荐）");
        send(ctx, "  §f/cmd pwsh-raw <命令> §7- PS原样执行（不编码）");
        send(ctx, "  §f/cmd pwsh-admin <命令> §7- §c⚠管理员§7执行PS(触发UAC)");
        send(ctx, "  §f/cmd pwsh-info §7- 查看PS版本信息");
        send(ctx, "§e▌ 系统信息");
        send(ctx, "  §f/cmd sysinfo §7- 系统总览");
        send(ctx, "  §f/cmd mem §7- 内存详情");
        send(ctx, "  §f/cmd disk §7- 磁盘空间");
        send(ctx, "  §f/cmd uptime §7- 运行时间");
        send(ctx, "  §f/cmd net §7- 网络信息");
        send(ctx, "  §f/cmd ping <host> §7- 测试连通");
        send(ctx, "  §f/cmd env [key] §7- 环境变量");
        send(ctx, "§e▌ 进程管理");
        send(ctx, "  §f/cmd ps [关键词] §7- 进程列表");
        send(ctx, "  §f/cmd kill <pid> §7- §c⚠需确认§7终止进程");
        send(ctx, "§e▌ 文件操作");
        send(ctx, "  §f/cmd ls [路径] §7- 列目录");
        send(ctx, "  §f/cmd cat <路径> §7- 读文件");
        send(ctx, "  §f/cmd write <路径> <内容> §7- 写文件");
        send(ctx, "  §f/cmd del <路径> §7- §c⚠需确认§7删文件");
        send(ctx, "  §f/cmd mkdir <路径> §7- 建目录");
        send(ctx, "  §f/cmd find <路径> <关键词> §7- 搜文件");
        send(ctx, "§e▌ 实用工具");
        send(ctx, "  §f/cmd open <路径/URL> §7- 打开文件或网页");
        send(ctx, "  §f/cmd clip <文本> §7- 复制到剪贴板");
        send(ctx, "  §f/cmd echo <文本> §7- 输出文本");
        send(ctx, "  §f/cmd shutdown [秒] §7- §c⚠需确认§7定时关机");
        send(ctx, "  §f/cmd restart [秒] §7- §c⚠需确认§7定时重启");
        send(ctx, "  §f/cmd cancel-shutdown §7- 取消关机");
        send(ctx, "§e▌ 安全确认");
        send(ctx, "  §f/cmd confirm §7- §a确认§7执行危险操作");
        send(ctx, "  §f/cmd cancel §7- §c取消§7待确认操作");
        send(ctx, "§e▌ 历史");
        send(ctx, "  §f/cmd history §7- 查看命令历史");
        send(ctx, "  §f/cmd clear-history §7- 清空历史");
        send(ctx, "§c▌ 注意：本模组仅限客户端，服务端安装无效");
        send(ctx, "§6╚════════════════════════════╝");
        return 1;
    }

    // ========== CMD 命令执行 ==========
    private int executeCommand(CommandContext<ServerCommandSource> ctx, boolean raw) {
        String cmd = StringArgumentType.getString(ctx, "command");
        addToHistory("cmd: " + cmd);

        try {
            String execCmd = raw ? cmd : escapeWindowsCmd(cmd);
            Process process = Runtime.getRuntime().exec(new String[]{"cmd.exe", "/c", execCmd});
            int exitCode = process.waitFor();

            String output = new String(process.getInputStream().readAllBytes()).trim();
            String error = new String(process.getErrorStream().readAllBytes()).trim();

            if (exitCode == 0) {
                send(ctx, "§a✅ 执行成功 (exit 0)");
            } else {
                send(ctx, "§c⚠ 退出码: " + exitCode);
            }
            if (!output.isEmpty()) send(ctx, "§f" + truncate(output, 800));
            if (!error.isEmpty()) send(ctx, "§c" + truncate(error, 400));

        } catch (Exception e) {
            send(ctx, "§c❌ 执行失败: " + e.getMessage());
        }
        return 1;
    }

    // ========== PowerShell 命令执行 ==========
    /**
     * 执行 PowerShell 命令
     * @param ctx 命令上下文
     * @param raw true=原样传递命令字符串，false=使用 EncodedCommand（推荐，避免转义问题）
     */
    private int executePowerShell(CommandContext<ServerCommandSource> ctx, boolean raw) {
        String command = StringArgumentType.getString(ctx, "command");
        addToHistory("pwsh: " + command);

        // PowerShell 危险命令检查
        if (isPsDangerous(command)) {
            send(ctx, "§c❌ 该 PowerShell 命令被安全策略阻止: " + truncate(command, 60));
            return 1;
        }

        try {
            String psExec = getPsExec();

            if (raw) {
                // 原样模式：直接用 -Command 传递（可能有引号问题）
                Process process = Runtime.getRuntime().exec(new String[]{
                        psExec, "-NoProfile", "-Command", command
                });
                int exitCode = process.waitFor();
                String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
                String error = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8).trim();

                if (exitCode == 0) {
                    send(ctx, "§a✅ PowerShell 执行成功 (exit 0)");
                } else {
                    send(ctx, "§e⚠ PowerShell 退出码: " + exitCode);
                }
                if (!output.isEmpty()) send(ctx, "§f" + truncate(output, 800));
                if (!error.isEmpty()) send(ctx, "§c" + truncate(error, 400));
            } else {
                // 推荐模式：使用 EncodedCommand，Base64(UTF-16LE) 避免所有转义问题
                String encoded = Base64.getEncoder().encodeToString(command.getBytes(StandardCharsets.UTF_16LE));
                Process process = Runtime.getRuntime().exec(new String[]{
                        psExec, "-NoProfile", "-EncodedCommand", encoded
                });
                int exitCode = process.waitFor();
                String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
                String error = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8).trim();

                if (exitCode == 0) {
                    send(ctx, "§a✅ PowerShell 执行成功 (exit 0)");
                } else {
                    send(ctx, "§e⚠ PowerShell 退出码: " + exitCode);
                }
                if (!output.isEmpty()) send(ctx, "§f" + truncate(output, 800));
                if (!error.isEmpty()) send(ctx, "§c" + truncate(error, 400));
            }
        } catch (Exception e) {
            send(ctx, "§c❌ PowerShell 执行失败: " + e.getMessage());
        }
        return 1;
    }

    // ========== PowerShell 管理员执行（触发 UAC）==========
    private int executePowerShellAdmin(CommandContext<ServerCommandSource> ctx) {
        String command = StringArgumentType.getString(ctx, "command");
        addToHistory("pwsh-admin: " + command);

        if (isPsDangerous(command)) {
            send(ctx, "§c❌ 该 PowerShell 命令被安全策略阻止: " + truncate(command, 60));
            return 1;
        }

        send(ctx, "§e⚡ 正在以管理员权限执行 PowerShell，请在弹出的 UAC 窗口中确认...");

        try {
            String psExec = getPsExec();
            // 使用 PowerShell Start-Process -Verb RunAs 触发 UAC 提升
            // 将命令写入临时脚本，通过 -Verb RunAs 以管理员权限运行
            // 输出重定向到临时文件，便于结果回显（管理员窗口独立，无法直接获取输出）
            File tmpScript = File.createTempFile("mc2cmd_ps_", ".ps1");
            tmpScript.deleteOnExit();
            // 写入脚本内容：执行命令 + 暂停（让用户看到结果）
            String scriptContent = command + "\nWrite-Host \"`n[MC2CMD] 命令执行完毕，按任意键关闭窗口...\"\n$null = $Host.UI.RawUI.ReadKey(\"NoEcho,IncludeKeyDown\")\n";
            Files.writeString(tmpScript.toPath(), scriptContent, StandardCharsets.UTF_8);

            // 使用 EncodedCommand 调用 Start-Process 来启动带 UAC 提升的 PowerShell
            String startProcessCmd = String.format(
                    "Start-Process %s -ArgumentList '-NoProfile','-ExecutionPolicy','Bypass','-File','%s' -Verb RunAs",
                    psExec,
                    tmpScript.getAbsolutePath().replace("'", "''")
            );
            String encodedStart = Base64.getEncoder().encodeToString(startProcessCmd.getBytes(StandardCharsets.UTF_16LE));

            Runtime.getRuntime().exec(new String[]{
                    psExec, "-NoProfile", "-EncodedCommand", encodedStart
            });

            send(ctx, "§a✅ 管理员 PowerShell 窗口已启动");
            send(ctx, "§7脚本: " + tmpScript.getAbsolutePath());
        } catch (Exception e) {
            send(ctx, "§c❌ 管理员 PowerShell 执行失败: " + e.getMessage());
        }
        return 1;
    }

    // ========== PowerShell 版本信息 ==========
    private int showPowerShellInfo(CommandContext<ServerCommandSource> ctx) {
        send(ctx, "§6══ PowerShell 信息 ══");
        send(ctx, "§e 当前使用: §f" + getPsExec());

        try {
            String psExec = getPsExec();
            Process p = Runtime.getRuntime().exec(new String[]{
                    psExec, "-NoProfile", "-Command", "$PSVersionTable.PSVersion.ToString()"
            });
            String version = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            p.waitFor();
            if (!version.isEmpty()) {
                String label = psExec.equals("pwsh.exe") ? "PowerShell 7+" : "Windows PowerShell 5.1";
                send(ctx, "§e 版本: §f" + version + " §7(" + label + ")");
            }
        } catch (Exception e) {
            send(ctx, "§c 获取版本失败: " + e.getMessage());
        }

        send(ctx, "§e 推荐用法:");
        send(ctx, "  §f/cmd pwsh <命令> §7- 安全执行（EncodedCommand）");
        send(ctx, "  §f/cmd pwsh-raw <命令> §7- 原样执行");
        send(ctx, "  §f/cmd pwsh-admin <命令> §7- 管理员执行(UAC)");
        send(ctx, "§7提示: pwsh 模式使用 Base64 编码，无需担心引号/特殊字符转义问题");
        return 1;
    }

    // ========== PowerShell 安全检查 ==========
    /**
     * 检查 PowerShell 命令是否包含危险操作
     */
    private boolean isPsDangerous(String command) {
        String lower = command.toLowerCase().trim();
        // 始终禁止的 PowerShell 危险 cmdlet 和模式
        String[] blocked = {
                "remove-item",       // 删除文件/目录
                "format-volume",     // 格式化卷
                "format-disk",       // 格式化磁盘
                "clear-disk",        // 清除磁盘
                "remove-itemproperty",
                "set-itemproperty",  // 修改注册表属性（高风险）
                "new-itemproperty",
                "remove-Item -recurse",
                "shutdown.exe",
                "bcdedit",
                "diskpart",
                "cipher /w",        // 擦除空闲空间
        };
        for (String b : blocked) {
            if (lower.contains(b)) return true;
        }
        return false;
    }

    // ========== 系统信息 ==========
    private int showSysInfo(CommandContext<ServerCommandSource> ctx) {
        send(ctx, "§6╔══════ 系统信息 ══════╗");
        send(ctx, "§e OS: §f" + System.getProperty("os.name") + " " + System.getProperty("os.version"));
        send(ctx, "§e 架构: §f" + System.getProperty("os.arch"));
        send(ctx, "§e 主机名: §f" + safeGet(() -> InetAddress.getLocalHost().getHostName()));
        send(ctx, "§e CPU核心: §f" + Runtime.getRuntime().availableProcessors());
        send(ctx, "§e Java: §f" + System.getProperty("java.version"));
        send(ctx, "§e 用户: §f" + System.getProperty("user.name"));
        send(ctx, "§e 工作目录: §f" + System.getProperty("user.dir"));
        send(ctx, "§e PowerShell: §f" + getPsExec());
        long maxMem = Runtime.getRuntime().maxMemory() / 1024 / 1024;
        long totalMem = Runtime.getRuntime().totalMemory() / 1024 / 1024;
        long freeMem = Runtime.getRuntime().freeMemory() / 1024 / 1024;
        send(ctx, "§e JVM内存: §f" + (totalMem - freeMem) + "MB / " + maxMem + "MB");
        send(ctx, "§6╚════════════════════╝");
        return 1;
    }

    private int showMemory(CommandContext<ServerCommandSource> ctx) {
        MemoryMXBean mem = ManagementFactory.getMemoryMXBean();
        send(ctx, "§6══ 内存详情 ══");
        send(ctx, "§e 堆内存使用: §f" + mem.getHeapMemoryUsage().getUsed() / 1024 / 1024 + "MB");
        send(ctx, "§e 堆内存最大: §f" + mem.getHeapMemoryUsage().getMax() / 1024 / 1024 + "MB");
        send(ctx, "§e 堆内存提交: §f" + mem.getHeapMemoryUsage().getCommitted() / 1024 / 1024 + "MB");
        send(ctx, "§e 非堆内存: §f" + mem.getNonHeapMemoryUsage().getUsed() / 1024 / 1024 + "MB");

        try {
            Process p = Runtime.getRuntime().exec(new String[]{"cmd.exe", "/c", "wmic OS get FreePhysicalMemory,TotalVisibleMemorySize /Value"});
            String out = new String(p.getInputStream().readAllBytes()).trim();
            for (String line : out.split("\n")) {
                if (line.contains("TotalVisibleMemorySize")) {
                    long total = Long.parseLong(line.split("=")[1].trim()) / 1024;
                    send(ctx, "§e 系统总内存: §f" + total + "MB");
                }
                if (line.contains("FreePhysicalMemory")) {
                    long free = Long.parseLong(line.split("=")[1].trim()) / 1024;
                    send(ctx, "§e 系统空闲: §f" + free + "MB");
                }
            }
        } catch (Exception ignored) {}
        return 1;
    }

    private int showDisk(CommandContext<ServerCommandSource> ctx) {
        send(ctx, "§6══ 磁盘空间 ══");
        for (File root : File.listRoots()) {
            String path = root.getAbsolutePath();
            long total = root.getTotalSpace() / 1024 / 1024;
            long free = root.getFreeSpace() / 1024 / 1024;
            long usable = root.getUsableSpace() / 1024 / 1024;
            int usedPct = total > 0 ? (int) ((total - free) * 100 / total) : 0;
            String color = usedPct > 90 ? "§c" : usedPct > 70 ? "§e" : "§a";
            send(ctx, color + path + " §f| 总: " + formatSize(total) + " | 可用: " + formatSize(usable) + " | 已用: " + usedPct + "%");
        }
        return 1;
    }

    private int showUptime(CommandContext<ServerCommandSource> ctx) {
        RuntimeMXBean rb = ManagementFactory.getRuntimeMXBean();
        long uptimeMs = rb.getUptime();
        long seconds = uptimeMs / 1000;
        long days = seconds / 86400;
        long hours = (seconds % 86400) / 3600;
        long mins = (seconds % 3600) / 60;
        send(ctx, "§e JVM运行时间: §f" + days + "天 " + hours + "小时 " + mins + "分钟");
        send(ctx, "§e 启动时间: §f" + new Date(rb.getStartTime()));
        return 1;
    }

    private int showAllEnv(CommandContext<ServerCommandSource> ctx) {
        send(ctx, "§6══ 环境变量（部分） ══");
        Map<String, String> env = System.getenv();
        int count = 0;
        for (Map.Entry<String, String> entry : env.entrySet()) {
            if (count++ >= 30) {
                send(ctx, "§7... 共 " + env.size() + " 项，使用 /cmd env <key> 查看指定项");
                break;
            }
            send(ctx, "§e" + entry.getKey() + "§f=" + truncate(entry.getValue(), 80));
        }
        return 1;
    }

    private int showEnvByKey(CommandContext<ServerCommandSource> ctx) {
        String key = StringArgumentType.getString(ctx, "key");
        String value = System.getenv(key);
        if (value == null) {
            send(ctx, "§c未找到环境变量: " + key);
        } else {
            send(ctx, "§e" + key + "§f=" + value);
        }
        return 1;
    }

    private int showNetwork(CommandContext<ServerCommandSource> ctx) {
        send(ctx, "§6══ 网络信息 ══");
        try {
            send(ctx, "§e 主机名: §f" + InetAddress.getLocalHost().getHostName());
            send(ctx, "§e 本地IP: §f" + InetAddress.getLocalHost().getHostAddress());
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces.hasMoreElements()) {
                NetworkInterface ni = interfaces.nextElement();
                if (ni.isLoopback() || !ni.isUp()) continue;
                Enumeration<InetAddress> addrs = ni.getInetAddresses();
                while (addrs.hasMoreElements()) {
                    InetAddress addr = addrs.nextElement();
                    if (addr.getAddress().length == 4) {
                        send(ctx, "§e " + ni.getDisplayName() + ": §f" + addr.getHostAddress());
                    }
                }
            }
        } catch (Exception e) {
            send(ctx, "§c获取网络信息失败: " + e.getMessage());
        }
        return 1;
    }

    private int pingHost(CommandContext<ServerCommandSource> ctx) {
        String host = StringArgumentType.getString(ctx, "host");
        send(ctx, "§e正在 ping " + host + " ...");
        try {
            InetAddress addr = InetAddress.getByName(host);
            long start = System.currentTimeMillis();
            boolean reachable = addr.isReachable(5000);
            long elapsed = System.currentTimeMillis() - start;
            if (reachable) {
                send(ctx, "§a✅ " + host + " (" + addr.getHostAddress() + ") 可达，延迟 <" + elapsed + "ms");
            } else {
                send(ctx, "§c❌ " + host + " 不可达（5秒超时）");
            }
        } catch (Exception e) {
            send(ctx, "§c❌ Ping 失败: " + e.getMessage());
        }
        return 1;
    }

    // ========== 进程管理 ==========
    private int listProcesses(CommandContext<ServerCommandSource> ctx, String filter) {
        try {
            Process p = Runtime.getRuntime().exec(new String[]{"tasklist", "/fo", "csv", "/nh"});
            String output = new String(p.getInputStream().readAllBytes()).trim();
            String[] lines = output.split("\n");
            send(ctx, "§6══ 进程列表 ══" + (filter != null ? " §e[过滤: " + filter + "]" : ""));
            int count = 0;
            int shown = 0;
            for (String line : lines) {
                if (filter != null && !line.toLowerCase().contains(filter.toLowerCase())) continue;
                if (shown++ >= 20) {
                    send(ctx, "§7... 共 " + (filter != null ? "匹配 " : "") + lines.length + " 个进程，仅显示前20");
                    break;
                }
                String[] parts = line.replace("\"", "").split(",");
                if (parts.length >= 3) {
                    send(ctx, "§f" + parts[0] + " §7(PID:" + parts[1] + " 内存:" + parts[parts.length - 1] + ")");
                } else {
                    send(ctx, "§f" + truncate(line, 80));
                }
                count++;
            }
            if (count == 0) send(ctx, "§7无匹配进程");
        } catch (Exception e) {
            send(ctx, "§c获取进程列表失败: " + e.getMessage());
        }
        return 1;
    }

    // ========== 危险指令：kill 需要二次确认 ==========
    private int killProcessConfirm(CommandContext<ServerCommandSource> ctx) {
        int pid = IntegerArgumentType.getInteger(ctx, "pid");
        UUID playerUUID = ctx.getSource().getPlayer() != null
                ? ctx.getSource().getPlayer().getUuid()
                : UUID.nameUUIDFromBytes("console".getBytes());

        String desc = "终止进程 PID " + pid;
        PENDING_CONFIRMATIONS.put(playerUUID, new PendingConfirmation(desc, () -> {
            try {
                Process p = Runtime.getRuntime().exec(new String[]{"taskkill", "/PID", String.valueOf(pid), "/F"});
                String output = new String(p.getInputStream().readAllBytes()).trim();
                String error = new String(p.getErrorStream().readAllBytes()).trim();
                if (output.contains("成功") || output.contains("SUCCESS") || p.waitFor() == 0) {
                    sendSys(ctx, "§a✅ 已终止进程 PID " + pid);
                } else {
                    sendSys(ctx, "§c⚠ " + (output.isEmpty() ? error : output));
                }
            } catch (Exception e) {
                sendSys(ctx, "§c❌ 终止失败: " + e.getMessage());
            }
        }));

        send(ctx, "§c⚠ 危险操作确认 ⚠");
        send(ctx, "§e即将执行: §f" + desc);
        send(ctx, "§e请在 §c30秒§e 内输入 §a/cmd confirm §e确认执行");
        send(ctx, "§7输入 /cmd cancel 取消操作");
        return 1;
    }

    // ========== 危险指令：del 需要二次确认 ==========
    private int deleteFileConfirm(CommandContext<ServerCommandSource> ctx) {
        String path = StringArgumentType.getString(ctx, "path");
        UUID playerUUID = ctx.getSource().getPlayer() != null
                ? ctx.getSource().getPlayer().getUuid()
                : UUID.nameUUIDFromBytes("console".getBytes());

        Path target = Paths.get(path).toAbsolutePath().normalize();
        String desc = "删除: " + target;

        PENDING_CONFIRMATIONS.put(playerUUID, new PendingConfirmation(desc, () -> {
            try {
                if (!Files.exists(target)) {
                    sendSys(ctx, "§c文件不存在: " + target);
                    return;
                }
                if (Files.isDirectory(target)) {
                    Files.walk(target).sorted(java.util.Comparator.reverseOrder()).map(Path::toFile).forEach(File::delete);
                    sendSys(ctx, "§a✅ 已删除目录: " + target);
                } else {
                    Files.delete(target);
                    sendSys(ctx, "§a✅ 已删除: " + target);
                }
            } catch (Exception e) {
                sendSys(ctx, "§c❌ 删除失败: " + e.getMessage());
            }
        }));

        send(ctx, "§c⚠ 危险操作确认 ⚠");
        send(ctx, "§e即将执行: §f" + desc);
        send(ctx, "§e请在 §c30秒§e 内输入 §a/cmd confirm §e确认执行");
        send(ctx, "§7输入 /cmd cancel 取消操作");
        return 1;
    }

    // ========== 危险指令：shutdown 需要二次确认 ==========
    private int scheduleShutdownConfirm(CommandContext<ServerCommandSource> ctx, int seconds) {
        UUID playerUUID = ctx.getSource().getPlayer() != null
                ? ctx.getSource().getPlayer().getUuid()
                : UUID.nameUUIDFromBytes("console".getBytes());

        String desc = "系统将在 " + seconds + " 秒后关机";

        PENDING_CONFIRMATIONS.put(playerUUID, new PendingConfirmation(desc, () -> {
            try {
                Runtime.getRuntime().exec(new String[]{"shutdown", "/s", "/t", String.valueOf(seconds)});
                sendSys(ctx, "§c⏰ 系统将在 " + seconds + " 秒后关机");
                sendSys(ctx, "§7使用 /cmd cancel-shutdown 取消");
            } catch (Exception e) {
                sendSys(ctx, "§c❌ " + e.getMessage());
            }
        }));

        send(ctx, "§c⚠ 危险操作确认 ⚠");
        send(ctx, "§e即将执行: §f" + desc);
        send(ctx, "§e请在 §c30秒§e 内输入 §a/cmd confirm §e确认执行");
        send(ctx, "§7输入 /cmd cancel 取消操作");
        return 1;
    }

    // ========== 危险指令：restart 需要二次确认 ==========
    private int scheduleRestartConfirm(CommandContext<ServerCommandSource> ctx, int seconds) {
        UUID playerUUID = ctx.getSource().getPlayer() != null
                ? ctx.getSource().getPlayer().getUuid()
                : UUID.nameUUIDFromBytes("console".getBytes());

        String desc = "系统将在 " + seconds + " 秒后重启";

        PENDING_CONFIRMATIONS.put(playerUUID, new PendingConfirmation(desc, () -> {
            try {
                Runtime.getRuntime().exec(new String[]{"shutdown", "/r", "/t", String.valueOf(seconds)});
                sendSys(ctx, "§c⏰ 系统将在 " + seconds + " 秒后重启");
                sendSys(ctx, "§7使用 /cmd cancel-shutdown 取消");
            } catch (Exception e) {
                sendSys(ctx, "§c❌ " + e.getMessage());
            }
        }));

        send(ctx, "§c⚠ 危险操作确认 ⚠");
        send(ctx, "§e即将执行: §f" + desc);
        send(ctx, "§e请在 §c30秒§e 内输入 §a/cmd confirm §e确认执行");
        send(ctx, "§7输入 /cmd cancel 取消操作");
        return 1;
    }

    // ========== 确认执行危险操作 ==========
    private int confirmDanger(CommandContext<ServerCommandSource> ctx) {
        UUID playerUUID = ctx.getSource().getPlayer() != null
                ? ctx.getSource().getPlayer().getUuid()
                : UUID.nameUUIDFromBytes("console".getBytes());

        PendingConfirmation pending = PENDING_CONFIRMATIONS.get(playerUUID);
        if (pending == null) {
            send(ctx, "§7当前没有待确认的危险操作");
            return 1;
        }

        if (pending.isExpired()) {
            PENDING_CONFIRMATIONS.remove(playerUUID);
            send(ctx, "§c⏰ 确认已超时（30秒），操作已自动取消");
            return 1;
        }

        String desc = pending.command;
        PENDING_CONFIRMATIONS.remove(playerUUID);
        send(ctx, "§a✅ 已确认，正在执行: " + desc);
        pending.action.run();
        return 1;
    }

    // ========== 取消危险操作 ==========
    private int cancelDanger(CommandContext<ServerCommandSource> ctx) {
        UUID playerUUID = ctx.getSource().getPlayer() != null
                ? ctx.getSource().getPlayer().getUuid()
                : UUID.nameUUIDFromBytes("console".getBytes());

        PendingConfirmation pending = PENDING_CONFIRMATIONS.remove(playerUUID);
        if (pending == null) {
            send(ctx, "§7当前没有待确认的操作");
        } else {
            send(ctx, "§a✅ 已取消操作: " + pending.command);
        }
        return 1;
    }

    private int cancelShutdown(CommandContext<ServerCommandSource> ctx) {
        try {
            Runtime.getRuntime().exec(new String[]{"shutdown", "/a"});
            send(ctx, "§a✅ 已取消关机计划");
        } catch (Exception e) {
            send(ctx, "§c❌ " + e.getMessage());
        }
        return 1;
    }

    // ========== 文件操作 ==========
    private int listDir(CommandContext<ServerCommandSource> ctx, String path) {
        try {
            Path dir = Paths.get(path).toAbsolutePath().normalize();
            if (!Files.isDirectory(dir)) {
                send(ctx, "§c不是有效目录: " + dir);
                return 1;
            }
            send(ctx, "§6══ 目录: " + dir + " ══");
            try (Stream<Path> stream = Files.list(dir)) {
                List<Path> entries = stream.sorted().collect(Collectors.toList());
                for (Path entry : entries) {
                    String name = entry.getFileName().toString();
                    if (Files.isDirectory(entry)) {
                        send(ctx, "§e📁 " + name + "/");
                    } else {
                        long size = Files.size(entry);
                        send(ctx, "§f📄 " + name + " §7(" + formatSize(size / 1024) + ")");
                    }
                }
                send(ctx, "§7共 " + entries.size() + " 项");
            }
        } catch (Exception e) {
            send(ctx, "§c❌ " + e.getMessage());
        }
        return 1;
    }

    private int readFile(CommandContext<ServerCommandSource> ctx) {
        String path = StringArgumentType.getString(ctx, "path");
        try {
            Path file = Paths.get(path).toAbsolutePath().normalize();
            if (!Files.exists(file)) {
                send(ctx, "§c文件不存在: " + file);
                return 1;
            }
            if (Files.size(file) > 100 * 1024) {
                send(ctx, "§c文件过大（>" + formatSize(Files.size(file) / 1024) + "），仅显示前50KB");
            }
            List<String> lines = Files.readAllLines(file);
            send(ctx, "§6══ " + file.getFileName() + " (" + lines.size() + " 行) ══");
            int limit = Math.min(lines.size(), 100);
            for (int i = 0; i < limit; i++) {
                send(ctx, "§7" + String.format("%3d", i + 1) + "│§f " + truncate(lines.get(i), 100));
            }
            if (lines.size() > limit) {
                send(ctx, "§7... 仅显示前" + limit + "行，共 " + lines.size() + " 行");
            }
        } catch (Exception e) {
            send(ctx, "§c❌ 读取失败: " + e.getMessage());
        }
        return 1;
    }

    private int writeFile(CommandContext<ServerCommandSource> ctx) {
        String path = StringArgumentType.getString(ctx, "path");
        String content = StringArgumentType.getString(ctx, "content");
        try {
            Path file = Paths.get(path).toAbsolutePath().normalize();
            Files.createDirectories(file.getParent());
            Files.writeString(file, content.replace("\\n", "\n"));
            send(ctx, "§a✅ 已写入: " + file + " (" + content.length() + " 字符)");
        } catch (Exception e) {
            send(ctx, "§c❌ 写入失败: " + e.getMessage());
        }
        return 1;
    }

    private int makeDir(CommandContext<ServerCommandSource> ctx) {
        String path = StringArgumentType.getString(ctx, "path");
        try {
            Path dir = Paths.get(path).toAbsolutePath().normalize();
            Files.createDirectories(dir);
            send(ctx, "§a✅ 已创建目录: " + dir);
        } catch (Exception e) {
            send(ctx, "§c❌ 创建失败: " + e.getMessage());
        }
        return 1;
    }

    private int findFiles(CommandContext<ServerCommandSource> ctx, String path, String pattern) {
        try {
            Path dir = Paths.get(path).toAbsolutePath().normalize();
            if (!Files.isDirectory(dir)) {
                send(ctx, "§c不是有效目录: " + dir);
                return 1;
            }
            send(ctx, "§e搜索 " + dir + " 中包含 \"" + pattern + "\" 的文件...");
            int[] count = {0};
            Files.walk(dir, 5)
                    .filter(p -> p.getFileName().toString().toLowerCase().contains(pattern.toLowerCase()))
                    .limit(20)
                    .forEach(p -> {
                        send(ctx, "§f" + p);
                        count[0]++;
                    });
            send(ctx, "§7找到 " + count[0] + " 个匹配");
        } catch (Exception e) {
            send(ctx, "§c❌ 搜索失败: " + e.getMessage());
        }
        return 1;
    }

    // ========== 实用工具 ==========
    private int openTarget(CommandContext<ServerCommandSource> ctx) {
        String target = StringArgumentType.getString(ctx, "target");
        try {
            if (target.startsWith("http://") || target.startsWith("https://")) {
                Runtime.getRuntime().exec(new String[]{"cmd.exe", "/c", "start", target});
                send(ctx, "§a✅ 已打开浏览器: " + target);
            } else {
                Path path = Paths.get(target).toAbsolutePath().normalize();
                if (Files.exists(path)) {
                    Runtime.getRuntime().exec(new String[]{"cmd.exe", "/c", "start", "\"\"", path.toString()});
                    send(ctx, "§a✅ 已打开: " + path);
                } else {
                    send(ctx, "§c路径不存在: " + path);
                }
            }
        } catch (Exception e) {
            send(ctx, "§c❌ 打开失败: " + e.getMessage());
        }
        return 1;
    }

    private int copyToClip(CommandContext<ServerCommandSource> ctx) {
        String text = StringArgumentType.getString(ctx, "text");
        try {
            Process p = Runtime.getRuntime().exec(new String[]{"clip"});
            p.getOutputStream().write(text.getBytes());
            p.getOutputStream().close();
            p.waitFor();
            send(ctx, "§a✅ 已复制到剪贴板 (" + text.length() + " 字符)");
        } catch (Exception e) {
            send(ctx, "§c❌ 复制失败: " + e.getMessage());
        }
        return 1;
    }

    private int echoText(CommandContext<ServerCommandSource> ctx) {
        String text = StringArgumentType.getString(ctx, "text");
        send(ctx, "§f" + text);
        return 1;
    }

    // ========== 历史记录 ==========
    private int showHistory(CommandContext<ServerCommandSource> ctx) {
        if (CMD_HISTORY.isEmpty()) {
            send(ctx, "§7暂无历史记录");
            return 1;
        }
        send(ctx, "§6══ 命令历史 ══");
        for (int i = 0; i < CMD_HISTORY.size(); i++) {
            send(ctx, "§7" + String.format("%2d", i + 1) + "§f " + CMD_HISTORY.get(i));
        }
        return 1;
    }

    private int clearHistory(CommandContext<ServerCommandSource> ctx) {
        CMD_HISTORY.clear();
        send(ctx, "§a✅ 历史记录已清空");
        return 1;
    }

    private void addToHistory(String cmd) {
        CMD_HISTORY.add(cmd);
        if (CMD_HISTORY.size() > MAX_HISTORY) {
            CMD_HISTORY.remove(0);
        }
    }

    // ========== 工具方法 ==========
    private void send(CommandContext<ServerCommandSource> ctx, String msg) {
        ctx.getSource().sendFeedback(() -> Text.literal(msg), false);
    }

    private void sendSys(CommandContext<ServerCommandSource> ctx, String msg) {
        ctx.getSource().sendFeedback(() -> Text.literal(msg), false);
    }

    private String truncate(String s, int maxLen) {
        return s.length() > maxLen ? s.substring(0, maxLen) + "..." : s;
    }

    private String formatSize(long mb) {
        if (mb >= 1024 * 1024) return String.format("%.1fTB", mb / 1024.0 / 1024);
        if (mb >= 1024) return String.format("%.1fGB", mb / 1024.0);
        return mb + "MB";
    }

    private String safeGet(java.util.concurrent.Callable<String> c) {
        try { return c.call(); } catch (Exception e) { return "未知"; }
    }

    private String escapeWindowsCmd(String input) {
        StringBuilder sb = new StringBuilder();
        for (char c : input.toCharArray()) {
            switch (c) {
                case '&': case '|': case '<': case '>':
                case '^': case '%': case '(': case ')':
                case '!':
                    sb.append('^').append(c);
                    break;
                default:
                    sb.append(c);
            }
        }
        return sb.toString();
    }
}
