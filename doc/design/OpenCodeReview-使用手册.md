# Open Code Review 使用手册

> 版本：v1.0
> 日期：2026-10-26
> 用途：后续每个任务的 AI 代码审核统一入口
> 插件：Qoder `open-code-review@qoder-marketplace`（`C:\Users\CZQ\.qoder\plugins\cache\qoder-marketplace\open-code-review\0.1.0`）
> 上游：https://github.com/alibaba/open-code-review

> 当前状态：**未启用**。OCR 需要额外配置 LLM，用户决定暂不使用；当前审核使用独立 Qoder 会话 + Qwen 子代理 + 隔离 worktree。本手册保留备查。

## 1. 它是什么

Open Code Review（`ocr`）是阿里开源的 AI 代码审核 CLI，读取 Git diff，按文件并发分析，输出带文件/行号的审核意见，支持自动修复建议。Qoder 插件把它的技能封装为 `open-code-review`，供新会话/子代理调用。

## 2. 前置条件

1. **安装 CLI**（插件本身不包含 CLI）：

   ```bash
   npm install -g @alibaba-group/open-code-review
   ocr --version
   ```

2. **配置 LLM**（Anthropic 或 OpenAI 兼容端点），任选一种：

   ```bash
   # 环境变量（推荐，CI 友好）
   export OCR_LLM_URL=https://api.anthropic.com/v1/messages
   export OCR_LLM_TOKEN=<your-api-key>
   export OCR_LLM_MODEL=claude-opus-4-6
   export OCR_USE_ANTHROPIC=true

   # 或本地持久配置
   ocr config set llm.url ...
   ocr config set llm.auth_token ...
   ocr config set llm.model ...
   ocr config set llm.use_anthropic true
   ```

3. **连通性检查**：

   ```bash
   ocr llm test
   ```

   `ocr llm test` 不通过时不得开始审核；凭证只放环境变量/本地配置，**禁止写入仓库或聊天记录**。

### Windows 安装备注

如果 `ocr --version` 报 `OpenCodeReview binary not found`，说明 npm 包已装但平台二进制未完成下载（GitHub release 可能 ECONNRESET）。可改用 npm 平台包：

```bash
npm install -g @alibaba-group/open-code-review@1.13.3 @alibaba-group/ocr-win32-x64@1.13.3
```

如果 npm 仍未生成 `ocr` 命令，在 PATH 目录（当前为 `D:\nvm4w\nodejs`）创建包装脚本：

```bash
# /d/nvm4w/nodejs/ocr
#!/usr/bin/env bash
exec node "D:/nvm4w/nodejs/node_modules/@alibaba-group/open-code-review/bin/ocr.js" "$@"
```

Windows cmd 使用 `ocr.cmd` 调同一个 JS。验证：`ocr --version`。

当前机器 CLI 已装好（`open-code-review v1.13.3 windows/amd64`），但 **LLM 尚未配置**：`ocr llm test` 报 no valid LLM endpoint。配置 `OCR_LLM_URL/OCR_LLM_TOKEN/OCR_LLM_MODEL` 或 `~/.opencodereview/config.json` 后再启用审核。

## 3. 常用命令

```bash
# 审核工作区（staged/unstaged/untracked）
ocr review --audience agent --background "业务上下文"

# 审核单个 commit（对父提交）
ocr review --audience agent --background "业务上下文" --commit <sha>

# 审核两个 ref 之间的 diff（本项目的标准做法）
ocr review --audience agent --background "业务上下文" --from <base> --to <head>

# 预览将审核哪些文件（不调用 LLM）
ocr review --preview --from <base> --to <head>

# 项目规则
ocr review --audience agent --rule .opencodereview/rule.json --from <base> --to <head>

# 控制超时/并发（大 diff）
ocr review --audience agent --timeout 15 --concurrency 4 ...
```

- **总是使用 `--audience agent`**，避免进度 UI 污染结果。
- `--background` 传业务上下文（本项目：演示优先、配置外置、Java 8 core 边界、无录屏等）。
- 默认超时 10 分钟/文件；大 diff 可调 `--timeout`、`--concurrency`。
- 默认只读审核；需要自动修复时必须明确授权，并逐条验证。

## 4. 审核流程（本项目）

1. 主开发会话完成任务并提交代码（`feat:`/`fix:`）。
2. 主会话创建一个**新的审核会话**；审核会话在**隔离 git worktree** 中执行，不修改主工作树、不重启共享 Docker 栈。
3. 审核会话读取任务 brief/report/diff，调用：

   ```bash
   ocr review --audience agent \
     --background "<任务目标、演示优先、配置外置、Java8 core 边界、无录屏>" \
     --rule .opencodereview/rule.json \
     --from <base> --to <head>
   ```

4. 按优先级处理：
   - **High**：明显 bug/安全/明确错误 → 必须修复；
   - **Medium**：合理但依赖上下文 → 评估后修复或记录；
   - **Low**：丢弃（可能误报/无关紧要）。
5. 修复在隔离 worktree 内完成，运行聚焦测试；提交 `fix:`；不在审核会话 merge/push。
6. 审核会话回报：审核范围、High/Medium findings、修复 commit、测试证据、遗留项。
7. 主会话合并修复，跑全量 `verify.sh`，写入本步交接文档。

## 5. 项目自定义规则

`.opencodereview/rule.json` 配置了本项目的强约束（配置外置、演示优先、核心边界、脚本安全）。规则优先级：`--rule` > `<repo>/.opencodereview/rule.json` > `~/.opencodereview/rule.json` > 内置。可用 `ocr rules check <file>` 预览命中规则。

## 6. 常见坑

- `ocr: command not found`：只装了 Qoder 插件，没装 CLI；执行 `npm i -g @alibaba-group/open-code-review`。
- `ocr llm test` 失败：LLM 端点/模型/凭证未配置；不要伪造审核结果。
- 工作区模式会审核 untracked 文件；标准做法用 `--from/--to` 只审任务 diff。
- 大 diff 可能截断；拆任务或用 `--timeout/--concurrency`。
- 审核只读；自动修复前需明确授权，修复后必须跑测试。
- 不要把 API key、`.env`、内部 token 写进规则文件或提交。

## 7. 资源

- 上游仓库：https://github.com/alibaba/open-code-review
- NPM：https://www.npmjs.com/package/@alibaba-group/open-code-review
- Qoder 插件目录：`C:\Users\CZQ\.qoder\plugins\cache\qoder-marketplace\open-code-review\0.1.0`
- 插件技能：`skills/open-code-review/SKILL.md`
