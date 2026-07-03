# cubeball

cubeball 是一个 Minecraft 足球插件，玩家可以用方块足球进行比赛。本分支基于原项目修改，目标运行环境为 Folia `1.21.11`。

## 主要改动

- 依赖更新为 Paper API `1.21.11-R0.1-SNAPSHOT`
- `plugin.yml` 添加 `folia-supported: true`
- 调度逻辑改为 Folia/Paper 的 Global、Region、Entity Scheduler
- 权限节点改为 `cubeball.manage` 和 `cubeball.admin`
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

## 权限

| 权限 | 默认 | 说明 |
| --- | --- | --- |
| `cubeball.manage` | OP | 允许打开管理菜单 |
| `cubeball.admin` | OP | 允许管理所有比赛和高级设置 |

## 许可证

本项目遵循 GNU General Public License v3.0。修改和分发时请保留许可证文本以及原作者信息。
