# 开发环境与命令

> 这份文档的用途：**新会话第一件事不用重新试错**。下面每条都是在本机真实撞过的。

## 怎么跑

| 目的 | 命令 |
|---|---|
| 全量后端测试（677 项） | `pwsh -File tools/run-tests.ps1` |
| 只跑某几个测试类 | `pwsh -File tools/run-tests.ps1 MessageHistoryServiceTest JobServiceTest` |
| 前端类型检查 + 构建 | `cd frontend; npm run build`（= `vue-tsc --noEmit && vite build`） |
| 起后端（8080） | `cd spike; ..\.tools\apache-maven-3.9.9\bin\mvn.cmd spring-boot:run` |
| 起前端（5173，代理 /api） | `cd frontend; npm run dev` |
| 起依赖（MySQL + RabbitMQ） | `cd spike; docker compose up -d mysql rabbitmq` |
| 界面验收脚本 | `node tools/ui/<名字>.cjs`（Playwright，依赖在 `tools/ui/node_modules`） |
| 场景验收脚本 | `pwsh -File tools/shadow-site/verify-*.ps1` |

- Maven 用仓库内的私有副本 `.tools/apache-maven-3.9.9`（**已 gitignore，不进版本库**）。
  没有它就退回 PATH 上的 `mvn`（CI 走这条）。`tools/run-tests.ps1` 会自动判断，并据此决定是否加 `-o`（离线）。
- 测试工作目录恒定为 `spike/`：有依赖相对路径的用例（`RuleMappingSuggesterEvalTest`）。脚本里已经 `Push-Location`。
- 冒烟：北向 `GET http://localhost:8080/mock/mes/received`；南向往队列 `mes.to.edge` 发一条 JSON。

## 已知故障

### 1. `pwsh` 一起就退：退出码 `0xC0000142`

`STATUS_DLL_INIT_FAILED`。**本机根因是 DSH 的 `workspace-write` 沙箱，不是机器坏了**：
同一条命令改成 `danger-full-access` 就正常起。表现为任何 pwsh 调用返回 `(no output)` + `[exit code: 3221225794]`。

- 处置：用 `danger-full-access` 重试一次（需要审批）。如果本会话不允许升级（审批被关掉），
  就请用户把会话权限改成 full access —— **不要连试多次，也不要猜别的命令能绕过**。
- 另外两个本机事实：
  - `pwsh` 实际是 **Windows PowerShell 5.1**，不是 PowerShell 7。
  - 执行策略禁止跑未签名脚本：先 `Set-ExecutionPolicy -Scope Process Bypass -Force`，
    或用 `powershell -ExecutionPolicy Bypass -File xxx.ps1`。

### 1b. 脚本编码：`.ps1` 要带 BOM，其余不要

- **`.ps1` 必须 UTF-8 with BOM**。PowerShell 5.1 会把无 BOM 的 UTF-8 当 GBK 读：中文变乱码，
  字符串字面量还会被吞掉，语法直接报错（实测报 `@(...)` 里 `意外的标记 ")"`）。
- **`.md` / `.yml` / `.githooks/*` 不要 BOM**：sh 脚本带 BOM 会解释失败。
- `write` 工具**新建**文件时会加 BOM；**`edit` 重写已有文件时会丢掉 BOM**——所以改完 `.ps1` 必须把 BOM 补回来，
  否则 PowerShell 5.1 读成 GBK，中文注释吞掉引号，报一堆 `意外的标记 ")"`。`tools/install-hooks.ps1` 会补。

### 2. 沙箱拒绝 fork：`CreatePipe error=5`

Surefire 需要 `cmd.exe` 管道才能 fork JVM，受限沙箱会拒绝。`tools/run-tests.ps1` 已经内置兜底：
失败后自动改用 JUnit Platform Launcher 在进程内跑，Mockito 以 `-javaagent` 加载（自附加同样走管道，会被拒）。

### 3. 「全量绿、单类红」

进程内 runner 单独跑一个类时，MyBatis-Plus 的 `TableInfo` 缓存和 Camel 上下文可能缺少别的用例建立的初始化状态，
出现假失败。**以全量结果为准**；单类红了先跑一次全量确认。

### 4. BOM：写文件时开头多了三个看不见的字节

本机 `write` 工具默认输出 UTF-8 with BOM。它会在两个地方咬人：

- `git commit -F 文件` → BOM 进提交信息第一行（2026-10-19 那次 14 个提交就是这么中招的）。
- `gh issue comment --body-file 文件` / `gh issue close --comment` → BOM 进评论正文。

处置：`.githooks/commit-msg` 会自动剥掉提交信息里的 BOM（见下节安装）；评论正文手写或用 `gh` 前先确认。
检查某个提交信息有没有 BOM：`cmd /c "git log -1 --format=%s <sha> > f"` 然后读前 3 字节
（**别用 PowerShell 的 `>`**，它会重编码成 UTF-16，测不准）。

### 5. 环境的其它约定

- 远端：`github` = `https://github.com/zz240/sailheader-mes-middleware.git`（`gh` 已登录 zz240）；
  `origin` = Gitee 的 edge-management，落后很多，**日常不用它**。
- 票在 GitHub Issues，`gh` 命令一律带 `--repo zz240/sailheader-mes-middleware`（见 [issue-tracker.md](issue-tracker.md)）。
- 端口：后端 8080、前端 5173、mock MES 9099。
- 前端构建报 `esbuild ... Access is denied`（删临时文件被拒）时，把 TEMP 指到工作区再跑：

  ```powershell
  $env:TEMP = 'D:\workspace\programs\edge-gateway\.scratch\tmp'
  $env:TMP  = $env:TEMP
  cd frontend; npm run build
  ```

## 一次性安装钩子

```powershell
Set-ExecutionPolicy -Scope Process Bypass -Force
& .\tools\install-hooks.ps1
```

它会：设 `core.hooksPath=.githooks` → 去掉 md/yml/sh 的 BOM → 给两个 `.ps1` 补 BOM → 自检。
卸载：`git config --unset core.hooksPath`。
