# Codex 手表宠物 UI 设计

目标设备：OPPO Watch X2，圆形 Wear OS 手表。

## 设计方向

手表应用应该在两秒内回答一个问题：“我现在还能继续使用 Codex 吗？”

宠物是情绪反馈层，不是装饰。它会映射额度健康状态：

| 数据状态 | 屏幕情绪 | 宠物动画 |
| --- | --- | --- |
| 本轮剩余额度 >= 35% | 平稳 / 就绪 | `idle` |
| 本轮剩余额度 15-34% | 注意 | `review` |
| 本轮剩余额度 < 15% | 低能量 | `failed` |
| Codex 任务进行中 | 工作中 | `running` |
| 额度刚刷新 | 庆祝 | `jumping` 或 `waving` |

现有 Codex 宠物图集尺寸为 `1536 x 1872`，按 8 列 9 行排列。每一帧是 `192 x 208`。

Codex 宠物工具里已知的行映射：

| 行 | 状态 | 帧数 |
| --- | --- | --- |
| 0 | `idle` | 6 |
| 1 | `running-right` | 8 |
| 2 | `running-left` | 8 |
| 3 | `waving` | 4 |
| 4 | `jumping` | 5 |
| 5 | `failed` | 8 |
| 6 | 预留 / 空行 | 0 |
| 7 | `running` | 6 |
| 8 | `review` | 6 |

第一版默认宠物：`yukino`，存放在：

`wear-app/app/src/main/assets/pets/yukino/spritesheet.webp`

## 主屏

目的：快速查看额度状态。

布局：

- 顶部弧线：紧凑状态标签，例如 `READY`、`LOW` 或 `WORKING`。
- 中央：大号本轮额度数字，例如 `89%`。
- 中央周围：使用额度颜色的圆形进度环。
- 下方中央：动画 Codex 宠物。
- 底部：重置倒计时，例如 `5h resets in 1h 52m`。

颜色：

- 就绪：绿色强调色。
- 注意：琥珀色强调色。
- 低额度：红色强调色。
- 工作中：蓝色强调色。
- 背景：接近黑色，以符合 Wear OS 上 AMOLED 省电显示习惯。

## 详情屏

目的：在不挤占主屏的前提下显示有用数字。

使用纵向滚动 Wear OS 列表：

- 本轮额度卡片：剩余百分比和重置时间。
- 每周额度卡片：剩余百分比和重置时间。
- 今日 token 卡片。
- 最近 7 天 token 卡片。
- 最近同步行。

## 磁贴

目的：不用打开应用，滑动一次就能快速查看。

磁贴布局：

- 应用标签：`Codex`
- 大号数字：本轮剩余百分比。
- 小号数字：每周剩余百分比。
- 很小的静态宠物帧：第一帧 `idle`。
- 底部操作：打开应用。

磁贴应该使用静态宠物帧来保护电量。

## 环境模式

手表变暗时：

- 停止宠物动画。
- 显示第一帧静态 `idle`。
- 只保留额度数字、细进度环和重置文本。
- 避免明亮的全屏色块。

## Windows 端需要的数据结构

Wear 应用只需要一个紧凑 JSON：

```json
{
  "updated": "14:33:44",
  "model": "gpt-5.5",
  "plan": "Plus",
  "status": "ready",
  "session": {
    "remaining_percent": 89,
    "used_percent": 11,
    "resets_in": "1小时 52分钟"
  },
  "weekly": {
    "remaining_percent": 98,
    "used_percent": 2,
    "resets_in": "2天 21小时"
  },
  "tokens": {
    "today": 403029,
    "last_7_days": 2489544,
    "last_30_days": 249687898
  },
  "pet": {
    "id": "yukino",
    "state": "idle",
    "atlas_columns": 8,
    "frame_width": 192,
    "frame_height": 208
  }
}
```

## 第一版实现建议

先实现完整应用的主屏和详情列表。等数据服务和 APK 安装流程稳定后，再添加磁贴。
