# PROJECT_NOTES.md

本项目（Nothing 电台组件）的注意事项、已踩坑、已做决策。多个对话只更新这一个文件。

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
