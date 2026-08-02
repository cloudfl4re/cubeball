<div align="center">

<img src="cube-ball-logo.png" alt="CubeCubeBall" width="760" />

# CubeCubeBall

**面向 Luminol、Folia 与 Paper 1.21.11 的多人方块足球系统**

多场地管理 · 队伍大厅 · 目标比分 · 技术暂停 · 背包保护 · CraftEngine 足球外观

![版本](https://img.shields.io/badge/%E7%89%88%E6%9C%AC-1.1.1-2ea44f)
![Minecraft](https://img.shields.io/badge/Minecraft-1.21.11-62b47a)
![Folia](https://img.shields.io/badge/Folia-%E5%B7%B2%E6%94%AF%E6%8C%81-f5a623)
![Java](https://img.shields.io/badge/Java-21-e76f00)
![许可证](https://img.shields.io/badge/%E8%AE%B8%E5%8F%AF%E8%AF%81-GPL--3.0-blue)

</div>

---

CubeCubeBall 是一个在 Minecraft 内直接配置和运行的方块足球小游戏插件。管理员可以通过 GUI 创建多个场地，设置双方出生点、球门区域、足球出生点、赛时、目标比分和冲刺冷却；玩家可从等待大厅选择红队、蓝队或旁观者，并在比赛期间使用统一的比分、暂停与视觉反馈系统。

- **作者：** xWtree
- **贡献者：** Crylonz、squi2rel、cloudfl4re
- **项目主页：** https://github.com/mcxqk/CubeCubeBall
- **许可证：** [GNU GPL v3.0](LICENSE)

## 目录

- [主要功能](#主要功能)
- [运行环境](#运行环境)
- [安装与建场](#安装与建场)
- [比赛与恢复规则](#比赛与恢复规则)
- [命令](#命令)
- [权限](#权限)
- [配置与热加载](#配置与热加载)
- [可选联动](#可选联动)
- [文件目录](#文件目录)
- [常见问题](#常见问题)
- [构建](#构建)

## 主要功能

- 在同一服务器维护多个独立比赛场地，并通过 GUI 创建、编辑、删除和浏览场地。
- 使用红蓝双方出生点、球门区域与足球生成点快速完成场地配置。
- 支持等待大厅、队伍选择、人数倒计时、随机旁观者分配和 `/ccb join` 直接加入。
- 支持常规赛、目标比分、加时、进球排行榜、管理员技术暂停和队伍暂停投票。
- 管理员可强制结束正在进行的比赛；尚未开赛但已扫描玩家的场地也可直接取消并清理。
- 比赛进入前会持久化玩家背包、盔甲、副手、游戏模式、飞行、饥饿和体型等状态，离场时自动恢复。
- 提供 `/ccb check <玩家名或 UUID>`，用于在线玩家的背包备份恢复；离线玩家的备份会保留到其下次上线。
- 支持足球发光、滚动、进球爆发、运动尾迹、菜单反馈和比赛结果等视觉与音效效果。
- 可选接入 CraftEngine 自定义足球外观、Residence 等待大厅/比分展示、Emotecraft 与 BodySize。
- 使用统一调度封装和线程安全状态表适配 Folia/Luminol 的 Region 线程模型。

## 运行环境

| 项目 | 目标版本 | 说明 |
| --- | --- | --- |
| 服务端 | Luminol/Folia/Paper `1.21.11` | 插件以 Paper API `1.21.11-R0.1-SNAPSHOT` 构建 |
| Java | `21` | 运行和构建都需要 Java 21 |
| 插件 API | Paper API | `plugin.yml` 已声明 `folia-supported: true` |
| 构建工具 | Maven 3.9+ | 使用 Maven Shade 生成最终 JAR |

`folia-supported: true` 仅允许插件在 Folia 上加载。CubeCubeBall 还通过 `FoliaScheduler` 路由全局、区域、实体与异步任务；部署前仍应在目标 Luminol/Folia 服务端完成实际联机验证。

## 安装与建场

1. 使用 Java 21 启动 Luminol、Folia 或 Paper `1.21.11` 服务端。
2. 将 `target/cubeball-1.1.1.jar` 放入服务器的 `plugins/` 目录。
3. 启动服务器，确认生成插件数据目录和默认 `config.yml`。
4. 管理员执行 `/ccb` 打开足球系统管理中心，创建一个场地。
5. 在场地配置页依次设置：红队出生点、蓝队出生点、双方球门区域与足球出生点。
6. 按需要设置比赛时间、目标比分与冲刺冷却。目标比分设为 `0` 表示不限分。
7. 让玩家站在对应的队伍出生点，使用“扫描玩家”确认阵容后，从 GUI 开始比赛。

场地位置建议都在同一世界内。安装 Residence 后，场地坐标会按领地规则校验；等待大厅领地名称可在 `waitingLobby.residence` 设置，留空即可关闭该项检查。

### 等待大厅

玩家可使用以下方式进入已配置场地的大厅：

```text
/ccb join <场地名>
```

大厅中可选择红队、蓝队或旁观者。可参赛人数满足条件时会进入倒计时；人数不足、队伍满员或比赛已开始时会自动取消或拒绝加入。

## 比赛与恢复规则

### 暂停与结束

| 场景 | 行为 |
| --- | --- |
| 管理员暂停 | `/ccb pause [场地]` 立即移除当前足球并进入无限期技术暂停 |
| 管理员恢复 | `/ccb resume [场地]` 以 3 秒倒计时重新开球 |
| 队伍暂停 | 参赛者通过 `/ccb votepause 5` 或 `/ccb votepause 10` 发起投票 |
| 暂停投票 | 当前在线参赛者使用 `/ccb votepause yes` 或 `/ccb votepause no` 表决 |
| 强制结束 | `/ccb end [场地]` 按当前比分结束；未开赛的已扫描场地会被取消并清理 |
| 自动结束 | 达到目标比分或比赛计时结束后进入结算；平分时进入加时 |

每支队伍每场比赛仅有一次成功的队伍暂停额度。管理员可以将队伍暂停升级为无限期技术暂停；暂停期间比赛计时不会继续推进。

### 背包保护与 `/ccb check`

比赛开始前，插件会先将玩家原始状态写入持久化备份，再清空并发放比赛装备。比赛正常结束、退出大厅或下一次安全恢复时，备份会覆盖恢复玩家状态并被清除。

```text
/ccb check <玩家名或 UUID>
```

- 仅 `cubeball.admin` 可使用。
- 目标玩家在线且存在备份时，会在该玩家的实体调度上下文中恢复背包和状态。
- 目标玩家离线时不写入离线玩家数据，备份会继续保存，等待其上线后的自动恢复流程。
- 该命令用于处理异常离场或遗留比赛状态，不会创建第二份背包内容。

## 命令

主命令为 `/ccb`。管理员命令在省略场地名时，会优先解析当前玩家所在的比赛；存在多个可操作场地时需要显式填写名称。

| 命令 | 执行者 | 权限 | 说明 |
| --- | --- | --- | --- |
| `/ccb` | 玩家 | `cubeball.manage` | 打开足球系统管理中心 |
| `/ccb help` | 玩家或控制台 | 无 | 查看可用命令 |
| `/ccb join [场地]` | 玩家 | 无 | 加入等待大厅；省略场地时列出可用场地 |
| `/ccb input <内容>` | 玩家 | 无 | 在客户端聊天受限时提交 GUI 输入；输入 `T` 取消 |
| `/ccb check <玩家名或 UUID>` | 玩家或控制台 | `cubeball.admin` | 恢复在线玩家的背包备份 |
| `/ccb reload` | 玩家或控制台 | `cubeball.admin` | 异步热加载配置、语言与视觉设置 |
| `/ccb spawn` | 玩家 | `cubeball.admin` | 设置等待大厅传送点 |
| `/ccb exitspawn` | 玩家 | `cubeball.admin` | 设置比赛或大厅退出传送点 |
| `/ccb pause [场地]` | 玩家或控制台 | `cubeball.admin` | 开始管理员技术暂停 |
| `/ccb resume [场地]` | 玩家或控制台 | `cubeball.admin` | 恢复暂停中的比赛 |
| `/ccb end [场地]` | 玩家或控制台 | `cubeball.admin` | 强制结束比赛或取消未开赛的已扫描场地 |
| `/ccb votepause <5\|10\|yes\|no>` | 玩家 | `cubeball.timeout` | 发起或参与队伍暂停投票 |
| `/ccb glow [on\|off]` | 玩家或控制台 | `cubeball.admin` | 切换足球发光 |
| `/ccb roll [on\|off\|speed <数值>]` | 玩家或控制台 | `cubeball.admin` | 切换或调整足球滚动效果 |
| `/ccb redteam <名称>` | 玩家或控制台 | `cubeball.admin` | 设置比分 BossBar 红队名称 |
| `/ccb blueteam <名称>` | 玩家或控制台 | `cubeball.admin` | 设置比分 BossBar 蓝队名称 |
| `/ccb setballhand <场地>` | 玩家 | `cubeball.admin` | 将主手物品保存为该场地的足球外观快照 |
| `/ccb setballce <场地> <命名空间:id\|clear>` | 玩家或控制台 | `cubeball.admin` | 设置或清除 CraftEngine 足球外观 ID |
| `/ccb debug [on\|off]` | 玩家或控制台 | `cubeball.admin` | 切换调试日志 |

## 权限

| 权限节点 | 默认值 | 作用 |
| --- | --- | --- |
| `cubeball.manage` | OP | 打开管理中心与管理自己拥有的场地 |
| `cubeball.admin` | OP | 管理全部场地、比赛控制、恢复命令和全局设置；包含 `cubeball.manage` |
| `cubeball.timeout` | 所有玩家 | 在参赛时发起或参与暂停投票 |
| `cubeball.commandbypass` | OP | 绕过比赛和大厅期间的命令限制 |

## 配置与热加载

默认配置位于插件数据目录的 `config.yml`。场地坐标和比赛数据建议始终通过 `/ccb` GUI 维护，不建议手工编辑 `matches`。

| 配置项 | 默认值 | 说明 |
| --- | --- | --- |
| `language` | `zh` | 消息语言，当前提供 `zh` 与 `en` |
| `player-identity.mode` | `name` | `name` 适合离线服，`uuid` 适合 UUID 稳定的正版服 |
| `maxMatchPerPlayer` | `3` | 非管理员可创建的场地上限 |
| `lobbySpawn` / `exitSpawn` | `null` | 等待大厅和退出传送点，可由命令或 GUI 设置 |
| `waitingLobby.residence` | `zqc` | 等待大厅使用的 Residence 领地名；留空关闭检查 |
| `bossbar.redteam` / `bossbar.blueteam` | 红队 / 蓝队 | 领地比分 BossBar 显示名称 |
| `ball.glow` | `true` | 足球发光开关 |
| `ball.roll.enabled` / `speed` | `true` / `1.0` | 足球滚动动画及速度 |
| `visuals.*` | 已启用 | 菜单、设置、大厅、比赛、碰撞、进球与尾迹反馈 |

执行 `/ccb reload` 会异步读取配置与语言文件，再在全局调度上下文应用通用设置。它会热加载语言、场地创建上限、BossBar 队名、大厅/退出位置、足球视觉效果和反馈开关；正在进行的比赛与 `matches` 场地数据不会被替换。

## 可选联动

| 插件 | 是否必需 | CubeCubeBall 行为 |
| --- | --- | --- |
| CraftEngine | 否 | 支持用自定义物品快照或命名空间 ID 作为足球外观；缺失时回退原版方块足球 |
| Residence | 否 | 支持等待大厅领地检查、场地位置校验与领地内比分 BossBar |
| emotecraft | 否 | 比赛流程中进行对应的表情联动控制 |
| BodySize | 否 | 比赛期间限制会改变参赛者体型的操作 |

所有可选插件都使用软依赖。缺少某项时，CubeCubeBall 会保留核心比赛功能，并禁用对应联动。

## 文件目录

```text
plugins/<CubeCubeBall 数据目录>/
├─ config.yml          # 主配置与场地数据
├─ messages.zh.yml      # 中文消息
├─ messages.en.yml      # 英文消息
└─ inv_backup/          # 玩家比赛前状态的持久化备份
```

`inv_backup/` 中的文件由插件自动维护。除非正在排查异常恢复，不应手动编辑或删除其中的玩家备份。

## 常见问题

### 扫描后无法开始比赛

确认双方都至少有一个出生点，两个球门区域与足球出生点已经完成设置，并让玩家站在对应队伍出生点后重新执行“扫描玩家”。目标比分可以设置为 `0`，表示不限制进球数。

### 玩家离开比赛后背包没有立即恢复

玩家状态会在安全的实体调度上下文中恢复。玩家重新上线后插件会自动检查备份；管理员也可以在目标玩家在线时执行 `/ccb check <玩家名或 UUID>`。

### 暂停后无法继续

管理员使用 `/ccb resume [场地]` 恢复比赛。恢复会进行 3 秒倒计时并重新开球；队伍暂停到期同样会自动进入该倒计时。

### CraftEngine 足球外观没有显示

确认 CraftEngine 已加载并且自定义物品 ID 可解析。未安装 CraftEngine、ID 不存在或资源不可用时，插件会回退为场地设置中的原版足球方块。

## 构建

项目使用 Maven 与 Java 21：

```text
mvn package
```

构建产物：

```text
target/cubeball-1.1.1.jar
```

## 开源许可

CubeCubeBall 使用 [GNU GPL v3.0](LICENSE) 开源。
