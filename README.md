# CubeCubeBall 1.1.0

CubeCubeBall 是一个支持 Folia、Luminol 和 Paper 1.21.11 的方块足球小游戏插件。本版本在原项目基础上重做了管理 GUI、修复了分页与输入边界，并增加了完整的比赛视觉与音效反馈。

作者：xWtree<br>
原作者与贡献者：Crylonz、squi2rel、cloudfl4re<br>
网站：[github.com/mcxqk](https://github.com/mcxqk)<br>
许可证：GNU GPL v3，详见 `LICENSE`。

## 安装

1. 使用 Java 21 或更高版本运行服务器。
2. 将 `target/cubeball-1.1.0.jar` 放入服务器的 `plugins` 目录。
3. 启动服务器后，管理员使用 `/ccb help` 查看指令，使用 `/ccb` 打开管理中心。
4. 如果安装了 CraftEngine、Residence、Emotecraft 或 BodySize，插件会自动启用对应联动；它们都是可选依赖。

## 常用指令

| 指令 | 权限 | 用途 |
| --- | --- | --- |
| `/ccb help` | 无 | 查看帮助 |
| `/ccb` | `cubeball.manage` | 打开管理中心 |
| `/ccb join [场地]` | 无 | 加入等待大厅；省略场地时会列出可用场地 |
| `/ccb reload` | `cubeball.admin` | 异步热加载语言、视觉、音效和通用配置 |
| `/ccb spawn` | `cubeball.admin` | 设置等待大厅传送点 |
| `/ccb exitspawn` | `cubeball.admin` | 设置比赛结束与退出传送点 |
| `/ccb pause [场地]` | `cubeball.admin` | 管理员技术暂停 |
| `/ccb resume [场地]` | `cubeball.admin` | 恢复暂停中的比赛 |
| `/ccb end [场地]` | `cubeball.admin` | 强制结束比赛 |
| `/ccb votepause <5\|10\|yes\|no>` | `cubeball.timeout` | 发起或参与暂停投票 |

## GUI 使用

- 首页提供创建场地、场地列表、等待大厅位置、退出位置、足球发光和滚动动画开关。
- 场地列表支持上一页和下一页，场地图标会显示未配置、已就绪、比赛中或加时赛状态。
- 场地配置页分为队伍出生点、球门区域、足球出生点、足球方块、CraftEngine 外观、冲刺冷却、比赛时间和目标分数。
- 出生点支持左键添加、右键移除最后一个、Shift + 右键清空；每队最多 20 个。
- 球门使用石铲左键选择第一个角、右键选择第二个角；再次点击对应菜单项可清空区域。
- 所有聊天输入都可以输入 `T` 取消，输入错误时会返回当前菜单。
- 删除场地必须在确认页面再次点击，避免误操作。
- 比赛开始后场地配置会锁定，只能查看比分或终止比赛。

## 热加载说明

`/ccb reload` 会异步读取配置和语言文件，再在全局上下文应用通用设置。它会热加载：语言、创建数量上限、BossBar 队名、大厅/退出位置、足球发光与滚动、视觉粒子和音效开关。正在进行的比赛和 `matches` 场地数据不会被替换，以免热加载造成比赛中断。

## 视觉配置

`config.yml` 已为每一项加入中文注释。`visuals.enabled` 是总开关，下面的分项开关可以分别控制菜单音效、场地设置反馈、大厅反馈、比赛反馈、足球碰撞反馈、进球效果和足球运动尾迹。高人数服务器可以将 `ball-trail.interval-ticks` 调大，或关闭 `ball-trail.enabled`。

## 构建

```text
mvn -Dmaven.repo.local=<可写缓存目录> package
```

构建产物：`target/cubeball-1.1.0.jar`。项目使用 Paper API 1.21.11、Java 21，并通过统一调度封装兼容 Folia 与 Paper。
