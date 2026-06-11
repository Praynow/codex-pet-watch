# Codex 手表宠物 UI 设计 V2

参考风格：圆形赛博仪表盘表盘，中央宠物，霓虹蓝/红指标，信息密度高但容易扫读。

## 视觉方向

这个版本应该更像实时仪表盘，而不是普通 Wear OS 应用页面。

- 黑色 AMOLED 背景。
- 宠物周围使用细分段弧线。
- 像素风 / 等宽风格标签。
- 健康或活动数值使用青色/蓝色。
- 警告或消耗数值使用红色。
- 小型中央 Codex 宠物作为身份锚点。

这个版本的默认宠物：

`wear-app/app/src/main/assets/pets/kabi/spritesheet.webp`

原因：参考图中央使用的是小型宠物形象。`kabi` 在很小的手表表盘尺寸下，比人形 Q 版角色更容易识别。

## 主屏布局

顶部：

- `Codex` 标题。
- 标题下方的小同步点。

中央：

- 动画宠物。
- 左右分段弧线包围宠物。
- 左侧信息块：模型。
- 右侧信息块：effort / reasoning。

指标：

- `Live Metrics`
- 本轮：`5H 4% used`，配短红色进度条。
- 每周：`WEEK 36% used`，配蓝色进度条。
- 活动：
  - 当日分钟数。
  - 当周分钟数。
- 底部电量/同步行：本地服务状态或手表电量。

## 数据映射

| UI 字段 | 来源 |
| --- | --- |
| `MODEL` | `UsageSnapshot.model` |
| `EFFORT` | 本地设置或最近任务元数据，兜底为 `XHIGH` |
| `5H used` | `session.used_percent` |
| `WEEK used` | `weekly.used_percent` |
| `DAY min` | 可选的本地 Codex 活跃分钟数 |
| `WK min` | 可选的本地 Codex 活跃分钟数 |
| 中央宠物状态 | 额度 / 工作状态 |

## 宠物状态映射

| 状态 | 宠物行 |
| --- | --- |
| ready | `running-right` 第 1 行，作为 `kabi` 的活跃待机循环 |
| working | `running` 第 7 行 |
| caution | `review` 第 8 行 |
| low | `failed` 第 5 行 |

## 实现说明

真实 Wear OS 应用里，这个版本应该做成一个紧凑 Compose 屏幕，而不是滚动列表。详情列表可以放在点击或表冠滚动之后，但 V2 的主表盘应该自成一屏。

环境模式：

- 停止动画。
- 隐藏装饰弧线动画。
- 保留标题、静态宠物帧、`5H used` 和 `WEEK used`。
