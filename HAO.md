# HAO.md

Hao CLI 改本仓库代码时读的项目说明。共享规则全部在下面导入的 `AGENTS.md`，这里只放 Hao CLI 专属的补充。

@AGENTS.md

## Hao CLI 补充

- 本文件连同导入的 `AGENTS.md` 最多放进 24,000 字符，超出部分被截掉，用户级 `~/.haocli/HAO.md` 先占预算；往 `AGENTS.md` 加内容前先看它末尾的体积上限
- PathGuard 不让读项目外的路径，包括 `~/.haocli/`；需要用户级配置、记忆或 Skill 补充目录的内容时请用户提供，不要换工具绕过
- 用 Hao CLI 改 Hao CLI 时，正在运行的还是改动前的 jar：要看到改动效果得重新 `mvn clean package` 并重启，当前会话里的行为不能当成改动后的验证结果
