# ESurfingDialer Docker 运行手册（诊断证据版）

> 适用镜像：`esurfing-dialer:diagnostic`（Dockerfile + docker-entrypoint.sh 交付）。
> 契约：host 网络必需；诊断证据只写 `/data/diagnostics`；凭据经 secret 文件或 stdin，
> 永不写入镜像层/进程参数。**本手册内的删除/清理命令均为人工执行步骤**——代码与脚本
> 不自动执行任何删除。

---

## 0. 术语与安全模型

| 术语 | 含义 |
| --- | --- |
| 证据根（容器内） | `/data/diagnostics`（`VOLUME` 声明） |
| 证据根（数据盘） | iStoreOS 上 `sda4` 数据盘，如 `/mnt/sata1-4/docker/esurfing-dialer/diagnostics` |
| run（一次诊断） | 容器每次启动在证据根下新建 `<runId>/` 目录，含 `redacted/`、`manifest.json`（sensitive 模式另有 `sensitive/`） |
| redacted tier | 脱敏派生物（普通模式唯一产物；`Redactor.standard()` 已掩码用户名/手机号/IP/MAC/schoolId，移除密码/SMS/ticket/cookie/密钥） |
| sensitive tier | 原始 portal/ZSM/密文/XML 字节，**只有显式 `--diagnostic-mode sensitive` 才会创建** |
| canary | 测试用假凭据（`CAnary-` 前缀，本手册不写具体值），扫到即回归 |

安全底线：

1. 密码/SMS/ticket/cookie/ZSM key/静态 key-IV 绝不出现在镜像层、`CMD`/`ENTRYPOINT` 参数、进程参数、普通日志。
2. 普通（`normal`）模式绝不创建 `sensitive/`；敏感捕获必须显式确认。
3. 证据根必须指向**数据盘**；iStoreOS 的 `sda3`（2GB 系统 overlay）只装系统，不许存证据。
4. 证据导出默认只含 redacted tier；raw 导出后必须立即用 7-Zip/GPG 加密。

---

## 1. 本地构建

前置：Java ≥ 23（本仓库定位 `JAVA_HOME=/home/admin01/jdk/jdk-23.0.2`），Docker daemon 运行中。

```bash
# 1) 产出 fat-jar（build/libs/ESurfingDialer-*-all.jar）
export JAVA_HOME=/home/admin01/jdk/jdk-23.0.2
export PATH=$JAVA_HOME/bin:$PATH
./gradlew shadowJar --no-daemon

# 2) 构建镜像（.dockerignore 已排除 build 内除 libs/*-all.jar 外的产物）
docker build -t esurfing-dialer:diagnostic .
```

验收：`docker images esurfing-dialer:diagnostic` 出现该 tag，记下 `IMAGE ID`。

---

## 2. 诊断运行

### 2.1 通用形态

```text
docker run -d --name <名称> --network host                       # host 网络必需
  -v <宿主机证据目录>:/data/diagnostics                           # 数据盘 bind mount
  -v <宿主机密码文件>:/run/secrets/pw:ro                          # 0600 secret 挂载（推荐）
  esurfing-dialer:diagnostic                                     # 镜像
  -u <账号>                                                      # 以下为 java CLI（原样转发）
  --password-file /run/secrets/pw
  --diagnostics-root /data/diagnostics
  [--diagnostic-mode normal|sensitive] [--payload-limit <bytes>] [--retention <runs:days>]
```

`--password-file`/`--password-stdin`/`-p`/`--password` 只要出现其一，入口脚本就原样转发、
不作任何自动附加；均未出现时，若 `/run/secrets/pw` 已挂载会自动补 `--password-file /run/secrets/pw`
（即上面的命令可省略最后一个 `--password-file`）。仍未给凭据 → 容器打印
`ERROR: NO_PASSWORD` 并以退出码 1 终止（**绝不打印密码值**）。

### 2.2 本地示例（fixture 模式，模拟 iStoreOS sda4 挂载语义）

fixture 模式 = 用 canary 假凭据（`CAnary-` 前缀哨兵值在测试源 `CanarySecrets.kt`，本手册以 `<canary-password>` 占位；绝不连接真实账号）跑通启动 → 停机关闭 → 证据持久化。

```bash
mkdir -p /tmp/opencode/t8-mount1 /tmp/opencode/t8-secrets
printf '%s\n' '<canary-password>' > /tmp/opencode/t8-secrets/pw   # 用 CanarySecrets.CANARY_PASSWORD_DEMO 的值
chmod 600 /tmp/opencode/t8-secrets/pw

docker run -d --name t8-run1 --network host \
  -v /tmp/opencode/t8-mount1:/data/diagnostics \
  -v /tmp/opencode/t8-secrets/pw:/run/secrets/pw:ro \
  esurfing-dialer:diagnostic -u '<fixture-user>' \
  --password-file /run/secrets/pw --diagnostics-root /data/diagnostics

docker stop -t 60 t8-run1        # SIGTERM → shutdown hook → 优雅 flush
ls /tmp/opencode/t8-mount1/*/    # 应有 runDir/redacted/EVENT_JSONL + manifest.json
```

### 2.3 iStoreOS 生产示例（真实模式）

```bash
# 密码文件放数据盘（勿放系统盘 sda3）
mkdir -p /mnt/sata1-4/docker/esurfing-dialer/{diagnostics,backups,config}
printf '%s\n' '<真实密码>' > /mnt/sata1-4/docker/esurfing-dialer/config/pw
chmod 600 /mnt/sata1-4/docker/esurfing-dialer/config/pw

docker run -d --name esurfing-dialer --network host --restart=always \
  -v /mnt/sata1-4/docker/esurfing-dialer/diagnostics:/data/diagnostics \
  -v /mnt/sata1-4/docker/esurfing-dialer/config/pw:/run/secrets/pw:ro \
  esurfing-dialer:diagnostic -u '<手机号>' \
  --password-file /run/secrets/pw --diagnostics-root /data/diagnostics
```

* 真实模式若需捕获门户/ZSM 原始报文（排障时才开），显式：`--diagnostic-mode sensitive`；
  启动会打印非密钥告警，证据根必须保持 0700 并在导出后加密。
* 保留策略示例：`--retention 30:90`（最多 30 个 run，超过 90 天由人工清理——应用不自动删除）。

---

## 3. 停止（优雅关闭）

```bash
docker stop -t 60 <容器名>
```

机制：`docker stop` 发送 `SIGTERM`（Dockerfile `STOPSIGNAL SIGTERM`）→ JVM shutdown hook → 在线注销 term
（失败也吞掉并 typed 记录）→ 无条件 `flushEventsAndFinalize()`（写 `EVENT_JSONL` + 落盘 `manifest.json`）→
状态机转 `SHUTDOWN` 后不再发任何请求。*默认 `docker stop` 超时 10s，认证链路可能 >10s，故建议 `-t 60`。*

若用 `docker run --user`（推荐）或未设置 `ESURFING_UID/GID`，java 即容器 PID 1，SIGTERM 直达、优雅关闭与
证据 flush 完整。若用 `ESURFING_UID/GID`（runuser 降权），SIGTERM 不向 java 转发，证据 flush 可能缺失——
该场景见第 8 节风险标注。

停止后宿主机挂载目录下必然出现：

```text
<证据根>/<runId>/
├── manifest.json      # runId/attemptId 关联、每个文件的 SHA-256、保留策略、残余风险
└── redacted/          # event-*.jsonl（EVENT_JSONL）与其他 *.redacted 派生物
```

---

## 4. 证据导出（两种方式）

### 4.1 docker cp（容器存活/已停止均可）

```bash
RUN_ID=$(docker exec <容器名> sh -c 'ls -1 /data/diagnostics | tail -1')   # 或直接认 runId
docker cp <容器名>:/data/diagnostics/$RUN_ID ./evidence-$RUN_ID
```

### 4.2 bind mount 直读（推荐，iStoreOS 本地）——宿主机上就是普通目录

```bash
RUN_ID=$(ls -1t /mnt/sata1-4/docker/esurfing-dialer/diagnostics | head -1)
cp -r /mnt/sata1-4/docker/esurfing-dialer/diagnostics/$RUN_ID ./evidence-$RUN_ID
```

---

## 5. manifest 校验（宿主机侧重算 SHA-256）

manifest 每行条目带 `sha256`；先在宿主机把它们整理成 `sha256sum` 兼容清单再复验：

```bash
EVID=/path/to/evidence-<runId>
cd "$EVID"
# 从 manifest.json 生成 "<hash>  <相对路径>" 清单（jq 不可用则用 python3）
python3 - <<'PY'
import json
m = json.load(open('manifest.json'))
for e in m['entries']:
    print(f"{e['sha256']}  {e['file']}")
PY | sha256sum -c -
```

期望：`*: OK` 全部通过；任一 mismatch 即证据被篡改或截断，停止使用该 run。

---

## 6. 默认 redacted-only 打包（显式排除 sensitive/）

普通模式根本不存在 `sensitive/`；sensitive 模式导出时必须显式只取 redacted tier：

```bash
cd "$EVID"
tar --exclude='sensitive' \
    -czf "$EVID-redacted.tar.gz" redacted/ manifest.json
sha256sum "$EVID-redacted.tar.gz" > "$EVID-redacted.tar.gz.sha256"
```

打包后自查（无论何种模式）：

```bash
tar -tzf "$EVID-redacted.tar.gz" | grep -c sensitive   # 必须为 0
grep -r 'CAnary-' "$EVID-redacted.tar.gz" >/dev/null && echo LEAK || echo CLEAN
```

---

## 7. 显式 raw 导出后用 7-Zip / GPG 加密

raw 只在显式 sensitive 模式下产生；导出即加密、加密后传输，明文不落地：

```bash
cd /path/to/evidence-<runId>

# 7-Zip AES-256 加密打包（推荐 Windows/无 GPG 环境；-p 后跟口令，会话内自行保管）
7z a -tzip -p -mem=AES256 "$EVID-raw.zip" sensitive/ manifest.json redacted/

# 或 GPG 对称加密（推荐 *nix）
gpg --symmetric --cipher-algo AES256 --output "$EVID-raw.gpg" --armor sensitive/ 

# 校验明文的 hash 存档
sha256sum -c manifest.sha256sum   # 见第 5 节
```

---

## 8. UID / GID / umask 配置

| 配置 | 入口 | 说明 |
| --- | --- | --- |
| `--user "$(id -u):$(id -g)"` | Docker CLI | **推荐**：runC 负责降权，java 保持 PID 1，信号直达、优雅关闭完整。 |
| `ESURFING_UID` / `ESURFING_GID` | 环境变量 | runuser 降权：脚本把 `/data/diagnostics` chown 到该 UID:GID 并以该用户跑 java；**残余风险**：SIGTERM 不向 java 继承转发，`docker stop` 可能不 flush（因此不推荐）。 |
| `ESURFING_UMASK`（默认 `0077`） | 环境变量 | 启动 umask；证据文件另有显式 POSIX 0700/0600（best-effort）。 |

> 非 POSIX 文件系统 / 部分 bind mount（如 `--user` 映射后属主不匹配、或文件系统不支持
> `setPosixFilePermissions`）会记录 `posix_permissions_not_enforced` 残余风险条目到 manifest
> —— 属预期「best-effort + 残余风险」行为，不是错误。据此在 iStoreOS（overlay/数据盘多为
> ext4）通常会拿到完整 0700/0600。

---

## 9. docker inspect 风险提示（凭据可见面）

```bash
docker inspect <容器名>       # .Config.Env 列出全部 ENV —— ESURFING_PASSWORD 明文在此
docker inspect <容器名>       # .Config.Cmd / .Config.Entrypoint / .Args —— 进程参数可见
```

结论：**任何环境变量密码（`ESURFING_PASSWORD`）与进程参数密码（`-p`）都会泄漏给「能运行
`docker inspect`/`ps` 的人」**。这就是入口脚本只推荐两条安全路径的原因：

1. `--password-file /run/secrets/pw`（secret 文件 0600、`:ro` 只读挂载）；
2. `--password-stdin`（容器内 `docker exec` 也不可取到值）。

`ESURFING_PASSWORD` 仅在不得已时兜底：入口脚本会打印 `docker inspect` 可见风险告警，
且经 stdin 管道喂给 `--password-stdin`，值不落入进程 argv。出问题排障时，先认
`manifest.json` 里每 run 的证据，而不是翻环境。

---

## 10. iStoreOS 生产部署指南

### 10.1 磁盘布局警告（重要）

目标设备：**iStoreOS 22.03.7（OpenWRT 底层）**。典型分区（以实际 `lsblk` 为准）：

| 分区 | 大小 | 挂载点 | 角色 | 本应用 |
| --- | --- | --- | --- | --- |
| `sda3` | **2GB** | `/overlay`（Docker 根 `/opt/docker` 在其上） | 系统可写层 | **禁止存放证据/镜像 tar** |
| `sda4` | **12.37GB** | `/mnt/sata1-4` | 数据盘 | ✅ 证据根、镜像 tar、备份全部在这里 |
| `/boot`、`/rom` | — | 只读 | 系统 | 不动 |

> **绝不把任何持久数据写进 `sda3`/`/overlay`**：它只有 2GB，Docker 镜像层本身已占空间，
> 证据/备份/镜像 tar 一旦落上去会很快撑爆 overlay 导致设备起不来。全部 bind mount
> 指向 `/mnt/sata1-4`。

### 10.2 目录规范

```text
/mnt/sata1-4/docker/<app-name>/
├── data/          # 应用运行数据（本应用一般不需要额外 data）
├── logs/          # 容器/应用日志（如需重定向）
├── config/        # 配置与 secret 文件（密码文件 0600）
├── backups/       # 证据归档 tar/加密档案
└── diagnostics/   # 诊断证据根（本应用建议，容器内挂 /data/diagnostics）
```

本应用证据根约定：`/mnt/sata1-4/docker/esurfing-dialer/diagnostics`；镜像 tar 存
`/mnt/sata1-4/docker/images/`；备份走 `backups/`。

### 10.3 离线传输（docker save → scp → docker load）

嵌入式设备通常离线/低带宽，构建在开发机完成、tar 拷贝过去：

```bash
# —— 开发机 ——
docker save esurfing-dialer:diagnostic | gzip > esurfing-dialer-diagnostic.tar.gz
scp esurfing-dialer-diagnostic.tar.gz root@<istoreos-ip>:/mnt/sata1-4/docker/images/

# —— iStoreOS ——
docker load -i /mnt/sata1-4/docker/images/esurfing-dialer-diagnostic.tar.gz
docker images | grep esurfing-dialer        # 确认 IMAGE ID 与开发机一致
```

### 10.4 备份与恢复

**备份 = 备份 sda4 下内容**（系统盘 sda3 坏了不需要管数据；反过来 sda4 才是命脉）：

```bash
# 证据与配置归档（redacted 默认；如需 raw 先按第 7 节加密）
mkdir -p /mnt/sata1-4/docker/esurfing-dialer/backups
tar -C /mnt/sata1-4/docker/esurfing-dialer \
    --exclude='diagnostics/*/sensitive' \
    -czf backups/evidence-$(date +%Y%m%d).tar.gz diagnostics/ config/ | cat

# 定期把 backups/ 拷到另一台机器/U盘（人工执行）
```

**恢复 = 挂载 sda4 重新 run**：系统重装后只须挂上数据盘、`docker load`（或重新 build），
再用第 2.3 节的完整命令 `docker run`。因为证据根/配置都在 sda4，容器重建后：
旧 `<runId>/` 全部仍在、`manifest.json` 的 SHA-256 复验不变（见第 5 节）——**容器重建不覆盖证据**。

### 10.5 自检清单（部署后逐项过）

```bash
lsblk                                            # 确认 sda4 已挂 /mnt/sata1-4
df -h /mnt/sata1-4                               # 数据盘有空间
docker info | grep -i storage                    # Docker 根仍位于 /opt/docker（overlay）
docker ps --filter name=esurfing-dialer          # 容器 Running、host 网络
ls /mnt/sata1-4/docker/esurfing-dialer/diagnostics/<runId>/   # 有 redacted/+manifest.json
```

---

## 附：容器清理（人工执行，脚本不自动删除）

```bash
docker stop <容器名> && docker rm <容器名>
docker rmi esurfing-dialer:diagnostic            # 删除镜像，谨慎
rm -rf /mnt/sata1-4/docker/esurfing-dialer/diagnostics/<旧runId>   # 人工销毁证据
```

证据保留=最多 `--retention` 指定的 run 数（默认 30:90）；超过后由人按第 10.4 节流程归档再人工删，
应用本身不做任何删除。