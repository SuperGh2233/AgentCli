# HITL 审批内容显示修复

状态：completed；2026-11-03 完成两轮代码、自审和获批的离线定向回归，最终七类 / 97 项通过。保留当前路径以维持会话中的审阅链接。

范围：ApprovalRequest 对动态元数据、JSON key/value 和非法 JSON 统一编码 C0/C1、DEL、Unicode 双向文本控制符为可见转义；execute_command.command 完整按现有框宽换行，其他长字符串保持 120 字符预览。删除终端、plain、inline 在框外重复打印的敏感提示。

不改审批结果、权限规则、CommandGuard、MCP/OAuth、评测或其他业务逻辑；测试明确通过后同步 README/ROADMAP，不 stage/commit/push/tag。

实施顺序：集中修复显示编码 → 新增独立 ApprovalRequestDisplayTest → 自审差异 → 提交准确离线测试命令和副作用等待批准。

验收：长命令重组后无丢失；动态内容不能带入原始控制字符或双向控制符；普通中文、路径和 Unicode 保留；最终七个定向测试类通过。测试构建与 Maven 缓存隔离在 /tmp，不写项目 target 或原 ~/.m2，不联网。回滚只需撤销本次显示代码和测试修改，无数据迁移。

验证记录：已完成代码与独立测试，静态自审确认没有改变 ApprovalResult、权限判断或实际工具参数。控制符采用 `<U+XXXX>` 标记，普通反斜线保留；仅含换行或制表符的元数据也显示标记。

## 离线测试结果

- 首次执行 exit code 1：空白 settings 没有匹配已有缓存的仓库标识，资源插件 maven-resources-plugin:3.5.0 无法离线解析。在编译前失败，没有部分测试，也没有 build 或测试报告生成；未联网下载。
- 经用户批准，只修临时 settings/runner：精确复制项目 `target/publication-check/settings.xml` 到 `/tmp/paicli-hitl-display-7yaiamg5/settings.xml`，`cmp` 确认一致。mirror id 为 `phase0-central-only`，mirrorOf 为 `*`，URL 为 `https://repo.maven.apache.org/maven2`。保留原缓存元数据，不修改缓存标识。
- 再次获得具体命令的一次性批准后，执行 `/tmp/paicli-hitl-display-7yaiamg5/run.py`。Maven 强制 `-o`，exit code 0，四类 / 57 项通过，0 failures、0 errors、0 skipped：ApprovalRequestDisplayTest 8 项、TerminalHitlHandlerTest 19 项、RendererHitlHandlerTest 9 项、ApprovalPolicyTest 21 项。仅编译这四个测试类；正常编译主代码，未执行评测或真实服务。
- 隔离根目录 `/tmp/paicli-hitl-display-7yaiamg5`；settings.xml、pom.xml、repository、build、home、tmp、work、logs 均在该目录。Maven 与 Surefire fork 的 user.home/java.io.tmpdir 均显式指向 home/tmp；Surefire argLine 还包含 UTF-8、paicli.log.dir 和 ErrorFile；JUnit workingDirectory 显式指向 work。环境不继承 API Key。
- 失败日志 `maven.log`，成功日志 `maven-settings-fixed.log`，报告 `build/surefire-reports/TEST-*.xml`。临时目录保留供审阅，没有清理。
- 执行前后递归核验：项目 target 内最新文件 mtime 均为 2026-11-03 08:53:53.654120，原 ~/.m2 内均为 2026-11-02 16:23:58.128680，均早于本次运行；未发现此次写入。未使用运行前内容哈希，核验依据为路径隔离和文件时间。

完整 Maven 命令（runner 使用无 API Key 的独立环境，工作目录为上述 work）：

```sh
/opt/homebrew/bin/mvn -o -s /tmp/paicli-hitl-display-7yaiamg5/settings.xml -gs /tmp/paicli-hitl-display-7yaiamg5/settings.xml -f /tmp/paicli-hitl-display-7yaiamg5/pom.xml -Dmaven.repo.local=/tmp/paicli-hitl-display-7yaiamg5/repository -DskipTests=false -Dtest=ApprovalRequestDisplayTest,TerminalHitlHandlerTest,RendererHitlHandlerTest,ApprovalPolicyTest test
```

## 第二轮补齐与验证

首轮通过后，差异审查发现审批子流程仍存在原样输出。第二轮将 `ApprovalRequest.encodeForDisplay` 公开供同一审批流程复用，补齐 Terminal/Plain/Inline 修改参数时的当前参数显示，以及 Terminal/Renderer/Plain/Inline 的 MCP 工具名、服务名、全部放行范围与缓存命中提示。自动审批提示、非法 JSON 错误和无效输入回显也使用同一编码器。Inline 的 Enter/CR 回显固定字样 `Enter`，其他按键安全编码，审批仍按原始 ch 判断。

编码只作用于显示副本；实际工具参数、modifiedArguments（修改后实际传递的参数）、原始工具名和缓存 key（审批缓存查询用的键）均原样保留。测试覆盖 ESC/CRLF/bidi、普通反斜线、MCP tool/server 两种放行范围和缓存命中。

本轮获一次性命令批准后执行 `/tmp/paicli-hitl-display-followup-20261103/run.py`，使用本地已安装 JDK 21。Maven exit code 0，七类 / 97 项通过，0 failures、0 errors、0 skipped：

| 测试类 | 项数 |
|---|---:|
| ApprovalRequestDisplayTest | 8 |
| TerminalHitlHandlerTest | 21 |
| RendererHitlHandlerTest | 10 |
| ApprovalPolicyTest | 21 |
| PlainRendererTest | 19 |
| InlineApprovalPrompterTest | 10 |
| InlineApprovalGatewayTest | 8 |

完整 Maven 命令：

```sh
/opt/homebrew/bin/mvn -o -s /tmp/paicli-hitl-display-followup-20261103/settings.xml -gs /tmp/paicli-hitl-display-followup-20261103/settings.xml -f /tmp/paicli-hitl-display-followup-20261103/pom.xml -Dmaven.repo.local=/tmp/paicli-hitl-display-followup-20261103/repository -DskipTests=false -Dtest=ApprovalRequestDisplayTest,TerminalHitlHandlerTest,RendererHitlHandlerTest,ApprovalPolicyTest,PlainRendererTest,InlineApprovalPrompterTest,InlineApprovalGatewayTest test
```

隔离根目录为 `/tmp/paicli-hitl-display-followup-20261103/`：

- settings.xml 精确复制项目已有的 `phase0-central-only` settings，`cmp` 一致；repository 为上轮离线缓存的独立副本。Maven 强制 `-o`，无联网、下载或真实服务调用。
- Maven 的独立环境只包含必要变量，不带 API keys；Maven 与 Surefire fork 的 user.home/java.io.tmpdir 显式指向 home/tmp。Surefire argLine 还设置 UTF-8、logs 和 ErrorFile；JUnit workingDirectory 为 work。七份测试 XML 中的 user.home、java.io.tmpdir、user.dir 均核对一致。
- build、logs、maven.log、build/surefire-reports 均位于隔离根目录。仅编译运行上述七个测试类，正常编译主代码，未执行模型、浏览器、Docker 或评测。七份测试源码副本与工作区逐字一致。
- 运行前后比较项目 target（38854 个文件）和原 ~/.m2（3699 个文件）的文件清单、SHA-256、大小及 mtime，全部一致。证据为 protected-before.json、protected-after.json、protected-check.json；最后一份文件的两个目录结果均为 true。临时 runner、日志和报告保留在 /tmp，未放入仓库。

最终审阅：仅修改本任务的显示代码、测试、README、ROADMAP、计划与文档索引；所有 modified/untracked 文件均已审阅，`git diff --check` 通过，staged 为空；没有 target、.env、凭证/私钥或临时 runner/报告进入本次变更。未 stage/commit/push/tag。
