# UI 重做（M3 Expressive）

分支 `ui-builder` · 模块 `uipreview/`（独立 applicationId，纯 UI、假数据、完全可交互）

| 文件 | 内容 |
|---|---|
| [01-ui-inventory.md](01-ui-inventory.md) | 重构前 11 页 + 6 Sheet 的功能清单、交互表、状态、UX 问题与旧→新映射 |
| [02-design-spec.md](02-design-spec.md) | M3 Expressive v2 设计规范：降噪规则、token、组件、IA、逐屏结构、交互/无障碍 |
| [03-delivery.md](03-delivery.md) | 下载安装、这一版做了什么/减了什么、10 分钟走查脚本、边界与接线方式 |

## 一眼看结论

- **IA 改为 2 tabs（书架 / 设置）**：任务变独立二级页，人物/存储/关于/模型/生成/网络全部二级页。
- **一屏三块**：首屏只留 1 个主视觉 + 2 个辅助；卡片只保留「继续收听」一张。
- **15 个页面 + 8 个 Sheet 全部可交互**，没有死按钮。
- **三态 + 字号 + 深浅色 + 动态取色 + 规范标注**集中在「设计实验室」，逐页可切。

## 产物

```
APK   https://github.com/EstimateFaster/android-audioforge/releases/download/ui-preview/AudioForge-UI-Preview.apk
截图  https://github.com/EstimateFaster/android-audioforge/releases/download/ui-preview-shots/ui-preview-shots.zip
```
