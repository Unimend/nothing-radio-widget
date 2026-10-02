# Nothing Radio Widget 📻

一个 **Nothing 风格（点阵）的 Android 电台播放桌面小组件**，从零手写、纯 Java 实现，不依赖 Gradle / Android Studio。

不透明黑色圆角卡片背景（Nothing 风格），点阵字体 + 点阵图标，用系统 `MediaPlayer` 播放网络直播流，支持播放/暂停 + 上一台/下一台。

## ✨ 功能特性

- **3×1 桌面小组件**，不透明黑色圆角卡片背景（Nothing 风格）
- **点阵中文电台名**（Zpix 最像素字体）+ **Ndot 字体**（英文/数字）
- **点阵图标**：上一台 `◀` / 播放 `▶` / 暂停 `❚❚` / 下一台 `▶`（红白圆点拼成）
- **系统 MediaPlayer** 播放 HLS 流，前台服务 + 唤醒锁，后台/锁屏不断流
- **出错自动切台**：某个流失效时自动跳到下一台
- **音量条**：底部横条分 10 段点击调节，控制**组件自身音量**（`MediaPlayer.setVolume`，不改系统音量）
- **点击分区**：上区左=上一台 / 中=播放暂停 / 右=下一台；下区=音量条

## 🖼️ 预览

![组件预览](preview.png)

（逻辑分辨率 1200×400，实际按桌面格子等比缩放；左侧 `◀` 上一台、右侧 `▶` 下一台、中间红色 `▶/❚❚` 播放/暂停）

## 📦 直接安装

仓库根目录的 [`radiowidget.apk`](./radiowidget.apk) 是已编译好的 APK（arm64 通用，签名已包含）：

```bash
adb install -r radiowidget.apk
```

安装后：长按桌面空白 → 小组件 → 找到 **「点阵电台」** → 拖到桌面。组件默认 3×1，可拉伸。

## 🔨 从源码构建

**环境要求：**
- JDK 17（`javac`、`keytool`、`jar`）
- Android SDK `build-tools 34.0.0`（`aapt2`、`d8`、`zipalign`、`apksigner`）
- Android SDK `platforms/android-34`（`android.jar`）

**一键构建（Windows PowerShell）：**

```powershell
.\build.ps1
```

构建产物在 `build/radiowidget.apk`。

> ⚠️ `build.ps1` 刻意保持纯 ASCII（Windows PowerShell 默认按 GBK 读无 BOM 的 .ps1，中文会乱码并扰乱解析）。

## 🛠️ 技术细节

| 模块 | 说明 |
|---|---|
| `RadioService.java` | 前台服务：`MediaPlayer` 播流 + 切台/暂停 + 刷新组件 + MediaStyle 通知 |
| `RadioWidgetProvider.java` | 组件生命周期：添加时启动服务，移除时停止 |
| `Station.java` | 电台清单（名称 + 流地址），改这里即可增删电台 |
| `assets/ndot.otf` | Ndot 点阵字体（Nothing 风格，英文/数字） |
| `assets/zpix.ttf` | Zpix 最像素字体（中文点阵，12px） |

### 关键实现点

- **HLS 播放**：CNR 流是 `.m3u8`（HLS），`MediaPlayer` 从 Android 4.0 起原生支持。
- **绕过 CDN 403**：CNR 的 CDN 会拦截非常规 User-Agent，故用 `setDataSource(context, uri, headers)` 传自定义 `User-Agent`/`Referer` 头。
- **保活**：前台服务（`mediaPlayback` 类型）+ `setWakeMode(PARTIAL_WAKE_LOCK)`，对抗 ColorOS 激进杀后台。
- **点阵绘制**：整块位图用 `Canvas` 绘制（因为 `RemoteViews` 无法直接设置自定义字体，只能画进位图），三个透明 `ImageView` 覆盖在按钮区做点击。

## 📄 字体来源

- `assets/ndot.otf`（NDot57Caps）：提取自 [rjwarrier/KWGT-Widgets](https://github.com/rjwarrier/KWGT-Widgets) 的 Nothing 风格预设，版权归原作者 / Nothing。
- `assets/zpix.ttf`（Zpix 最像素）：来自 [SolidZORO/zpix-pixel-font](https://github.com/SolidZORO/zpix-pixel-font)（v3.3.0），开源中文像素字体。

## 📡 电台源

内置 10 个中文电台（央广 CNR + 中国国际广播电台 CRI + 省级电台），全部为实测可用流：

- **央广 CNR**（`ngcdn001/002.cnr.cn`）：中国之声、经济之声
- **中国国际广播电台 CRI**（`sk.cri.cn`）：环球资讯、中文环球、南海之声、海峡飞虹
- **省级电台**（`satellitepull.cnr.cn`）：浙江之声、浙江交通之声、江苏新闻广播、江苏经典音乐

> ⚠️ 注意：CNR 的 `ngcdn003+` 节点（音乐之声、文艺之声等）已关闭，请求会 403，勿使用。增删电台改 `Station.java` 的 `LIST` 数组即可。

## 📄 许可

MIT License，详见 [LICENSE](./LICENSE)。

---

*一个在 OnePlus 7T Pro（ColorOS 12.1 / Android 12，root）上从零定制、手写编译的 Nothing 风格电台组件。*
