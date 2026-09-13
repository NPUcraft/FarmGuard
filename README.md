# FarmGuard

智能农场 / 自动化设施 / 卡服检测与限制插件（Paper）。

Automation Facility Monitoring, Lag Detection and Protection System for Paper.

当前版本：`1.0.0-beta.3`（**Beta**，不是 Production Stable）

FarmGuard by NPUcraft

仓库：https://github.com/NPUcraft/FarmGuard

FarmGuard V1 回答这些问题：

1. 服务器现在是否出现性能问题？
2. 哪些 Chunk 当前活动最异常？
3. 这些 Chunk 主要是什么机制在高频运行？
4. 某个热点区域与 MSPT 上升是否存在明显关联？
5. 风险严重时能否对该区域进行非破坏性限制？
6. 服务器恢复后能否自动解除临时限制？

V1 **不会**精确识别铁农场、甘蔗农场或刷怪塔种类，也不会追踪建造者。

## 功能

- 事件驱动的区块活动统计（红石、活塞、漏斗、实体、村民、矿车、掉落物、生成、繁殖、低开销方块活动）
- TPS / MSPT 监控，带滞后（hysteresis）的服务器压力等级
- 可解释的活动分与风险分（大型机器 ≠ 危险机器）
- 热点排行、邻近区块 Cluster、基础卡顿关联
- MONITOR / PROTECT 模式
- 临时限流与逐级自动恢复（不破坏建筑、不杀村民、不清空容器）
- 世界 / 区块 / 集群白名单（免自动限制，仍监控、评分、排行、记录 Incident）
- 管理员命令、通知去重与合并、Lag Incident 历史（YAML）

## 安装方法

1. 使用 Java 21 构建：`gradlew.bat build`
2. 将 `build/libs/FarmGuard-1.0.0-beta.3.jar` 放入 Paper 服务器的 `plugins/` 目录
3. 启动服务器，确认日志出现 `FarmGuard by NPUcraft` 以及 `FarmGuard enabled successfully`
4. 按需编辑 `plugins/FarmGuard/config.yml` 后执行 `/fg reload`

## 系统需求

- Java 21
- Paper 1.21.x（API `1.21`）
- 不支持 Folia
- 无第三方运行时依赖

## 命令

主命令：`/farmguard`，别名：`/fg`

| 命令 | 说明 |
| --- | --- |
| `/fg status` | 模式、TPS、MSPT、服务器压力、活跃/高风险区块、当前限制、Debug log ON/OFF |
| `/fg top [n]` | 值得关注的设施/热点（同一 Cluster 只占一个名次） |
| `/fg inspect` | 检查当前所在 Chunk |
| `/fg inspect <x> <z>` | 检查指定 Chunk |
| `/fg inspect <world> <x> <z>` | 检查指定世界 Chunk |
| `/fg limits` | 列出正在限制的区域 |
| `/fg mode` | 查看模式 |
| `/fg mode monitor` | 仅监控 |
| `/fg mode protect` | 允许自动保护 |
| `/fg whitelist add` | 将当前 Chunk 加入白名单 |
| `/fg whitelist remove` | 移除当前 Chunk 白名单 |
| `/fg whitelist list` | 列出白名单 |
| `/fg reload` | 重载 FarmGuard 配置与语言文件（不影响其他插件） |
| `/fg language` | 查看当前界面语言 |
| `/fg language zh_CN` | 切换为简体中文（立即生效并写入 config.yml） |
| `/fg language en_US` | 切换为 English |
| `/fg help` | 帮助 |

## 权限

| 权限 | 默认 | 说明 |
| --- | --- | --- |
| `farmguard.admin` | OP | 全部管理权限 |
| `farmguard.status` | OP | 状态 |
| `farmguard.top` | OP | 排行 |
| `farmguard.inspect` | OP | 检查 |
| `farmguard.manage` | OP | 模式、白名单与界面语言 |
| `farmguard.reload` | OP | 重载配置 |
| `farmguard.bypass` | 无 | **不是**“该玩家的机器永久豁免”。V1 没有玩家归属。该权限只跳过能明确关联到当前玩家的直接行为（目前：玩家亲自触发的繁殖限制）。长期自动机器请用 Chunk / Cluster 白名单。 |

普通玩家默认不能查看性能数据。

## 运行模式

- **MONITOR（默认）**：收集、分析、排行、通知、记录。绝不主动限制游戏行为。
- **PROTECT**：在服务器压力和区域风险同时满足配置条件时，允许临时限流。

首次安装不会自动限制玩家。

## 配置说明

主文件：`plugins/FarmGuard/config.yml`

- `mode`：MONITOR / PROTECT
- `language`：界面语言，默认 `zh_CN`
- `monitoring`：窗口长度、TTL、最大跟踪区块数
- `server-pressure`：TPS/MSPT 进出阈值、最短持续时间、冷却
- `activity-thresholds`：各机制每秒参考线与实体密度
- `risk`：权重、压力乘数、分数边界
- `lag-correlation`：共变判定与 STRONG/POSSIBLE 证据保持窗口（非永久锁死，也不会把 CRITICAL 本身当成关联）
- `protection`：限流令牌桶、升级条件
- `whitelist`：世界忽略、区块白名单
- `notifications`：控制台/管理员通知冷却
- `history`：Incident 文件与上限
- `debug-log`：独立诊断 JSONL 日志，默认关闭
- `debug`：默认关闭（仅用于聚合 tick 失败时打印堆栈，不是诊断日志）

玩家可见文案：`plugins/FarmGuard/lang/zh_CN.yml` 与 `en_US.yml`。旧版 `messages.yml` 如存在会迁移到 `lang/zh_CN.yml`，原文件保留作备份。

## Language

默认界面为**简体中文**。内置：

```text
zh_CN
en_US
```

配置：

```yaml
language: zh_CN
```

命令：

```text
/fg language
/fg language zh_CN
/fg language en_US
```

`/fg language en_US` 立即切换全部管理员命令与通知，并写入 `config.yml`。不需要重启。`/fg reload` 会重新读取语言文件和 `language:`。

这是服务器全局语言，不是按玩家自动检测。诊断日志 `debug.log` 保持机器可读的英文/enum 字段，不随界面语言变化。

`/fg top` 回答「哪里值得看」：按已有热点排序做展示层 Cluster 去重，不改变 Risk / Activity 算法。`/fg inspect` 回答「这里发生了什么」。风险文案使用中性描述（例如「漏斗活动较高」），颜色表达严重程度。

## Monitor Only

`mode: MONITOR` 时，ProtectionManager 仍会计算“建议保护等级”，但 `shouldThrottle` 为 false。所有保护 Listener 只能通过这一道门取消/改写游戏事件，因此 MONITOR 不会 `setCancelled`、不会改红石电流、不会改物品栏、不会删除实体。

`/fg mode monitor` 会立即释放已有 THROTTLE / EMERGENCY，而不是等待退出滞后。

`/fg inspect` 中的实体数量是 **估算实体密度**，会因传送、卸载、转化、插件移除等产生漂移，不是精确实时计数。

## 自动保护说明

PROTECT 模式下默认行为：

- 服务器 NORMAL：只监控
- WARNING：提高观察
- HIGH：允许对 HIGH / CRITICAL 区域做 **漏斗 / 刷怪笼类生成 / 繁殖** 等较窄限流
- CRITICAL：在区域也为 CRITICAL 且存在非 NONE 的卡顿关联时，才允许对红石、活塞、掉落物、矿车做 EMERGENCY 抑制。关联来自最近窗口内的 activity/MSPT 共变证据，并在 `lag-correlation.strong-hold-seconds` / `possible-hold-seconds` 内保持；MSPT 停止上升不会立刻清掉证据，CRITICAL 本身也不会凭空变成 STRONG。从未共变过的稳定高产区（例如 Farm A）保持 NONE。

默认 **不会** 因为某 Chunk 是热点就取消所有 `CreatureSpawnEvent`。可限流的 `SpawnReason` 默认只有：

- `SPAWNER`
- `TRIAL_SPAWNER`
- `SLIME_SPLIT`

以下原因默认不限制（配置里也不应轻易打开）：`NATURAL`、`COMMAND`、`CUSTOM`、`RAID`、`PATROL`、`BREEDING`、`SPAWNER_EGG`、`BUILD_WITHER`、`BUILD_IRONGOLEM`、`NETHER_PORTAL` 等。名称按 Paper `CreatureSpawnEvent.SpawnReason.name()` 匹配。

掉落物保护默认关闭。即使打开，也只取消没有 thrower/owner 的 **新** `ItemSpawnEvent`，不会删除已有 Item 实体。

矿车保护只取消 **新的** `VehicleCreateEvent`，不删除已有矿车。

繁殖限制只 `cancel` `EntityBreedEvent`，不杀死动物、不删除已存在的幼体。

漏斗限制只处理 `InventoryMoveItemEvent` 且 initiator 为漏斗 / 漏斗矿车，不处理玩家点击背包。

保护使用确定性令牌桶，而不是随机取消。令牌补充最多回溯 2 秒，避免服务器卡顿后一次性放出巨量 token。

恢复路径：`EMERGENCY → THROTTLE → WARNING → NORMAL`，需要稳定窗口，避免限制抖动。 `/fg mode monitor` 会立即解除限制。

## 性能设计

- Listener 只做：定位 ChunkKey + 计数 + 返回
- 禁止每 tick 遍历实体/区块/红石
- 不监听昂贵的 `BlockPhysicsEvent`
- 复杂分析只在低频聚合任务中进行
- 区块指标有 TTL 和数量上限
- 不长期持有 World / Entity / Block / Chunk
- 文件 IO 异步；主线程不写磁盘
- DEBUG 日志默认关闭
- Debug Diagnostic Log 默认关闭；开启后也只做低频快照 + 状态变化，异步写入 `plugins/FarmGuard/logs/debug.log`

## Debug Diagnostic Log

独立诊断日志，用于真实 Beta Pilot 分析卡顿、误报、限流和恢复。**默认 OFF。**

这是聚合日志，不是逐条 Minecraft 事件轨迹。Listener 热路径仍然只做计数；`debug.log` 记录的是周期性快照和显著状态变化。开启后由独立 bounded writer 线程异步写盘，主线程不执行日志文件 IO。

### Configuration

```yaml
debug-log:
  enabled: false
  snapshot-interval-seconds: 10
  hotspot-top-n: 10
  max-file-size-mb: 32
  max-files: 5
```

真实部署需要分析时把 `enabled` 改成 `true`，然后执行 `/fg reload`。关闭同理。不需要重启服务器，也没有单独的 `/fg debug on|off` 命令。`/fg status` 的诊断日志开启/关闭（英文界面为 `Debug log: ON/OFF`）反映 writer 是否真正在跑。

### Log path and rotation

```
plugins/FarmGuard/logs/debug.log
```

内容是 JSON Lines（每行一个事件，公共字段 `ts` / `schema` / `session` / `type`，`schema: 1`）。默认约 32 MB 后轮转为 `debug.1.log` … 最多保留 `max-files` 个文件。

`server_snapshot` 还可包含：

- `onlinePlayers`（在线人数）
- `playersByWorld`（各世界人数，不含玩家名/UUID）
- `chunkLoads` / `chunkUnloads` / `newChunkLoads`（周期内计数，不是逐条 Chunk 事件）
- `trackedChunksDelta`

用于区分跑图、新区块生成和原地自动化活动。LOW/MEDIUM 的频繁 `risk_transition` 可能被聚合成 `risk_transition_summary`；进入或离开 HIGH / CRITICAL 仍立即写 `risk_transition`。机器字段保持英文 enum，不随界面语言变化。

### Debug Log — High Activity Servers

默认 `32MB × 5` 适合普通服务器。工业级高活动服日志可能到约数 MiB/小时量级，默认保留窗口大约只有几天。

如果希望尽量覆盖约 72 小时诊断数据，可以**自行**增加文件个数，例如：

```yaml
debug-log:
  max-file-size-mb: 32
  max-files: 8
```

这不是自动配置，也不保证固定小时数；实际可保留时长取决于服务器活动量。

### Privacy

日志不记录玩家 IP、聊天、命令全文、密码、token、完整背包或玩家 UUID 行为跟踪。允许记录在线人数和按世界的人数统计。世界名和 Chunk 坐标可以出现。

### Performance notes

默认关闭时几乎没有额外开销。开启后只读取已经计算好的 FarmGuard 状态做快照，不会为了写日志再扫描 Chunk / Entity，也不会重新计算 Risk / Cluster。

### How to share diagnostics

遇到异常卡顿、误报、异常限流、恢复慢或无法确定热点时：

1. 开启 `debug-log.enabled: true`
2. `/fg reload`
3. 复现问题
4. 保留问题发生前后至少 5–15 分钟
5. 提供 `debug.log`
6. 如果已经轮转，同时提供 `debug.1.log` 等相邻文件

不要发送整个 server world。说明问题大概时间、Paper 版本、FarmGuard mode，以及（如果知道）所在 world / chunk。


## 测试方法

单元测试（不启动 Minecraft）：

```
gradlew.bat test
```

真实 Paper 测试服请遵循仓库根目录 **[TESTING.md](TESTING.md)**（Phase 14 受控验证）。不要使用 Bukkit `/reload`。

1. 红石时钟：`/fg top` 应看到 Redstone / Oscillation
2. 高频活塞：Piston 活动上升
3. 大量漏斗：Hopper 成为主要原因
4. 大量村民：Villager density + 活动
5. 大量掉落物：Item / Runaway growth
6. 大量矿车：Minecart
7. 大量实体生成：Spawn
8. Monitor 模式：只报警，不取消事件
9. Protect 模式：服务器持续高压时对该区域限流
10. 自动恢复：压力下降后逐级解除，不破坏建筑

## 已知限制

- 这是 **Beta**，推荐测试服或有管理员观察的小型/中型服务器，不是 Production Stable
- 不识别具体农场种类
- 不追踪玩家归属
- Protection 是 Chunk 级，不是精确机器轮廓
- Cluster 边界是相邻热点的近似聚合
- 卡顿关联是统计同步，不是因果证明
- 实体密度是估算（事件增减 + 热点抽样），不是精确计数
- analysis / census 有预算上限
- 不支持 Folia 分区调度
- 掉落物限流默认关闭
- 红石自动抑制对部分 observer-clock 模式效果有限；不能保证冻结所有高频红石
- 活塞 EMERGENCY 抑制覆盖面小于漏斗保护
- `/fg reload` 不会用 config 覆盖已持久化的 `state.yml` 模式/白名单
- 历史为本地 YAML，无数据库

## V1.5 Roadmap

- 具体农场类型识别（铁、甘蔗、刷怪塔等）
- 玩家/建造者关联
- GUI
- PlaceholderAPI / 领地联动
- Discord / Webhook
- 更长周期基线

## 构建

```
gradlew.bat clean build
```

产物：`build/libs/FarmGuard-1.0.0-beta.3.jar`
