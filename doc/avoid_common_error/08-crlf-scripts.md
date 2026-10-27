# CRLF 脚本/模板进容器

## 现象

容器启动即退出，日志类似：

```
exec /usr/local/bin/entrypoint.sh: no such file or directory
```

或脚本里报 `$'\r': command not found` / `syntax error near unexpected token`；模板渲染出来的配置文件里每行末尾带 `\r`，Redis/MySQL 解析失败。

## 根因

Windows 上 Git 默认 `core.autocrlf=true`，检出时把 `.sh`、`.tpl`、`.conf` 里的 LF 换成 CRLF。容器里执行时，`#!/bin/sh\r` 的内核 shebang 解析失败；即使能跑，`\r` 也会被当成命令的一部分。

## 正确做法

1. **仓库级强制 LF**：`.gitattributes` 里对脚本与模板声明 `eol=lf`：
   ```
   *.sh    text eol=lf
   *.tpl   text eol=lf
   ```
   并用 `git ls-files --eol` 抽检（期望 `i/lf w/lf`）。
2. **入口脚本自带防御**：读取模板/写配置时做 `tr -d '\r'`（本仓库 `sentinel.conf.tpl` 渲染、Redis/MySQL entrypoint 都加了这一步）。
3. **不要只靠编辑器设置**：换机器、CI、`git archive` 都会重新决定换行符，仓库级声明才是唯一可靠来源。

## 验证

```bash
git ls-files --eol -- '*.sh' '*.tpl' | grep -v 'i/lf' ; echo "（无输出 = 全部 LF）"
docker exec jl-sentinel-1 sh -c 'head -1 /data/sentinel.conf | od -c | head -2'   # 行尾应是 \n
```

## 出处

- `doc/process/Stage1/step-24a-sentinel-compose.md`（`.gitattributes` + `tr -d '\r'` 修复，`3cbbbab`）
- `doc/process/Stage1/step-25a-mysql-cluster-compose.md`（`lib/render-tpl.sh`）
