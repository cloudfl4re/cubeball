# cubeball

cubeball 是一个 Minecraft 足球插件，玩家可以用方块足球进行比赛。本分支基于原项目修改，目标运行环境为 Folia `1.21.11`。

## 主要改动

- 依赖更新为 Paper API `1.21.11-R0.1-SNAPSHOT`
- `plugin.yml` 添加 `folia-supported: true`
- 调度逻辑改为 Folia/Paper 的 Global、Region、Entity Scheduler
- 权限节点包括 `cubeball.manage`、`cubeball.admin` 和默认开启的 `cubeball.timeout`
- 构建目标更新为 Java 21

## 环境要求

- Folia `1.21.11`
- Java 21 或更高版本
- Maven 3.9+

## 构建

```bash
mvn package
```

构建完成后，插件文件位于：

```text
target/cubeball-1.0.0.jar
```

## 安装

1. 将 `target/cubeball-1.0.0.jar` 放入 Folia 服务端的 `plugins` 目录。
2. 启动或重启服务端。
3. 在游戏内使用 `/ccb` 打开管理菜单。

## 命令

| 命令 | 权限 | 说明 |
| --- | --- | --- |
| `/ccb` | `cubeball.manage` | 打开 cubeball 管理菜单 |
| `/ccb spawn` | `cubeball.admin` | 设置比赛等待大厅传送点 |
| `/ccb exitspawn` | `cubeball.admin` | 设置比赛结束后的退出传送点 |
| `/ccb pause [比赛名]` | `cubeball.admin` | 管理员无限期技术暂停当前比赛 |
| `/ccb resume [比赛名]` | `cubeball.admin` | 结束暂停并以 3 秒倒计时继续比赛 |
| `/ccb end [比赛名]` | `cubeball.admin` | 按当前比分强制结束比赛，不进入加时 |
| `/ccb votepause 5\|10` | `cubeball.timeout` | 发起本队 5 或 10 分钟暂停投票 |
| `/ccb votepause yes\|no` | `cubeball.timeout` | 对当前暂停投票表决 |

管理员命令省略比赛名时，仅在服务器上恰好有一场活动比赛时自动选择；存在多场活动比赛时请显式填写比赛名。暂停期间不会继续比赛计时或保留当前足球，当前回合直接结束且不计分；管理员暂停没有自动恢复时间，使用 `/ccb resume` 后固定进行 3 秒倒计时。管理员也可以在任何暂停阶段使用 `/ccb end` 强制结束比赛。

参赛者每队每场比赛只有一次成功的暂停额度。使用 `/ccb votepause 5` 或 `/ccb votepause 10` 发起投票，投票通过后立即结束当前回合且不计分，并暂停对应时长；暂停到期后自动进行 3 秒倒计时继续。暂停期间管理员可以使用 `/ccb pause` 接管为无限期技术暂停，但该队已使用的暂停额度不会恢复。投票失败或超时不会消耗额度。

## 权限

| 权限 | 默认 | 说明 |
| --- | --- | --- |
| `cubeball.manage` | OP | 允许打开管理菜单 |
| `cubeball.admin` | OP | 允许管理所有比赛和高级设置 |
| `cubeball.timeout` | true | 允许参赛者发起和参与暂停投票 |

## 许可证

本项目遵循 GNU General Public License v3.0。修改和分发时请保留许可证文本以及原作者信息。
