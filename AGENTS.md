# AGENTS.md — 蓝航Lite（com.lanhang.lite）

南昌航空大学官方 App「蓝航」（`cn.edu.nchu.nahang`，UA 标识 `NchuApp/1.0.14`）的第三方轻量客户端。
功能范围：账号登录 + 课表原生渲染（周视图/网格）+ 成绩（学期切换/统计）+ 工作圈（融云圈动态）+ 设置（亮/暗/跟随系统主题、课表导出 .ics/.csv/分享）；底栏四 Tab（课表/成绩/工作圈/设置）。不做 OA 应用列表、IM。
（成绩曾被用户移除，2026-10-21 应用户要求加回。）

---

## 1. 主要数据来源（API 端点）

### 1.1 主 API：`https://nginx-1.app.nchu.edu.cn/api`
官方 App 自身的 REST 接口，常量取自反编译 `cn.edu.nchu.nahang.ui.CustomHttp:13` 与 `ui.utils.HttpUtils:35`。

| 端点 | 用途 | 说明 |
|---|---|---|
| `POST /user/security_login` | 登录 | body：`username`、`password`(RSA 加密)、`agent{platform,device_id}`、`status`、`device_info{...}`；返回 LoginInfo（token、cookie 随 OkHttp 会话保存） |
| `GET /configuration/all` | 全局配置 | 下发 `password_public_key_rsa`（登录密码公钥）等，见 `net/Security.kt` |
| `GET /apps/getLdapToken` | LDAP 令牌 | **必须带登录 Cookie**；返回 `{"code":10000,"result":"字符串"}`（不是嵌套对象！）→ 用作课表握手的 `Token` 头 |
| `GET /apps/getAppToken/{appId}` | OA 应用令牌 | 同样返回 `{"code":10000,"result":"字符串"}` |
| `POST /apps/all` | OA 应用列表 | body `{"state":[0,1]}`；本项目仅保留接口，UI 已删除应用列表 |
| `GET /user/refreshUserByDel/{userId}` | 登录态探测 | 登录成功后调用 |

成功码：一般 `code=10000` 且外层 `{"code":..,"msg":..,"result":..}`；token 类接口用 `RawTokenResponse` 解析（见 `net/Models.kt`）。

### 1.2 课表 miniapp：`http://miniapp.app.nchu.edu.cn`
课表不是主 API，是独立「小程序」站点，须先经网关握手换会话：

1. **握手**：`GET http://go.nchu.edu.cn/?id=78&state=/schedule`，带请求头：
   - `Token` = ldapToken（步骤 1.1）
   - `Version` = `1.0.14`
   - `DeviceID` = ANDROID_ID
   - `User-Agent` = 完整 UA 指纹（见下）
   服务端 302 → `miniapp.app.nchu.edu.cn/api/auth?uid=...&signtext=...&redir=/schedule`
   （**signtext 由服务端生成，客户端不做签名**）→ 跟随后获得 miniapp 会话 Cookie（存入 `ApiClient` CookieJar）。
2. **取数**：`GET /api/common?method=GetSchedule&week=N`，带同一 UA。
   成功 `{"code":0,"msg":"ok","data":{...}}`（**成功码是 0，不是 10000**）。
   **关键坑（2026-10-22 实测）**：`GetSchedule` 返回的是**该周展开的课程集合**，不是全量课表——不带 `week` 参数=只返回当前周该上的课（当周不开的课根本不在里面，本地 `isWeekValid` 过滤变不出来）。官方 chunk_698.js 每次切周都重新请求 `GetSchedule&week=N`。必须按周请求、按周缓存。
   **会话坑**：miniapp 会话失效时服务端仍返 `200` 但 **body 为空**；ldapToken（JWT）约 2 小时过期，握手会返 `Error.aspx?code=4000 客户端身份鉴权失败`。`MiniappApi.fetchCommon` 已实现：空 body → `clearMiniappCookies()`（**只清 miniapp 域，勿用 clearCookies() 全清会删掉 RCESESSIONID**）→ `handshake`（内部先 `isJwtFresh` 判断并用 RCE 会话 `getLdapToken` 刷新）→ 重试一次。

`GetSchedule` 数据结构（models 见 `schedule/ScheduleModels.kt`）：

```json
{"code":0,"data":{
  "xnxqId":"2026-2027-1",
  "week":{"当前周次":"8","起始周次":"1","结束周次":"20"},
  "schedule":[{"星期":"星期一","节次":[{"节次名":"05","课程":[
    {"课程名":"...","教师":"...","教室":"...","周次":"6-15","单双周":"0"}]}]}]
}}
```
- `周次`：如 `"6-15"`、`"1-16,18"`；`单双周`：`0`=每周、`1`=单周、`2`=双周
- 一天最多 11 节；`节次名` 是两位字符串（"01"~"11"）

#### 成绩（同一 miniapp，state=/score）
- 入口：OA 应用列表（`POST /api/apps/all`）下发 `http://go.nchu.edu.cn/?id=78&state=/score`（与课表同 id=78）
- **会话复用**：与课表同一 miniapp 会话（同一 `_uuid` Cookie），无需二次握手（已实测）；页面 JS chunk 见 miniapp `/public/js/727.*.js`
- `GET /api/common?method=GetXnxqList` → `{"code":0,"data":[{"学期ID","学期名称","当前学期":"1"}...]}`
- `GET /api/common?method=GetStudentScore&xnxqId=2025-2026-1` → `{"code":0,"data":[{"课程名称","学分","总成绩","课程性质","课程属性","考试方式","总学时","课程号","学年学期ID"}...]}`
- models 见 `grades/GradeModels.kt`；当前学期可能为空数组（期中无成绩属正常）

**UA 指纹**（`MiniappApi.UA`，抄自官方 `RceWebViewActivity.java:229` 的拼接规则）：
```
Mozilla/5.0 (Linux; Android 16; 23116PN5BC Build/BP2A.250605.031.A3) AppleWebKit/537.36
(KHTML, like Gecko) Version/4.0 Chrome/153.0.8010.36 Mobile Safari/537.36
NchuApp/1.0.14 NetType/WIFI MicroMessenger/8.0.47.2560
```

### 1.3 其他站点（WebView，仅 OaWebViewActivity）
- `https://form-design-m.paas.nchu.edu.cn/`（表单）
- `go.nchu.edu.cn/?id=60/63/79/83` 等（官方各功能入口）
- `?id=78&state=/schedule`（课表入口）——该地址**未在官方代码硬编码**，由 OA 应用数据动态下发 + 抓包探测确认。

### 1.4 会话与 Cookie
- 主 API 会话：登录下发 `RCESESSIONID`（**path=/api**），持久化在 SP `lanhang_cookies`（重启不丢）；miniapp `_uuid` 仅内存。
- 融云圈鉴权取 cookie 值须用 **`https://nginx-1.app.nchu.edu.cn/api/` 探测 URL 匹配**（用根路径会漏掉 path=/api 的 RCESESSIONID），取 `=` 后的值。
- 登录态持久化在 `SharedPreferences lanhang_session`（`session/Session.kt`），**仅存本机，不上传**。

### 1.5 工作圈（融云圈，`https://nginx-1.app.nchu.edu.cn/moments/api`）
- `POST /user/auth` body **`{"cookie":"<RCESESSIONID值>","user_id":"<staff.id>"}`**（键名是 `user_id`！）→ `{"code":20000,"result":{"authorization":...}}`；成功码 **20000**，`20003`=auth过期自动重登重试
- `GET /timeline?from_feed_id=&size=10` → `{feed_ids:[...]}`；`POST /feed/batch?with_comments=true&comment_size=50&with_likes=true&like_size=50` body **`{"ids":[...]}`** 拿动态正文
- `POST /like {"feed_id"}`、`DELETE /like/{id}`、`POST /comment {"feed_id","type":0,"content","reply_to":""}`；请求带 `authorization` 头
- 用户姓名头像：主 API `POST /staffs/batch` body `{"ids":[...]}` → `code:10000`，`result.data[]{id,name,portrait_url,depart_name,duty_name}`（`getUserId()` 返回的是 `id`）
- 登录模型坑：LoginInfo JSON 字段是 **`staff`**（`@SerializedName`），staff 里是 **`id`** = 融云 userId
- 请求/响应调试 dump：`filesDir/moments_last.json`（含 req/resp）

---

## 2. 反编译获取的内容

### 2.1 位置与工具
- 反编译产物：`decompiled/sources/`（jadx 导出的官方 APK Java 源码；`decompiled/resources/` 未保留资源导出）。
- 工具：`tools/jadx/`（jadx **1.5.3**，`bin/jadx.bat`，另有 `tools/jadx.zip`）。
- 重新反编译示例：`tools\jadx\bin\jadx.bat -d decompiled 官方APK.apk`（官方 APK 不入库）。

### 2.2 关键逆向结论（反编译依据 → 本项目实现）

| 反编译证据（file:line） | 结论 | 本项目实现 |
|---|---|---|
| `cn/rongcloud/rce/lib/AuthTask.java:55` `USER_SECURITY_LOGIN = "/user/security_login"` | 登录走融云 RCE SDK 的 security_login | `RceApi.login()` |
| `AuthTask.java:301,320` `RSAUtils.encrypt(password)`；有公钥时走 security_login 否则 `/user/login` | 密码 RSA 加密 | `Security.encryptPassword()`（`RSA/None/PKCS1Padding`，公钥来自 `/configuration/all`） |
| `cn/rongcloud/rce/lib/config/FeatureConfigManager.java:23` `CONFIG_SERVER_URL = "/configuration/all"` | 配置下发端点 | `RceApi.configurationAll()` |
| `cn/edu/nchu/nahang/ui/CustomHttp.java:13`、`ui/utils/HttpUtils.java:35` `baseUrl = "https://nginx-1.app.nchu.edu.cn"` | 主 API 基址 | `ApiClient.BASE_URL` |
| `cn/edu/nchu/nahang/ui/oa/OAFragment.java:246` `get("/api/apps/getLdapToken", ...TokenBean)` | ldapToken 取法（需 Cookie） | `RceApi.getLdapToken()` + `executeTokenString()` |
| `OAFragment.java:192-201,250` header 放 `Token`/`Version`/`DeviceID`/`ssid`，结果存 SP | 网关请求头格式 | `MiniappApi.handshake()`（未发 `ssid`，实测非必需） |
| `OAFragment.java:269+` 定时刷新 Token；`:301` OkHttp GET 带 UA 头，`:314` `handleRedirect(Location)` | 网关 302 手工跟随模式 | OkHttp 默认跟随 302，Cookie 进 CookieJar |
| `cn/edu/nchu/nahang/ui/oa/RceWebViewActivity.java:215-218` Token/Version/DeviceID/ssid；`:229` UA 拼接 `NchuApp/{ver} NetType/WIFI MicroMessenger/8.0.47.2560` | WebView 注入头与 UA 指纹 | `MiniappApi.UA`、握手请求头 |
| `OAFragment.java:418`、`ui/oa/OASearchAppsAdapter.java:87` `get("/api/apps/getAppToken/{id}", ...)` | 应用令牌 | `RceApi.getAppToken()` |
| `ui/MainActivity.java:841` `go.nchu.edu.cn/?id=63...`；`SettingActivity:132,138` id=79/83；`LoginActivity:181,642` id=60 | go 网关入口集合（**不含 id=78**） | 课表 id=78 为探测所得 |
| `AuthTask.java:46` `/user/login`、`:47 /user/login_refresh`、`:53 /user/reset_password` 等 | 其余账号端点备用 | 暂未使用 |
| IM/推送相关：`io.rong.imkit`、`cn.rongcloud.rce`、小米推送等 | 官方 App 是融云 IM 壳 | 本项目完全不涉及 |

反编译代码**仅供参考**，官方 App 代码受其许可约束，本项目只重写独立的 Kotlin 实现（接口协议属事实性信息）。

---

## 3. 代码结构（`app/src/main/java/com/lanhang/lite/`）

```
App.kt                      启动 init 顺序：ApiClient → MiniappApi → MomentsApi → Session → applyTheme → AppContext → ScheduleRepository → GradesRepository
net/
  ApiClient.kt              OkHttp 单例、BASE_URL/API_PREFIX、跨子域 CookieJar（持久化 SP lanhang_cookies）、firstSessionCookieValue(/api/ 探测+RCESESSIONID 优先）
  RceApi.kt                 主 API（login/configurationAll/getLdapToken/getAppToken/allApps/staffsBatch）
  MiniappApi.kt             握手（含 isJwtFresh 自动刷 ldapToken）+ GetScheduleRaw/GetXnxqListRaw/GetStudentScoreRaw（dump 到 filesDir，空 body 自动重握手重试）+ probe()
  MomentsApi.kt             融云圈鉴权+时间线/点赞/评论（dump moments_last.json）
  Models.kt                 ApiResponse/RawTokenResponse/LoginInfo(staff)/StaffInfo(id)/StaffBrief/ConfigInfo
  Security.kt               RSA 公钥加密密码
session/Session.kt          SharedPreferences 持久化（username/name/token/ldapToken/gradesTerm/themeMode/...，appVersion 常量）
schedule/
  ScheduleModels.kt         GetSchedule 数据模型 + isWeekValid/isOddEvenValid
  ScheduleRepository.kt     loadCached()/loadCachedWeek(n)/refresh(week)；主缓存 schedule_cache.json（week=null 写）+ 按周缓存 schedule_w<N>.json（week=N 写）；内含 AppContext
  ScheduleFragment.kt       全部 UI 逻辑（周视图 renderWeekView 周一~周日七天、网格 renderGrid、分段切换+右上角 btnViewToggle 图标切换、周次胶囊）；**数据策略：有缓存直接显示不联网，只有下拉刷新才联网覆盖；切周无该周缓存才按周补拉（ensureWeekData）**；SwipeRefreshLayout 根级包裹（同成绩/工作圈）
  ScheduleAdapter.kt        DayItem/CourseItem 数据类（被 Fragment 引用）
grades/
  GradeModels.kt            XnxqListResponse/GradeSemester/ScoreResponse/GradeItem（字段带 @SerializedName 中文键）
  GradesRepository.kt       学期列表+各学期成绩缓存（grades_semesters.json / grades_<term>.json），复用课表握手
  GradesFragment.kt         学期胶囊行(bg_week_pill 复用)+统计卡(加权平均/学分/课数)+成绩列表；GradeAdapter 内部类
moments/
  MomentModels.kt           MomentFeed/FeedContent/LikeEntry/CommentEntry 等
  MomentAdapter.kt          MomentItem(可变点赞评论)+九宫格图片；图片点击传 (urls, index)
  MomentsFragment.kt        时间线加载/翻页/点赞/评论
  UserInfoCache.kt          userId→姓名头像缓存（批量走 staffsBatch）
  ZoomImageView.kt          自写可缩放图（捏合/双击1x↔2.5x/放大后拖动，MATRIX+clamp）
  ImagePreviewActivity.kt   应用内图片预览（ViewPager2 多图滑动+顶部计数+单击关闭，黑底 Fullscreen 主题）
settings/
  SettingsFragment.kt       主题模式三选一（亮色/暗色/跟随系统，胶囊样式同成绩页）+ 课表工具（导出 .ics/.csv/分享文本，见 ScheduleExporter）+ 诊断 + 关于卡；内部调 AppCompatDelegate.setDefaultNightMode
ui/
  LoginActivity.kt          登录 → 自动 handshake + refresh 导入课表
  MainActivity.kt           Toolbar + 底栏四 Tab（switchTab hide/show）；菜单：调试导出/退出登录
  OaWebViewActivity.kt      通用带 header 的 WebView（备用）
```

### 课表 UI 关键参数（`ScheduleFragment.renderGrid`，勿随意改）
- 布局常量：`rowH=56dp, gapH=4dp, gapW=5dp, timeW=46dp, bandH=22dp`
- 休息带：`breaksAfter = mapOf(4 to "午休", 8 to "晚休")`；带的 Y = `k*(rowH+gapH) + breaksBefore(k)*bandH`（漏加 breaksBefore 会与右侧错位）
- `mergeCourses` **不得**跨节次 4→5、8→9 合并（跨带块高度需另加 bandH，故直接禁止）
- 行定位：`yTopOfRow(p) = (p-1)*(rowH+gapH) + breaksBefore(p)*bandH`
- 节次时间（硬编码，与教务一致）：1 `08:00-08:45`、2 `08:55-09:40`、3 `10:00-10:45`、4 `10:55-11:40`、5 `14:00-14:45`、6 `14:55-15:40`、7 `16:00-16:45`、8 `16:55-17:40`、9 `19:00-19:45`、10 `19:55-20:40`、11 `20:50-21:35`
- 课程配色：黄金角 HSL（`hue = name.hashCode()*137.508f % 360`，同课同色白字）

---

## 4. 构建与调试

### 环境（本机固定路径）
- Gradle：`E:\dev\gradle\gradle-8.14.3\bin\gradle.bat`（**项目内 gradlew.bat 已丢失，必须绝对路径调用**；AGP 8.13 要求 Gradle ≥8.13，8.9 不够用）
- JDK17：`E:\dev\jdk-17`，Android SDK：`E:\dev\android-sdk`，adb：`E:\dev\android-sdk\platform-tools\adb.exe`
- AGP 8.13.2 / Kotlin 2.3.21 / compileSdk 36 / minSdk 26 / targetSdk 35

### 常用命令
```powershell
# 构建（workdir = E:\Projects\lanhang-thirdparty）
& "E:\dev\gradle\gradle-8.14.3\bin\gradle.bat" assembleDebug --console=plain
# 输出：app\build\outputs\apk\debug\app-debug.apk（~7.6MB）

# 安装（HyperOS 会拦直接 install，必须走 pm install -r）
& "E:\dev\android-sdk\platform-tools\adb.exe" push app\build\outputs\apk\debug\app-debug.apk /data/local/tmp/lanhang.apk
& "E:\dev\android-sdk\platform-tools\adb.exe" shell pm install -r /data/local/tmp/lanhang.apk

# 读取课表原始响应 / 缓存 / 会话（logcat 中文乱码，勿用 logcat 看内容）
adb shell run-as com.lanhang.lite cat files/schedule_wcur.json
adb shell run-as com.lanhang.lite cat files/schedule_cache.json
adb shell run-as com.lanhang.lite cat shared_prefs/lanhang_session.xml
adb shell run-as com.lanhang.lite cat shared_prefs/lanhang_cookies.xml   # RCESESSIONID 等
adb shell run-as com.lanhang.lite cat files/xnxq_list.json               # 学期列表
adb shell run-as com.lanhang.lite cat files/score_2025-2026-1.json       # 成绩
adb shell run-as com.lanhang.lite cat files/moments_last.json            # 工作圈最近 req/resp
```

### 调试设施
- `MiniappApi.getScheduleRaw` 每次把返回体写 `filesDir/schedule_wcur.json`
- 菜单 `action_debug_schedule` 手动触发握手+拉课表并保存

---

## 5. 约定与红线

- **禁止新增第三方库，优先原生实现（2026-10-23）**：
  - **适用范围：全局**。约束对象是 `app/build.gradle.kts` 的 `dependencies` 全集，覆盖所有代码路径（课表/成绩/工作圈/设置/小部件等全部页面与模块），也覆盖今后新增的 module、source set、productFlavor 与 Kotlin/Java 源码——**不是只限某个特定模块或某个页面**。新增文件里 `import` 未在依赖中声明的外部库同样视为违规。
  - **默认做法**：能用 Android Framework 原生能力（View 体系、Canvas、RemoteViews、Handler/AlarmManager、Animation/Animator、SharedPreferences、SAF、MediaStore 等）或 AndroidX 官方 Jetpack 实现的，**一律用原生方案**，不得为省事引入第三方库。AndroidX 官方件（core-ktx、appcompat、material、recyclerview、swiperefreshlayout、constraintlayout、viewpager2、activity-ktx）、Kotlin 官方件与 kotlinx-coroutines 属平台官方组件，**不受本条限制**。
  - **唯一例外：底部导航栏已使用的库**——即 `androidx.compose.*`（compose-bom、activity-compose、ui、material3）与 `io.github.kyant0:backdrop`，用于 `ui/navigation/LiquidGlassBottomBar.kt` 的液态玻璃底栏，以及与之同款的现有组件 `moments/LiquidGlassFab.kt`。该例外**仅限这两处玻璃效果**，不得把 Compose 或 backdrop 扩散到其他页面/新功能，也不得在这两个库之上再叠加新的第三方 Compose 组件。
  - **存量依赖**：`okhttp`、`gson`、`coil` 已被现有代码使用，允许继续在**既有用途**中使用；但不得为新增需求引入同类替代库（已有 coil 就用 coil，不得再引图片库；已有 okhttp 不得再引 retrofit 等网络库），也不得借「已声明」之名扩大用途范围。
  - **需要新库时**：必须先向用户说明「原生方案为什么做不到、成本多大」，取得明确同意后才能修改 `dependencies`；**未经同意不得新增、替换或升级任何依赖**。
- **安装与测试（2026-10-23）**：构建通过后可以用 adb 安装 APK（HyperOS 按上文 push + `pm install -r`）。**agent 也要做测试**：可做构建/lint/单元测试、adb 启动检查崩溃、logcat 排错、缓存与导出数据校验等**非界面测试**；但**滑动屏幕、点击操作、截图/录屏等表面 UI 测试一律交由用户执行**，结果由用户反馈。
- **课表导出保存路径（2026-10-23）**：导出 `.ics`/`.csv` 必须走系统文件选择器（SAF `ActivityResultContracts.CreateDocument`），保存路径由用户自己选择；禁止写死固定目录、禁止静默保存。导出范围为**整学期**（`ScheduleExporter.plan` 的 `startWeek..endWeek`），CSV 带 UTF-8 BOM（Excel 中文）；实现见 `schedule/ScheduleExporter.kt`（plan/buildIcs/buildCsv/buildText）。
- **小部件预览（2026-10-23）**：选择器里的预览必须同时给 `previewImage`（位图，API ≤30 与兜底）和 `previewLayout`（可缩放布局，API 31+ 优先），见官方 `develop/ui/views/appwidgets/previews`。**预览资源里禁止用固定 dp 偏移画内容**：2×2「今日课程」原用 layer-list（top=34dp/bottom=118dp 等 inset）画色条，2×2 高度 <152dp 时 inset 溢出、色条被裁成 0 高 → picker 只剩一张纯白卡片（实测 237px 高的预览里色条完全没出现）；现改为 `drawable-nodpi/widget_schedule_today_preview.png`（1000×1000 位图，按 `ScheduleWidgetRenderer.renderToday` 的几何/配色绘制）+ weight 分配的 `layout/widget_schedule_today_preview.xml`。另外 API 35+ 在 `ScheduleWidgetUpdater.pushGeneratedPreview()` 里用 `AppWidgetManager.setWidgetPreview` 推送真实渲染结果（系统限频 ~2 次/小时，本地再限 12h，时间戳存 `Session.widgetPreviewPushedAt`）。
- **CSV 必须能被 Sleepy 导入（2026-10-23）**：Sleepy（github.com/lingion/sleepy）的 `ScheduleParser.parseCsv` 规则：列名按**包含匹配**（课程/星期/开始节数/结束节数/周次/类型/教师/教室/开始时间/结束时间）；**星期只认「周一…周日」或 1-7，写「星期一」会解析失败→跳过整行→「未能解析任何课程」**；节次必须是「开始节数+结束节数」两列（单列会被当成“节次”把多节连上压成 1 节）；一行 = 一个「星期+起止节+周次区间」，**不能按周展开**（否则导入成几十条重复课程）。故 `buildCsv` 走 `collectSlots`（保留原始 `周次`/`单双周`）而不是 `collectOccurrences`（按周展开，仅供 ICS/文本）。Sleepy 的 CSV 导入不读日期，`startDate` 默认取导入当天，需用户在确认框把「开学日期」改成第 1 周周一（导出对话框里已加提示）。
- **sleepy**（github.com/lingion/sleepy）是 GPL-3.0：只参考其设计风格，不抄代码。
- 成绩 2026-10-21 应用户要求加回（勿再以旧「不做成绩」为由删除）；OA 应用列表、IM 仍不做。
- **2×2「今日课程」翻天导航（2026-10-23 加，2026-10-24 改版 + 修 vivo 错位）**：底部左右两个按钮 **◀ ▶** 切换前后一天，**翻到别的日期后中间才多出 ⟳（回到今天）**——当天本来就在今天，不显示它（隐藏后按钮组从 80dp 收到 52dp，左侧胶囊因此能用完整文案）。语义对齐 Sleepy（github.com/lingion/sleepy 的 `TodayDateNavCore`，独立实现，未抄代码）：每个实例在 `widget_today_nav` SP 里存 `(selectedEpochDay, anchorEpochDay)`，**anchor ≠ 今天就回到今天**（隔天自动复位，靠午夜闹钟触发的 updateAll 生效）；偏移 `coerceIn(-92, +92)` 天，目标日期再钳到学期首周周一~末周周日；跨周没缓存时 `ScheduleRepository.refresh(week)` 补拉（6s 超时，失败保持当前渲染）。实例删除时 `onDeleted` 清状态。
  - **底栏（胶囊 + ◀ ⟳ ▶）必须 100% 是布局里的真实 View，一个像素都不许画进位图（2026-10-24，vivo 实测 bug）**。根因：位图是**应用进程**按自己的 `displayMetrics.density` 画的，而 `widget_schedule_week.xml` 是**桌面进程** inflate 的、dp 走桌面的 Configuration；vivo/OriginOS 会给桌面小部件套独立缩放，两边 density 一不相等，画在位图里的底板就和布局里的图标错开，**越靠左偏得越多**（真机截图：最左的 ◀ 底板上是空的、⟳ 骑在两个底板中间、▶ 才勉强对齐）。现在底板/图标/点击热区是同一个 View，缩放到哪都不会错位；位图只画标题 / 课程行 / 空态文案（这些不需要和任何 View 对齐）。
  - 几何：按钮 **24×18dp**、间距 4dp、组右边距 9dp、底栏高 22dp（按钮在其中垂直居中 → 距底 11dp）；三个 ImageView 装在 `wrap_content` 的横向 LinearLayout 里靠 `bottom|end`，**不要再用固定 `marginEnd` 定位**（中间 ⟳ 一 GONE 就错位）。整组仅 2×2 且高度 ≥ `FOOTER_MIN_HEIGHT_DP`(130dp) 时 VISIBLE；高度不够时胶囊一起隐藏（位图同样按 `FOOTER_HEIGHT_DP`(22dp) 给底栏留空，两边必须一致）。
  - **浅/深两套各占一组 View**（`widget_nav_group` / `widget_nav_group_dark`、`widget_week_pill` / `widget_week_pill_dark`），绑定时只切 `setViewVisibility` + `setTextViewText` + `setOnClickPendingIntent`。**禁止 `views.setInt(id, "setBackgroundResource"/"setTextColor", …)` 这类反射调 View 方法**（launcher 端可能静默拒绝，且插件化桌面行为不一致）。改几何时两组必须一起改。
  - 胶囊文案由 `ScheduleWidgetRenderer.footerLabel(context, week, target, compact)` 生成；宽度由外层 `layout_weight="1"` 的容器约束，放不下交给 TextView `ellipsize`。**2×2 在 vivo 实测只有 ~110~130dp 宽**（不是设计的 154dp），所以 `NARROW_WIDTH_DP=150` 以下改用无空格短文案（`widget_week_compact`）。
  - 点击走 `ACTION_PREV_DAY/NEXT_DAY/RESET_DAY` 广播（显式组件 + `EXTRA_APPWIDGET_ID`）→ `ScheduleWidgetProvider.onReceive` → `goAsync` → `ScheduleWidgetUpdater.handleNavAction`；`handleNavAction` 里对「已经在今天」的 RESET 保留**直接 return** 作兜底。
- **4×4 周视图课程名 = 竖排、最多两列、整组居中（2026-10-24，用户明确要求）**：课程名**必须竖排**（一个字一行），一列排满才折到右边第二列（左列先填，右列承接剩余），**最多 `NAME_MAX_COLS=2` 列**，整组在色块里水平 + 垂直居中。**不要改成横排折行**——用户实测横排（`高等数/学B3` 那种）会把色块左右撑满，看起来被拉宽；竖排两列只占两个字宽，两侧自然留白。
  - 字号按块自适应（`fitNameSize`）：从 `NAME_MAX_SIZE_DP=13dp` 往下找第一个满足「列数≤2 且两列总宽 ≤ 块宽（扣 `NAME_PAD_H_DP` 2.5）且每列行数×`NAME_ROW_SPACING`(1.14) ≤ 可用高」的字号，下限 `NAME_MIN_SIZE_DP=7dp`；**不要退回全局统一字号**——4×4 里并行分栏的窄块只有半列宽，取全网格最小值会把整周的字一起压到下限。
  - 行距 1.14 不是 1.0：汉字字形高约 1.0em，基线以下还有 descent(≈0.22em)，行距太紧最后一行会压到底部的教室行。绘制基线用 `groupTop + row*rowH + size*0.78`。
  - `roomReserve`（底部教室行预留）必须 ≥ `min(12dp, 块高*0.32)`：教室字号约 7dp、基线距底 5.6dp，字形顶部实际到距底 ~12dp，只留 7dp 会让最后一行课程名和教室叠在一起。
  - 排不下的字用「…」占最后一格（不是直接截掉，否则看不出被截断）。改排版后可用 `.workbuddy/tmp/preview_week4x4.py` 离线出图核对几何（该脚本逐行对齐 `CanvasRenderer`）。
- **小部件预览图必须与真实渲染一致（2026-10-23）**：`drawable-nodpi/widget_schedule_today_preview.png`（1000×1000，picker 的 previewImage）由 `.workbuddy/tmp/gen_widget_preview.py` 生成；改了 `TodayRenderer` 的几何、按钮个数、文案后必须重跑该脚本（`python gen_widget_preview.py <输出路径>`，依赖 Pillow），否则选择器里看到的和放置后不一致。149dp 宽度下底部胶囊放不下「第 4 周」，会退成 `widget_week_compact`（「第4周」）。4×4 的 `widget_schedule_preview.png` 同理，仍是旧的单列竖排手绘图。
- **2×2「今日课程」排课规则（2026-10-23，用户明确要求）**：
  - **上完即走**：今天已经下课（`PeriodClock.endMinute(end) <= 现在`）的课**不再出现在小部件里**，后面的课依次顶上来；不是「按当天课时从前到后截断」。翻到昨天/明天时不做时间过滤，只按节次顺序取前几节。
  - **最多 [MAX_TODAY_ROWS]=3 节**（`ScheduleWidgetRenderer.MAX_TODAY_ROWS`，用户说「总共能显示三节」，后面又提到「不多于四节」，按前者实现，改常量即可）。隐藏的课不画「+N」胶囊——2×2 没有多余高度，课时增加靠前面的课下课后自动补位。
  - **倒计时**：正在上的那节（`start <= now < end`）在**行右侧**显示「剩 N 分」（`widget_today_countdown`，向上取整、最少 1 分），其余行右侧显示教室。倒计时只按分钟跳，所以 `ScheduleWidgetUpdater.refreshTick()` 在**上课期间排链式 60s 单次闹钟**（`ACTION_TICK`）重绘；离下课不足 60s 就精确排在下课那一刻（那次重绘负责让这节课消失、下一节顶上来）。**没上课时排到下一节上课时刻，今天全上完/没放实例就取消闹钟**，不空转。
  - **闹钟必须用 `AlarmManager.ELAPSED_REALTIME`（非 WAKEUP）**：熄屏不唤醒设备，亮屏后补一次，因此**不需要 SCHEDULE_EXACT_ALARM 权限**；别改成 `setExact*`（会抛 SecurityException）或 `setRepeating`（会被批量延迟）。
  - **节次时间只有一个来源**：`schedule/PeriodClock.kt`（08:00-08:45 … 20:50-21:35）。小部件判断上课/下课、算倒计时都走它，禁止再硬编码第三份时间表。
  - **空态要区分**：今天本来有课但全上完了显示「今天的课已上完」（`widget_today_done`），不要复用「今天没有课程」。
  - 布局几何随 3 行改为 outerPad 9dp / header 24dp / headerGap 5dp / cardGap 4dp / footer 22dp（2×2 实测 149dp → 每行 24dp，单行排版）；改这些常量时必须同步 `drawNavButtons`、`layout/widget_schedule_week.xml` 与预览图/预览布局。
- 工作圈只重写协议不搬融云 SDK 代码（io.rongcloud.moment 为商业 SDK）。
- **工作圈评论整行可点（2026-10-23）**：禁止把所有评论拼进一个 TextView 用 `ClickableSpan` 做回复（旧实现只有人名能点，点正文毫无反应）。现在每条评论独占一行 `layout/item_moment_comment.xml`，动态挂到 `item_moment.xml` 的 `llComments`；整行（含行末空白）点击 = 回复该评论作者（`onReply(item, comment.user_id)`），多行复用子 View 避免重复 inflate。注意：行设了 `clickable` 会吞掉触摸流，**长按删除必须挂在同一行**（`onCommentsLongPress`），否则父容器收不到长按。
- **暗色适配（2026-10-21）**：新写 UI 的文字/卡片底/分隔线一律用 `?attr/color*`（XML）或 `getThemeColor(ctx, MaterialR.attr.color*)`（代码），**禁止硬编码黑字 hex**（如 #1D1B20/#49454F）与固定浅底（如 #FEF7FF）；课表网格与周视图已全部改主题属性；课程块彩色底（HSV/白色文字）是固定设计可保留。
- **语言切换走 LocaleHelper + recreate()，禁止用系统 per-app locale（2026-10-24 重写）**：`util/LocaleHelper.wrap(base)` 用 `createConfigurationContext` 包住 Activity/小部件的 base context，切换时 `AppLanguage.apply(mode)`（存 `Session.language` + `LocaleHelper.prime`）+ `requireActivity().recreate()`。**不要用 `AppCompatDelegate.setApplicationLocales`**——它走系统 relaunch，切换过程有约 0.4s 纯黑帧（实测 Android 15），且 `configChanges="locale|layoutDirection"` 拦截也不行（设置页文字不刷新、切 tab 假死，已回滚）。口径对照 E:\app_start 的 `util/LocaleHelper`。接入点：5 个 Activity 的 `attachBaseContext`（Login/Main/OaWebView/ImagePreview/ScheduleBackground）、`App.onCreate` prime 一次（顺带清掉旧版写进系统的 per-app locale）、小部件文案在 `ScheduleWidgetRenderer.render/renderToday/footerLabel` 与 `ScheduleWidgetUpdater` 的 contentDescription、`ScheduleWidgetData` 的兜底课名处 wrap。**新增 Activity 必须自己加 `attachBaseContext`，新增小部件/通知文案必须用 `LocaleHelper.wrap(context)`**，否则语言不跟随。
- `res/values/colors.xml` **禁止整体覆盖**（曾误删 primary/primary_dark/gray 导致构建失败）。
- 图标用官方 Material Symbols（如 `drawable/ic_calendar.xml` = Material Symbols Rounded `calendar_month` 原版路径 + `translateY=960` 转换），配 `?attr/colorPrimary` 着色。
- 课表数据只存本机（filesDir 缓存 + SP），不外传；密码仅在登录时 RSA 加密上送。
- **每次功能实现完必须安装（2026-10-23）**：任何功能（新特性/修改）实现完成后，流程固定为「构建 → adb 安装到设备（上文 push + `pm install -r`）→ 非界面测试（启动/崩溃/logcat/构建校验）→ 交付」，**不允许只构建不安装就交付**；滑动、点击、截图等表面测试由用户完成，结果由用户反馈。
