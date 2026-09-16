package dev.configpatcher.agent;

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
