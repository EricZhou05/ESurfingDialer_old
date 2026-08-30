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
