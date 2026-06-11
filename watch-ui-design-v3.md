# Codex 手表宠物 UI 设计 V3

目标：干净、可读、准确。

这个版本去掉了 V2 里较繁复的圆形线框。表盘使用简洁的半圆形 5 小时用量弧线，以及底部 2x2 指标网格，让圆形屏幕被更充分地利用。

## 设计原则

- 一个主答案：当前 5 小时 Codex 额度。
- 一个次级答案：每周额度。
- 一个陪伴信号：宠物情绪。
- 不使用装饰性圆形网格，也不让完整圆环穿过数据区域。
- 硬性规则：有意识地使用圆形屏幕区域，不要让下半屏闲置。
- 硬性规则：线条、弧线、进度条和分隔线绝不穿过文字下方或文字内部。
- 标签和值不能重叠。
- 每个数字都要说明它表示“剩余”、“已用”还是“时间”。

## 主屏

顶部：

- 居中应用标题：`Codex`
- 表盘内不显示时间；如果需要时间，交给系统手表外壳。

上方中央：

- 大号本轮剩余百分比。
- 半圆形 `5H USED` 弧线，显示当前窗口已用量，并用文本展示精确值。
- 宠物位于主数字下方。
- 宠物和底部指标网格之间保持清晰的垂直间距。

底部网格：

- `5H USED`：当前 5 小时窗口已用百分比。
- `WEEK`：每周额度剩余百分比。
- `TODAY`：token 数或活跃分钟数。
- `7D`：token 数或活跃分钟数。

## 推荐标签

使用明确标签来避免歧义：

- `5H LEFT 89%`
- `USED 11%`
- `RESET 1h52m`
- `WEEK LEFT 98%`
- `TODAY 403K tok`
- `7D 2.5M tok`

## 宠物映射

默认宠物：`yukino`

| 数据状态 | UI 情绪 | 宠物行 |
| --- | --- | --- |
| remaining >= 35% | ready | 第 0 行，idle |
| 15-34% | caution | 第 8 行，review / focus |
| < 15% | low | 第 5 行，failed / low energy |
| Codex active | working | 第 7 行，running / processing |

## 数据载荷

表盘应该从 Windows 服务接收已经标准化的值：

```json
{
  "updated": "14:33",
  "model": "gpt-5.5",
  "effort": "xhigh",
  "session": {
    "remaining_percent": 89,
    "used_percent": 11,
    "resets_in": "1h52m"
  },
  "weekly": {
    "remaining_percent": 98,
    "used_percent": 2,
    "resets_in": "2d21h"
  },
  "tokens": {
    "today_label": "403K",
    "last_7_days_label": "2.5M"
  },
  "pet_state": "ready"
}
```

## Wear OS 实现说明

使用单个 Compose 屏幕：

- `Box` 根节点，AMOLED 黑色背景。
- `Canvas` 绘制半圆形 5 小时用量弧线。
- `Image` 做宠物精灵帧动画。
- 底部 2x2 网格展示四个指标块。
- 环境模式：停止动画，隐藏 token 详情，保留额度和重置时间。
