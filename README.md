# ESurfingDialer
[![GitHub License](https://img.shields.io/github/license/Rsplwe/ESurfingDialer?style=flat-square)](https://github.com/Rsplwe/ESurfingDialer/blob/main/LICENSE)

广东电信天翼校园（ZSM验证）登入认证客户端

### 旧版（基于 unidbg）
* [分支](https://github.com/Rsplwe/ESurfingDialer/tree/sim)
* [Release](https://github.com/Rsplwe/ESurfingDialer/releases/tag/v1.8.0)

### 运行环境
* Java 23 及以上
* 内存 ≥ 50M

### 使用
```bash
# 最简单（Docker 兼容）——但密码会暴露在 ps 进程列表中
java -jar client.jar -u <用户名/手机号> -p <密码>

# 推荐：从受限权限文件读取密码（首行）
chmod 600 /path/to/password
java -jar client.jar -u <用户名/手机号> --password-file /path/to/password

# 推荐：从标准输入读取密码
echo -n "<密码>" | java -jar client.jar -u <用户名/手机号> --password-stdin
```

#### 凭据安全输入
- `--password-file <path>`：读取文件首行作为密码。POSIX 上若文件被 group/other 可读会输出 `chmod 0600` 警告（权限检查为 best-effort，Windows/bind mount 不保证）。
- `--password-stdin`：从标准输入读取一行作为密码。
- `-p/--password <密码>`：**保留以保持旧用法/Docker 兼容，但存在残余风险**——密码会出现在进程参数（`ps`）中。使用 `-p` 时启动会输出一次风险提示，日志只记录配置来源（`cli_flag`）而从不记录密码值。
- `ESURFING_PASSWORD` 环境变量：作为最后的兜底来源（残余风险：可通过 `/proc/<pid>/environ` 读取，故只在不得已时使用）。
- 同一命令只能指定**一种**密码来源；同时给出多个来源会以 `CONFLICT_PASSWORD` 拒绝。

#### 配置优先级（default < 环境变量 < 命令行参数）
| 设置 | 命令行 | 环境变量 | 默认值 |
| --- | --- | --- | --- |
| 诊断证据目录 | `--diagnostics-root <path>` | `ESURFING_DIAGNOSTICS_ROOT` | 关闭（不写证据） |
| 诊断模式 | `--diagnostic-mode normal\|sensitive` | `ESURFING_DIAGNOSTIC_MODE` | `normal`（敏感捕获默认关闭，必须显式开启） |
| 单次证据载荷上限 | `--payload-limit <bytes>` | `ESURFING_PAYLOAD_LIMIT` | `131072` |
| 证据保留 | `--retention <maxRuns>:<maxAgeDays>` | `ESURFING_RETENTION` | `30:90` |

- 密码来源优先级：`--password-file` > `--password-stdin` > `-p` > `ESURFING_PASSWORD`；其中 `-p` 与 `--password-file`/`--password-stdin` 同时出现视为冲突。
- 诊断模式为 `sensitive` 时必须显式确认（`--diagnostic-mode sensitive` 或在环境变量中显式设置），启动时会打印非密钥告警；普通模式绝不创建 `sensitive/` 目录。

#### 可靠关闭
- 进程终止（SIGTERM/JVM shutdown hook）会先执行在线注销（term）、无论 term 成败都会把内存中的诊断事件刷入证据仓库并落盘 `manifest.json`，随后将认证状态机置为 `SHUTDOWN`，不再发起任何请求。
- 所有 term/会话关闭失败都会以 typed、非密钥、无内置堆栈的方式记录到日志。

### 详细部署教程
[ESurfingDialerTutorial](https://github.com/EricZhou05/ESurfingDialerTutorial)

### OpenWrt 部署
当以 Docker 运行时，请包含 `--network host` 参数

推荐容器：amazoncorretto:23

![](imgs/01.png)
![](imgs/02.png)

```bash
docker build -t dialer .
docker run -itd -e DIALER_USER=<用户名/手机号> -e DIALER_PASSWORD=<密码> --name dialer-client --network host --restart=always dialer
```

Dockerfile
```dockerfile
FROM amazoncorretto:23
WORKDIR /app
COPY run.sh /app
COPY client.jar /app
CMD ["./run.sh"]
```
run.sh
```bash
#!/bin/sh
java -jar client.jar -u ${DIALER_USER} -p ${DIALER_PASSWORD}
```


### 构建
需要 Java 版本 >= 23
```bash
./gradlew shadowJar
```

---

## 构建与回滚

### 1. 完整构建门（CI 与本地等价）

CI（`.github/workflows/build.yml`）在 Java 23 下执行 `clean check shadowJar`，
任一测试/check 失败即阻断 artifact 上传；上传物（测试报告、JAR、provenance）
在收集前必须通过 canary/sensitive 扫描门。本地等价命令：

```bash
export JAVA_HOME=<你的 JDK 23 路径>
export PATH=$JAVA_HOME/bin:$PATH

# Gate 1：测试必须真实执行（clean 防 UP-TO-DATE 伪装）+ 产出 fat-jar
./gradlew clean check shadowJar --no-daemon --console=plain

# Gate 2：生成非敏感溯源元数据 provenance.json（commit SHA / JAR SHA-256 /
# 依赖指纹 / 构建时间；-e 显式记录排除路径：sensitive/.omo/logs）
bash scripts/ci-provenance.sh \
  -o build/artifacts/provenance.json -j build/libs/*-all.jar \
  -e sensitive -e .omo -e logs

# Gate 3：上传内容安全扫描（命中 CAnary- / sensitive 路径 / 真实 KeyData 十六进
# 制 / *.bin 现场 dump 任一即 exit 1 阻断）
bash scripts/ci-artifact-check.sh \
  build/artifacts/provenance.json build/reports/tests/test \
  build/test-results/test build/libs/*-all.jar
```

`provenance.json` 字段：`commitSha/commitShortSha/gitDescribe`、`jarFile/jarSha256`、
`dependencyFingerprintSha256`、`buildTimestampIso`、`javaVersion/gradleVersion`、
`excludedPaths`。产物可完全溯源：JAR 或部署物随时可与 provenance 对账。

### 2. 版本命名与 baseline 概念

| 名称 | 含义 | tag / 文件 |
| --- | --- | --- |
| `baseline` | 修复前版本（本仓库生产逻辑当前即修复前基线；Git `018952b` 为准） | 镜像 `esurfing-dialer:baseline`；JAR `client.jar`（保留副本 `client.jar.baseline-<短SHA>`） |
| `diagnostic` | 诊断线产物（当前 main，含 Todo 8 容器包装） | 镜像 `esurfing-dialer:diagnostic` |
| `repair` | 未来修复版本（Todo 12 之后） | 建议 tag `esurfing-dialer:repair` |

每次构建记录三样东西：Git SHA（短 12 位）、JAR SHA-256、`provenance.json`。
**回滚 = 把部署物换回 baseline 版本**；回滚前后的版本以 SHA 对账，而不是凭 tag 名。

### 3. 校验 hash 的命令

```bash
# JAR 与 provenance 对账
sha256sum build/libs/ESurfingDialer-*-all.jar
python3 -c "import json;print(json.load(open('build/artifacts/provenance.json'))['jarSha256'])"

# 一条命令复验（provenance 的 jarSha256 与文件一致才算数）
python3 - <<'PY'
import json,sys,os,hashlib
m=json.load(open('build/artifacts/provenance.json'))
h=hashlib.sha256(open(m['jarFile'],'rb').read()).hexdigest()
print('OK  '+m['jarFile']) if h==m['jarSha256'] else (print('MISMATCH '+m['jarFile']),sys.exit(1))
print('commit='+m['commitShortSha'])
PY

# 现场证据（<runId>/manifest.json）的逐文件 SHA-256 复验见 docker/README.md 第 5 节
```

### 4. Docker 回滚（推荐生产路径）

核心原则：**回滚只换镜像/容器，绝不触碰 bind mount 的证据目录**（
`/data/diagnostics` 指向数据盘 sda4 的那部分保持原样）。容器每启动一次都在
证据根新建独立 `<runId>/` 目录，回滚不会覆盖、删除或修改任何旧 evidence。

```bash
# 0) 回滚前先保存当前有效镜像（防 tag 覆盖丢引用）
docker save esurfing-dialer:baseline | gzip > esurfing-dialer-baseline.tar.gz

# 1) 发布新版本时把当前 tag 重新指向（示例：diagnostic 成为新 baseline）
docker tag esurfing-dialer:diagnostic esurfing-dialer:baseline
#    或修复后：docker tag esurfing-dialer:repair esurfing-dialer:baseline

# 2) 回滚 = 用旧镜像重新 run（同一 host 网络 / 同一证据 bind mount / 同一 secret）
docker run -d --name esurfing-dialer --network host \
  -v /mnt/sata1-4/docker/esurfing-dialer/diagnostics:/data/diagnostics \
  -v /mnt/sata1-4/docker/esurfing-dialer/config/pw:/run/secrets/pw:ro \
  esurfing-dialer:baseline -u '<手机号>' \
  --password-file /run/secrets/pw --diagnostics-root /data/diagnostics
docker stop -t 60 esurfing-dialer          # SIGTERM → 优雅 flush → manifest 落盘

# 3) 离线设备：先 docker load 旧 tar 再按第 2 步 run
docker load -i esurfing-dialer-baseline.tar.gz
docker images | grep esurfing-dialer       # 核对 IMAGE ID 与开发机一致
```

回滚后验证：`docker inspect` 的 Env/Cmd 无凭据；`provenance.json` 的
`commitSha/jarSha256` 与容器内 `client.jar` 实际 SHA 一致：

```bash
docker exec esurfing-dialer sh -c 'sha256sum /app/client.jar'
```

### 5. JAR 回滚（无 Docker 的裸 JVM 部署）

```bash
# 保留旧 jar 与当前 jar，替换后重启
cp /app/client.jar /app/client.jar.diagnostic            # 备份当前版本
cp /app/client.jar.baseline-<短SHA> /app/client.jar      # 用保存的旧版覆盖
sha256sum /app/client.jar                                # 与 provenance 对账
# 重启服务（systemd/supervisor/脚本均可），确认进程以新替换的 jar 启动
```

### 6. 回滚不覆盖 evidence（强制契约）

- 证据根（`/data/diagnostics` 的 bind mount）**不随回滚**：不删目录、不重写旧
  `<runId>/`、不清 manifest。回滚前后宿主机侧 `sha256sum -c`（docker/README
  第 5 节清单）对旧 run 逐文件复验一字不差。
- 诊断目录向后兼容：回滚回旧版本应用仍按同一 `<runId>/redacted/` + `manifest.json`
  结构写入，新运行只新增新 runId；旧证据 hash 不变（`RollbackEvidenceEquivalenceTest`
  在本地/CI 锁死该契约：同一挂载目录 baseline→diagnostic→rollback 三轮，
  首轮 evidence 的文件 SHA-256 目录清单逐字节不变）。
- `sensitive/`、现场 evidence、真实 fixture、canary 值绝不进 Git / CI artifact /
  文档；CI 上传前由 `ci-artifact-check.sh` 强制阻断开上传。

### 7. CI artifact 安全门（上传前强制）

`build.yml` 的 3 道门：① `clean check shadowJar` 任一失败即停；② provenance
生成并记录排除路径；③ `ci-artifact-check.sh` 校验上传内容——命中
`sensitive` 路径组件、`CAnary-` 哨兵、未脱敏 `xxx-xxxx-xxxx` 号码、真实
`KeyData` key/IV 十六进制、`*.bin`/`algo_dump_*` 现场 dump 即 exit 1。
`if: failure()` 步骤汇总失败原因；**失败时上传步骤不执行**。
