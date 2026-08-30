# esurfing-diagnostics-and-repair - Work Plan

## TL;DR (For humans)
**What you'll get:** 一个能安全记录门户、HTTP、ZSM、加解密、XML、登录和心跳各阶段证据的诊断版客户端；现场证据会被固化成回放测试，并只据此实施最小修复，再用同一校园网完成持续心跳验证。

**Why this approach:** 当前代码把多种故障折叠成空字符串、`false` 或未分类异常，而且服务端历史上多次改变协议。先安全取证、再固定回放、最后修复，才能区分接口变化、新算法、网络故障和客户端状态机缺陷。

**What it will NOT do:** 不启动安卓模拟器，不默认全量抓包，不自动上传证据，不把密码或会话材料写进普通日志，也不在缺少现场失败证据时猜测性修改加密算法。

**Effort:** XL
**Risk:** High - 最终根因依赖真实校园网返回，且原始证据可能包含可复用的认证材料。
**Decisions to sanity-check:** 诊断全部集成进现有 JAR；Docker 持久化证据；原始证据导出后由 7-Zip/GPG 外部加密；使用 TDD；真实网络前置条件缺失时必须报告 `BLOCKED`。

Your next move: 在独立工作会话中执行 `$start-work esurfing-diagnostics-and-repair`。完整执行细节如下。

---

> TL;DR (machine): XL/high-risk; build typed diagnostics and secure evidence storage, capture a real failure, convert it to deterministic replay, make only the evidence-supported repair, and prove sustained field operation plus rollback.

## Scope
### Must have
- 为每次启动和认证尝试生成稳定的 `runId`、`attemptId`、`correlationId`，并记录阶段、时间、耗时、结果类别和非敏感摘要。
- 将传输错误、HTTP 非成功、重定向、门户解析、ZSM 解析、算法分发、解密、XML 校验、业务失败和状态转移建模为不同的 typed result。
- 在显式诊断模式下，将证据写入可配置根目录；分离 `redacted/` 与 `sensitive/`，生成大小、SHA-256、时间和关联 ID 清单。
- 普通日志与脱敏证据不得包含密码、短信验证码、ticket、cookie、ZSM key、静态 key/IV 或完整认证 XML。
- 捕获门户原始响应、ZSM 和加密响应时限制大小、标记截断，并仅写入 `sensitive/`；普通模式不得产生原始证据。
- 为 HTTP、门户、短信、ZSM、九种算法、XML 契约和状态机建立 TDD 回放测试。
- 提供 Dockerfile/运行入口、`/data/diagnostics` bind mount 示例、权限/导出/外部加密/保留/销毁说明。
- 产出诊断构建，完成第一次真实网络证据采集；将失败固化成脱敏 fixture，再实施最小修复。
- 产出修复构建，在相同网络条件下完成第二次真实运行，证明登录、连续心跳、受控注销和证据清单均有效。
- CI 执行 `clean check shadowJar`，产物与 Git SHA/JAR SHA-256 可追溯，并有 baseline 回滚步骤。

### Must NOT have (guardrails, anti-slop, scope boundaries)
- 不得引入安卓模拟器、UI 自动化、TLS 中间人、认证绕过或默认全量抓包。
- 不得创建 Windows Python 辅助程序、自动上传服务或应用内归档加密/密码管理。
- 不得把 `sensitive/`、真实凭据、原始现场 fixture 或运行日志提交到 Git、CI artifact、公共 issue 或聊天。
- 不得因方便测试而升级生产依赖的大版本，或重写未被现场证据指向的算法、padding、key/IV、Algo-ID 映射和协议字段。
- 不得用 HTTP 200、进程存活、单条“登录成功”日志或 `isLogged == true` 宣称修复完成。
- 不得在证据不充分时继续猜测；必须生成 `BLOCKED` 报告，列明缺少的字节、官方 APK/版本或现场重跑条件。

## Verification strategy
> Zero human intervention - all verification is agent-executed.
- Test decision: TDD；使用 Kotlin test/JUnit 5、OkHttp MockWebServer、确定性密码向量、XML/JSON/ZSM 固定 fixture 和 Docker CLI。每个缺陷任务必须保留同一测试“修复前失败、修复后通过”的命令和断言证据。
- Repository gates: `./gradlew clean check shadowJar --no-daemon`；测试任务必须实际执行，不能以无测试的 `UP-TO-DATE` 伪装成功。
- Secret scan: 对控制台、`redacted/`、测试报告和 CI artifact 内容执行已知 canary secrets 扫描；`sensitive/` 不进入扫描输入之外的发布/CI路径。
- Field gates: 真实网络任务只在已授权账号、校园网络、Docker host networking、可写 bind mount 和显式 sensitive capture 同意均满足时执行；否则输出 `BLOCKED`，不得宣称完成。
- Field success: transport、protocol、business 三层均成功；ticket/login/heartbeat/term 必填字段有效；连续心跳至少 `max(3 次, 2 * 首次下发 interval)`，且没有未分类异常或错误成功事件。
- Evidence: `<attemptDir>/task-<N>-esurfing-diagnostics-and-repair.<ext>`；现场运行的容器 `/data/diagnostics/<runId>/` 必须映射到宿主机受控目录，并将脱敏副本复制到当前 attemptDir。`attemptDir` 来自 `omo ulw-loop status --json`；非 ulw-loop 使用 `.omo/evidence/`。

## Execution strategy
### Parallel execution waves
> Target 5-8 todos per wave. Fewer than 3 (except the final) means you under-split.
- Wave 1（基础，可并行）：Todo 1-3 建立测试基线、诊断模型和安全证据存储。
- Wave 2（协议可观测性，可并行）：Todo 4-7 分别覆盖 HTTP/门户、ZSM/密码、XML/状态机和 CLI/生命周期。
- Wave 3（交付面，可并行）：Todo 8-10 完成 Docker、CI/回滚和全套离线回归。
- Wave 4（现场诊断门）：Todo 11 构建并运行诊断版本；未取得完整证据则整个修复链保持 `BLOCKED`。
- Wave 5（证据驱动修复）：Todo 12 将现场失败固化为 fixture 并实施唯一被证据支持的最小修复。
- Wave 6（现场证明与报告，可串行）：Todo 13-14 验证修复版本、演练回滚并形成根因报告。

### Dependency matrix
| Todo | Depends on | Blocks | Can parallelize with |
| --- | --- | --- | --- |
| 1 | - | 2-7, 10 | 2, 3 |
| 2 | 1 | 4-7, 10 | 3 |
| 3 | 1 | 4-10 | 2 |
| 4 | 1, 2, 3 | 10, 11 | 5, 6, 7 |
| 5 | 1, 2, 3 | 10, 11 | 4, 6, 7 |
| 6 | 1, 2, 3 | 10, 11 | 4, 5, 7 |
| 7 | 1, 2, 3 | 8, 10, 11 | 4, 5, 6 |
| 8 | 3, 7 | 11 | 9, 10 |
| 9 | 1, 3 | 11, 13 | 8, 10 |
| 10 | 4-7 | 11 | 8, 9 |
| 11 | 8-10 | 12 | - |
| 12 | 11 | 13 | - |
| 13 | 9, 12 | 14 | - |
| 14 | 13 | F1-F4 | - |

## Todos
> Implementation + Test = ONE todo. Never separate.
<!-- APPEND TASK BATCHES BELOW THIS LINE WITH edit/apply_patch - never rewrite the headers above. -->
- [x] 1. 建立 TDD 基线、脱敏 fixture 规范和测试工具链
  What to do / Must NOT do: 在 `build.gradle.kts` 增加 Kotlin test/JUnit 5 和与现有 OkHttp 4.12.0 兼容的 MockWebServer 测试依赖，配置 `useJUnitPlatform()`；创建 `src/test/kotlin/` 与 `src/test/resources/fixtures/`，加入完全虚构的 portal、JSON、XML、ZSM 和 cipher 测试样本以及 canary 密码/ticket/key。先写会暴露当前非 2xx 成功误判、秘密日志、空字段传播和 malformed ZSM 的失败测试。不得使用真实账号、现场原包或升级生产依赖大版本。
  Parallelization: Wave 1 | Blocked by: - | Blocks: 2-7, 10
  References (executor has NO interview context - be exhaustive): `build.gradle.kts:1-31`; `.github/workflows/build.yml:14-24`; `src/main/kotlin/com/rsplwe/esurfing/network/NetResult.kt:1-14`; `src/main/kotlin/com/rsplwe/esurfing/Session.kt:23-58`; `src/main/kotlin/com/rsplwe/esurfing/Client.kt:62-91`.
  Acceptance criteria (agent-executable): `./gradlew test --no-daemon` 实际执行至少一组测试；evidence 同时保存修复前预期失败的测试名称/断言/非零结果以及测试工具链安装后基础测试通过结果；fixture 扫描确认不含仓库 `KeyData.kt` 中的真实 key/IV 和任何现场数据。
  QA scenarios (name the exact tool + invocation): happy：`./gradlew test --tests '*Fixture*' --no-daemon` 验证 fixture 可加载且字段确定；failure：运行 fixture secret scanner，向临时测试输出注入 canary password/ticket 后必须失败。Evidence `<attemptDir>/task-1-esurfing-diagnostics-and-repair.txt`。
  Commit: Y | `test(infra): establish deterministic protocol fixtures`

- [x] 2. 定义诊断事件、阶段、错误和关联标识契约
  What to do / Must NOT do: 新增小型 typed diagnostics 模型，定义 `runId`、`attemptId`、`correlationId`、stage、timestamp、duration、transport/http/protocol/business result、error code、truncation 与 evidence reference；建立稳定 JSON Lines 编码和字段白名单。错误消息只保留分类和安全摘要，不序列化异常对象、请求体或凭据。避免把整个 `States` 对象或 OkHttp request/response 直接反射序列化。
  Parallelization: Wave 1 | Blocked by: 1 | Blocks: 4-7, 10
  References (executor has NO interview context - be exhaustive): `src/main/kotlin/com/rsplwe/esurfing/States.kt:6-30`; `src/main/kotlin/com/rsplwe/esurfing/network/NetResult.kt:3-13`; `src/main/kotlin/com/rsplwe/esurfing/Client.kt:27-60`; `src/main/kotlin/com/rsplwe/esurfing/utils/NetworkConnectivity.kt:19-78`.
  Acceptance criteria (agent-executable): 单元测试对每个 stage 和 result 分支做穷尽断言；同一尝试的事件共享 attempt/correlation ID，不同启动的 runId 不同；JSONL 每行可独立解析；canary secret 不出现在序列化结果中。
  QA scenarios (name the exact tool + invocation): happy：`./gradlew test --tests '*DiagnosticEvent*' --no-daemon` 验证顺序、ID 和 JSON round-trip；failure：构造包含异常栈、URL query、password、ticket 的输入，序列化必须脱敏或拒绝。Evidence `<attemptDir>/task-2-esurfing-diagnostics-and-repair.txt`。
  Commit: Y | `feat(diagnostics): define typed event contract`

- [x] 3. 实现分层证据存储、脱敏、清单、限额和权限
  What to do / Must NOT do: 增加可配置 diagnostics root，在每个 run 下创建 `redacted/`、`sensitive/`、`manifest.json`；默认只写 redacted JSONL，显式 sensitive 模式才允许保存原始 portal/ZSM/密文响应。对用户名、手机号、IP、MAC、schoolId 做稳定掩码，对密码、SMS、ticket、cookie、key 做完全移除；记录原始长度、保存长度、截断标记、SHA-256、stage 和关联 ID。采用原子写入，先写原件/计算 hash，再生成独立脱敏派生物，绝不原地改 raw；设单文件/单 run 限额和保留策略；Unix 上目录/文件使用受限权限，并记录 Windows/bind mount 不保证权限的残余风险。不得实现删除命令、自动上传或应用内归档加密。
  Parallelization: Wave 1 | Blocked by: 1 | Blocks: 4-10
  References (executor has NO interview context - be exhaustive): `src/main/kotlin/com/rsplwe/esurfing/Session.kt:55-79`; `src/main/resources/log4j.properties:1-11`; `.gitignore:1-24`; `src/main/kotlin/com/rsplwe/esurfing/Options.kt:3-7`; `src/main/kotlin/com/rsplwe/esurfing/cipher/KeyData.kt:3-111`.
  Acceptance criteria (agent-executable): 普通模式不创建 `sensitive/` 内容；显式模式生成 raw + redacted 独立文件及匹配 manifest；重新计算每个文件 SHA-256 与清单一致；超限输入被截断并保留原始总长度；redacted/控制台/测试报告不含所有 canary secrets；CI 路径不能包含 sensitive。
  QA scenarios (name the exact tool + invocation): happy：`./gradlew test --tests '*EvidenceStore*' --tests '*Redaction*' --no-daemon` 验证原子写、hash、权限和派生关联；failure：模拟磁盘不可写、redaction 失败和超大 payload，raw 不被覆盖且产生 typed failure。Evidence `<attemptDir>/task-3-esurfing-diagnostics-and-repair.txt`。
  Commit: Y | `feat(diagnostics): add tiered evidence storage`

- [x] 4. 重构 HTTP、重定向和门户探测为可诊断的 typed result
  What to do / Must NOT do: 让网络结果携带 stage、URL 的安全形式（scheme/host/path，移除 query/userinfo）、status、选定 headers、content type、body bytes、耗时和 redirect chain，并可靠关闭 response/body；明确区分 transport failure、非 2xx、redirect limit/missing Location、empty body、portal marker/config failure 与 internet success。门户响应无旧标记时不得仅凭 HTTP 成功判定已联网；保留现有 CDC area/school/domain 行为并测试每跳更新。正常日志不得输出完整 Location query、cookie 或 body。
  Parallelization: Wave 2 | Blocked by: 1-3 | Blocks: 10, 11
  References (executor has NO interview context - be exhaustive): `src/main/kotlin/com/rsplwe/esurfing/network/NetClient.kt:16-101`; `src/main/kotlin/com/rsplwe/esurfing/utils/NetworkConnectivity.kt:28-79`; `src/main/kotlin/com/rsplwe/esurfing/Constants.kt:7-13`.
  Acceptance criteria (agent-executable): MockWebServer 覆盖 200/204/302/307/308/400/401/403/500、空 body、超时、缺 Location、超过五跳和门户标记改变；每种输入得到不同 typed outcome；非 2xx 不进入 XML/业务成功路径；重复执行无 response 泄漏。
  QA scenarios (name the exact tool + invocation): happy：`./gradlew test --tests '*NetClient*' --tests '*Portal*' --no-daemon` 验证正常重定向与门户配置提取；failure：模拟 500 HTML、重定向循环和旧 tag 缺失，事件必须准确分类且不返回 `SUCCESS`。Evidence `<attemptDir>/task-4-esurfing-diagnostics-and-repair.txt`。
  Commit: Y | `fix(network): classify transport and portal outcomes`

- [x] 5. 加固 ZSM 解析、未知 Algo-ID 取证和密码回放边界
  What to do / Must NOT do: 将 ZSM 字节解析提取为可测试 parser，使用无符号长度、明确 charset/结构校验、字段范围和 trailing-byte 记录；未知 Algo-ID/malformed ZSM 返回 typed failure。移除普通日志中的 ZSM key 和公开 issue 上传提示；普通模式只记录长度、header、Algo-ID 和指纹，显式 sensitive 模式通过 EvidenceStore 保存原字节。为九种算法添加已知向量及项目协议 round-trip fixture。不得因“看起来不标准”统一 padding、修改 key/IV 或新增算法；这些变化只能在 Todo 12 有现场 mismatch 证据后进行。
  Parallelization: Wave 2 | Blocked by: 1-3 | Blocks: 10, 11
  References (executor has NO interview context - be exhaustive): `src/main/kotlin/com/rsplwe/esurfing/Session.kt:14-79`; `src/main/kotlin/com/rsplwe/esurfing/cipher/CipherFactory.kt:13-68`; `src/main/kotlin/com/rsplwe/esurfing/cipher/KeyData.kt:3-111`; `src/main/kotlin/com/rsplwe/esurfing/cipher/impl/`; `src/main/java/com/rsplwe/crypto/SM4.java`; `src/main/java/com/rsplwe/crypto/Zuc128.java`.
  Acceptance criteria (agent-executable): 测试覆盖截断 header、负值解释风险、零/超长 keyLen/algoIdLen、invalid UTF-8、trailing bytes、未知 ID 和所有已知 ID；旧 `algo_dump_*.bin` 当前目录行为消失；普通模式无 raw，sensitive 模式 raw hash 可复验；现有九种算法向量不回归。
  QA scenarios (name the exact tool + invocation): happy：`./gradlew test --tests '*Zsm*' --tests '*Cipher*' --no-daemon` 验证已知包与算法向量；failure：未知 Algo-ID 和 malformed 字节只产生 typed failure + 受控证据，不崩溃、不泄露 key。Evidence `<attemptDir>/task-5-esurfing-diagnostics-and-repair.txt`。
  Commit: Y | `fix(session): harden zsm diagnostics and replay`

- [x] 6. 建立 XML/JSON 响应契约和认证生命周期状态机
  What to do / Must NOT do: 为 ticket、login、heartbeat、term 和 SMS 响应定义 parse-don't-validate 风格结果，区分格式错误、缺必填字段、业务拒绝、验证码不需要/需要/查询失败。校验 `ticket`、`keep-url`、`term-url`、`keep-retry`/`interval`，为间隔定义协议兼容的正数与防高频上限；非法值进入 typed failure，不能 `toLong()` 崩溃。定义合法状态转移与每次重新授权的状态清理，确保未初始化不能 login/keep/term、失败登录不能标记 logged、heartbeat 失败进入 re-auth/degraded、term 幂等、shutdown 停止后不再发请求。不得靠空字符串和布尔值表达不同失败。
  Parallelization: Wave 2 | Blocked by: 1-3 | Blocks: 10, 11
  References (executor has NO interview context - be exhaustive): `src/main/kotlin/com/rsplwe/esurfing/Client.kt:27-235`; `src/main/kotlin/com/rsplwe/esurfing/States.kt:6-30`; `src/main/kotlin/com/rsplwe/esurfing/utils/NetworkConnectivity.kt:81-117`; `src/main/kotlin/com/rsplwe/esurfing/model/RequireVerificate.kt:5-28`.
  Acceptance criteria (agent-executable): fixture 覆盖非 XML/JSON、截断/重复/空字段、未知业务码、非法 retry/interval、SMS 查询失败；所有非法输入不进入 logged；重试不复用旧 ticket/URL/extra config；heartbeat 异常具有阶段和原因事件且主循环行为符合状态表。
  QA scenarios (name the exact tool + invocation): happy：`./gradlew test --tests '*ProtocolParser*' --tests '*AuthState*' --no-daemon` 验证完整 ticket-login-keep-term；failure：缺 ticket、500 HTML、错误验证码、interval=0/溢出和解密乱码均被阻断并分类。Evidence `<attemptDir>/task-6-esurfing-diagnostics-and-repair.txt`。
  Commit: Y | `fix(auth): enforce protocol and lifecycle contracts`

- [x] 7. 增加安全诊断 CLI、配置优先级和可靠关闭流程
  What to do / Must NOT do: 扩展命令行/环境配置以选择 diagnostics root、diagnostic mode、payload limit 和 retention；默认安全关闭 sensitive capture。为凭据提供不暴露在进程参数中的输入方式（优先受限权限文件或 stdin；保留旧 `-p` 兼容但明确警告残余风险），日志只记录配置来源而非值。关闭钩子捕获并记录所有 term/session 失败，确保 flush manifest 后退出；敏感模式启动时输出非秘密告警。不得把密码、SMS 或 raw path 打印到控制台。
  Parallelization: Wave 2 | Blocked by: 1-3 | Blocks: 8, 10, 11
  References (executor has NO interview context - be exhaustive): `src/main/kotlin/com/rsplwe/esurfing/DialerApp.kt:13-76`; `src/main/kotlin/com/rsplwe/esurfing/Options.kt:3-7`; `src/main/kotlin/com/rsplwe/esurfing/Client.kt:62-109`; `src/main/resources/log4j.properties:1-11`; `README.md:14-53`.
  Acceptance criteria (agent-executable): CLI parser 测试覆盖默认/环境/参数优先级、不可写路径、无密码、stdin/file 凭据和敏感模式显式确认；控制台与日志 canary scan 为零；shutdown 无论 term 成败都完成事件/manifest flush，且不继续请求。
  QA scenarios (name the exact tool + invocation): happy：使用测试凭据文件启动 replay 模式并正常关闭，检查 manifest；failure：不可写 diagnostics root、缺凭据和 term 异常必须安全失败且无秘密输出。Evidence `<attemptDir>/task-7-esurfing-diagnostics-and-repair.txt`。
  Commit: Y | `feat(cli): add safe diagnostic configuration`

- [x] 8. 提供并验证 Docker 持久化、权限和证据导出流程
  What to do / Must NOT do: 将 README 中的示例落实为仓库内 Dockerfile/入口脚本或等价容器配置，使用 Java 23、host network 和 `/data/diagnostics` bind mount；让 UID/GID、umask 和只写指定根目录可配置。提供诊断运行、停止、`docker cp`/bind mount 导出、manifest 校验、仅 redacted 默认打包、显式 raw 导出后用 7-Zip/GPG 加密的命令。不得自动上传、删除或把密码写入镜像/命令行；如保留环境变量方式，必须清楚标明 `docker inspect` 风险并推荐 secret/file 输入。
  Parallelization: Wave 3 | Blocked by: 3, 7 | Blocks: 11
  References (executor has NO interview context - be exhaustive): `README.md:19-47`; `build.gradle.kts:1-31`; `src/main/resources/log4j.properties:7-11`; `.gitignore:1-24`.
  Acceptance criteria (agent-executable): `docker build` 成功；replay/fixture 模式运行时 evidence 写入宿主机挂载目录；容器重建后文件仍在；普通导出不包含 sensitive；manifest hash 可从宿主机复验；镜像层、`docker inspect` 和 stdout canary scan 不含测试密码。
  QA scenarios (name the exact tool + invocation): happy：`docker build -t esurfing-dialer:diagnostic .` 后以 fixture 模式和 bind mount 运行两次，验证持久化；failure：只读 mount/错误 UID/无显式 sensitive 确认必须输出 typed failure 而非写到容器其他目录。Evidence `<attemptDir>/task-8-esurfing-diagnostics-and-repair.txt`。
  Commit: Y | `build(docker): persist diagnostic evidence safely`

- [x] 9. 加固 CI、产物溯源和 baseline 回滚
  What to do / Must NOT do: 将 CI 改为 Java 23 下执行 `clean check shadowJar --no-daemon`，上传测试报告、JAR、Git SHA/JAR SHA-256 和非敏感构建元数据；明确排除 `sensitive/`、现场 evidence 和真实 fixture。记录 baseline 与 diagnostic/repair 的版本和 hash，提供 Docker/JAR 回滚命令，并保证诊断目录向后兼容、回滚不覆盖证据。不得顺便升级生产依赖大版本。
  Parallelization: Wave 3 | Blocked by: 1, 3 | Blocks: 11, 13
  References (executor has NO interview context - be exhaustive): `.github/workflows/build.yml:1-24`; `build.gradle.kts:1-31`; `README.md:50-54`; `.gitignore:1-24`.
  Acceptance criteria (agent-executable): 本地 `./gradlew clean check shadowJar --no-daemon` 通过且测试非空；workflow 静态/本地等价验证确认任一 check 失败会阻断 artifact；artifact 清单含 commit 与 SHA-256，排除 sensitive；baseline 回滚命令在 fixture 容器中启动/停止成功且旧 evidence hash 不变。
  QA scenarios (name the exact tool + invocation): happy：构建 diagnostic 与 baseline 两个可识别 artifact 并核验 hash；failure：注入失败测试和 sensitive canary，CI 等价检查必须分别阻断发布和 artifact 收集。Evidence `<attemptDir>/task-9-esurfing-diagnostics-and-repair.txt`。
  Commit: Y | `ci: verify tests provenance and rollback artifacts`

- [x] 10. 运行完整离线故障矩阵和秘密泄露回归【范围调整：按用户指示停止秘密扫描/脱敏专项，仅保留构建+全量测试验证 281/0/0】
  What to do / Must NOT do: 集成 Todo 4-7 的 MockWebServer/fixture 流程，覆盖 DNS/连接/读写超时、redirect、HTTP 状态、portal 结构、SMS、ZSM、未知算法、密文格式、XML 业务字段、状态清理、心跳和 term；验证每个输入仅落入预期 stage/outcome。对 console、log、redacted evidence、test report、JAR 资源和拟上传 CI artifact 做 canary secret 扫描。不得把 `sensitive/` 当作普通测试输出，也不得以 grep 日志文案替代行为断言。
  Parallelization: Wave 3 | Blocked by: 4-7 | Blocks: 11
  References (executor has NO interview context - be exhaustive): Todo 4-7 的全部实现与测试；`src/main/kotlin/com/rsplwe/esurfing/Client.kt`; `src/main/kotlin/com/rsplwe/esurfing/network/NetClient.kt`; `src/main/kotlin/com/rsplwe/esurfing/utils/NetworkConnectivity.kt`; `src/main/kotlin/com/rsplwe/esurfing/Session.kt`.
  Acceptance criteria (agent-executable): `./gradlew clean check shadowJar --no-daemon` 全通过；故障矩阵每类至少有一项明确断言；同一 replay 重复运行结果稳定；秘密扫描零命中；无未关闭 response、未分类异常或非法成功事件。
  QA scenarios (name the exact tool + invocation): happy：执行完整正常认证 replay，得到 transport/protocol/business success 和连续 heartbeat；failure：执行全部 malformed/non-2xx/unknown-algo fixture，均返回对应 failure 且无崩溃。Evidence `<attemptDir>/task-10-esurfing-diagnostics-and-repair.txt`。
  Commit: Y | `test: cover end-to-end diagnostic failure matrix`

- [~] 11. 构建诊断版本并完成第一次真实校园网取证【BLOCKED：需要用户在真实校园网现场执行 + 授权账号 + 用户明确指示严禁自动触发；实测指南与前置检查清单已输出，等待用户现场取证后回传证据】
  What to do / Must NOT do: 构建带 Git SHA/JAR SHA-256 的 diagnostic artifact，生成一份机器可读现场前置清单：授权账号、真实校园网、host network、时间同步、可写 bind mount、外部加密工具和 sensitive capture 明确同意。使用凭据文件/stdin 启动，记录 runId、版本、配置摘要、网络摘要、每阶段结果和 manifest；保持运行直到失败重现，或完成至少三次心跳后受控停止。由具备现场访问能力的执行环境运行固定命令；若当前 worker 无法访问该网络或前置条件不全，输出 `BLOCKED` 和准确命令/缺失项，等待用户提供加密证据后从本任务继续，不得跳到 Todo 12。
  Parallelization: Wave 4 | Blocked by: 8-10 | Blocks: 12
  References (executor has NO interview context - be exhaustive): Todo 7 的 CLI；Todo 8 的 Docker/导出命令；Todo 9 的 artifact provenance；Todo 10 的成功契约；`.omo/drafts/esurfing-diagnostics-and-repair.md` 的 evidence policy。
  Acceptance criteria (agent-executable): 现场 run 目录含 app/Git/JAR identity、run/attempt IDs、portal 至最终阶段的 redacted JSONL、manifest 和必要 sensitive 原件；manifest 全部 hash 复验；普通输出无秘密；证据由外部 7-Zip/GPG 加密后传递；若未复现，报告观察时长/心跳次数而非声称根因已找到。
  QA scenarios (name the exact tool + invocation): happy：按 Todo 8 文档用 diagnostic image + bind mount 运行并导出，审计三个成功层次；failure：网络不可达、未知 Algo-ID、解密/XML/业务失败时必须产生可归因事件和受控原件，或明确 `BLOCKED`。Evidence `<attemptDir>/task-11-esurfing-diagnostics-and-repair.txt` 加现场 manifest/脱敏副本。
  Commit: N | 现场证据不得提交；仅在必要时提交不含现场数据的 runbook 修正

- [~] 12. 将现场失败固化为回放测试并实施证据支持的最小修复【BLOCKED：依赖 Todo 11 现场失败证据；用户实测回传证据后解除】
  What to do / Must NOT do: 验证 Todo 11 原件 hash，在本地受控位置解析，生成不可逆脱敏 fixture 和失败断言；先证明当前版本对该 fixture 失败。按分类矩阵处理：portal/endpoint/header 变化修改发现/请求契约；HTTP/redirect 修改网络层；XML/JSON/业务字段修改 parser/state；已知算法输出 mismatch 才修改对应实现；未知 Algo-ID 仅在能够从授权官方客户端/库或充分输入输出样本证明算法和 key/IV 时新增映射。每个生产改动记录 evidence reference、排除的替代假设、修复前后差异。证据只证明服务端拒绝但无法解释算法时必须 `BLOCKED` 并列出所需官方 APK/so、更多样本或抓取点，不得猜算法。
  Parallelization: Wave 5 | Blocked by: 11 | Blocks: 13
  References (executor has NO interview context - be exhaustive): Todo 11 的受控 manifest；`src/main/kotlin/com/rsplwe/esurfing/network/NetClient.kt`; `src/main/kotlin/com/rsplwe/esurfing/utils/NetworkConnectivity.kt`; `src/main/kotlin/com/rsplwe/esurfing/Session.kt`; `src/main/kotlin/com/rsplwe/esurfing/Client.kt`; `src/main/kotlin/com/rsplwe/esurfing/cipher/`.
  Acceptance criteria (agent-executable): evidence 保存同一测试修复前非零失败和修复后通过；脱敏 fixture 不含 secret 且能稳定复现根因；完整离线矩阵和九算法向量通过；diff 只触及根因所需模块；root-cause note 解释为何该改动匹配现场证据。
  QA scenarios (name the exact tool + invocation): happy：运行新现场 fixture 的定向测试后再执行 `./gradlew clean check shadowJar --no-daemon`；failure：移除/反转修复时定向测试必须重新失败，证明确实锁定根因而非偶然通过。Evidence `<attemptDir>/task-12-esurfing-diagnostics-and-repair.txt`。
  Commit: Y | `fix(protocol): repair evidence-confirmed authentication failure`

- [~] 13. 完成修复版本真实网络验证和可执行回滚演练【BLOCKED：依赖 Todo 9 与 Todo 12（Todo 12 又依赖 Todo 11 现场证据）；用户实测回传证据后解除】
  What to do / Must NOT do: 用与 Todo 11 相同账号、校园网络、容器网络、诊断配置和可比较时间窗口运行 repair artifact；比较 baseline/diagnostic 与 repair 的 app/Git/JAR identity、Algo-ID、阶段结果和 manifest。要求登录三层成功、连续心跳达到门槛、term 完成或明确安全失败、无未分类异常/秘密泄露。随后切回 baseline artifact，验证可启动/停止、诊断 evidence 不被覆盖，再恢复 repair artifact 并复验。现场条件不一致必须记录差异，不能伪装为 A/B 证明。
  Parallelization: Wave 6 | Blocked by: 9, 12 | Blocks: 14
  References (executor has NO interview context - be exhaustive): Todo 9 的版本/回滚命令；Todo 11 的 baseline field manifest；Todo 12 的 root-cause fixture；Todo 8 的 Docker 持久化配置。
  Acceptance criteria (agent-executable): repair run manifest 证明 portal、session、ticket、login、heartbeat 和 term 契约；心跳至少 `max(3, 2 * interval)`；baseline→repair 对比显示原失败阶段已转为成功且其他阶段无回归；rollback/redeploy 后旧 evidence hash 不变；无法满足前置条件则 `BLOCKED`。
  QA scenarios (name the exact tool + invocation): happy：按固定 Docker 命令运行 repair、rollback baseline、再部署 repair，并用 manifest auditor 比较；failure：故意部署错误 hash/只读 mount，provenance/写入检查必须阻断验证。Evidence `<attemptDir>/task-13-esurfing-diagnostics-and-repair.txt` 加三次 run manifest。
  Commit: N | 现场 evidence 不提交；必要的非敏感部署修正另行原子提交

- [~] 14. 完成运维文档、根因报告和安全交付清单【BLOCKED：依赖 Todo 13（级联依赖 Todo 11 现场证据）；用户实测回传证据后解除】
  What to do / Must NOT do: 更新 README/诊断文档，覆盖安全凭据输入、Docker 构建运行、diagnostic mode、目录布局、权限、默认 redacted 导出、raw 显式导出、7-Zip/GPG 外部加密、hash 校验、保留/人工销毁责任、字段解释、常见 failure code、两阶段排查和回滚。生成根因报告，引用 Todo 11/12/13 的 runId 与 hash，但不嵌入原始秘密；明确已修复根因、排除项、残余风险和新协议再次变化时的采集步骤。
  Parallelization: Wave 6 | Blocked by: 13 | Blocks: F1-F4
  References (executor has NO interview context - be exhaustive): `README.md:1-54`; Todo 3 的 evidence contract；Todo 7 的 CLI；Todo 8 的 Docker/runbook；Todo 9 的 provenance/rollback；Todo 11-13 的脱敏报告。
  Acceptance criteria (agent-executable): 从空临时目录按文档命令完成 fixture/replay Docker 启动、证据导出、hash 校验和 rollback；链接/命令/文件名与实际产物一致；文档和根因报告 secret scan 零命中；所有残余风险和 `BLOCKED` 条件明确。
  QA scenarios (name the exact tool + invocation): happy：由未参与实现的代理严格执行文档中的离线命令并生成 evidence；failure：移除 bind mount、凭据文件或 sensitive 确认，文档流程必须给出正确阻断和恢复步骤。Evidence `<attemptDir>/task-14-esurfing-diagnostics-and-repair.txt`。
  Commit: Y | `docs: publish diagnostic and recovery runbook`

## Final verification wave
> Runs in parallel after ALL todos. ALL must APPROVE. Surface results and wait for the user's explicit okay before declaring complete.
- [~] F1. Plan compliance audit【BLOCKED：Todo 11-14 均 BLOCKED，存在 BLOCKED 即 FAIL；等待用户实测回传证据】。Evidence `<attemptDir>/final-f1-plan-compliance.md`。
- [~] F2. Code quality and security audit【按用户策略调整：秘密扫描/保密工程已叫停；quality 基线已由 ./gradlew clean check shadowJar 281/0/0 覆盖；等待用户实测后再定】。Evidence `<attemptDir>/final-f2-quality-security.md`。
- [~] F3. Real-network evidence audit【BLOCKED：Todo 11/13 未执行（需真实校园网现场证据），无法审计运行身份/复现/心跳达标】。Evidence `<attemptDir>/final-f3-field-evidence.md`。
- [~] F4. Scope fidelity audit【按用户策略调整：保密交付清单已简化；等待用户实测后再定】。Evidence `<attemptDir>/final-f4-scope.md`。

## Commit strategy
- 每个 Todo 按其 `Commit` 行形成原子提交；测试与对应实现放在同一提交中，TDD 的修复前失败证据保存在 `.omo/evidence/` 而不是提交失败状态。
- 推荐顺序：测试基础 → 诊断模型 → evidence store → 网络 → ZSM/密码 → 协议/状态 → CLI → Docker → CI → 离线矩阵 → 证据确认修复 → 文档。
- Todo 11/13 的现场原件、日志、凭据、加密归档和 manifest 原件绝不提交；只提交不可逆脱敏、经 secret scan 的最小回放 fixture。
- 不重写用户已有提交，不混入无关格式化、IDE 元数据、依赖大版本升级或生成物。

## Success criteria
- 普通运行、控制台、`logs/`、`redacted/`、测试报告、JAR 和 CI artifact 中均不存在密码、SMS、ticket、cookie、ZSM key、静态 key/IV 或完整认证 XML。
- HTTP 非 2xx、门户结构变化、SMS 查询失败、malformed ZSM、未知 Algo-ID、解密失败、XML 缺字段和非法 heartbeat interval 均有不同 typed outcome，并且不会被标记为登录成功。
- diagnostic mode 的 raw evidence 只写入受控 `sensitive/`，具备大小限制、截断标记、SHA-256、run/attempt/correlation ID 和独立 redacted 派生物；默认导出仅包含 redacted。
- `./gradlew clean check shadowJar --no-daemon` 通过，测试实际执行并覆盖九种算法向量、协议回放和失败矩阵；Docker fixture 模式证明 bind mount 持久化和回滚不覆盖 evidence。
- 第一次真实网络运行产生能稳定复现当前失效的证据，Todo 12 的脱敏 fixture 在修复前失败、修复后通过；若证据不足，则最终状态明确为 `BLOCKED` 而不是假修复。
- 第二次真实网络运行在可比较条件下完成 portal/session/ticket/login/heartbeat/term 验证，连续心跳达到既定门槛，没有未分类异常和秘密泄露。
- baseline rollback 与 repair redeploy 均按文档成功，artifact 的 Git SHA/JAR SHA-256 与 manifest 一致，旧 evidence 未被覆盖。
- F1-F4 全部返回 PASS；任何现场门仍为 `BLOCKED` 时不得宣称项目已修复。
