package dev.configpatcher.agent;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * 启动前注入的执行体（Java Agent 侧），每次启动游戏时运行一次。
 *
 * <p>运行时机：JVM {@code premain}，早于 FML 扫描 mods 目录，所以这里放进去的 jar「本次启动」就会加载。
 * 只用 JDK 自带能力（NIO / ZipFile / 正则），不引用 Minecraft、NeoForge、Gson 或本 mod 的其它包。
 *
 * <h2>每次启动都会做的事</h2>
 * <ol>
 *     <li><b>扫描一次</b>实例 {@code mods/} 目录，建立 modId 索引（对账和注入共用这一份，不重复扫）；</li>
 *     <li><b>对账</b>：读注入账本，清理「上次注入过、这次整合包自己带了」的副本；</li>
 *     <li><b>注入</b>：扫描来源目录，按 modId 去重后复制缺失的 mod；</li>
 *     <li><b>资源包</b>：复制到 resourcepacks 并写进 options.txt 启用；</li>
 *     <li><b>文件任务</b>：整份覆盖 / 只合并键位（写前备份）。</li>
 * </ol>
 * 如果 {@code inject.properties} 里一个注入项都没配，会直接返回，不做任何扫描。
 *
 * <h2>防冲突设计</h2>
 * <ul>
 *     <li>整合包已有同 modId（不论新旧版本）→ 不注入；</li>
 *     <li>来源目录里同一 mod 有多个版本 → 只取版本号更大的那个；</li>
 *     <li>上次注入过、整合包后来自己带了 → 凭账本认出并删除自己那份，避免重复 mod 崩游戏；</li>
 *     <li>目标已有同名文件但属于别的 mod → 跳过，不覆盖；</li>
 *     <li>本 mod 自己 → 强制与运行中的 Agent 版本一致；</li>
 *     <li>改文件前留 {@code *.configpatcher.bak}。</li>
 * </ul>
 */
public final class AgentInjector {

    private static final Pattern MOD_ID = Pattern.compile("(?m)^\\s*modId\\s*=\\s*\"([^\"]+)\"");

    public static final String PRESET_PREFIX = "preset:";
    private static final String PRESET_DIR = "/configpatcher/presets/";
    private static final String BACKUP_SUFFIX = ".configpatcher.bak";

    private AgentInjector() {
    }

    /** 一次注入动作的结果记录。 */
    public record Action(String kind, String name, String detail, boolean changed) {
    }

    /** "把 from 的内容写到实例里的 to"。 */
    public record FileOverride(String from, String to) {
    }

    /** "把某个配置文件里的某个键改成指定值"（TOML / INI 文本级，启动前就改好）。 */
    public record ValueEdit(String file, String key, String value) {
    }

    /** 注入配置（来自 inject.properties）。 */
    public record Settings(
            List<Path> modSources,
            List<Path> resourcePackSources,
            boolean injectSelf,
            List<FileOverride> fileOverrides,
            List<FileOverride> keybindFileOverrides,
            String keybindSource,
            String keybindTarget,
            List<ValueEdit> valueEdits
    ) {
        public static Settings empty() {
            return new Settings(List.of(), List.of(), true, List.of(), List.of(), null, "options.txt", List.of());
        }

        /** 是否有任何需要动 mods 目录的配置（没有就完全不用扫描）。 */
        public boolean touchesMods() {
            return injectSelf || !modSources.isEmpty();
        }
    }

    /** 实例 mods 目录的 modId 索引：整次启动只扫一遍，对账与注入共用。 */
    public static final class ModsIndex {

        private final Map<String, List<Path>> byKey = new LinkedHashMap<>();
        private final Map<String, String> keyByFile = new LinkedHashMap<>();
        private final long scanMillis;

        private ModsIndex(long scanMillis) {
            this.scanMillis = scanMillis;
        }

        public static ModsIndex scan(Path modsDir) {
            long start = System.currentTimeMillis();
            ModsIndex index = new ModsIndex(0L);
            for (Path jar : listJars(modsDir)) {
                index.put(jar, readModId(jar));
            }
            return new ModsIndex(System.currentTimeMillis() - start).copyFrom(index);
        }

        private ModsIndex copyFrom(ModsIndex other) {
            byKey.putAll(other.byKey);
            keyByFile.putAll(other.keyByFile);
            return this;
        }

        /** 索引键：优先 modId，读不出来时退回文件名，避免同名文件被重复注入。 */
        public static String keyOf(Path jar, String modId) {
            return modId != null && !modId.isBlank()
                    ? modId.toLowerCase(Locale.ROOT)
                    : "file:" + jar.getFileName().toString().toLowerCase(Locale.ROOT);
        }

        void put(Path jar, String modId) {
            String key = keyOf(jar, modId);
            byKey.computeIfAbsent(key, ignored -> new ArrayList<>()).add(jar);
            keyByFile.put(normalize(jar), key);
        }

        void remove(Path jar) {
            String normalized = normalize(jar);
            String key = keyByFile.remove(normalized);
            if (key == null) {
                return;
            }
            List<Path> jars = byKey.get(key);
            if (jars != null) {
                jars.removeIf(path -> normalize(path).equals(normalized));
                if (jars.isEmpty()) {
                    byKey.remove(key);
                }
            }
        }

        public boolean contains(String modId) {
            return modId != null && byKey.containsKey(modId.toLowerCase(Locale.ROOT));
        }

        /** 除 exclude 之外，是否还有别的 jar 属于同一个 modId。 */
        public boolean hasOther(String modId, Path exclude) {
            List<Path> jars = modId == null ? null : byKey.get(modId.toLowerCase(Locale.ROOT));
            if (jars == null || jars.isEmpty()) {
                return false;
            }
            String normalized = normalize(exclude);
            for (Path jar : jars) {
                if (!normalize(jar).equals(normalized)) {
                    return true;
                }
            }
            return false;
        }

        public int size() {
            return keyByFile.size();
        }

        public long scanMillis() {
            return scanMillis;
        }

        private static String normalize(Path path) {
            return path.toAbsolutePath().normalize().toString().toLowerCase(Locale.ROOT);
        }
    }

    // ------------------------------------------------------------------ 配置解析

    /**
     * 极简 properties 解析：{@code key=value}，一行一个键，值里不做转义处理
     * （Windows 路径原样书写即可，避免 Java Properties 把反斜杠当转义符的老坑）。
     */
    public static Settings parseSettings(List<String> lines) {
        Map<String, String> map = new LinkedHashMap<>();
        for (String raw : lines) {
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            int eq = line.indexOf('=');
            if (eq <= 0) {
                continue;
            }
            map.put(line.substring(0, eq).trim().toLowerCase(Locale.ROOT), line.substring(eq + 1).trim());
        }

        List<Path> modSources = splitPaths(map.get("modsources"));
        List<Path> packSources = splitPaths(map.get("resourcepacksources"));
        boolean self = !"false".equalsIgnoreCase(map.getOrDefault("injectself", "true"));

        List<FileOverride> overrides = parseOverrides(map.get("fileoverrides"));
        List<FileOverride> keybindOverrides = parseOverrides(map.get("keybindfileoverrides"));

        String keybindSource = map.get("keybindsource");
        if (keybindSource != null && keybindSource.isBlank()) {
            keybindSource = null;
        }
        String keybindTarget = map.getOrDefault("keybindtarget", "options.txt");
        if (keybindTarget.isBlank()) {
            keybindTarget = "options.txt";
        }

        List<ValueEdit> valueEdits = parseValueEdits(map.get("valueedits"));
        return new Settings(modSources, packSources, self, overrides, keybindOverrides,
                keybindSource, keybindTarget, valueEdits);
    }

    /** 解析 {@code 相对路径:段.键=值;...} 形式的键值改写项。 */
    private static List<ValueEdit> parseValueEdits(String value) {
        List<ValueEdit> result = new ArrayList<>();
        for (String item : splitList(value)) {
            int colon = item.indexOf(':');
            int equals = item.lastIndexOf('=');
            if (colon <= 0 || equals <= colon) {
                continue;
            }
            String file = item.substring(0, colon).trim();
            String key = item.substring(colon + 1, equals).trim();
            String val = item.substring(equals + 1).trim();
            if (!file.isEmpty() && !key.isEmpty()) {
                result.add(new ValueEdit(file, key, val));
            }
        }
        return result;
    }

    private static List<FileOverride> parseOverrides(String value) {
        List<FileOverride> result = new ArrayList<>();
        for (String item : splitList(value)) {
            int arrow = item.lastIndexOf('>');
            if (arrow <= 0) {
                continue;
            }
            String from = item.substring(0, arrow).trim();
            String to = item.substring(arrow + 1).trim();
            if (!from.isEmpty() && !to.isEmpty()) {
                result.add(new FileOverride(from, to));
            }
        }
        return result;
    }

    private static List<Path> splitPaths(String value) {
        List<Path> result = new ArrayList<>();
        for (String item : splitList(value)) {
            result.add(Path.of(item));
        }
        return result;
    }

    private static List<String> splitList(String value) {
        List<String> result = new ArrayList<>();
        if (value == null || value.isBlank()) {
            return result;
        }
        for (String item : value.split(";")) {
            String trimmed = item.trim();
            if (!trimmed.isEmpty()) {
                result.add(trimmed);
            }
        }
        return result;
    }

    public static Settings loadSettings(Path file) throws IOException {
        if (!Files.isRegularFile(file)) {
            return Settings.empty();
        }
        return parseSettings(Files.readAllLines(file, StandardCharsets.UTF_8));
    }

    // ------------------------------------------------------------------ 游戏目录

    /**
     * 推断当前实例的游戏目录：优先用启动参数里的 {@code --gameDir}，
     * 拿不到就退回 JVM 的工作目录（绝大多数启动器都把工作目录设为 .minecraft 或版本隔离目录）。
     */
    public static Path resolveGameDir() {
        String[] argv = ProcessHandle.current().info().arguments().orElse(new String[0]);
        for (int i = 0; i + 1 < argv.length; i++) {
            if ("--gameDir".equalsIgnoreCase(argv[i])) {
                return Path.of(argv[i + 1]).toAbsolutePath().normalize();
            }
        }
        return Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
    }

    // ------------------------------------------------------------------ 注入

    /** 执行全部注入任务（每次启动调用一次），返回逐条结果。 */
    public static List<Action> run(Path gameDir, Settings settings, Path selfJar) {
        List<Action> actions = new ArrayList<>();
        Path modsDir = gameDir.resolve("mods");
        Path ledgerFile = gameDir.resolve(InjectionLedger.RELATIVE);

        // 扫描只做一次：mods 目录的 modId 索引被对账与注入共用
        ModsIndex index = ModsIndex.scan(modsDir);
        actions.add(new Action("scan", "mods",
                "扫描 " + index.size() + " 个 jar，用时 " + index.scanMillis() + " ms", false));

        List<InjectionLedger.Entry> ledger = reconcile(modsDir, ledgerFile, index, actions);
        injectMods(gameDir, settings, selfJar, actions, ledger, index);
        InjectionLedger.save(ledgerFile, ledger);

        List<String> packs = injectResourcePacks(gameDir, settings, actions);
        if (!packs.isEmpty()) {
            enableResourcePacks(gameDir, packs, actions);
        }
        applyFileOverrides(gameDir, settings, actions);
        return actions;
    }

    /**
     * 对账 + 清理。只处理账本里记录过的文件：
     * 文件不在 → 清记录；文件被改过（大小不符）→ 交回用户管理；
     * mods 里出现同 modId 的其它 jar → 删除自己注入的那份，避免重复 mod。
     */
    public static List<InjectionLedger.Entry> reconcile(Path modsDir, Path ledgerFile, List<Action> actions) {
        return reconcile(modsDir, ledgerFile, ModsIndex.scan(modsDir), actions);
    }

    public static List<InjectionLedger.Entry> reconcile(Path modsDir, Path ledgerFile, ModsIndex index,
                                                       List<Action> actions) {
        List<InjectionLedger.Entry> kept = new ArrayList<>();
        for (InjectionLedger.Entry entry : InjectionLedger.load(ledgerFile)) {
            Path target = modsDir.resolve(entry.fileName());
            if (!Files.isRegularFile(target)) {
                actions.add(new Action("cleanup", entry.fileName(), "注入的文件已不在，清理账本记录", false));
                continue;
            }
            long size;
            try {
                size = Files.size(target);
            } catch (IOException ex) {
                size = -1L;
            }
            if (entry.size() >= 0 && size != entry.size()) {
                actions.add(new Action("cleanup", entry.fileName(),
                        "文件后来被改动过，交回整合包/用户管理，本工具不再处理", false));
                continue;
            }
            String modId = entry.modId().isBlank() ? readModId(target) : entry.modId();
            if (modId != null && !modId.isBlank() && index.hasOther(modId, target)) {
                try {
                    Files.delete(target);
                    index.remove(target);
                    actions.add(new Action("cleanup", entry.fileName(),
                            "整合包已自带 " + modId + "，删除之前注入的副本以避免重复 mod", true));
                    continue;
                } catch (IOException ex) {
                    actions.add(new Action("cleanup", entry.fileName(), "删除失败：" + ex.getMessage(), false));
                    kept.add(entry);
                    continue;
                }
            }
            kept.add(entry);
        }
        return kept;
    }

    /** 兼容旧调用：内部自建空账本与索引。 */
    public static void injectMods(Path gameDir, Settings settings, Path selfJar, List<Action> actions) {
        injectMods(gameDir, settings, selfJar, actions, new ArrayList<>(),
                ModsIndex.scan(gameDir.resolve("mods")));
    }

    public static void injectMods(Path gameDir, Settings settings, Path selfJar, List<Action> actions,
                                  List<InjectionLedger.Entry> ledger) {
        injectMods(gameDir, settings, selfJar, actions, ledger, ModsIndex.scan(gameDir.resolve("mods")));
    }

    /** 把来源目录里的 mod 复制进实例 mods 目录，并把成功注入的项写进账本。 */
    public static void injectMods(Path gameDir, Settings settings, Path selfJar, List<Action> actions,
                                  List<InjectionLedger.Entry> ledger, ModsIndex index) {
        Path modsDir = gameDir.resolve("mods");
        Map<String, Path> plan = planCandidates(settings, selfJar, actions);

        for (Path jar : plan.values()) {
            String fileName = jar.getFileName().toString();
            String modId = readModId(jar);
            boolean self = selfJar != null && jar.toAbsolutePath().normalize()
                    .equals(selfJar.toAbsolutePath().normalize());

            // 自己那份允许覆盖，保证 mod 侧与正在运行的 Agent 版本一致
            if (!self && modId != null && index.contains(modId)) {
                actions.add(new Action("mod", fileName, "整合包里已经有 " + modId + "，跳过注入", false));
                continue;
            }

            Path target = modsDir.resolve(fileName);
            if (Files.isRegularFile(target)) {
                String existingId = readModId(target);
                if (!self && existingId != null && modId != null && !existingId.equalsIgnoreCase(modId)) {
                    actions.add(new Action("mod", fileName,
                            "mods 里已有同名文件但属于 " + existingId + "，为避免互相覆盖而跳过", false));
                    continue;
                }
                if (!self && sameSize(jar, target)) {
                    actions.add(new Action("mod", fileName, "已存在且内容一致", false));
                    remember(ledger, fileName, modId, jar);
                    continue;
                }
            }
            try {
                copy(jar, target);
                index.put(target, modId);
                actions.add(new Action("mod", fileName,
                        modId == null ? "已注入" : "已注入（modId=" + modId + "）", true));
                remember(ledger, fileName, modId, jar);
            } catch (IOException ex) {
                actions.add(new Action("mod", fileName, "注入失败：" + ex.getMessage(), false));
            }
        }
    }

    /** 把候选 jar 整理成「每个 modId 只保留一个」，同一 mod 多版本时保留版本号更大的。 */
    static Map<String, Path> planCandidates(Settings settings, Path selfJar, List<Action> actions) {
        List<Path> candidates = new ArrayList<>();
        if (settings.injectSelf() && selfJar != null && Files.isRegularFile(selfJar)) {
            candidates.add(selfJar);
        }
        for (Path source : settings.modSources()) {
            if (Files.isDirectory(source)) {
                candidates.addAll(listJars(source));
            } else if (Files.isRegularFile(source)) {
                candidates.add(source);
            }
        }

        Map<String, Path> chosen = new LinkedHashMap<>();
        for (Path jar : candidates) {
            String modId = readModId(jar);
            String key = ModsIndex.keyOf(jar, modId);
            Path existing = chosen.get(key);
            if (existing == null) {
                chosen.put(key, jar);
                continue;
            }
            Path preferred = preferNewer(existing, jar);
            Path dropped = preferred.equals(existing) ? jar : existing;
            actions.add(new Action("mod", dropped.getFileName().toString(),
                    "同一来源里有多个 " + key + "，只注入 " + preferred.getFileName(), false));
            chosen.put(key, preferred);
        }
        return chosen;
    }

    /** 文件名自然序比较（数字段按数值比），版本号更大的算更新。 */
    static Path preferNewer(Path left, Path right) {
        return compareNatural(left.getFileName().toString(), right.getFileName().toString()) >= 0 ? left : right;
    }

    static int compareNatural(String left, String right) {
        int i = 0;
        int j = 0;
        while (i < left.length() && j < right.length()) {
            char cl = left.charAt(i);
            char cr = right.charAt(j);
            if (Character.isDigit(cl) && Character.isDigit(cr)) {
                int startI = i;
                int startJ = j;
                while (i < left.length() && Character.isDigit(left.charAt(i))) {
                    i++;
                }
                while (j < right.length() && Character.isDigit(right.charAt(j))) {
                    j++;
                }
                String nl = left.substring(startI, i).replaceFirst("^0+(?=.)", "");
                String nr = right.substring(startJ, j).replaceFirst("^0+(?=.)", "");
                int cmp = nl.length() != nr.length() ? Integer.compare(nl.length(), nr.length()) : nl.compareTo(nr);
                if (cmp != 0) {
                    return cmp;
                }
            } else {
                int cmp = Character.compare(Character.toLowerCase(cl), Character.toLowerCase(cr));
                if (cmp != 0) {
                    return cmp;
                }
                i++;
                j++;
            }
        }
        return Integer.compare(left.length() - i, right.length() - j);
    }

    private static void remember(List<InjectionLedger.Entry> ledger, String fileName, String modId, Path source) {
        long size;
        try {
            size = Files.size(source);
        } catch (IOException ex) {
            size = -1L;
        }
        String sourceSpec = source.toString();
        ledger.removeIf(entry -> entry.fileName().equalsIgnoreCase(fileName));
        ledger.add(InjectionLedger.Entry.of(fileName, modId, size, sourceSpec));
    }

    /** 把资源包复制进实例 resourcepacks 目录，返回需要启用的包名（形如 {@code file/xxx.zip}）。 */
    public static List<String> injectResourcePacks(Path gameDir, Settings settings, List<Action> actions) {
        List<String> enabled = new ArrayList<>();
        if (settings.resourcePackSources().isEmpty()) {
            return enabled;
        }
        Path packsDir = gameDir.resolve("resourcepacks");
        List<Path> packs = new ArrayList<>();
        for (Path source : settings.resourcePackSources()) {
            if (Files.isDirectory(source)) {
                packs.addAll(listZipFiles(source));
            } else if (Files.isRegularFile(source)) {
                packs.add(source);
            }
        }
        for (Path pack : packs) {
            String fileName = pack.getFileName().toString();
            try {
                Path target = packsDir.resolve(fileName);
                if (Files.isRegularFile(target) && sameSize(pack, target)) {
                    actions.add(new Action("resourcepack", fileName, "已存在且内容一致", false));
                } else {
                    copy(pack, target);
                    actions.add(new Action("resourcepack", fileName, "已复制到 resourcepacks", true));
                }
                enabled.add("file/" + fileName);
            } catch (IOException ex) {
                actions.add(new Action("resourcepack", fileName, "复制失败：" + ex.getMessage(), false));
            }
        }
        return enabled;
    }

    /** 把注入的资源包写进 options.txt 启用（并从 incompatibleResourcePacks 移除）。 */
    public static void enableResourcePacks(Path gameDir, List<String> packNames, List<Action> actions) {
        Path options = gameDir.resolve("options.txt");
        try {
            String existing = Files.isRegularFile(options) ? Files.readString(options, StandardCharsets.UTF_8) : "";
            write(options, KeybindMerger.ensureResourcePacks(existing, packNames));
            actions.add(new Action("resourcepack", "options.txt",
                    "已启用 " + packNames.size() + " 个注入的资源包", true));
        } catch (IOException ex) {
            actions.add(new Action("resourcepack", "options.txt", "启用失败：" + ex.getMessage(), false));
        }
    }

    /** 键值改写 + 整份覆盖 + 只合并键位，三类文件任务。 */
    public static void applyFileOverrides(Path gameDir, Settings settings, List<Action> actions) {
        for (ValueEdit edit : settings.valueEdits()) {
            Path target = gameDir.resolve(edit.file());
            try {
                if (!Files.isRegularFile(target)) {
                    actions.add(new Action("value", edit.file(),
                            "配置文件不存在，跳过（" + edit.key() + "）", false));
                    continue;
                }
                String existing = Files.readString(target, StandardCharsets.UTF_8);
                TomlValueEditor.Result result = TomlValueEditor.set(existing, edit.key(), edit.value());
                if (!result.changed()) {
                    actions.add(new Action("value", edit.file(),
                            edit.key() + "：已是目标值或未找到该键", false));
                    continue;
                }
                write(target, result.content());
                actions.add(new Action("value", edit.file(), edit.key() + " = " + edit.value(), true));
            } catch (IOException ex) {
                actions.add(new Action("value", edit.file(), "改写失败：" + ex.getMessage(), false));
            }
        }

        for (FileOverride override : settings.fileOverrides()) {
            Path target = gameDir.resolve(override.to());
            try {
                String content = readSource(override.from());
                write(target, content);
                actions.add(new Action("file", override.to(), "已整份覆盖（源：" + override.from() + "）", true));
            } catch (IOException ex) {
                actions.add(new Action("file", override.to(), "覆盖失败：" + ex.getMessage(), false));
            }
        }

        for (FileOverride override : settings.keybindFileOverrides()) {
            Path target = gameDir.resolve(override.to());
            try {
                String sample = readSource(override.from());
                String existing = Files.isRegularFile(target) ? Files.readString(target, StandardCharsets.UTF_8) : "";
                write(target, mergeKeybinds(override.to(), existing, sample));
                actions.add(new Action("keybinds", override.to(),
                        "已按键位样本合并（源：" + override.from() + "）", true));
            } catch (IOException ex) {
                actions.add(new Action("keybinds", override.to(), "合并失败：" + ex.getMessage(), false));
            }
        }

        if (settings.keybindSource() != null) {
            Path target = gameDir.resolve(settings.keybindTarget());
            try {
                String sample = readSource(settings.keybindSource());
                String existing = Files.isRegularFile(target) ? Files.readString(target, StandardCharsets.UTF_8) : "";
                write(target, mergeKeybinds(settings.keybindTarget(), existing, sample));
                actions.add(new Action("keybinds", settings.keybindTarget(),
                        "已按键位样本合并（源：" + settings.keybindSource() + "）", true));
            } catch (IOException ex) {
                actions.add(new Action("keybinds", settings.keybindTarget(), "合并失败：" + ex.getMessage(), false));
            }
        }
    }

    // ------------------------------------------------------------------ 工具

    /** 按目标文件类型选择键位合并器：JSON 走路径感知合并，其余按 options.txt 文本行处理。 */
    public static String mergeKeybinds(String targetName, String existing, String sample) {
        if (targetName != null && targetName.toLowerCase(Locale.ROOT).endsWith(".json")) {
            return JsonKeybindMerger.merge(existing, sample);
        }
        return KeybindMerger.merge(existing, sample);
    }

    /** 读取源内容：支持 {@code preset:xxx}（jar 内置预设）与绝对路径。 */
    public static String readSource(String spec) throws IOException {
        if (spec == null || spec.isBlank()) {
            throw new IOException("源为空");
        }
        if (spec.startsWith(PRESET_PREFIX)) {
            String name = spec.substring(PRESET_PREFIX.length()).trim();
            try (InputStream in = AgentInjector.class.getResourceAsStream(PRESET_DIR + name)) {
                if (in == null) {
                    throw new IOException("找不到内置预设 " + name);
                }
                return new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
        }
        Path path = Path.of(spec);
        if (!Files.isRegularFile(path)) {
            throw new IOException("源文件不存在 " + spec);
        }
        return Files.readString(path, StandardCharsets.UTF_8);
    }

    /** 从 jar 里的 {@code META-INF/neoforge.mods.toml}（或旧名 mods.toml）读出 modId。 */
    public static String readModId(Path jar) {
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            for (String entryName : List.of("META-INF/neoforge.mods.toml", "META-INF/mods.toml")) {
                ZipEntry entry = findEntry(zip, entryName);
                if (entry == null) {
                    continue;
                }
                String text;
                try (InputStream in = zip.getInputStream(entry)) {
                    text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                }
                int modsSection = text.indexOf("[[mods]]");
                String scope = modsSection >= 0 ? text.substring(modsSection) : text;
                Matcher matcher = MOD_ID.matcher(scope);
                if (matcher.find()) {
                    return matcher.group(1).trim().toLowerCase(Locale.ROOT);
                }
            }
        } catch (IOException | RuntimeException ignored) {
            // 损坏的 jar 或不是 mod：当作识别失败处理
        }
        return null;
    }

    /**
     * 找 jar 内的 entry。除了标准写法，还兼容两种不规范的打包结果：
     * 路径分隔符写成反斜杠（PowerShell 的 Compress-Archive），或多包了一层目录前缀。
     * 读不出 modId 会让去重与清理失效，所以这里刻意宽松。
     */
    private static ZipEntry findEntry(ZipFile zip, String name) {
        ZipEntry direct = zip.getEntry(name);
        if (direct != null) {
            return direct;
        }
        String wanted = name.toLowerCase(Locale.ROOT);
        java.util.Enumeration<? extends ZipEntry> entries = zip.entries();
        while (entries.hasMoreElements()) {
            ZipEntry candidate = entries.nextElement();
            String normalized = candidate.getName().replace('\\', '/').toLowerCase(Locale.ROOT);
            if (normalized.equals(wanted) || normalized.endsWith("/" + wanted)) {
                return candidate;
            }
        }
        return null;
    }

    public static List<Path> listJars(Path dir) {
        return listByExtension(dir, ".jar");
    }

    public static List<Path> listZipFiles(Path dir) {
        return listByExtension(dir, ".zip");
    }

    private static List<Path> listByExtension(Path dir, String extension) {
        List<Path> result = new ArrayList<>();
        if (dir == null || !Files.isDirectory(dir)) {
            return result;
        }
        try (var stream = Files.list(dir)) {
            stream.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(extension))
                    .sorted()
                    .forEach(result::add);
        } catch (IOException ignored) {
            // 目录不可读时按空处理
        }
        return result;
    }

    /** 写文本；内容一致就不动文件；第一次修改某个文件前先留一份 {@code .configpatcher.bak}。 */
    private static void write(Path target, String content) throws IOException {
        Path parent = target.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        if (Files.isRegularFile(target)) {
            if (content.equals(Files.readString(target, StandardCharsets.UTF_8))) {
                return;
            }
            Path backup = target.resolveSibling(target.getFileName() + BACKUP_SUFFIX);
            if (!Files.isRegularFile(backup)) {
                Files.copy(target, backup, StandardCopyOption.COPY_ATTRIBUTES);
            }
        }
        Files.writeString(target, content, StandardCharsets.UTF_8);
    }

    private static boolean sameSize(Path left, Path right) {
        try {
            return Files.size(left) == Files.size(right);
        } catch (IOException ex) {
            return false;
        }
    }

    private static void copy(Path from, Path to) throws IOException {
        Path parent = to.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.copy(from, to, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
    }
}
