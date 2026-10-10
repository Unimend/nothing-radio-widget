# PROJECT_NOTES.md

本项目（Nothing 电台组件）的注意事项、已踩坑、已做决策。多个对话只更新这一个文件。

---

## 2026-10-10 - v2.1 HTTPS 流修复与半透明点阵背景

### v2.1.1 点阵可见性修正

- 背景点阵间距由 `30×27 px` 收紧为 `27×25 px`，白点半径由 `1.8 px` 提高为 `3.0 px`。
- 白色点阵透明度提高到 `30/37/44`，稀疏红色强调点提高到 `42`，确保在 1440×3120、560 dpi 的一加 7T Pro 桌面上可见，同时不干扰文字与控制区域。
- 已在实体桌面组件上截图确认，并重新导出 `preview-v2.png`。
- 版本升级为 `2.1.1`、`versionCode=4`；仓库根目录 APK 同步为当前可安装版本。

### 用户反馈

- 新版动画效果正常。
- 浙江之声等后半组电台没有反应。
- 希望纯黑背景增加一些变化，例如在后方叠加半透明点阵。

### 根因

四个 `satellitepull.cnr.cn` HTTP 主清单本身返回 200，但二级清单跳转到动态明文 IP。v2 的 Network Security Config 只允许 `cnr.cn`/`cri.cn` 域名明文通信，因此 MediaPlayer 无法继续访问 IP 清单，15 秒后超时。电台源本身没有失效。

### 改动

- 10 个电台地址全部从 HTTP 改为 HTTPS。
- 四个卫星源经 HTTPS 请求后会返回 HTTPS `100ycdn.com` 二级清单，不再使用明文 IP。
- `usesCleartextTraffic` 改为 `false`，Network Security Config 全局禁止明文流量。
- 版本升级为 `2.1.0`、`versionCode=3`。
- 黑色圆角背景增加错位排列的低透明度白色点阵，少量点使用极低透明度红色强调。
- 播放和调频的有限帧反馈会让背景点阵产生轻微相位移动；动画结束后不再刷新。

### 实机验证

- 新版组件实例 ID：54，Launcher 为 `com.android.launcher`。
- 浙江之声、浙江交通之声、江苏新闻广播、江苏经典音乐均在真机触发 `onPrepared` 并开始播放。
- 轮播结束后自动暂停，Service 已退出；持久化状态为第 10 台、音量约 0.33、`PAUSED`。
- 1440×3120 真实桌面截图确认点阵背景可见但不遮挡主信息，3×1 布局和点击区域保持正常。

---

## 2026-10-10 - v2 基础架构改造与首轮实机验证

### 目标

在保留纯 Java、原生 RemoteViews 和 Nothing 点阵风格的前提下，修复历史 APK 的 SDK 元数据、自动播放、状态丢失、音频生命周期、无限重试和高成本渲染问题。

### 主要改动

- 新包名：`com.unimend.nothingradio`，与历史 `com.example.radiowidget` 并存，避免签名不同导致必须卸载旧组件。
- 显式构建元数据：`minSdk=23`、`targetSdk=31`、`compileSdk=34`、`versionCode=2`、`versionName=2.0.0`。
- 新增 macOS/Linux `build.sh`，复用 Unity 的 Android SDK 与 OpenJDK。
- 添加 `WidgetStateStore`，持久化台号、音量、播放状态、消息和更新时间。
- 添加 `WidgetRenderer`，画布由 1200×400 降到 900×300；一次更新只绘制一张共享位图。
- Provider 更新只渲染状态，不再自动启动播放。
- RadioService 改为明确状态机，加入网络检测、Audio Focus、MediaSession、拔耳机暂停、有限重试和 15 秒连接超时。
- 播放、调频和音量反馈采用有限帧动画；空闲时无循环刷新。
- 明文网络只对白名单域名 `cnr.cn`、`cri.cn` 开放。
- Android 12–14 增加 `previewLayout`。

### 构建与安装验证

- `build.sh` 构建成功。
- APK v1/v2/v3 签名验证成功。
- `aapt dump badging` 确认 SDK 与版本元数据正确，不再隐式申请存储或电话权限，`supports-any-density=true`。
- APK 已安装到 OnePlus 7T Pro，Provider 注册成功。
- SHA-256（首次 v2 构建，后续修复会变化）：`5fdc9df11202b6f3ff45e00424d4c526d0513820a0a5f75c25460d24c66611ef`。

### 实机发现与修复

- 首次短测因缺少 `ACCESS_NETWORK_STATE` 导致 `SecurityException`；已补充普通权限并重新构建安装。
- 修复后设备真实处于断网状态：Wi-Fi 开启但未连接、无默认路由。组件正确保存 `OFFLINE / 网络不可用` 并退出服务，没有伪装成播放中。
- `SharedPreferences` 已在实机生成，台号 0、音量 0.6 和离线状态持久化成功。
- 新版 900×300 预览已从设备导出为 `preview-v2.png`，确认中文点阵、FM 台号、离线状态、左右切台、中央播放和 10 档音量均正常绘制。

### 尚未验证

- 手机联网后的 HLS 实际播放。
- 桌面放置新版 3×1 组件后的真实 Launcher 缩放与点击热区。
- 播放、换台、音量有限帧动画的桌面观感。
- Audio Focus、拔耳机暂停、通知按钮与蓝牙控制的完整行为。

### 下一步

手机联网后添加新版“点阵电台”组件，逐项进行播放、暂停、换台、音量、异常源、锁屏、耳机和截图验收。

---

## 2026-10-02 - 真机反馈修复（流源 / 尺寸 / 布局）

### 现象（用户反馈）
1. 只有前两个电台（中国之声、经济之声）有声音，后面全无声。
2. 组件显示成 4×2，太大，要 3×1。
3. 布局：点阵点太大、播放键不在正中心、要**不透明背景**。

### 原因（定位）
1. **流源**：CNR 国家级电台只有 `ngcdn00X.cnr.cn` 一种地址，其中 `ngcdn001/002` 节点开放、`ngcdn003+` 节点已关闭/区域封锁。用 curl 实测：ngcdn003 无论换什么 UA/Referer 都返回 `403`（不是请求头问题，是节点本身失效）。
2. **尺寸**：原 `minWidth=250dp minHeight=90dp` 被 ColorOS 判成 4×2。
3. **布局**：原 dotSize=14/gap=22 点太粗；播放键画在 y=290（偏下）；背景是透明。

### 已做改动
1. **换流源**（`Station.java`）：保留 ngcdn001/002（中国之声、经济之声）；其余换成实测可用的两个源——
   - CRI 中国国际广播电台 `sk.cri.cn/*.m3u8`（环球资讯、中文环球、南海之声、海峡飞虹）
   - 省级电台 `satellitepull.cnr.cn/live/wx*/playlist.m3u8`（浙江之声、浙江交通之声、江苏新闻广播、江苏经典音乐）
   - 全部 curl 实测 200，真机循环切台全部「开始播放」成功。
2. **3×1 尺寸**（`widget_info.xml`）：`minWidth=200dp minHeight=65dp` + `targetCellWidth=3 targetCellHeight=1`（API 31+ 精确格子）+ `resizeMode=none`。
3. **布局**（`RadioService.drawWidget()`）：不透明黑色圆角卡片背景（`drawRoundRect`）；dotSize 14→6、gap 22→16；电台名上移、播放键移到正中心 (600,200)、左右箭头同水平线。

### 关键结论
- **ngcdn003+ 永久不可用**（节点关闭），不要再用；可用源 = `ngcdn001/002`、`sk.cri.cn`、`satellitepull.cnr.cn`。
- **换台后需重加组件**：已放置的组件实例可能保留旧尺寸，改 minWidth/targetCell 后建议删除重拖一次。

### 已验证
- ✅ 10 个电台真机全部「开始播放」（日志确认）。
- ✅ 像素分析：不透明黑背景 96.6%、圆角四角透明、中文名/箭头/红播放键均正常、点面积缩到约 1/4。

---

## 2026-10-02 - 项目初始搭建

### 已做决策（原因）
- **播放引擎用系统 `MediaPlayer`**，不用 ExoPlayer/Media3：保持「纯 javac+d8 无 Gradle」的构建方式（ExoPlayer 是 AAR 依赖，会破坏现有构建链）。
- **电台源内置固定清单**（10 个央广 CNR 流），不接 radio-browser API：无需手写 HTTP+JSON，代码最简。
- **中文电台名用 Zpix 最像素字体**：Ndot（NDot57Caps）只有大写字母+数字，**没有中文字形**，直接渲染中文会空白。
- **组件尺寸 3×1**：`minWidth=250dp minHeight=90dp`，`resizeMode=horizontal|vertical` 可拉伸。

### 已验证有效
- ✅ `MediaPlayer` 能播 CNR 的 HLS 流（`onPrepared` 触发「开始播放:中国之声」，音频 HAL 正常输出，无 403）。
- ✅ 切台逻辑：`ACTION_NEXT` 触发「开始播放:经济之声」。
- ✅ 组件 provider 已注册（`dumpsys appwidget` 可见）。

### 踩坑记录

#### 1. `build.ps1` 中文乱码导致解析失败
- **现象**：`& "$BT\aapt2.exe"` 报 `无法将"\aapt2.exe"项识别`，`$BT` 变空。
- **原因**：Windows PowerShell 默认按 GBK 读取**无 BOM** 的 `.ps1`；文件里的中文注释/字符串被误读成乱码，扰乱解析。
- **修复**：`build.ps1` 全部改为纯 ASCII（英文提示）。
- **避免**：任何 `.ps1` 要么加 UTF-8 BOM，要么纯 ASCII。

#### 2. keytool 生成密钥触发 NativeCommandError
- **现象**：`keytool -genkeypair` 把「正在生成密钥」进度写到 stderr，被 `$ErrorActionPreference="Stop"` 当成错误抛出 `NativeCommandError`，构建中断（但 keystore 其实已生成）。
- **修复**：keytool 调用处临时把 `$ErrorActionPreference` 降为 `Continue`，stderr 重定向 `2>$null`。

#### 3. `exported="false"` 导致 adb 无法直接起服务
- **现象**：`adb shell am start-foreground-service` 报 `Permission Denial ... not exported from uid 10271`。
- **原因**：服务 `android:exported="false"`（安全正确），shell（uid 2000）无权启动。
- **测试技巧**：设备已 root，用 `adb shell "su -c 'am start-foreground-service -n com.example.radiowidget/.RadioService -a com.example.radiowidget.START'"` 绕过。
- **正常路径**：桌面 AppWidget 的 `onUpdate` 在应用自身进程内 `startForegroundService`，不受影响。

#### 4. CNR 流 403 风险（已规避）
- **现象**：用工具直接请求 `ngcdn*.cnr.cn` 的 m3u8 返回 `403 Forbidden`（CDN 访问控制）。
- **规避**：`MediaPlayer.setDataSource(context, uri, headers)` 传自定义 `User-Agent`（浏览器 UA）+ `Referer` 头。真机验证通过（无 403）。

#### 5. 位图渲染验证（无需桌面）
- **方法**：`RadioService` 加了一个调试动作 `ACTION_PREVIEW`，把 `drawWidget()` 的位图导出到 `getFilesDir()/preview.png`，用 root `am` 触发后 pull 回来做像素分析。
- **结论（已验证）**：中文电台名（Zpix 字体）正常渲染（6252 白像素）、左右箭头对称（各 8928 白像素）、红色播放图标（9064 红像素）、背景透明（442929 透明像素）。
- **分析脚本**：`analyze_preview.py`（一次性，用 Pillow 统计像素，已删除）。
- **保留**：`ACTION_PREVIEW` 调试动作保留，方便日后无需桌面就能预览/调整 UI 布局。

### 已知小问题（不影响运行）
- `RadioService.java` 有一条 deprecation 警告：`startForeground(int, Notification)`（API 31 起建议用三参版）。因 targetSdkVersion=0，无害，暂不改。

### 后续优化建议
- 可加 `MediaSession` + `MediaStyle` 通知，让系统通知栏/锁屏/蓝牙也能切台暂停。
- 电台清单可改为读外部文件或接 radio-browser API，避免重编译换台。
- 组件位图目前固定逻辑分辨率 1200×400，可改为按实际像素密度适配，点阵更锐利。
