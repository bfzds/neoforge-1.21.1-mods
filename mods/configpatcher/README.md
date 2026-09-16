# Config Patcher（NeoForge 1.21.1）

按规则条件式改写**其它 mod** 的配置：

- 目标 mod **存在** → 在它的配置加载完成的那一刻，按规则改写指定条目；
- 目标 mod **不存在** → 什么都不做，只在日志里说明“已跳过”；
- 目标 mod **改了配置项** → 别名兜底 + 相似路径提示 + dump 工具，让规则能快速修好；
- **改不动就跳过** → 没改成功的条目立刻放弃、不再重试，并在玩家进入游戏时于聊天栏说明“哪个 mod 的哪个选项没改成功”。

不需要为目标 mod 写一行 Java 代码，也不需要把目标 mod 加进编译依赖。

---

## 一、快速开始

1. 把构建出的 jar 放进 `mods/`，启动一次游戏；
2. 自动生成规则文件：`config/configpatcher/rules.json`（自带中文说明与一条示例规则）；
3. 用 `/configpatcher dump <modid>` 导出目标 mod 的真实配置项，或者直接手写规则；
4. 改完规则后在游戏里执行 `/configpatcher reload`，**不用重启**。

### 规则文件长什么样

```json
{
  "schemaVersion": 1,
  "enabled": true,
  "rules": [
    {
      "id": "othermod-关掉实验性功能",
      "comment": "别人写的备注，随便填",
      "target": "othermod",
      "versionRange": "[1.0.0,2.0.0)",
      "configFile": "othermod-common.toml",
      "configType": "COMMON",
      "handler": "configvalue",
      "required": false,
      "patches": {
        "features.enableExperimental": false,
        "limits.maxMachines": 256
      }
    }
  ]
}
```

字段说明：

| 字段 | 是否必填 | 说明 |
|---|---|---|
| `target` | 必填 | 目标 mod 的 modId |
| `patches` | 必填 | 改写项，见下面的两种写法 |
| `id` | 选填 | 规则名，只用于日志和命令输出 |
| `enabled` | 选填 | 默认 `true`，单条规则可以随时停用 |
| `versionRange` | 选填 | 空/`*` 不限；支持 `[1.0.0,2.0.0)`、`>=1.2,<2.0` |
| `configFile` | 选填 | 目标配置文件名；省略则对该 mod 所有配置生效 |
| `configType` | 选填 | `COMMON` / `SERVER` / `CLIENT` / `STARTUP` |
| `handler` | 选填 | `configvalue`（默认，改内存、立即生效）/ `toml-file`（改文件、重启生效） |
| `required` | 选填 | `true` 时目标 mod 缺失会打 WARN，便于发现自己写错了 modId |

改写项的两种写法：

```json
"patches": {
  "features.enableThing": false
}
```

```json
"patches": [
  {
    "path": "features.enableThing",
    "aliases": ["features.enable_thing", "features.enableThingV2"],
    "optional": true,
    "value": false
  }
]
```

---

## 二、配置项改了怎么办（预案）

目标 mod 更新后配置项改名、换类型、甚至整段删掉，是这套东西最常遇到的麻烦。按下面的顺序处理，基本不用翻目标 mod 的源码。

### 0. 没改成功的会怎样：跳过 + 进游戏提醒

这是默认行为，不需要任何配置：

1. 某个配置项**第一次**没改成功（路径对不上 / 写入失败），引擎立刻把它记进“失败账本”并**加入跳过名单**；
2. 之后的重试（配置热重载、服务器启动兜底、`/configpatcher apply`）都会**直接跳过**这些条目，不会反复报错、不会反复写盘；
3. 玩家**进入游戏时**，聊天栏会出现一段说明（只发给 OP / 单人游戏玩家）：

```
[Config Patcher] 以下配置项没有改成功，已跳过不再重试：
  • othermod 的 features.enableThing —— 目标配置里没有这个路径；最接近的候选：features.enable_thing
  用 /configpatcher check 看详情，/configpatcher dump <modid> 导出该 mod 的实际配置项，
  /configpatcher failures clear 清空这份提示。
```

4. 修好规则后执行 `/configpatcher reload`（重读规则会同时清空失败账本，让这些条目重新尝试一次）；
   只想清掉提示就执行 `/configpatcher failures clear`。

注意区分两种“不动”：

| 情况 | 行为 |
|---|---|
| 目标 mod 没装 | 整条规则按设计跳过，**不进失败账本**、不提醒（这是本来就要的结果） |
| 版本不在 `versionRange` 内 | 同上，跳过但不提醒 |
| 装了但对不上路径 / 写入失败 | **进失败账本**，跳过重试，并在进入游戏时提醒 |

### 1. 先看体检结果

```
/configpatcher check
```

会把规则分成几类报出来：

- `[目标缺失]` —— 这个 mod 没装（按设计跳过，不用管）；
- `[版本不匹配]` —— 装了，但版本落在 `versionRange` 之外；
- `[从未命中]` —— **最可疑**：目标 mod 装了，规则却一次都没生效，通常是配置项改名或删掉了；
- 具体的 `找不到配置项` / `执行出错` 明细会直接列在下面。

### 2. 一条命令拿到真实配置项

```
/configpatcher dump othermod
```

会在 `config/configpatcher/` 下生成两个文件：

- `dump-othermod.txt` —— 该 mod 全部配置项：路径、当前值、类型；
- `draft-othermod.json` —— 可直接粘进 `rules.json` 的规则草稿（按配置文件分组，已经填好 `target`/`configFile`/`configType`）。

对着 dump 清单把旧路径改成新路径即可。

### 3. 用别名一次性兼容新旧名字

```json
{
  "path": "features.enableThing",
  "aliases": ["features.enable_thing", "features.enableExperimental"],
  "value": false
}
```

引擎按 `path → aliases` 的顺序找，**命中哪个都用**，所以目标 mod 在新老版本之间来回横跳也不用改规则。
报告里会写明“通过别名 xxx 命中”，方便你事后决定要不要把主路径换成最新的。

### 4. 路径没写对时，日志会直接给候选

找不到路径时，引擎会对目标 mod 的全部配置项做归一化相似度比较（忽略大小写、`_`、`-`），
把最接近的几个直接写进报告：

```
[找不到配置项] othermod.features.enableThing —— 最接近的候选：features.enable_thing、features.enableNew
```

### 5. 配置项被删掉了 → 标记 optional

```json
{ "path": "features.removedThing", "optional": true, "value": false }
```

这样规则不会一直报问题，`/configpatcher check` 也不会把它算成待处理项。

### 6. 值类型变了（int → double、单值 → 列表、加了枚举）

大部分情况不用你管，引擎会按目标配置项的实际类型自动转换：

- 整型项里写 `"256"`（字符串）也能进；
- 列表项里写单值 `"x"` 会当成 `["x"]`；
- 枚举项可以写名字（大小写不敏感）或序号。

实在转不过去时会给出明确报错，例如：
`值转换失败：…（规则里写的是 [1,2]，目标类型是 Boolean）`。

### 7. 目标 mod 换了配置系统（不用 ModConfigSpec 了）

把这条规则的落地方式换掉即可：

```json
{ "handler": "toml-file" }
```

文件方式直接按行改写 TOML（保留缩进和行尾注释），代价是**需要重启游戏生效**。
`configvalue` 方式拿不到值时也会自动降级尝试一次文件方式，日志里会写明。

### 8. 不同版本要改不同的东西

同一个目标写多条规则，用 `versionRange` 分流即可，先后顺序不影响结果（引擎按区间判断）：

```json
{ "id": "old", "target": "othermod", "versionRange": "[1.0.0,2.0.0)", "patches": { "a.b": 1 } },
{ "id": "new", "target": "othermod", "versionRange": "[2.0.0,)",   "patches": { "c.d": 2 } }
```

### 9. 出问题要快速回滚

- 单条规则：加 `"enabled": false`；
- 全部规则：顶层 `"enabled": false`，或直接把 `rules.json` 里的内容清空成 `{"rules": []}`；
- 改完执行 `/configpatcher reload` 立即生效。

---

## 三、命令一览

| 命令 | 权限 | 作用 |
|---|---|---|
| `/configpatcher status` | 无 | 规则总数、最近一次改了什么、失败项数量、哪些规则从未命中 |
| `/configpatcher check` | 无 | 规则体检：目标缺失 / 版本不匹配 / 路径对不上 |
| `/configpatcher failures` | 无 | 列出所有没改成功、已被跳过的配置项 |
| `/configpatcher failures clear` | 2 | 清空失败账本（下次会重新尝试这些条目） |
| `/configpatcher reload` | 2 | 重新读取 `rules.json`（会同时清空失败账本） |
| `/configpatcher apply` | 2 | 把所有已加载配置重新过一遍规则 |
| `/configpatcher dump <modid>` | 2 | 导出目标 mod 的真实配置项 + 生成规则草稿 |

---

## 四、代码结构

```
src/main/java/dev/configpatcher/
├── ConfigPatcher.java                 主类：只做事件接线
├── Log.java                           统一日志
├── engine/
│   ├── PatchEngine.java               调度核心：事件 → 规则 → 落地方式 → 报告
│   ├── PatchContext.java              一次处理的输入
│   ├── PatchOutcome.java              单条结果（已改写/无需改写/找不到/出错…）
│   ├── PatchReport.java               结果汇总
│   ├── FailureLedger.java             失败账本：跳过重试 + 进入游戏时的提醒内容
│   ├── ModPresence.java               目标 mod 是否存在、版本号
│   ├── VersionRange.java              版本区间判断
│   ├── ValueCoercion.java             JSON 值 → 目标类型的宽容转换
│   ├── PathSuggest.java               相似路径提示（改名预案）
│   └── ReflectSupport.java            少量反射工具
├── event/
│   └── JoinNotice.java                玩家进游戏时在聊天栏报告没改成功的项
├── handler/
│   ├── PatchHandler.java              【扩展点】落地方式接口
│   ├── HandlerRegistry.java           注册表
│   ├── ConfigValuePatchHandler.java   ModConfigSpec 内存改写（首选）
│   └── TomlFilePatchHandler.java      TOML 文件改写（兜底）
├── rule/
│   ├── PatchEntry.java                单条改写项（path / aliases / optional / value）
│   ├── PatchRule.java                 一条规则
│   ├── RuleSet.java                   规则集合 + 按 modId 索引
│   └── RuleLoader.java                rules.json 读写（逐条容错）
└── command/
    ├── ConfigPatcherCommand.java      游戏内命令
    └── ConfigDumper.java              配置项导出 + 草稿生成
```

### 想支持别的配置系统

实现 `PatchHandler` 并注册即可，例如针对 Cloth Config / YACL / 纯 JSON 配置的处理器：

```java
public final class MyJsonPatchHandler implements PatchHandler {
    @Override
    public String id() {
        return "my-json";
    }

    @Override
    public List<PatchOutcome> apply(PatchContext context) {
        // context.config() 是目标 mod 的 ModConfig；context.rule() 是命中的规则
        return List.of();
    }
}
```

在 mod 构造阶段调用 `HandlerRegistry.register(new MyJsonPatchHandler())`，
然后规则里写 `"handler": "my-json"`。

---

## 五、构建

```powershell
.\gradlew.bat build          # 编译 + 跑单元测试
.\gradlew.bat runClient      # 起客户端调试
.\gradlew.bat runServer      # 起服务端调试
```

产物在 `build/libs/configpatcher-<版本>.jar`。要求 JDK 21。

依赖来源：全部走标准仓库（`mavenLocal` / `mavenCentral`）；
本机若存在同目录下的 `../ae2wtlib-forge-1.20.1-port/offline-maven`，
`build.gradle` 会把它作为第一顺位仓库，这样在没网的环境里也能编译并跑测试。
当前的平台版本对齐为 `minecraft 1.21.1` + `neoforge 21.1.216`（`gradle.properties` 里改）。

单元测试覆盖纯逻辑部分（版本区间、值转换、路径相似度、规则解析、失败账本、注入去重、键位合并），共 49 个用例，不依赖游戏启动。

---

## 六、NeoForge 1.21.1 的 mod 加载顺序

顺序不是「谁先放到 mods 目录谁先加载」，而是由依赖图决定：

1. **发现（只做一次，很早）**：FML 扫描 `mods/`、classpath、`--mods` 参数指定的目录，逐个读 jar 里的 `META-INF/neoforge.mods.toml`；
2. **排序**：按 `[[dependencies]]` 做拓扑排序，`ordering = "BEFORE" / "AFTER" / "NONE"`、`type = "required" / "optional"`；
3. **构造**：按排好的顺序依次实例化 `@Mod` 类（构造器在这里跑）；
4. **配置加载**：`COMMON` / `STARTUP` 等配置在 mod 构造之后加载 → 这时才会触发 `ModConfigEvent.Loading`；
5. **事件阶段**：`FMLCommonSetupEvent`（并行）→ 服务器/世界相关事件。

由此得到三条实用结论：

- **同层内的顺序不可控**，只有显式声明依赖的 mod 之间才有确定的先后；
- **本 mod 不需要「抢先加载」**：改写发生在 `ModConfigEvent.Loading`，也就是目标 mod 的配置刚加载完的那一刻，
  只要监听器在 mod 构造期注册就一定赶得上（构造早于配置加载）；
- **运行期把 jar 放进 `mods/` 不会让本次启动加载它**：发现阶段已经跑完了。
  想让「本次启动」就加载，注入动作必须发生在 FML 之前 —— 这就是下面 Agent 存在的理由。

确实需要抢先时，可以在本 mod 的 `neoforge.mods.toml` 里声明（对方存在时生效）：

```toml
[[dependencies.configpatcher]]
modId = "create"
type = "optional"
ordering = "BEFORE"
versionRange = "*"
side = "BOTH"
```

---

## 七、启动前注入（Java Agent）

同一个 jar 既是 NeoForge mod，也是 Java Agent：

```
Premain-Class: dev.configpatcher.agent.PatcherAgent
```

在实例的 JVM 参数里加一行（配一次，之后每次启动自动执行）：

```
-javaagent:D:\mc\常用mod\neoforge 1.21.1\configpatcher-0.1.0.jar
```

启动最早期的动作顺序是：**JVM premain →（本 mod 注入 mod / 资源包 / 改键位 / 改配置）→ FML 发现 mods 目录 → mod 构造 → 配置加载 → 进游戏**。
所以 Agent 放进去的东西，本次启动就会被加载。

### 注入清单

首次启动会自动生成 `<实例目录>/config/configpatcher/inject.properties`：

```properties
# 注入 mod（目录或单个 jar）；目标 mods 里已有同 modId 的会自动跳过
# 下面的路径请改成你自己的目录
modSources=D:\mc\常用mod\neoforge 1.21.1

# 资源包：复制到 resourcepacks，并写进 options.txt 启用、从 incompatibleResourcePacks 移除
resourcePackSources=D:\mc\常用mod\材质包

# 把 Agent jar 自己也注入 mods（这样配置改写部分才生效）
injectSelf=true

# 整份覆盖
fileOverrides=preset:recipe_type_names.json>config/extendedae_plus/recipe_type_names.json

# 只合并键位（JSON 按层级路径匹配，不动其它开关）
keybindFileOverrides=preset:tweakeroo.json>config/tweakeroo.json

# 只合并键位（options.txt 文本行，其它设置不动）
keybindSource=preset:options.txt
keybindTarget=options.txt

# 启动前直接改配置文件里的单个键值（TOML/INI 文本级，保留缩进与注释）
valueEdits=config/ae2-client.toml:terminals.terminalMargin=0;config/create-server.toml:kinetics.encasedFan.fanProcessingTime=0;config/jei/jei-client.ini:cheating.giveMode=INVENTORY
```

上面这份 `valueEdits` 是拿一个 NeoForge 1.21.1 整合包的真实配置文件校准的：

| mod | 文件 | 段 | 键 | 改前 → 改后 |
|---|---|---|---|---|
| 应用能源2 | `config/ae2-client.toml` | `[terminals]` | `terminalMargin` | 25 → 0 |
| 机械动力 | `config/create-server.toml` | `[kinetics.encasedFan]` | `fanProcessingTime` | 150 → 0 |
| JEI | `config/jei/jei-client.ini` | `[cheating]` | `giveMode` | （已是 `INVENTORY`） |

- 多个条目用 `;` 分隔；Windows 路径原样写（不用转义反斜杠）；
- 源可以写绝对路径，也可以写内置预设 `preset:xxx`（打包在 jar 的 `configpatcher/presets/` 里，换电脑不会丢）；
- 清单也可以放到别处：`-javaagent:xxx.jar=D:\somewhere\inject.properties`。

### 防冲突措施（「整合包有了就不加载」的完整实现）

注入类工具最危险的失败模式不是「没注入」，而是「注入过、后来整合包自己更新了，同一个 modId 出现两份」——
FML 遇到重复 mod 会直接崩游戏。所以这里做了六层防范：

| # | 场景 | 处理 |
|---|---|---|
| 1 | 整合包里已有同一个 mod（新或旧版本） | **不注入**。判定依据是 jar 里的 `modId`，不看文件名、不看版本号 |
| 2 | 源目录里有同一 mod 的多个版本 | 只注入一个：按文件名自然序取版本号更大的 |
| 3 | 上次注入过，这次整合包自己带了这个 mod | **删掉上次注入的那一份**，保留整合包自带的 |
| 4 | `mods/` 里已有同名文件，但属于另一个 mod | 跳过，绝不覆盖别人的文件 |
| 5 | 本 mod（configpatcher）自己 | 强制与当前运行的 Agent 版本一致（允许覆盖旧副本） |
| 6 | 任何被本工具改写过的文件 | 第一次修改前留一份 `*.configpatcher.bak` |

### 账本：怎么做到「只删自己放的那一份」

每次注入后，往 `<实例目录>/config/configpatcher/injected.log` 记一行：

```
文件名|modId|字节数|来源|注入时间
```

下次启动先对账，规则是：

- 文件不在了 → 只清理记录；
- 文件**大小和记录不一致**（被改过）→ 交回给整合包/用户管理，本工具不再碰它；
- `mods/` 里出现了同 `modId` 的**其它** jar → 认定整合包自己带了这个 mod，删除自己那份。

也就是说：**只有账本里记过、且内容没被改动的文件才会被删除**，整合包自带的文件一律不动。
想彻底取消本工具对某个文件的管理，把账本里对应行删掉即可。

### 每次启动都会做什么（开销说明）

Agent 每次启动游戏时都会跑一遍，顺序是：

1. **扫描一次** 实例 `mods/` 目录，读每个 jar 的 `modId` 建索引（整次启动只扫这一遍，对账和注入共用）；
2. **对账**：按账本清理「整合包已经自带」的重复副本；
3. **注入**：扫描 `modSources` 指向的来源目录，按 modId 去重后复制缺失的 mod；
4. **资源包**：复制并写进 `options.txt` 启用；
5. **文件任务**：整份覆盖 / 只合并键位。

开销与注意点：

- **完全不扫描的情况**：`inject.properties` 里 `modSources` 为空且 `injectSelf=false` 时直接返回，不扫任何目录；
- **主要开销**是打开每个 jar 读一次 `mods.toml`。100 个 mod 的整合包大约几十到几百毫秒，
  日志里会打出一行 `[scan] mods —— 扫描 N 个 jar，用时 X ms`，可以直接看到实际数字；
- **不会重复写盘**：键位 / `options.txt` / 被覆盖的文件只有在内容真的变化时才写，内容一致时连文件时间戳都不动；
- **mod 侧（游戏内）不扫描**：配置改写是事件驱动（`ModConfigEvent.Loading` 时用 `modId` 做哈希查找），
  规则数再翻几倍也不影响启动；只有 `/configpatcher dump`、`/configpatcher apply` 这类手动命令才会遍历已加载配置。

### 键位合并为什么不是整份覆盖

整份覆盖 `options.txt` 会连带改掉分辨率、语言、视野等一堆无关设置，所以只做两件事：
把样本里的 `key_*` 行写进目标（目标缺的补上、多的保留），以及把注入的资源包写进 `resourcePacks` 并
从 `incompatibleResourcePacks` 移除（否则材质不会真正启用）。
`tweakeroo.json` 这类 JSON 配置用「层级路径」匹配（例如 `GenericHotkeys>flexibleBlockPlacementOffset>keys`），
只改键位那一行，其它开关原样保留。

