# FarmGuard

智能农场 / 自动化设施 / 卡服检测与限制插件（Paper）。

Automation Facility Monitoring, Lag Detection and Protection System for Paper.

当前版本：`1.0.0-beta.1`（**Beta**，不是 Production Stable）

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
2. 将 `build/libs/FarmGuard-1.0.0-beta.1.jar` 放入 Paper 服务器的 `plugins/` 目录
3. 启动服务器，确认日志出现 `FarmGuard enabled successfully`
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
| `/fg status` | 模式、TPS、MSPT、服务器压力、活跃/高风险区块、当前限制 |
| `/fg top [n]` | 热点 Chunk / Cluster 排行 |
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
| `/fg reload` | 重载 FarmGuard 配置（不影响其他插件） |
| `/fg help` | 帮助 |

## 权限

| 权限 | 默认 | 说明 |
| --- | --- | --- |
| `farmguard.admin` | OP | 全部管理权限 |
| `farmguard.status` | OP | 状态 |
| `farmguard.top` | OP | 排行 |
| `farmguard.inspect` | OP | 检查 |
| `farmguard.manage` | OP | 模式与白名单 |
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
- `monitoring`：窗口长度、TTL、最大跟踪区块数
- `server-pressure`：TPS/MSPT 进出阈值、最短持续时间、冷却
- `activity-thresholds`：各机制每秒参考线与实体密度
- `risk`：权重、压力乘数、分数边界
- `lag-correlation`：共变判定与 STRONG/POSSIBLE 证据保持窗口（非永久锁死，也不会把 CRITICAL 本身当成关联）
- `protection`：限流令牌桶、升级条件
- `whitelist`：世界忽略、区块白名单
- `notifications`：控制台/管理员通知冷却
- `history`：Incident 文件与上限
- `debug`：默认关闭

玩家可见文本：`plugins/FarmGuard/messages.yml`

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

产物：`build/libs/FarmGuard-1.0.0-beta.1.jar`
