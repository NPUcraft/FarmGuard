# FarmGuard Testing Guide

可重复的 Paper 1.21.x 受控验证方法。每个版本发布前按本文件执行。

不要使用 Bukkit `/reload`。插件配置使用 `/fg reload`。插件代码变更后完整停止再启动 Paper。

测试服目录（运行时，已 gitignore）：`test-server/`

配置 profile：`test-profiles/`

自动化 harness：`python scripts/phase14_validate.py`

环境变量：

- `PHASE14_IDLE_SECONDS`（默认 600）
- `PHASE14_SKIP_IDLE=1` 跳过空闲基线（不宣称已完成 10 分钟观察）
- `PHASE14_SKIP_PERSIST=1` 跳过停服/损坏 YAML 重测
- `PHASE14_START_TIMEOUT` 启动等待秒数

`/fg reload` 只重载 `config.yml` / `messages.yml`。**运行模式以 `state.yml` 为准。** 复制测试 profile 后必须再执行 `/fg mode monitor` 或 `/fg mode protect`。不要用 Bukkit `/reload`。

---

## Smoke Test

1. Java 21 + Paper 1.21.8（或与 `paper-api` 尽量接近的 1.21.x）。
2. 将 `build/libs/FarmGuard-*.jar` 放入 `test-server/plugins/`。
3. 使用 `test-profiles/monitor/config.yml`（默认 jar 内配置已是 MONITOR）。
4. 启动：`java -Xms512M -Xmx2G -jar paper.jar --nogui`
5. Console 必须出现：
   - `FarmGuard enabled successfully`
   - `Mode: MONITOR`
   - `Protection disabled because mode is MONITOR`
6. 不得出现 `NoClassDefFoundError` / `NoSuchMethodError` / `InvalidPluginException`。
7. `/plugins`：FarmGuard 为 enabled。
8. Console 执行：
   - `/fg help`
   - `/fg status`
   - `/fg top`
   - `/fg limits`
   - `/fg mode`
   - `/fg whitelist list`
   - `/fg reload`
   - `/fg inspect world 0 0`
9. Player-only：`/fg inspect`、`/fg whitelist add` 应提示需要玩家，不得抛 exception。
10. 错误参数：`/fg top abc`、`/fg mode xxx`、`/fg inspect abc def` 应有清晰中文错误。

---

## MONITOR Safety

`mode: MONITOR` 时下列行为必须与无插件时相同：

- 按钮、拉杆、门、中继器、比较器、侦测器、红石时钟
- 活塞 / 粘性活塞推拉
- Chest → Hopper → Chest 完整传输，物品总数守恒
- 玩家丢出的钻石等贵重物品不得消失
- 方块破坏掉落正常
- Minecart / Hopper Minecart 可创建并保留
- 自然生成、刷怪笼、COMMAND/CUSTOM 生成不被 MONITOR 取消
- 繁殖不杀死亲本、不删除已有幼体

任一项改变游戏行为 = **CRITICAL FAILURE**，修复后重跑本节全部项目。

Harness 可自动验证：漏斗传输守恒、召唤 Item/矿车/COMMAND 实体保留、控制台 inspect。玩家点击门/按钮仍需人工。

---

## Load Scenarios

分阶段增加，不要一次把服务器冻死。全程先 MONITOR。

| 场景 | 做法 | 观察 |
| --- | --- | --- |
| Idle 10 min | 无压力设施 | TPS/MSPT、tracked chunks、1s/5s/60s 是否有周期 spike |
| Redstone small/medium/heavy | 侦测器时钟逐步加密 | `/fg top` `/fg inspect` REDSTONE，门不应单独上榜 |
| Piston | 周期活塞阵列 | PISTON activity，单个活塞不应长期占榜 |
| Hopper A/B/C | 单漏斗 → 小分类 → 持续运输 | 静止漏斗 activity 低；持续 transfer 才升高 |
| Villager 10/50/100+ | 逐步增加 | 估算实体密度；服务器健康时 Risk 不应无脑 CRITICAL |
| Item 少/中/大量 | 召唤或掉落 | ITEM、RUNAWAY_GROWTH；MONITOR 不得删除 |
| Minecart | 普通/漏斗矿车 | 不删除已有车辆 |
| 三设施对比 | 红石 / 漏斗 / 村民 分 Chunk | `/fg top` 主 activity family 应可区分 |
| Cluster A | 漏斗跨 3–4 相邻 Chunk | Cluster ID 多次刷新稳定 |
| Cluster B | 相邻漏斗 vs 红石 | 不应仅因相邻热点而合并 |
| Cluster C | 停机等待 TTL | Cluster 消失 |
| Lag correlation | 先稳定 Farm A，再启动真正抬 MSPT 的 Farm B | A 不应只因绝对值得到 STRONG |

记录 Before / During / After 的 TPS、MSPT、Risk、Activity、Protection。没有真实数字就留空，不要编造。

---

## PROTECT Safety

必须先完成 MONITOR 测试。

Profiles：

- `test-profiles/safe-protect/config.yml`：生产级保守阈值 + `mode: PROTECT`
- `test-profiles/trigger-protect/config.yml`：**仅测试**，阈值被故意降低。禁止用于生存服。测完恢复 MONITOR。

复制到 `plugins/FarmGuard/config.yml` 后执行 `/fg reload`（不要 `/reload`）。

检查：

1. 健康服务器 + 活跃机器：红石/活塞/掉落物/矿车不应被限制。
2. Hopper 在真正 THROTTLE 时传输变慢，但 **源+漏斗+目的地物品总数守恒**。
3. 红石/活塞 EMERGENCY 仅在 Server CRITICAL + 区域 EMERGENCY + 关联非 NONE + 持续时间满足时出现。记录同 Chunk 门的误伤。若不可接受：默认关闭该 suppression，而不是做 V1.5 机器识别。
4. 刷怪笼：仅 `SPAWNER`/`TRIAL_SPAWNER`/`SLIME_SPLIT` 默认可限流。NATURAL/COMMAND/CUSTOM/RAID 不被同 Chunk 风险误杀。
5. 繁殖：可取消新的 breed 事件；亲本和已有幼体仍在。
6. Item throttling 默认 OFF。即使 EMERGENCY 也不应删除已有 Item。
7. 已有矿车不删除；新 `VehicleCreate` 仅 EMERGENCY 可能被取消。

---

## Recovery

1. 制造真实 THROTTLE/EMERGENCY 后立即 `/fg mode monitor`。Hopper/红石/活塞/生成/繁殖必须马上不再被 FarmGuard 取消，不能等 cooldown。
2. 停止压力源后保护应 `EMERGENCY → THROTTLE → WARNING → NORMAL`，记录每段耗时，不得快速振荡。
3. 白名单：`/fg whitelist add` 或 config `chunks`。仍出现在 inspect/top/risk，但不再自动限制。移除后条件满足应能再次进入保护。

---

## Persistence

1. 完整 `stop`，确认 onDisable 无 async-after-disable / scheduler warning。
2. 再启动：`state.yml` / `incidents.yml` 可读，模式符合预期。
3. 停服后损坏 `incidents.yml`：仍能 enable，文件改名为 `*.corrupt-*`，Console 有 warning。
4. 损坏 `state.yml`：安全默认，**不得意外进入 PROTECT**。
5. `plugins/FarmGuard/` 不得无限快速增长。

不要用 Bukkit `/reload` 验证上述项目。

---

## Profiling

Paper 1.21.8 自带 spark。可执行 `/spark profiler start`，复现负载后 `/spark profiler stop`，在报告中搜索 `com.npucraft.farmguard`。

关注 listener、analysis、census `getEntities`、risk、cluster、sort、notification、history snapshot。

不要把「服务器 TPS 正常」当成 FarmGuard 自身不占 CPU 的证明。

若无法采样：使用 `/tps`、`/fg status` 的 MSPT、以及 1s/5s/60s 是否出现规律 spike。

FarmGuard 空闲时应几乎不可感知。不要为 zero allocation 牺牲可维护性。

---

## Release Checklist

V1 不允许 Beta，若任一项失败：

- [ ] MONITOR 改变游戏行为
- [ ] Hopper 复制或吞物品
- [ ] 已有实体被自动删除
- [ ] 插件主动 load Chunk
- [ ] analysis 造成明显周期 lag
- [ ] Protect→Monitor 仍取消事件
- [ ] 自动恢复失败
- [ ] 严重 exception
- [ ] Map 无边界增长
- [ ] 历史文件无限快增长

允许作为 V1 限制写入文档：

- Chunk 级 EMERGENCY 可能影响同 Chunk 正常红石
- 实体密度是估算
- Cluster 边界不精确
- Lag correlation 不是因果证明
- 极多热点受 analysis-max-chunks 预算限制
- 不支持 Folia

发布结论只能是：`NOT READY` / `READY FOR MORE CONTROLLED TESTING` / `READY FOR 1.0.0-BETA.1`。不要标 Production Stable。

---

## Phase 15 automation

冻结构建：`python -c "from scripts.phase15_common import freeze_record; import json; print(json.dumps(freeze_record(), indent=2))"` 或运行 harness 时写入 `phase15-freeze.json`。

短验证（spark 5+5 分钟、Farm A/B、真实压力尝试）：

```
python scripts/phase15_validate.py
```

环境变量：

- `PHASE15_SPARK_SECONDS` 默认 300
- `PHASE15_FARM_A_SECONDS` 默认 60
- `PHASE15_MAX_MOBS` 默认 360
- `PHASE15_TARGET_MSPT` 默认 50
- `PHASE15_SKIP_SPARK=1` 跳过 profiler（不得把跳过写成 spark PASS）

2 小时长跑（Beta.1 强制 Gate，不可缩短后宣称通过）：

```
python scripts/phase15_soak.py
```

或 `scripts/phase15-soak.cmd`

默认 `PHASE15_SOAK_SECONDS=7200`。若小于 7200，脚本会警告，且 `meetsTwoHourGate` 为 false。摘要：`test-server/phase15-soak-summary.json`。

spark 语法以当前 Paper 为准。先 `/spark help`。Paper 文档示例：

```
/spark profiler start --timeout 300
/spark profiler info
/spark profiler stop
```

Windows 上 Paper 自带 spark 可能回退到 Java engine（无 async-profiler）。报告 URL 出现在控制台 / `logs/latest.log`。

Phase 15 已捕获的 spark 会话（元数据 JSON，方法树需在浏览器 Sources 视图打开）：

- Idle ~5 min：https://spark.lucko.me/q6g8Uwp4iZ
- Attempted load ~5 min（无玩家时远端漏斗场未抬高 MSPT，画像接近空闲）：https://spark.lucko.me/8PqaEKYsuu

无玩家时：远离 spawn 的 Chunk 即使 `forceload`，本环境也**没有**观察到 Hopper `InventoryMoveItemEvent` / MSPT 上升。Lag Correlation、真实 HIGH/CRITICAL、Hopper 保护完整性需要**玩家进入该区域**或确认 `forceload query` 后区块确实在 tick。不要用 trigger-protect 阈值冒充真实压力。

---

## Phase 15 Manual Player Checklist

当前 Cursor 环境**不能**操作 Minecraft 客户端。下列任一项未由真人玩家勾选前，Player MONITOR Safety / Breeding / Spawner / 活塞 ghost 均必须保持 **NOT EXECUTED**。

测试服：`test-server/`，游戏端口 **25570**，RCON **25576**（避免与其它本机 Minecraft 服务抢 25575），`online-mode=false`。先 `mode: MONITOR`（`/fg mode monitor`）。**不要**用 Bukkit `/reload`。

记录表：复制本表，每行填 PASS / FAIL / SKIP，以及 TPS、MSPT、`/fg inspect` 摘要。

### A. MONITOR Redstone（必须）

开始前：`/fg status` 确认 MONITOR，正在限制 = 0。

| # | 操作 | 期望 | 结果 | 备注 |
| --- | --- | --- | --- | --- |
| A1 | 按钮控制红石灯 | 按下亮、松开灭 |  |  |
| A2 | 拉杆 | 切换稳定，不自动回弹 |  |  |
| A3 | 木门 / 铁门 / 活板门 | 开关正常 |  |  |
| A4 | 中继器 | 延迟与锁定正常 |  |  |
| A5 | 比较器 | 容器信号正常 |  |  |
| A6 | 侦测器时钟 | 持续闪烁，不卡死 |  |  |

任一项被 FarmGuard 取消或 ghost = **FAIL / P0**。

### B. MONITOR Piston（必须，看客户端）

| # | 操作 | 期望 | 结果 | 备注 |
| --- | --- | --- | --- | --- |
| B1 | 普通活塞推拉 | 方块与活塞头一致 |  |  |
| B2 | 粘性活塞 | 拉回正确 |  |  |
| B3 | 活塞时钟 | 无 ghost、无卡住活塞头 |  |  |
| B4 | 史莱姆/蜂蜜推拉（可选） | 无复制/丢失方块 |  |  |

方块复制/丢失 = **P0**。明显 ghost/desync = **P1**。

### C. MONITOR Player Item / Minecart

| # | 操作 | 期望 | 结果 | 备注 |
| --- | --- | --- | --- | --- |
| C1 | 丢出泥土 | 不消失 |  |  |
| C2 | 丢出命名物品 | 不消失 |  |  |
| C3 | 丢出附魔物品 | 不消失 |  |  |
| C4 | 丢出钻石或其它贵重物品 | 不消失 |  |  |
| C5 | 放置矿车 | 可坐下、可破坏收回 |  |  |
| C6 | 放置漏斗矿车 | 保留且可工作 |  |  |

### D. Breeding（真人喂食）

MONITOR：

| # | 记录 |
| --- | --- |
| 动物种类 |  |
| 食物是否扣除 |  |
| 爱心 |  |
| 幼体 |  |
| 父母存活 |  |

PROTECT：仅当该区域确实 THROTTLE/EMERGENCY 且 breeding 生效时再测。记录：食物是否已扣、能否再次繁殖、有无 ghost baby。若取消 Event 体验很差：考虑 V1 默认关闭 breeding throttle。

### E. SpawnReason（需要玩家在场）

`gamerule doMobSpawning true`。记录 `/forge` 不可用；用 `/fg inspect` + 观察实体。

| Reason | 做法 | MONITOR | PROTECT 热点 Chunk |
| --- | --- | --- | --- |
| COMMAND | `/summon` | 应生成 | 默认**不应**因热点取消 |
| NATURAL | 夜晚、玩家附近黑暗地面 | 应生成 | 默认**不应**取消 |
| SPAWNER | 玩家进入 16 格，刷怪笼工作 | MONITOR 必须工作 | 仅真正满足保护条件才可能限流 |
| CUSTOM / RAID / TRIAL_SPAWNER | 若场景可建 | 记录实际 SpawnReason | 默认 RAID/CUSTOM 不在 deny-list |

### F. 红石 / 活塞 EMERGENCY（需真实 CRITICAL）

仅当 `/fg status` 显示 CRITICAL 且 `/fg limits` 为 EMERGENCY、inspect 关联非 NONE。同 Chunk 放拉杆、门、红石灯。记录误伤。严重不可用 → 不要保留默认自动红石/活塞 suppression。

### G. 签核

玩家 ID：________  日期：________  MONITOR 全表：________  发现 P0/P1：________

---

## Phase 16 — Automated Bot Integration

Phase 15 历史结果不得改写。未执行项在 Phase 15 报告中仍为 NOT EXECUTED。Phase 16 是独立自动化门禁。

入口：

```
python scripts/phase16/run.py --until login
python scripts/phase16/run.py --until spawn
python scripts/phase16/run.py --until recovery
python scripts/phase16/run.py --until all --soak 2h --clean-build
```

测试依赖（不进入 FarmGuard.jar / Release ZIP）：见 `test-support/DEPENDENCIES.md`

- Fake Player Plugin 2.0.6 → `test-server/plugins/`（控制台不能 `/fpp spawn`，必须由在线玩家执行）
- Mineflayer 4.39.0 → `test-bot/`（已证明可登录 Paper 1.21.8）
- FarmGuardTestProbe → `test-support/FarmGuardTestProbe/`（`/fgtest` 输出 `FGTEST {json}`）

报告：`test-results/phase16/`（gitignored）

本轮已自动完成（MONITOR / 协议 / SpawnReason）：按钮、拉杆、木门、铁门、活板门、中继器、比较器、活塞推拉最终状态、丢物回收、矿车放置、繁殖、COMMAND/NATURAL/SPAWNER。

本轮已自动完成 Phase 16 Beta Gate：MONITOR / 协议 / SpawnReason、生产阈值下 HIGH / CRITICAL / EMERGENCY、漏斗守恒、LagCorrelation、自动恢复、Protect→Monitor、JFR、2 小时 soak、Restart Gate。版本：`1.0.0-beta.1`（Beta，不是 Production Stable）。

Phase 16B 增加独立测试插件内的 Controlled Tick Pressure（`FarmGuardTestProbe` / `TickPressureController`）。它只在 Paper 主线程做有上限的 CPU 工作，不进入 FarmGuard.jar，不调用 FarmGuard 内部 API，不 mock MSPT。报告用语是 SYNTHETIC CONTROLLED TICK PRESSURE，不是“原版农场导致 65ms”。

```
python scripts/phase16/run.py --from pressure --until pressure
python scripts/phase16/run.py --from pressure --until recovery
```

2 小时 soak 仅在 `test-results/phase16/pre-soak-gate.json` 的 `readyForSoak` 为 true 后才允许。该文件要求真实 HIGH / CRITICAL / EMERGENCY、MONITOR 安全、相关性、漏斗守恒、自动恢复均 PASS，且无未解决 P0/P1。

LagCorrelation 对已观察到的 STRONG/POSSIBLE 共变证据有限时保持（`lag-correlation.strong-hold-seconds` / `possible-hold-seconds`）。不得把 Server CRITICAL 本身当成关联，也不得让 Farm A 这类预先稳定的高活动区误升 STRONG。


