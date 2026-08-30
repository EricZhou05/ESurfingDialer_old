# ESurfingDialer 诊断工具 —— iStoreOS 部署教程

> 目标设备：**iStoreOS**（OpenWRT 底层）。本教程把诊断工具**永久安装**到路由器上。
> 全程只需几分钟；所有命令可直接复制，把 `<路由器IP>` / `<你的账号>` / `<你的密码>` 换成实际值。
>
> 磁盘布局（重要，先看这个）：
>
> | 分区 | 大小 | 挂载点 | 本应用 |
> | --- | --- | --- | --- |
> | `sda3` | 2GB（系统 overlay，Docker 根在其上） | `/overlay` | **禁止存放任何数据/镜像 tar** |
> | `sda4` | 12.37GB（数据盘） | `/mnt/sata1-4` | ✅ 证据、密码文件、镜像 tar 全放这里 |
>
> 一句话：**一切持久数据只写 `/mnt/sata1-4`，绝不碰 `sda3`。**
>
> 凭据原则：账号经环境变量 `ESURFING_USER` 传入；**密码绝不进命令行/环境变量**，
> 只写入一个 0600 权限的密码文件，由容器以只读挂载读取。

---

## 1. 本机：确认镜像已构建

开发机上执行：

```bash
docker images esurfing-dialer:diagnostic
```

输出应类似（IMAGE ID 以你机器为准）：

```text
REPOSITORY             TAG          IMAGE ID       CREATED         SIZE
esurfing-dialer        diagnostic   f15626abebc1   ...             811MB
```

本文以当前版 `f15626abebc1` 为准（按 Todo 9 回滚演练重建的诊断镜像；旧版
`b14a905b07b0` 同样是诊断版，也可部署，流程完全一致）。若还没有镜像，先在
仓库根目录构建：

```bash
export JAVA_HOME=/home/admin01/jdk/jdk-23.0.2
export PATH=$JAVA_HOME/bin:$PATH
./gradlew shadowJar --no-daemon
docker build -t esurfing-dialer:diagnostic .
```

## 2. 本机：导出镜像

```bash
docker save esurfing-dialer:diagnostic | gzip > /tmp/opencode/esurfing-dialer-diagnostic.tar.gz
```

记录大小与校验和（**每次重新导出 sha256 都会变**，以本次输出为准）：

```bash
ls -l /tmp/opencode/esurfing-dialer-diagnostic.tar.gz
sha256sum /tmp/opencode/esurfing-dialer-diagnostic.tar.gz
```

本次实际导出参考值：

- 大小：249,904,807 字节（约 238 MiB）
- sha256：`a910da8195aecaa9d9deadce08b46d96077acf334a735a54a1f0f3933e3f46d3`

## 3. 本机：上传到路由器数据盘

```bash
scp /tmp/opencode/esurfing-dialer-diagnostic.tar.gz root@<路由器IP>:/mnt/sata1-4/docker/images/
```

> 若 `images/` 目录不存在，scp 会失败，先在路由器上 `mkdir -p /mnt/sata1-4/docker/images` 再传。

## 4. 路由器：建目录 + 写密码文件

```bash
mkdir -p /mnt/sata1-4/docker/esurfing-dialer/{diagnostics,config}

# 写密码文件（首行即密码；替换 <你的密码> 为真实值）
printf '%s\n' '<你的密码>' > /mnt/sata1-4/docker/esurfing-dialer/config/pw
chmod 600 /mnt/sata1-4/docker/esurfing-dialer/config/pw
ls -l /mnt/sata1-4/docker/esurfing-dialer/config/pw   # 确认权限为 -rw-------
```

密码文件必须保持 `0600`；`docker compose up` 前若该文件不存在，容器会立即以
`ERROR: NO_PASSWORD` 退出——先建好这里再做第 6 步。

## 5. 路由器：加载镜像

```bash
docker load -i /mnt/sata1-4/docker/images/esurfing-dialer-diagnostic.tar.gz
docker images | grep esurfing-dialer
```

确认 IMAGE ID 与本机一致（应为 `f15626abebc1`）。

## 6. 路由器：部署 compose

先把本仓库的 `docker/docker-compose.yml` 放到路由器（两者任选其一）：

```bash
# 方式 A：从开发机 scp 上去
scp docker/docker-compose.yml root@<路由器IP>:/mnt/sata1-4/docker/esurfing-dialer/

# 方式 B：直接在路由器上创建
vi /mnt/sata1-4/docker/esurfing-dialer/docker-compose.yml
```

该 compose 已内置：`esurfing-dialer:diagnostic` 镜像、`network_mode: host`（iStoreOS 必需）、
`restart: unless-stopped`、证据目录与密码文件挂载（只读）。卷路径全部指向数据盘。

然后在路由器上启动（**先导出账号再 up**）：

```bash
export ESURFING_USER='<你的账号>'
docker compose -f /mnt/sata1-4/docker/esurfing-dialer/docker-compose.yml up -d
```

> 忘记 `export ESURFING_USER` 会启动失败或拿到空账号——`export` 与 `up -d`
> 要在同一终端会话里连续执行。

## 7. 验证

```bash
docker ps                                          # esurfing-dialer 应为 Up（host 网络、重启策略生效）
docker compose -f /mnt/sata1-4/docker/esurfing-dialer/docker-compose.yml logs -f   # 看启动日志，Ctrl-C 退出
ls /mnt/sata1-4/docker/esurfing-dialer/diagnostics/*/      # 每个 <runId>/ 下应有 manifest.json
```

正常证据目录结构：

```text
/mnt/sata1-4/docker/esurfing-dialer/diagnostics/<runId>/
├── manifest.json      # runId 关联、逐文件 SHA-256 清单
└── redacted/          # event-*.jsonl 诊断事件
```

账号/密码只出现在日志里"来源"名称（如 `password source: secret_file`），**值永远不会出现**。

## 8. 停止 / 重启 / 更新 / 卸载

```bash
# 停止（优雅关闭：SIGTERM → flush 证据 → manifest 落盘。时间按需给足）
docker stop -t 60 esurfing-dialer

# 重启（改配置后生效）
docker compose -f /mnt/sata1-4/docker/esurfing-dialer/docker-compose.yml restart

# 更新（新镜像版本：开发机重新构建/导出 → 上传 → 重新加载 → 强制重建容器）
docker save esurfing-dialer:diagnostic | gzip > /tmp/opencode/esurfing-dialer-diagnostic.tar.gz
scp /tmp/opencode/esurfing-dialer-diagnostic.tar.gz root@<路由器IP>:/mnt/sata1-4/docker/images/
docker load -i /mnt/sata1-4/docker/images/esurfing-dialer-diagnostic.tar.gz
docker compose -f /mnt/sata1-4/docker/esurfing-dialer/docker-compose.yml up -d --force-recreate
```

卸载（**以下命令由人工执行**，确认不再需要时再跑）：

```bash
docker compose -f /mnt/sata1-4/docker/esurfing-dialer/docker-compose.yml down     # 停止并删除容器
docker rmi esurfing-dialer:diagnostic                                             # 删除镜像（谨慎）
# 需要清证据/配置时（数据盘上的内容，删除前务必确认已不需要）：
# rm -rf /mnt/sata1-4/docker/esurfing-dialer/diagnostics
# rm -rf /mnt/sata1-4/docker/esurfing-dialer/config
```

## 9. 常见问题

**1）sda3 / overlay 空间告警**

```bash
df -h /overlay
```

`/overlay` 只有 2GB，装系统与 Docker 镜像层已占用大半。证据目录、镜像 tar、
密码文件必须全部落在 `/mnt/sata1-4`（数据盘）。检查是不是有东西误写进了
`/opt/docker` 之外的系统路径（例如把证据目录挂错到了 overlay）。

**2）网络不可达 / 认证连不上**

- 确认容器为 host 网络：`docker inspect esurfing-dialer | grep -i netmode` 应为 `host`；
- 确认 `ESURFING_USER` 正确、密码文件首行为正确密码；
- 校园网场景下需先连上图网络或关闭热点干扰。

**3）`docker stop` 后证据没落盘 / 想优雅停止**

认证链路可能超过 `docker stop` 默认的 10s 超时，一律用 `docker stop -t 60`

```bash
docker stop -t 60 esurfing-dialer
```

评价停止是否完整：停止后 `ls .../diagnostics/*/` 每个 run 目录里 **必须有
`manifest.json`**；缺失则说明没走到优雅 flush。

**4）容器启动即退出 `ERROR: NO_PASSWORD`**

密码文件未创建或未挂载。回第 4 步建好 `.../config/pw`（0600）再 `up -d`。

**5）`docker compose up` 报 `ESURFING_USER` 为空**

同一终端先执行 `export ESURFING_USER='<你的账号>'` 再 up；或把 export 追加到
路由器的 `/etc/profile.d/` 下某个脚本里使其常驻（本教程假定临时 export即可）。

**6）旧运行遗留容器名冲突**

若之前用 `docker run --name esurfing-dialer` 建过同名容器，先停用旧的：

```bash
docker stop -t 60 <旧容器名> && docker rm <旧容器名>
```

再执行第 6 步 `up -d`。