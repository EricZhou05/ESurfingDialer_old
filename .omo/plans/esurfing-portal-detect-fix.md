# esurfing-portal-detect-fix - Work Plan

## TL;DR
**What this fixes:** 门户检测（`detectConfig()`）在访问 `connect.rom.miui.com/generate_204` 时，被校园网重定向到 `enet.10000.gd.cn:10001/`（根URL），但根URL返回参数错误页面，不包含中国电信门户配置标签，导致 `IllegalStateException`/`PORTAL_CONFIG_TAG_MISSING`，客户端反复进入 `REQUEST_ERROR` 循环。

**Root cause (field-confirmed):** 
- 校园网将 `generate_204` 重定向到 `enet.10000.gd.cn:10001/`（仅根路径，无查询参数）
- 实际门户配置在 `enet.10000.gd.cn:10001/qs/index.jsp?wlanacip=...&wlanuserip=...`（需查询参数）
- 当前代码期望重定向终点直接返回包含门户配置标签的 HTML
- 根URL返回参数错误页面 → 找不到配置标签 → `REQUEST_ERROR` → 5秒重试 → 死循环

**What it will NOT do:** 不改加密算法、不改 ZSM 解析、不改认证协议流程、不改 Docker/CI。

## Verification strategy
- `./gradlew clean check shadowJar --no-daemon` 全通过
- MockWebServer 测试覆盖 enet.10000.gd.cn 重定向场景
- 重新测试现有 PortalDetectTest 全部用例不回归

## Todos

### Wave 1: 诊断现有问题并修复核心逻辑
- [x] 1. 修改 `detectConfig()` 使其能识别重定向到已知中国电信门户域名的场景，即使最终页面不包含配置标签也返回 `REQUIRE_AUTHORIZATION`
  - 在 `catch` 块中检查 `RedirectInterceptor.takeChain()` 的重定向链
  - 如果重定向链的目标是已知门户域名（`enet.10000.gd.cn` 等），返回 `REQUIRE_AUTHORIZATION` 而非 `REQUEST_ERROR`
  - 记录诊断事件 `PROTOCOL.PORTAL_DETECTED_BY_DOMAIN`（新的 `ProtocolErrorCode`）
  - 避免 `IllegalStateException` 导致整个检测失败
  - References: `NetworkConnectivity.kt:56-96`, `Constants.kt:11`
  - Acceptance: 重定向到 `enet.10000.gd.cn:10001/` 且返回参数错误页时，`detectConfig()` 返回 `REQUIRE_AUTHORIZATION`

- [x] 2. 在 `classifyPortalBody()` 中增加门户域名检测回退逻辑
  - 当配置标签缺失时，检查响应是否来自已知门户域名
  - 如果是已知门户域名，记录 `PORTAL_DETECTED_BY_DOMAIN` 事件
  - 返回 `REQUIRE_AUTHORIZATION` 而非 `REQUEST_ERROR`
  - References: `NetworkConnectivity.kt:98-145`
  - Acceptance: `connectivity.portal_detected_by_domain` 事件被记录，`detectConfig()` 返回 `REQUIRE_AUTHORIZATION`

### Wave 2: 增强重定向链信息捕获
- [x] 3. 在 `RedirectInterceptor` 中捕获重定向目标 URL 的 host 信息
  - 当前 `RedirectHop` 只存 `safeLocation`（脱敏后的 URL）
  - 添加 `portalDomain` 字段标识是否为已知门户域名
  - 在 `detectConfig()` 中使用 `redirectChain` 判断是否经过门户重定向
  - References: `HttpExchangeRecord.kt:13-19`, `NetClient.kt:26-102`
  - Acceptance: 重定向到 `enet.10000.gd.cn` 被识别并记录

### Wave 3: 测试覆盖
- [x] 4. 添加 `PortalDetectTest` 新测试用例覆盖 enet.10000.gd.cn 重定向场景
  - MockWebServer 模拟 `generate_204` → 302 → `enet.10000.gd.cn:10001/`（参数错误页面）
  - 验证不抛出 `IllegalStateException`
  - 验证返回 `REQUIRE_AUTHORIZATION`
  - 验证现有测试用例全部继续通过
  - References: `PortalDetectTest.kt:1-163`
  - Acceptance: `./gradlew test --tests '*PortalDetect*' --no-daemon` 全通过

- [x] 5. 全量回归验证
  - `./gradlew clean check shadowJar --no-daemon` 通过
  - 确认 281 个测试全部通过，0 失败，0 跳过
  - Acceptance: 构建产出 `build/libs/ESurfingDialer-*-all.jar`

## Final verification wave
- [x] F1. 代码审查：修复不引入新安全漏洞，不泄露秘密
- [x] F2. 测试审查：PortalDetectTest 覆盖新场景，现有测试不回归
- [x] F3. 构建审查：`./gradlew clean check shadowJar` 通过

## Commit strategy
- Todo 1-2: `fix(portal): detect captive portal by redirect domain when config tags missing`
- Todo 3: `refactor(network): capture portal domain in redirect chain`
- Todo 4: `test(portal): cover enet.10000.gd.cn redirect scenario`