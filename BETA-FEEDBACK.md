# FarmGuard Beta Feedback

当前版本：`1.0.0-beta.1` 起的 Beta 线。这不是 Production Stable。

报告问题时，请尽量同时提供：

```
plugins/FarmGuard/logs/debug.log
```

如果已经轮转，也请带上最近的：

```
plugins/FarmGuard/logs/debug.1.log
plugins/FarmGuard/logs/debug.2.log
```

（有多少带多少，不必刻意凑齐。）

## 如何打开诊断日志

默认关闭。需要分析真实服时，在 `plugins/FarmGuard/config.yml` 设置：

```yaml
debug-log:
  enabled: true
```

然后执行 `/fg reload`。不必重启服务器。

## 覆盖窗口

请尽量覆盖问题发生前后 **5–15 分钟**。

Debug Log 是聚合诊断日志，不会记录每一个 Minecraft event。它用于判断：

- 服务器何时进入压力
- FarmGuard 当时看到的 Activity / Risk / Correlation
- Protection 是否启动、如何恢复
- 实际 allowed / suppressed 数量
- 是否像误报或长时间高 Activity

请不要发送玩家 IP、聊天记录、密码或 token。世界名和 Chunk 坐标可以保留。
