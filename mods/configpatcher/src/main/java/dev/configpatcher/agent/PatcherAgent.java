package dev.configpatcher.agent;

import java.io.IOException;
import java.lang.instrument.Instrumentation;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Java Agent 入口：在 JVM 启动的最早期（FML 扫描 mods 目录之前）完成注入。
 *
 * <p>为什么必须是 Agent：mod 自己的代码跑在 FML 的 mod 发现之后，
 * 运行期往 {@code mods/} 里放 jar 只会「下次启动」生效；
 * 想「本次启动就加载」，注入动作必须发生在 FML 开始扫描之前，Agent 的 {@code premain} 是唯一时机。
 *
 * <p>用法（配置一次，之后每次启动自动执行）：
 * <pre>
 * -javaagent:C:\path\configpatcher-0.1.0.jar
 * -javaagent:C:\path\configpatcher-0.1.0.jar=D:\somewhere\inject.properties
 * -javaagent:C:\path\configpatcher-0.1.0.jar=gameDir=E:\...\versions\实例名
 * </pre>
 *
 * <p>日志同时输出到控制台和 {@code <游戏目录>/config/configpatcher/agent.log}（UTF-8），
 * 后者不受启动器控制台编码影响，出问题时直接看这个文件。
 */
public final class PatcherAgent {

    /** 默认的注入清单位置，相对游戏目录。 */
    public static final String SETTINGS_RELATIVE = "config/configpatcher/inject.properties";
    private static final String LOG_RELATIVE = "config/configpatcher/agent.log";

    /**
     * 退出钩子里先等一会儿再读配置：退出阶段其它 mod（tweakeroo / malilib 等）也在写各自的配置文件，
     * JVM 的多个 shutdown hook 是并发跑的，不等就有可能读到它们保存之前的旧内容。
     */
    private static final long EXPORT_DELAY_MILLIS = 1500L;

    private static final List<String> LINES = new ArrayList<>();

    private PatcherAgent() {
    }

    public static void premain(String args, Instrumentation instrumentation) {
        run(args);
    }

    /** 支持运行期 attach（{@code agentmain}），逻辑与 premain 一致。 */
    public static void agentmain(String args, Instrumentation instrumentation) {
        run(args);
    }

    private static void run(String args) {
        Path gameDir = null;
        try {
            // 参数三种写法：
            //   -javaagent:xxx.jar                                 自动探测游戏目录 + 默认清单位置
            //   -javaagent:xxx.jar=D:\somewhere\inject.properties  指定清单
            //   -javaagent:xxx.jar=gameDir=E:\...\versions\实例名    手工指定游戏目录
            Path gameDirOverride = null;
            Path settingsOverride = null;
            if (args != null && !args.isBlank()) {
                String trimmed = args.trim();
                if (trimmed.toLowerCase(Locale.ROOT).startsWith("gamedir=")) {
                    gameDirOverride = Path.of(trimmed.substring("gamedir=".length()).trim())
                            .toAbsolutePath().normalize();
                } else {
                    settingsOverride = Path.of(trimmed).toAbsolutePath().normalize();
                }
            }

            gameDir = gameDirOverride != null ? gameDirOverride : AgentInjector.resolveGameDir();
            Path settingsFile = settingsOverride != null
                    ? settingsOverride
                    : gameDir.resolve(SETTINGS_RELATIVE);
            ensureSettingsFile(settingsFile);
            log("游戏目录：" + gameDir + (gameDirOverride != null ? "（手工指定）" : "（自动探测）"));
            log("注入清单：" + settingsFile + (Files.isRegularFile(settingsFile) ? "" : "（不存在，跳过注入）"));

            AgentInjector.Settings settings = AgentInjector.loadSettings(settingsFile);
            // 本次会话的「启动成功」握手：先作废旧凭据，等 mod 侧跑到 FMLCommonSetupEvent 才认账。
            // 游戏崩在 mod 加载阶段时，凭据永远写不出来，退出钩子就不会回写样本。
            String bootId = SessionMarker.beginSession(gameDir);
            long crashBaseline = latestCrashReportMillis(gameDir);
            log("本次启动编号：" + bootId + "；只有游戏加载成功过才会回写样本");
            registerAutoExportHook(gameDir, settings, bootId, crashBaseline);
            if (settings.modSources().isEmpty() && settings.resourcePackSources().isEmpty()
                    && settings.fileOverrides().isEmpty() && settings.keybindFileOverrides().isEmpty()
                    && settings.valueEdits().isEmpty()
                    && settings.keybindSource() == null && !settings.injectSelf()) {
                log("没有配置任何注入项，结束");
                return;
            }

            List<AgentInjector.Action> actions = AgentInjector.run(gameDir, settings, selfJar());
            report(actions);
        } catch (Throwable throwable) {
            // 注入失败绝不能拦住游戏启动
            log("注入过程出错（游戏会照常启动）：" + throwable);
        } finally {
            flushLog(gameDir);
        }
    }

    /**
     * 注册「关闭游戏自动导出」：JVM 退出时把本实例当前生效的配置回写成样本库的文件。
     *
     * <p>为什么必须放在退出钩子：用户是在游戏里调的键位，只有退出阶段磁盘上的文件才是最终版本；
     * 而下次启动 Agent 又会用样本覆盖实例，所以「收编」只能发生在这一次退出之前。
     *
     * <p><b>但退出钩子崩溃时也会跑</b>，而崩溃时实例里的配置文件可能只是半成品。所以回写前要过两道闸：
     * <ol>
     *     <li>mod 侧有没有写下「本次启动成功」的凭据（{@link SessionMarker}）—— 挡 mod 加载失败；</li>
     *     <li>本次会话有没有新增崩溃报告 —— 挡运行期崩溃。</li>
     * </ol>
     * 任何一道没过就整体跳过回写，宁可少收编一次，也不能把样本库写坏。
     *
     * @param bootId        本次启动编号（只用于日志对照）
     * @param crashBaseline 本次启动前 {@code crash-reports/} 里最新的文件时间
     */
    private static void registerAutoExportHook(Path gameDir, AgentInjector.Settings settings,
                                               String bootId, long crashBaseline) {
        if (!settings.autoExportOnExit()) {
            return;
        }
        Thread hook = new Thread(() -> {
            try {
                Thread.sleep(EXPORT_DELAY_MILLIS);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
            try {
                if (!SessionMarker.startedThisSession(gameDir)) {
                    appendExportLog(gameDir, List.of(new AgentInjector.Action("export", "-",
                            "本次会话没有成功启动（游戏没走到 mod 加载完成就退出了），已跳过回写样本，"
                                    + "样本保持不变；本次启动编号 " + bootId, false)));
                    return;
                }
                long newestCrash = latestCrashReportMillis(gameDir);
                if (newestCrash > crashBaseline) {
                    appendExportLog(gameDir, List.of(new AgentInjector.Action("export", "-",
                            "本次会话生成过崩溃报告，已跳过回写样本，避免把半成品配置写进样本库", false)));
                    return;
                }
                List<AgentInjector.Action> actions = SampleExporter.export(gameDir, settings);
                appendExportLog(gameDir, actions);
            } catch (Throwable throwable) {
                appendExportLog(gameDir, List.of(new AgentInjector.Action("export", "-",
                        "导出出错：" + throwable, false)));
            }
        }, "configpatcher-auto-export");
        try {
            Runtime.getRuntime().addShutdownHook(hook);
            log("已开启「关闭游戏自动导出」：退出时会把当前配置回写样本（前提是本次启动成功且没崩溃）");
        } catch (Throwable throwable) {
            log("注册自动导出钩子失败：" + throwable);
        }
    }

    /** {@code crash-reports/} 目录里最新的文件时间（0 表示目录不存在或没有文件）。 */
    static long latestCrashReportMillis(Path gameDir) {
        if (gameDir == null) {
            return 0L;
        }
        Path dir = gameDir.resolve("crash-reports");
        if (!Files.isDirectory(dir)) {
            return 0L;
        }
        long newest = 0L;
        try (var stream = Files.list(dir)) {
            List<Path> files = stream.filter(Files::isRegularFile).toList();
            for (Path file : files) {
                try {
                    newest = Math.max(newest, Files.getLastModifiedTime(file).toMillis());
                } catch (IOException ignored) {
                    // 读不到这一个就跳过
                }
            }
        } catch (IOException ignored) {
            // 目录不可读时按「没有崩溃报告」处理
        }
        return newest;
    }

    /** 导出结果追加到 agent.log（不能用覆盖写，否则启动阶段的记录会被挤掉）。 */
    private static void appendExportLog(Path gameDir, List<AgentInjector.Action> actions) {
        if (gameDir == null) {
            return;
        }
        long changed = actions.stream().filter(AgentInjector.Action::changed).count();
        List<String> block = new ArrayList<>();
        block.add("[ConfigPatcher Agent] ===== 关闭游戏自动导出 =====");
        for (AgentInjector.Action action : actions) {
            block.add("[ConfigPatcher Agent] [export] " + action.name() + " —— " + action.detail());
        }
        block.add("[ConfigPatcher Agent] 导出完成：共 " + actions.size() + " 项，其中 " + changed + " 项写回样本");
        try {
            Path file = gameDir.resolve(LOG_RELATIVE);
            Path parent = file.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            List<String> all = Files.isRegularFile(file)
                    ? new ArrayList<>(Files.readAllLines(file, StandardCharsets.UTF_8))
                    : new ArrayList<>();
            all.add("");
            all.addAll(block);
            Files.write(file, all, StandardCharsets.UTF_8);
        } catch (Exception ignored) {
            // 写日志失败不影响游戏退出
        }
    }

    /** 清单不存在时，从 jar 内模板生成一份，方便用户直接改。 */
    static void ensureSettingsFile(Path settingsFile) {
        if (Files.isRegularFile(settingsFile)) {
            return;
        }
        try (java.io.InputStream in = PatcherAgent.class.getResourceAsStream("/configpatcher/inject.properties")) {
            if (in == null) {
                return;
            }
            Files.createDirectories(settingsFile.getParent());
            Files.write(settingsFile, in.readAllBytes());
            log("已生成注入清单模板：" + settingsFile);
        } catch (Exception ignored) {
            // 生成失败不影响游戏启动
        }
    }

    /** Agent 自己所在的 jar —— 会一并注入到 mods 目录，让本 mod 与它注入的 mod 一起生效。 */
    static Path selfJar() {
        try {
            URI location = PatcherAgent.class.getProtectionDomain().getCodeSource().getLocation().toURI();
            Path path = Path.of(location);
            return Files.isRegularFile(path) ? path : null;
        } catch (Exception ex) {
            return null;
        }
    }

    private static void report(List<AgentInjector.Action> actions) {
        int changed = 0;
        for (AgentInjector.Action action : actions) {
            if (action.changed()) {
                changed++;
            }
            // 变化的、扫描/清理汇总、以及“因为冲突而跳过”的信息都要让人看到
            boolean important = action.changed()
                    || "scan".equals(action.kind())
                    || "cleanup".equals(action.kind())
                    || "value".equals(action.kind())
                    || action.detail().contains("跳过");
            if (important) {
                log("[" + action.kind() + "] " + action.name() + " —— " + action.detail());
            }
        }
        log("本次启动处理完成：共检查 " + actions.size() + " 项，其中 " + changed + " 项发生变化");
    }

    private static void log(String message) {
        String line = "[ConfigPatcher Agent] " + message;
        System.out.println(line);
        LINES.add(line);
    }

    /** 把本次日志写到游戏目录下的 agent.log（UTF-8），不受启动器控制台编码影响。 */
    private static void flushLog(Path gameDir) {
        if (gameDir == null || LINES.isEmpty()) {
            return;
        }
        try {
            Path file = gameDir.resolve(LOG_RELATIVE);
            Path parent = file.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.write(file, LINES, StandardCharsets.UTF_8);
        } catch (Exception ignored) {
            // 写日志失败不影响游戏启动
        }
    }
}
