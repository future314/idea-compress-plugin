# File Compressor（IDEA 文件压缩插件）

> **Overview** — An IntelliJ Platform plugin that adds a right-click **Compress to ZIP**
> action to the Project view and editor tabs. Select any mix of files and folders,
> and a ZIP archive appears next to the selection. Build-output and dependency
> directories are excluded by default and the rules are editable.
> UI is available in English and Simplified Chinese.

在 IDEA 项目视图里选中文件或文件夹，右键 → **Compress to ZIP**，同级目录直接生成压缩包。
不必再切到资源管理器找目录、右键压缩、再切回来。

## 功能

| | |
|---|---|
| 压缩格式 | ZIP（JDK 内置 `java.util.zip`，无额外依赖） |
| 输出位置 | 选中项的**同级目录**，自动命名 |
| 多选 | 合并进**同一个**压缩包，每个选中项作为顶层条目 |
| 排除规则 | 默认跳过 VCS 元数据 / 构建产物 / 依赖缓存，可在设置里改 |
| 执行方式 | 后台任务 + 进度条，可取消；失败或取消不留半成品 |
| 完成提示 | 右下角通知，带「在资源管理器中显示」 |
| 菜单入口 | 项目视图右键 + 编辑器标签右键 |
| 界面语言 | 英文 / 简体中文（跟随 IDE 语言） |

## 使用

```
Project 视图
 ├─ src          ← 右键选中（可多选）
 ├─ README.md
 └─ ...
      └─ Compress to "src.zip"        ← 点击

→ 右下角进度 → 完成通知
   Created src.zip (12.4 MB, 318 files)   [Show in Explorer]
```

**命名规则**

- 单选 `src/` → `src.zip`；单选 `readme.md` → `readme.zip`（去掉扩展名，与 Windows / 7-Zip 一致）
- **多选**（同一父目录下）→ 用**父目录名**：在 `my-app/` 里选中 `src`、`docs`、`README.md` → `my-app.zip`
- 多选但**不在同一父目录**（如跨模块）→ 回退为第一个选中项的名字，因为没有统一的"父级目录"
- 重名自动递增：`src.zip` → `src (1).zip` → `src (2).zip`
- 两个同名项同时选中（如 `a/src` 与 `b/src`）→ 条目名自动消歧为 `src/`、`src (1)/`

**排除规则**

`Settings | Tools | File Compressor`

```
每行一条规则。
  target              ← 纯名称：匹配任意同名文件或目录（含嵌套）
  docs/**/*.tmp       ← 含 / 或 ** ：按相对归档根的路径做 glob
```

被排除的目录**整棵剪枝**，不进遍历（所以大目录不会拖慢速度）。
**选中的项目本身永不被排除** —— 右键 `node_modules` 就是要压它。

## 开发

### 环境要求

- JDK 21（本机 `D:\install\JDK21`，已在 PATH）
- Gradle 9.9.2（Wrapper 自带；本机另装于 `D:\install\Gradle`）
- 网络：首次构建需下载 IntelliJ Platform 依赖

### 常用命令

```bash
./gradlew test                 # 纯逻辑单元测试（不起 IDE，几秒）
./gradlew runIde               # 起一个装好插件的沙箱 IDE
./gradlew buildPlugin          # 产出 build/distributions/*.zip
./gradlew verifyPlugin         # Plugin Verifier 二进制兼容性校验
./gradlew verifyPluginProjectConfiguration   # plugin.xml / 构建配置体检
```

### 装到本机 IDEA

```bash
./gradlew buildPlugin
```

然后在 IDEA 里 `Settings | Plugins | ⚙ | Install Plugin from Disk…`，
选 `build/distributions/idea-compress-plugin-<version>.zip`，重启即可。

## 技术要点

这几条是踩过的坑，改动相关代码前请先读：

**1. 字节码必须是 Java 17**
`sinceBuild = 241` 是 IDEA 2024.1，它跑在 **JBR 17** 上（2024.2 才换 JBR 21）。
所以 toolchain 用 21 编译，但 `jvmTarget` 压到 17，外加 `-Xjdk-release=17` 禁用 Java 18+ API。
**如果把 sinceBuild 提到 242+，这三处要一起改。**

**2. 中文文件名靠 EFS 标志位**
`ZipOutputStream(OutputStream, UTF_8)` 会让 JDK 自动置上 general purpose bit 11（EFS），
Windows 资源管理器和 Linux `unzip` 都认这个标志。
**不要**改用平台默认字符集构造，否则中文名在资源管理器里是乱码。
`ZipWriterTest` 里有一条直接读 zip 二进制头校验该位的用例。

**3. 自包含防护**
输出路径在遍历之前就算好了。选中 `src/` 加上旁边已存在的 `src.zip` 时，
默认命名正好指向那个正在被压缩的输入 → 归档里会出现自己，体积滚雪球。
两道防线：命名判重时排除「命中输入」的路径 + 遍历时跳过输出路径。

**4. 重复条目名会直接崩**
`ZipOutputStream.putNextEntry` 遇到重复条目名抛 `ZipException: duplicate entry`。
多选 `a/src` + `b/src` 必须走 `ZipWriter.resolveSources` 做前缀消歧。

**5. 排除规则的匹配语义**
纯名称匹配**名字**（任意深度）；含 `/` 或 `**` 的按**相对归档根的路径**做 glob，
其中 `**` 匹配零段或多段，所以 `docs/**` 连 `docs` 本身也命中。

**6. Kotlin 块注释是嵌套的**
注释正文里出现 `docs/**` 会再开一层注释，整个文件报 "Unclosed comment"。
注释里**绝不要**写 `/**`。

## 已知限制

- **符号链接**会被跳过而不是跟随（避免链接成环死循环）。两个相关单元测试在 Windows
  下需要开发者模式或管理员权限才能创建符号链接，权限不足时会自动跳过。
- 默认排除里包含 `*.zip`（不想把旧归档再打进去）。如果需要保留，请在设置里删掉这条。
- 压缩读取的是**磁盘上的内容**。执行前会 `saveAllDocuments()` 把未保存的编辑器缓冲写盘，
  保证归档和你看到的一致。

## 发布到 JetBrains 插件市场

**身份已定稿**（`<id>` 上传后永久不可更改，已确认未被占用）：

| 项 | 值 |
|---|---|
| id | `lrong.file-compressor` |
| 显示名 | `File Compressor` |
| 作者 | `lrong` / `1452678450@qq.com` |

### 已就绪

- [x] `<id>` / `<name>` / `<vendor>` 定稿，id 已查重未被占用
- [x] `<description>` 英文 HTML ≥ 40 字符，符合 JetBrains 文案规范
- [x] `./gradlew verifyPlugin` 对 IC-241.19416.15 / IU-251.25410.129 / IC-251.25410.109 全 **Compatible**
- [x] `signPlugin` / `publishPlugin` 配置就绪（密钥走环境变量，不进仓库）
- [x] README / CHANGELOG / 插件图标 / 英中双语 UI

### 上传前还差

- [ ] **`<vendor url>`** —— 项目主页（一般填源码仓库地址），让用户有地方报 bug
- [ ] **实际运行验证** —— 尚未在真实 IDEA 里跑过；至少确认中文文件名在 Windows 资源管理器里不乱码
- [ ] **Marketplace 账号 + 签名证书** —— 在 <https://plugins.jetbrains.com/author/me/signing> 生成
- [ ] 英文 README 与市场页面截图

### 相关地址（均已实测可达）

| 用途 | 地址 |
|---|---|
| 作者面板（登录入口） | <https://plugins.jetbrains.com/author/me> |
| **签名密钥**（证书链 + 私钥） | <https://plugins.jetbrains.com/author/me/signing> |
| **上传令牌**（`PUBLISH_TOKEN`） | <https://plugins.jetbrains.com/author/me/tokens> |
| 已发布插件管理 | <https://plugins.jetbrains.com/author/me/plugins> |
| JetBrains 账号 | <https://account.jetbrains.com/> |

用你的 JetBrains 账号登录即可。注意顶层的 `/author` 是 404，必须带 `/me`。

### 签名与上传

密钥全部走环境变量，**不要写进 `gradle.properties` 或提交进仓库**：

```bash
export CERTIFICATE_CHAIN="$(cat cert-chain.pem)"
export PRIVATE_KEY="$(cat private.pem)"
export PRIVATE_KEY_PASSWORD="..."
export PUBLISH_TOKEN="..."          # https://plugins.jetbrains.com/author/me/tokens

./gradlew verifyPlugin signPlugin   # 签名并做最终校验
./gradlew publishPlugin             # 上传
```

没有这些变量时 `buildPlugin` 照常工作，只有 `signPlugin` / `publishPlugin` 会失败 —— 这是有意的。

## 目录结构

```
src/main/kotlin/com/lrong/compress/
  ArchiveNaming.kt        输出命名与重名递增        （纯 JDK，可单测）
  ExclusionRules.kt       排除规则匹配              （纯 JDK，可单测）
  ZipWriter.kt            压缩核心                  （纯 JDK，可单测）
  CompressAction.kt       右键菜单入口
  CompressTask.kt         后台任务 / 进度 / 取消
  CompressNotifier.kt     完成通知 + 在资源管理器显示
  VfsPaths.kt             VirtualFile ↔ java.nio.Path
  settings/               设置存储与设置页
src/main/resources/
  META-INF/plugin.xml     插件描述符
  META-INF/pluginIcon.svg 40×40 插件图标
  messages/               英文与中文文案
src/test/kotlin/          54 个纯逻辑单元测试
```

## License

待定。
