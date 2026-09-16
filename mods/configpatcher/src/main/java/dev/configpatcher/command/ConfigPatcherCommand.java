package dev.configpatcher.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
import dev.configpatcher.engine.FailureLedger;
import dev.configpatcher.engine.ModPresence;
import dev.configpatcher.engine.PatchEngine;
import dev.configpatcher.engine.PatchOutcome;
import dev.configpatcher.engine.PatchReport;
import dev.configpatcher.rule.PatchRule;
import dev.configpatcher.rule.RuleLoader;
import dev.configpatcher.rule.RuleSet;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

import java.util.Map;
import java.util.Set;

/**
 * 游戏内命令，用来排查“某个 mod 的配置项改了”这类问题。
 *
 * <pre>
 * /configpatcher status           看规则总数、最近一次改了什么、哪些规则从未命中
 * /configpatcher check            给所有规则做体检：目标缺失 / 版本不匹配 / 路径对不上
 * /configpatcher reload           重新读取 rules.json（改完规则不用重启）
 * /configpatcher apply            把所有已加载配置重新过一遍规则
 * /configpatcher dump &lt;modid&gt;     导出目标 mod 的真实配置项 + 生成规则草稿
 * </pre>
 */
public final class ConfigPatcherCommand {

    private ConfigPatcherCommand() {
    }

    public static void register(RegisterCommandsEvent event) {
        event.getDispatcher().register(
                Commands.literal("configpatcher")
                        .then(Commands.literal("status")
                                .executes(context -> status(context.getSource())))
                        .then(Commands.literal("check")
                                .executes(context -> check(context.getSource())))
                        .then(Commands.literal("failures")
                                .executes(context -> failures(context.getSource()))
                                .then(Commands.literal("clear")
                                        .requires(source -> source.hasPermission(2))
                                        .executes(context -> clearFailures(context.getSource()))))
                        .then(Commands.literal("reload")
                                .requires(source -> source.hasPermission(2))
                                .executes(context -> reload(context.getSource())))
                        .then(Commands.literal("apply")
                                .requires(source -> source.hasPermission(2))
                                .executes(context -> apply(context.getSource())))
                        .then(Commands.literal("dump")
                                .requires(source -> source.hasPermission(2))
                                .then(Commands.argument("modid", StringArgumentType.word())
                                        .executes(context -> dump(context.getSource(),
                                                StringArgumentType.getString(context, "modid")))))
        );
    }

    private static int status(CommandSourceStack source) {
        RuleSet rules = PatchEngine.rules();
        send(source, "规则文件：" + RuleLoader.rulesPath());
        send(source, "总开关：" + (rules.enabled() ? "开" : "关") + "，规则数：" + rules.rules().size());

        Map<String, PatchReport> reports = PatchEngine.lastReports();
        if (reports.isEmpty()) {
            send(source, "本次启动还没有任何配置被改写（可能是没有目标 mod 命中）。");
        } else {
            send(source, "最近一次改写结果：");
            for (PatchReport report : reports.values()) {
                send(source, "  " + report.summary());
            }
        }

        int failureCount = FailureLedger.size();
        if (failureCount > 0) {
            send(source, "有 " + failureCount + " 项改写失败并已跳过"
                    + "（玩家进入游戏时会在聊天栏提醒，用 /configpatcher failures 查看明细）。");
        }

        Set<String> idle = PatchEngine.idleRuleIds();
        if (!idle.isEmpty()) {
            send(source, "从未命中的规则（目标已安装却没生效，多半是配置项改名了）：");
            send(source, "  " + String.join("、", idle));
            send(source, "用 /configpatcher dump <modid> 导出实际配置项对一下。");
        }
        return Command.SINGLE_SUCCESS;
    }

    private static int check(CommandSourceStack source) {
        RuleSet rules = PatchEngine.rules();
        if (rules.isEmpty()) {
            send(source, "当前没有任何规则。规则文件：" + RuleLoader.rulesPath());
            return Command.SINGLE_SUCCESS;
        }

        int problems = 0;
        for (PatchRule rule : rules.rules()) {
            if (!rule.enabled()) {
                send(source, "[停用] " + rule.describe());
                continue;
            }
            if (!ModPresence.isLoaded(rule.targetMod())) {
                problems++;
                send(source, "[目标缺失] " + rule.describe() + " —— 已按设计跳过");
                continue;
            }
            String version = ModPresence.versionOf(rule.targetMod());
            if (!rule.matchesVersion(version)) {
                problems++;
                send(source, "[版本不匹配] " + rule.describe() + " —— 实际版本 " + version);
                continue;
            }
            int before = problems;
            problems += printFailures(source, rule);
            if (problems == before && PatchEngine.idleRuleIds().contains(rule.id())) {
                send(source, "[从未命中] " + rule.describe()
                        + " —— 检查规则里的 configFile / configType / 路径是否还对得上");
                problems++;
            }
        }

        if (problems == 0) {
            send(source, "体检通过：所有规则都能对上当前安装的目标 mod。");
        } else {
            send(source, "共发现 " + problems + " 处需要确认的地方。");
        }
        return Command.SINGLE_SUCCESS;
    }

    /** 打印该规则上一次执行里失败/没对上的条目，返回问题条数。 */
    private static int printFailures(CommandSourceStack source, PatchRule rule) {
        int count = 0;
        for (PatchReport report : PatchEngine.lastReports().values()) {
            for (PatchOutcome outcome : report.outcomes()) {
                if (!rule.id().equals(outcome.ruleId())) {
                    continue;
                }
                if (outcome.status() == PatchOutcome.Status.MISSING_ENTRY
                        || outcome.status() == PatchOutcome.Status.ERROR) {
                    send(source, "    " + outcome.line());
                    count++;
                }
            }
        }
        return count;
    }

    /** 没改成功的配置项清单（进入游戏时也会在聊天栏提醒一次）。 */
    private static int failures(CommandSourceStack source) {
        var failures = FailureLedger.failures();
        if (failures.isEmpty()) {
            send(source, "没有改写失败的记录：所有规则的配置项都改成功了，或者目标 mod 本来就没装。");
            return Command.SINGLE_SUCCESS;
        }
        send(source, "以下 " + failures.size() + " 项没有改成功，已跳过不再重试：");
        for (FailureLedger.Failure failure : failures) {
            send(source, "  • " + failure.modId() + " 的 " + failure.path() + " —— " + failure.reason());
        }
        send(source, "修好规则后执行 /configpatcher reload 会重新尝试；只想清掉提示就执行 /configpatcher failures clear。");
        return Command.SINGLE_SUCCESS;
    }

    private static int clearFailures(CommandSourceStack source) {
        int before = FailureLedger.size();
        FailureLedger.clear();
        send(source, "已清空 " + before + " 条失败记录。下次配置加载或执行 /configpatcher apply 时会重新尝试这些条目。");
        return Command.SINGLE_SUCCESS;
    }

    private static int reload(CommandSourceStack source) {
        PatchEngine.reload();
        RuleSet rules = PatchEngine.rules();
        send(source, "已重新加载规则：" + rules.rules().size() + " 条");
        if (rules.isEmpty()) {
            send(source, "注意：规则为空，文件里 rules 数组还是空的。");
        }
        return Command.SINGLE_SUCCESS;
    }

    private static int apply(CommandSourceStack source) {
        var reports = PatchEngine.applyAll();
        if (reports.isEmpty()) {
            send(source, "所有已加载配置都过了一遍，没有需要改写的。");
            return Command.SINGLE_SUCCESS;
        }
        for (PatchReport report : reports) {
            send(source, report.summary());
        }
        return Command.SINGLE_SUCCESS;
    }

    private static int dump(CommandSourceStack source, String modId) {
        ConfigDumper.Result result = ConfigDumper.dump(modId);
        if (result == null) {
            send(source, "找不到 mod「" + modId + "」的任何配置：modId 写错了，或者这个 mod 没被加载。");
            return Command.SINGLE_SUCCESS;
        }
        send(source, "已导出 " + result.configCount() + " 个配置、"
                + result.entryCount() + " 个配置项：");
        if (result.dumpFile() != null) {
            send(source, "  清单：" + result.dumpFile());
        }
        if (result.draftFile() != null) {
            send(source, "  规则草稿：" + result.draftFile());
        }
        for (String line : result.preview()) {
            sendRaw(source, line);
        }
        return Command.SINGLE_SUCCESS;
    }

    private static void send(CommandSourceStack source, String message) {
        source.sendSuccess(() -> Component.literal("[configpatcher] " + message), false);
    }

    private static void sendRaw(CommandSourceStack source, String message) {
        source.sendSuccess(() -> Component.literal(message), false);
    }
}
