# Side-Git revert_turn 安全恢复

状态：completed；最终修复后的隔离离线定向回归 18/18 通过（SideGitManagerTest 4/4、SnapshotRestorerTest 14/14）；本阶段不代表全量测试通过。

## 目标

让 `revert_turn` 在恢复 pre-turn 快照时满足：

1. 不通过项目内符号链接向项目根目录之外写入。
2. 正确恢复普通文件、可执行文件和符号链接。
3. 恢复中途失败时，尽可能将工作区恢复到 restore 开始前的状态，并明确报告回滚是否成功。
4. 不递归删除未跟踪或被排除的文件；遇到无法安全处理的冲突时 fail closed。

## 接手时的问题（已补实现，待执行验证）

- `treeEntries` 只保存 path → ObjectId，丢失 JGit 的 `FileMode`。
- `writeTargetTree` 使用 `Files.write`，没有检查每一级父目录是否为符号链接；路径虽然做了 `normalize/startsWith`，但这不能阻止父组件 symlink 指向项目外。
- Git symlink blob 当前会被当作普通文件内容写入；`EXECUTABLE_FILE` 的执行位没有恢复。
- 当前流程先删除再写入，若中途出现 IOException，之前的删除/写入不会自动回滚。
- 现有 `SideGitManagerTest` 只覆盖普通文件内容恢复和异步 post-turn 快照，没有 symlink、mode、路径冲突或失败回滚用例。

## 安全不变量

### 路径边界

- 将 project root 解析为真实路径用于恢复路径解析；Git tree 中的每个 path 必须是非空相对路径。
- 拒绝绝对路径、空组件、`.`、`..`、反斜线和 Windows drive/UNC 歧义；再次检查 normalize 后的目标仍位于真实 project root 内。
- 各平台统一拒绝 Windows 保留设备名 CON/PRN/AUX/NUL/COM1–9/LPT1–9（含扩展名与 COM/LPT 上标数字别名）、尾随空格/点、Windows 不合法字符和控制字符，避免设备与名称映射歧义；这仍不表示支持 Windows 软链或执行位恢复。
- 每棵 tree 的完整路径及全部父路径使用 NFC + Locale.ROOT 大写再小写 + NFC 建立保守 collision key；不同原文映射到同一 key 即在项目写入前拒绝，包括 Foo/foo、Unicode 规范化形式以及父目录大小写别名。这是保守拒绝规则，不宣称覆盖文件系统的全部别名形式。
- 每次创建目录、删除、移动或创建 symlink 前，都检查所有父组件；父组件必须是实际目录，不能是 symlink。
- 最终路径本身可以是 symlink，但只能删除或替换 symlink 本身，不能读取/写入其目标。
- 如果某个父 symlink 是当前 pre-restore snapshot 中受跟踪的条目，而目标 snapshot 在该位置需要真实目录，则先 unlink 该 symlink 本身，再创建目录；绝不沿链接写入。若该 symlink 是 ignored/untracked、未被当前快照记录，或无法证明可以安全替换，则 fail closed。
- 对 file ↔ directory 形态变化，只能移除快照中明确跟踪且目标不再需要的文件；若目录中存在 excluded/untracked 内容，保留内容并拒绝覆盖，不做递归删除。

### Git 文件类型与权限

- Tree entry 保存 `ObjectId + FileMode`。
- 支持 `REGULAR_FILE`、`EXECUTABLE_FILE`、`SYMLINK`。
- `SYMLINK` blob 解码为链接目标，并通过 `Files.createSymbolicLink` 创建链接本身；不能把链接目标内容覆盖到链接路径或目标文件。
- POSIX 文件系统恢复执行位；无 POSIX 权限的平台（包括 Windows）对 executable 或 symlink 快照在写入前拒绝，不虚报支持。
- Git tree 只保存可执行模式，不保存完整 Unix 权限。写回保留现有普通文件的读写权限，新文件保留临时文件的保守权限；只增减执行位，不把原本 0600 的文件扩大为 0644。
- `GITLINK` 和未知 FileMode 在任何项目文件变更前拒绝恢复，避免把 commit 对象错误写成普通文件。

### 事务与回滚

- 在修改项目之前，先生成 pre-restore 快照并读取 current/target tree。
- 先校验所有路径、FileMode、symlink target 和文件/目录冲突；不安全时不进行项目写入。
- 项目写入前逐个流式读取 target 与独立 pre-restore commit 的 blob，验证对象类型与可读性，不累计整个 tree 的 byte[]。软链目标限制为 16 KiB，严格验证 UTF-8、NUL 与平台路径可解析性；应用时再次从 JGit stream 写入同目录临时文件。
- 每项写回使用目标的 sibling temp（同目录临时文件/链接）再 move/replace，优先 atomic move；删除操作只针对明确跟踪的条目。记录已成功操作的条目和新建目录，供失败回滚使用。
- 任一应用步骤失败时，重放独立 pre-restore commit 的恢复数据。返回信息必须区分：
  - 恢复失败，但工作区已回滚到 restore 前状态；
  - 恢复失败，且回滚也失败，需要用户人工检查。
- 临时文件清理同样逐级检查父路径；失败需保留原始错误并报告清理问题，不能冒险沿链接清理。

### TOCTOU 与平台边界

- 评估 macOS 与 Windows 默认文件系统 provider 是否支持 `SecureDirectoryStream`，以及能否在目录句柄相对路径下完成安全创建/移动。
- 本轮不实现 SecureDirectoryStream，统一采用每次 mutation 前逐级 `NOFOLLOW_LINKS` 重检的 path-based NIO fallback。
- 若 Windows provider 不支持，fallback 必须在每次 mutation 前重新检查父路径的 symlink 状态，并在不确定时 fail closed；文档要明确普通 path-based NIO 仍存在并发 TOCTOU 窗口，不得宣称彻底消除竞态。
- 不改变现有快照存储位置、排除规则、offset 语义或 UI 命令。

## 建议实现顺序

1. **TreeEntry 与路径预检**：引入携带 FileMode 的 tree entry；增加相对路径验证、真实 root 解析、父目录 NOFOLLOW 检查和 unsupported mode 拒绝。
2. **文件类型恢复**：实现 regular/executable/symlink 三种 staging；安全处理 final symlink 与 tracked symlink parent；保留 excluded/untracked 内容。
3. **streaming 与 rollback**：预检 target/current 两份 tree，再流式应用 target；异常时从独立 pre-restore commit 的条目重放 current，并报告回滚结果。每个 delete/write 在尝试 mutation 前登记，回滚 hook 使用独立 phase。
4. **故障注入 seam**：优先考虑一个最小 package-private `RestoreFileOps` 接口，仅抽象会影响事务的 create/move/delete/symlink/permission 操作；通过“第 N 次写入抛 IOException”的测试验证部分应用后回滚。若能用更小的单点 hook 实现同等确定性测试，则不引入大而全的文件系统抽象。
5. **回归与文档**：待用户 approve_once 后，隔离到 `/private/tmp` 的 Maven offline 定向测试；通过后再更新 ROADMAP 和本计划状态，本轮不修改 README。

## 必须覆盖的测试

| 场景 | 预期 |
|---|---|
| 已跟踪 symlink parent 当前指向项目外目录，目标快照在该位置需要目录 | unlink 链接本身并恢复真实目录；项目外 sentinel 内容不变 |
| ignored/untracked symlink parent 指向项目外目录 | fail closed；外部 sentinel 不变；项目文件无变化 |
| 目标快照包含 symlink，当前路径是普通文件 | 恢复为 symlink；链接目标文件内容不变 |
| 目标快照包含 executable file，当前文件已失去执行位 | 恢复执行位 |
| file → directory / directory → file | 只移除被快照跟踪且目标不再需要的条目；遇到 excluded/untracked 子项时拒绝破坏 |
| tree 中出现 GITLINK/未知 mode | 任何项目写入前失败 |
| 注入中途写入失败 | rollback 后所有文件内容、symlink 和执行位与 pre-restore snapshot 一致 |
| 注入 rollback 自身失败 | 返回明确的双重失败信息，不谎报已恢复 |

## 验收边界

- 不用真实用户项目执行破坏性 restore；所有测试都在 JUnit `@TempDir` 隔离目录。
- Maven 使用 `-o`，独立 repository、build、home、tmp、workingDirectory、logs 和 reports 均在 `/tmp`；不写项目 `target` 或原 `~/.m2`。
- 不访问网络、不调用真实模型、浏览器、Docker 或 benchmark。
- 本轮允许修改仅限 snapshot 主代码、对应测试、本计划及必要的 docs/index.md/ROADMAP；保留工作区已有 HITL 改动，不改 README 或其他模块。测试脚本本身不执行 stage/commit/push/tag；阶段验收后由独立 Repo Ops 提交并推送。
- 2026-11-04：开始实施；本地源码写入已获批准，网络禁止，Maven/测试/构建尚未获批。测试执行方案将在代码自审后给出准确命令及完整隔离路径。

## 2026-11-04 实现与静态自审

- 已实现 SnapshotRestorer；SideGitManager 的恢复根目录使用 toRealPath。excluded 条目仍在 validateEntry 前过滤。
- 兼容性补充：SideGitManager 单独保留调用方传入路径的 absolute+normalize 结果作为 snapshotIdentityRoot，仅用于计算 Side-Git 存储目录；projectRoot 继续使用 toRealPath 作为恢复边界。这样经软链接启动时不会改变既有快照目录身份。SideGitManagerTest 已验证 alias 与真实路径保留不同的快照存储身份。
- 预检所有 current/target 路径、模式、父冲突、blob；写入走同目录临时文件和 replace，最终路径软链只替换链接本身。
- 目录来源在 preflight 和 removeEmptyTree 都检查：只有 from tree 中 tracked 文件的父目录，或本次创建的目录，才允许递归检查/删除。未跟踪空目录也阻止目录→文件转换；预检后新出现的空目录仍拒绝删除。
- 已添加 SnapshotRestorerTest，并扩展 SideGitManagerTest 的真实 commit 调用链用例；涵盖外部 sentinel、软链、执行位、双向形态转换、未跟踪空目录、预检后新增空目录、不支持模式、缺失 blob、路径格式、forward one-shot 和 rollback failure。
- 补充每棵 tree 的大小写/Unicode/父目录别名碰撞、Windows 设备名和尾随空格/点拒绝测试；权限测试验证不会扩大原有 0600 文件的读取范围。
- 初次静态自审阶段尚未执行 Maven、测试或构建；当时的 `git diff --check` 无空白错误仅属于静态检查。2026-11-04 的一次全量回归尝试因 MockWebServer socket `EPERM` 按停止条件终止，虽有部分 Surefire 结果落盘，但不是完整回归；该次失败不计入本阶段定向回归结果。
- 只读 javap 检查本机 JGit 7.6.0 的 FileMode.fromBits：0140000 不在已知 type switch 中，走未知模式分支（object type = -1）；测试显式断言 bits、OBJ_BAD、非 GITLINK 与 mutation hook 零次。javap 只读反汇编，未执行恢复代码或测试。
- path-based NIO 在检查与操作之间仍有 TOCTOU residual，不能宣称抵御并发恶意目录替换；检测到不安全路径就失败，回滚也遵守同一边界。
- 首轮隔离离线定向回归（修改缺失父目录处理前）已通过：`SideGitManagerTest` 4/4、`SnapshotRestorerTest` 13/13，共 17 passed、0 failures、0 errors、0 skipped，Maven `BUILD SUCCESS`。材料位于 `/private/tmp/paicli-sidegit-2hwrb_g7/`。
- 最终代码审查发现：current tree 中已跟踪文件若连同父目录一起缺失，旧 preflight 将其误判为不安全父路径。现改用 `existingParents` 区分“父目录不存在”（允许后续重建/跳过删除）与“父组件存在但不是实际目录”（拒绝）；新增 `restoresNestedTrackedFileWhenItsParentDirectoryIsMissing` 测试。修复后已重新执行定向回归并通过，首轮 17 项结果仅作为历史记录。

## 定向回归隔离命令（已执行两次；最终修复后 18/18 通过）

以下是本阶段采用的可复核命令。最终修复后再次原样执行，使用全新 `/private/tmp/paicli-sidegit-*` 隔离副本；不更改原 pom，不写原 target 或原 ~/.m2。副本 pom 明确指定独立 build、classes、test-classes、report 和 workingDirectory；HOME、Java user.home、tmp、Maven repository、日志均隔离。Maven `-o` 禁止下载，macOS sandbox-exec 再禁止网络与隔离目录外写入。缺依赖或 Seatbelt 下 JVM 139 就停止并报告，不联网补包、不去掉限制重跑。没有 stage/commit/push/tag。

```bash
/usr/bin/python3 - <<'PY'
import os
import pathlib
import shlex
import shutil
import subprocess
import tempfile
import xml.etree.ElementTree as ET

source = pathlib.Path.cwd().resolve()
assert (source / 'pom.xml').is_file(), '请在 PaiCLI 工作区根目录启动'
assert (source / 'src/main/java/com/paicli/snapshot/SideGitManager.java').is_file()
cache = pathlib.Path.home() / '.m2/repository'
assert cache.is_dir(), '本机离线 Maven 缓存不存在；停止，不下载'
mvn_found = shutil.which('mvn')
assert mvn_found, 'PATH 中没有 Maven'
maven = pathlib.Path(mvn_found).resolve()
assert maven.is_file() and os.access(maven, os.X_OK)
if os.environ.get('JAVA_HOME'):
    java_home = pathlib.Path(os.environ['JAVA_HOME']).resolve()
else:
    java_found = shutil.which('java')
    assert java_found, 'PATH 中没有 Java'
    java_home = pathlib.Path(java_found).resolve().parent.parent
java = java_home / 'bin/java'
assert java.is_file() and os.access(java, os.X_OK) and (java_home / 'lib/modules').is_file(), \
    '无法解析实际 JDK；请设置 JAVA_HOME，本脚本不调用 Java 探测'
assert pathlib.Path('/usr/bin/sandbox-exec').is_file(), '需要 macOS sandbox-exec 禁网'
isolated = pathlib.Path(tempfile.mkdtemp(prefix='paicli-sidegit-', dir='/private/tmp'))
for name in ('home', 'tmp', 'repository', 'build', 'log', 'report', 'workingDirectory'):
    (isolated / name).mkdir()
work = isolated / 'source'
work.mkdir()
shutil.copy2(source / 'pom.xml', work / 'pom.xml')
shutil.copytree(source / 'src', work / 'src')
shutil.copytree(cache, isolated / 'repository', dirs_exist_ok=True)
settings = isolated / 'settings.xml'
settings.write_text('<settings xmlns="http://maven.apache.org/SETTINGS/1.0.0">'
    '<mirrors><mirror><id>phase0-central-only</id><mirrorOf>*</mirrorOf>'
    '<url>https://repo.maven.apache.org/maven2</url></mirror></mirrors></settings>')

ns = 'http://maven.apache.org/POM/4.0.0'
ET.register_namespace('', ns)
q = lambda tag: '{' + ns + '}' + tag
pom = ET.parse(work / 'pom.xml')
build = pom.getroot().find(q('build'))
def put(parent, tag, text):
    child = parent.find(q(tag))
    if child is None:
        child = ET.SubElement(parent, q(tag))
    child.text = str(text)
put(build, 'directory', isolated / 'build')
put(build, 'outputDirectory', isolated / 'build/classes')
put(build, 'testOutputDirectory', isolated / 'build/test-classes')
for plugin in build.find(q('plugins')):
    if plugin.findtext(q('artifactId')) == 'maven-surefire-plugin':
        config = plugin.find(q('configuration'))
        put(config, 'reportsDirectory', isolated / 'report')
        put(config, 'workingDirectory', isolated / 'workingDirectory')
        put(config, 'argLine', '-Dfile.encoding=UTF-8 -Duser.home=' + str(isolated / 'home')
            + ' -Djava.io.tmpdir=' + str(isolated / 'tmp'))
pom.write(work / 'pom.xml', encoding='UTF-8', xml_declaration=True)
profile = isolated / 'offline.sb'
profile.write_text('(version 1) (deny default) (allow file-read*) (allow process*) '
    + '(allow sysctl-read) (allow mach-lookup) '
    + '(allow file-write* (subpath "' + str(isolated) + '") (literal "/dev/null")) '
    + '(deny network*)\n')
env = {
    'PATH': str(java_home / 'bin') + ':' + str(maven.parent) + ':/usr/bin:/bin:/usr/sbin:/sbin',
    'JAVA_HOME': str(java_home),
    'HOME': str(isolated / 'home'),
    'TMPDIR': str(isolated / 'tmp'),
    'LANG': 'en_US.UTF-8',
    'MAVEN_OPTS': '-Duser.home=' + str(isolated / 'home')
        + ' -Djava.io.tmpdir=' + str(isolated / 'tmp'),
}
command = [
    '/usr/bin/sandbox-exec', '-f', str(profile), str(maven),
    '-o', '-B', '-ntp', '-s', str(settings), '-f', str(work / 'pom.xml'),
    '-Dmaven.repo.local=' + str(isolated / 'repository'),
    '-DskipTests=false', '-Dtest=SideGitManagerTest,SnapshotRestorerTest',
    '-DfailIfNoTests=true', '-l', str(isolated / 'log/maven.log'), 'test',
]
for name, value in [('original source (read only)', source), ('original cache (read only)', cache),
                    ('Maven', maven), ('JAVA_HOME', java_home), ('Java', java),
                    ('isolation root', isolated), ('source copy', work), ('settings', settings), ('sandbox', profile)]:
    print(name + ':', value, flush=True)
for name in ('home', 'tmp', 'repository', 'build', 'log', 'report', 'workingDirectory'):
    print(name + ':', isolated / name, flush=True)
print('Maven log:', isolated / 'log/maven.log', flush=True)
print('Actual command:', shlex.join(command), flush=True)
raise SystemExit(subprocess.run(command, cwd=isolated / 'workingDirectory', env=env).returncode)
PY
```
