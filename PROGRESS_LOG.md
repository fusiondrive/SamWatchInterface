# W25 心系天下 Premium Watch AOD 移植到直板屏 — 进度日志

**最后更新**: 2026-06-09（最终版本）
**目标设备**: SM-S948N (Galaxy S24系列), Android 16, OneUI 8.0 (build: 80000)
**目标**: 把国行三星 W25 折叠屏专属的锁屏高级钟表 AOD 移植到直板机运行

---

## 文件结构

```
/Users/steve/Downloads/W25PWATCHRelated/
├── AODService_v80/
│   └── AODService_v80.apk          (v8.8.40.8, com.samsung.android.app.aodservice)
├── AODService_v80-decompiled/      (jadx 反编译结果)
│   ├── resources/AndroidManifest.xml
│   ├── sources/
│   └── PremiumWatch-decompiled/    (jadx 反编译结果)
├── PremiumWatch/
│   └── PremiumWatch.apk            (v1.7.07, com.sec.android.app.premiumwatch)
├── PushServiceCN/
│   └── PushServiceCN.apk           (v16.0.00.7, com.samsung.android.pushservice)
├── privapp-permissions-com.sec.android.app.premiumwatch.xml  (用户已有的旧版XML)
│
├── W25WatchModule/                 ← LSPosed 模块项目
│   ├── src/
│   │   ├── com/w25watch/barphone/MainHook.java   ← 核心 Hook 代码
│   │   └── de/robv/android/xposed/...            ← XposedBridge stubs
│   ├── res/values/strings.xml
│   ├── assets/xposed_init           ← 指向 com.w25watch.barphone.MainHook
│   ├── AndroidManifest.xml
│   └── W25WatchBarPhone.apk        ← 已编译的 LSPosed 模块 ✅ 已安装到手机
│
├── W25WatchMagisk/                 ← Magisk 模块项目
│   ├── META-INF/com/google/android/update-binary
│   ├── META-INF/com/google/android/updater-script
│   ├── module.prop
│   └── system/
│       ├── priv-app/
│       │   ├── PremiumWatch/PremiumWatch.apk
│       │   └── PushServiceCN/PushServiceCN.apk
│       └── etc/permissions/
│           ├── privapp-permissions-com.sec.android.app.premiumwatch.xml
│           └── privapp-permissions-com.samsung.android.pushservice.xml
│
└── W25WatchPrivApp.zip             ← 已打包的 Magisk 模块 ✅ 已推送到手机 /sdcard/Download/
```

---

## 逆向分析结论

### 为什么直板机装上后看不到高级钟表

AODService 通过 `AbstractC0974St0` 类（在 `aod/` 包里，是混淆名）读取三星浮动特性，决定是否启用 W25 外屏功能。关键字段：

```
SEC_FLOATING_FEATURE_LOCKSCREEN_CONFIG_SUBDISPLAY_POLICY
```

W25 上该值 = `COVER|WATCHFACE|AOD|LOCKSCREEN|LARGESCREEN`

由此生成静态布尔标志：

| 标志 | 条件 | 含义 | W25 | 直板机 |
|------|------|------|-----|--------|
| `D`  | 含 "COVER" | 有外屏 | true | false |
| `E`  | D && "WATCHFACE" | 外屏支持表盘 | true | false |
| `G`  | D && "LARGESCREEN" | **大外屏（W系列专属）** | true | false |
| `I`  | 含 "LOCKSCREEN" | 外屏锁屏 | true | false |
| `J`  | 含 "AOD" && (I\|E) | 外屏 AOD | true | false |

**4 个关卡全部失败导致直板机没有高级钟表：**

1. `G = false` → 整个 LARGESCREEN 分支跳过
2. `SemWindowManager.isFolded()` 永远返回 false → 所有需要"已折叠"的逻辑跳过
3. `getSubDisplay()` 返回 null：
   ```java
   // MODManager.java
   return mDisplayManager.getDisplay(C0083Bp0.U().q); // q = EXTRA_BUILT_IN_DISPLAY
   ```
   `EXTRA_BUILT_IN_DISPLAY` 是三星私有字段，直板机上不存在（反射返回 -1），`getDisplay(-1)` = null → 被识别为内部显示屏
4. PremiumWatch 本体 onCreate 直接退出：
   ```java
   if (!SemWindowManager.getInstance().isFolded()) {
       finish(); // 直板机直接 finish！
   }
   ```

---

## 已完成的工作

### ✅ 1. LSPosed 模块（W25WatchBarPhone.apk）

**包名**: `com.w25watch.barphone`
**状态**: 已安装到手机，但需要在 LSPosed 里手动激活
**源码**: `/Users/steve/Downloads/W25PWATCHRelated/W25WatchModule/src/com/w25watch/barphone/MainHook.java`

模块做了 3 个 Hook，都在 SystemUI 和 PremiumWatch 进程里：

```
Hook 1: SemFloatingFeature.getString("SEC_FLOATING_FEATURE_LOCKSCREEN_CONFIG_SUBDISPLAY_POLICY")
        → 返回 "COVER|WATCHFACE|AOD|LOCKSCREEN|LARGESCREEN"
        → 效果：让 D/E/G/I/J 全部为 true

Hook 2: SemWindowManager.isFolded()
        → 永远返回 true
        → 效果：通过所有折叠状态检测，阻止 PremiumWatch 自动 finish()

Hook 3: DisplayManager.getDisplay(int id)
        → 当返回值为 null 时（即找不到外屏 ID），改为返回 getDisplay(0)
        → 效果：直板机的屏幕被当成外屏使用，而不是报 null
```

**激活方法**：
1. 打开 LSPosed Manager
2. 找到 "W25 Watch Bar Phone"
3. 开关打开，作用域勾选 `com.android.systemui` 和 `com.sec.android.app.premiumwatch`
4. 重启

### ✅ 2. Magisk 模块（W25WatchPrivApp.zip）

**状态**: 已推送到手机 `/sdcard/Download/W25WatchPrivApp.zip`，**尚未安装**（等待 ADB 重连后确认）

包含：
- `PremiumWatch.apk` → 安装到 `/system/priv-app/PremiumWatch/`
- `PushServiceCN.apk` → 安装到 `/system/priv-app/PushServiceCN/`
- privapp-permissions XML（两个 app 的权限白名单）

**安装方法**：
1. 打开 Magisk Manager → 模块 → 从本地安装
2. 选择 `/sdcard/Download/W25WatchPrivApp.zip`
3. 重启

---

## 当前状态（2026-06-08 第二次会话后）

| 项目 | 状态 |
|------|------|
| APK 反编译分析 | ✅ 完成 |
| 根本原因定位 | ✅ 完成（4个关卡全部分析清楚）|
| LSPosed 模块编译 | ✅ 完成（已修复：移除了 Xposed stub 类）|
| LSPosed 模块安装到手机 | ✅ 已安装（修复版）|
| LSPosed 模块激活 | ✅ 已激活，**3个 Hook 全部正常工作** |
| PremiumWatch priv-app 安装 | ✅ 通过 bind mount 安装（`post-fs-data.sh`）|
| LSPosed 作用域 | ✅ systemui + premiumwatch 均已加载 |
| 高级钟表自动显示 | ✅ **已实现！锁屏后自动亮屏显示高级钟表约8秒** |
| ADB 连接 | ✅ 正常 |

### ✅ 最终成功（2026-06-09）

**Hook 5 — SCREEN_OFF 广播监听**（关键突破）：
- 在 SystemUI 进程的 `Application.onCreate` 里注册 `ACTION_SCREEN_OFF` 广播
- 屏幕关闭后 500ms（等 keyguard 上锁）+ `premium_watch_switch_onoff=1` → 自动启动 `PremiumWatch.activity.PremiumWatch`
- PremiumWatch 有 `setTurnScreenOn(true)` 自动亮屏，内置 8 秒计时器后调用 `semGoToSleep()` 熄屏
- **关键细节**：加入 30 秒冷却时间，防止 PremiumWatch 自身的 semGoToSleep() 再次触发 SCREEN_OFF 造成无限循环

**完整 5 个 Hook 列表**：
1. SemFloatingFeature.getString() → 返回 W25 subdisplay policy
2. SemWindowManager.isFolded() → 永远返回 true（防止 PremiumWatch 自动 finish）
3. DisplayManager.getDisplay() → null 重定向到 display 0
4. aod.aB0.B = true（触发 AODService 注册 clock type 100014）
5. **SystemUI SCREEN_ON 广播 → 启动 PremiumWatch**（缺失的最后一块）

### 已确认的 Hook 日志（2026-06-08 20:54）
```
W25WatchHook: hooking SystemUI for AODService
W25WatchHook: hooked SemFloatingFeature.getString(String)
W25WatchHook: hooked SemFloatingFeature.getString(String,String)
W25WatchHook: hooked SemWindowManager.isFolded()
W25WatchHook: hooked DisplayManager.getDisplay(int)
W25WatchHook: intercepted getString(SEC_FLOATING_FEATURE_LOCKSCREEN_CONFIG_SUBDISPLAY_POLICY)  [多次]
W25WatchHook: redirected getDisplay(1) -> display 0
W25WatchHook: hooking PremiumWatch
W25WatchHook: hooked SemWindowManager.isFolded()  [in PremiumWatch process]
```

---

## 如果高级钟表仍然不显示，下一步排查

### 已排除的问题
- ~~LSPosed 模块无法加载（Xposed stubs 编译进 APK）~~ ✅ 已修复
- ~~PremiumWatch 未安装（系统分区满，mountify 损坏）~~ ✅ 已修复（bind mount）
- ~~LSPosed 作用域 WAL 文件覆盖问题~~ ✅ 已修复

### 如果设置里还是看不到高级钟表，检查：

**Option A: AbstractC1081Uv.t 字段问题**（见下方"潜在优化"）
```bash
# 检查 AODService 日志里是否有"strD4 = empty"或相关错误
adb logcat | grep -i "aod\|subdisplay\|watchface"
```

**Option B: PremiumWatch 权限不足**
```bash
adb logcat | grep -i "premiumwatch.*denied\|not allowed.*premiumwatch"
```

**Option C: configuration.semDisplayDeviceType 需要返回 5**
- 如果 PremiumWatch 启动后立即被销毁（自动销毁计时器触发），需要额外 Hook

---

## 重要代码位置（反编译源码）

| 文件 | 作用 |
|------|------|
| `sources/aod/AbstractC0974St0.java` | 所有设备特性标志，核心 static 块在第 91-185 行 |
| `sources/aod/P51.java` | `isFolded` 包装、`o` 字段（是否为主屏模式）|
| `sources/aod/C0083Bp0.java` | Display ID 常量（EXTRA_BUILT_IN_DISPLAY / VIEW_COVER_DISPLAY）|
| `sources/com/samsung/android/app/aodservice/manager/MODManager.java` | `getSubDisplay()` 实现，第 126 行 |
| `sources/com/samsung/android/app/aodservice/AlwaysOnDisplayEx.java` | AOD 主逻辑 |
| `PremiumWatch-decompiled/sources/com/sec/android/app/premiumwatch/activity/PremiumWatch.java` | 第 310-318 行有 `isFolded()` 守门检查 |

---

## 工具环境

- **JAVA_HOME**: `$(brew --prefix openjdk@17)` (需要 Java 17，系统默认是 Java 11)
- **Android SDK**: `~/Library/Android/sdk/`
- **Build Tools**: `~/Library/Android/sdk/build-tools/36.0.0/`
- **ADB**: `/usr/local/bin/adb`
- **jadx**: 已安装 (v1.5.5)

---

## 潜在的下一步优化

如果上述步骤都做了还是不显示，可能还需要 Hook：

```java
// aod/AbstractC1081Uv.java 里的 t 字段
// AbstractC0974St0.java 第 122 行:
// String strD4 = (AbstractC1081Uv.t & 1) == 0 ?
//     AbstractC4875zw0.d("SEC_FLOATING_FEATURE_...") : "";
// 如果 (t & 1) != 0，strD4 强制为空字符串，导致所有标志全部 false
// 需要 hook AbstractC1081Uv.t 字段返回 0
```

另外 `configuration.semDisplayDeviceType` 需要返回 `5`（外屏类型）而不是 `0`（内屏），否则 PremiumWatch 里会触发自动销毁计时器。
