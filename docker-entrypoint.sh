#!/bin/sh
# ESurfingDialer container entrypoint (Todo 8).
#
# 职责（详见 docker/README.md）：
#   1. 凭据永不进入进程参数（`ps`/`docker inspect` 可见即泄漏）：
#      - 优先沿用调用方已给的 --password-file / --password-stdin / -p/--password；
#      - 否则若 secret 文件 /run/secrets/pw 已挂载，自动追加
#        `--password-file /run/secrets/pw`（推荐路径）；
#      - 否则仅当设了 ESURFING_PASSWORD 时兜底：经 stdin 管道喂给
#        `--password-stdin`（值只存在于 shell 变量与 java 内存，不落 argv），
#        同时打印 `docker inspect` 可见风险告警；
#      - 均无 → 输出 typed 错误(ERROR:NO_PASSWORD)，退出码 1，
#        绝不打印任何密码值。
#   2. -u/--user、--diagnostics-root、--diagnostic-mode、--payload-limit、
#      --retention 等选项原样转发给 `java -jar /app/client.jar`。
#   3. UID/GID/umask 可配置：
#      - umask 默认 0077，可用 ESURFING_UMASK 覆盖；
#      - 以 root 运行时若设 ESURFING_UID/ESURFING_GID，会把 /data/diagnostics
#        改属主并降权到该 UID/GID 执行 java。注意：该降权场景下 SIGTERM 由
#        runuser 派生子进程承载，优雅关闭不保证（见下 4）。
#        推荐替代：直接用 `docker run --user "$(id -u):$(id -g)"`，由 runC 降权，
#        java 仍是容器 PID 1，SIGTERM 直达、优雅关闭与证据 flush 完整。
#   4. 无降权（默认/--user）时以 `exec` 运行 java，java 即容器 PID 1，
#      `docker stop` 的 SIGTERM → JVM shutdown hook → term + evidence flush。
#      凭据若经 stdin（PW_MODE=env）则用非 exec 管道，java 为管道末进程，
#      退出码仍来自 java。
set -u

umask "${ESURFING_UMASK:-0077}" 2>/dev/null || umask 0777

PASSWORD_FLAGS=0
for a in "$@"; do
    case "$a" in
        --password-file | --password-stdin | -p | --password) PASSWORD_FLAGS=1 ;;
    esac
done

HAS_USER=0
prev=""
for a in "$@"; do
    if [ "$prev" = "-u" ] || [ "$prev" = "--user" ]; then HAS_USER=1; fi
    prev="$a"
    case "$a" in
        -u | --user) HAS_USER=1 ;;
    esac
done

# --- 凭据来源决策（绝不打印值）---
PW_MODE=none
if [ "$PASSWORD_FLAGS" -eq 1 ]; then
    PW_MODE=none
elif [ -f /run/secrets/pw ]; then
    PW_MODE=secret-file
elif [ -n "${ESURFING_PASSWORD:-}" ]; then
    PW_MODE=env
else
    PW_MODE=missing
fi

if [ "$PW_MODE" = env ]; then
    echo "WARNING: ESURFING_PASSWORD is visible via 'docker inspect' (Env) and inside the container (/proc/<pid>/environ). Mount a 0600 secret file and use --password-file instead." >&2
fi

if [ "$PW_MODE" = missing ]; then
    echo "ERROR: NO_PASSWORD: no credential source given. Mount a secret file (docker run -v ./pw:/run/secrets/pw:ro) and pass --password-file /run/secrets/pw, pass --password-stdin, or set ESURFING_PASSWORD (risky, see docker inspect warning). The password value is never accepted from the process list." >&2
    exit 1
fi

# --- 登录账号兜底：ESURFING_USER 或旧式 DIALER_USER ---
if [ "$HAS_USER" -eq 0 ] && [ -n "${ESURFING_USER:-${DIALER_USER:-}}" ]; then
    set -- --user "${ESURFING_USER:-$DIALER_USER}" "$@"
fi

# --- 挂载 secret 文件自动追加为密码来源 ---
if [ "$PW_MODE" = secret-file ]; then
    set -- --password-file /run/secrets/pw "$@"
fi

# --- UID/GID 降权（仅当以 root 运行且显式配置，best-effort）---
RUNAS=""
if [ "$(id -u)" = "0" ] && { [ -n "${ESURFING_UID:-}" ] || [ -n "${ESURFING_GID:-}" ]; }; then
    if [ -n "${ESURFING_GID:-}" ]; then
        groupmod -o -g "${ESURFING_GID}" esurfing 2>/dev/null \
            || echo "WARNING: cannot remap group to ESURFING_GID=${ESURFING_GID}" >&2
    fi
    if [ -n "${ESURFING_UID:-}" ]; then
        usermod -o -u "${ESURFING_UID}" esurfing 2>/dev/null \
            || echo "WARNING: cannot remap user to ESURFING_UID=${ESURFING_UID}" >&2
    fi
    if [ "${PW_MODE:-none}" != missing ]; then
        mkdir -p /data/diagnostics 2>/dev/null || true
        chown -R esurfing:esurfing /data/diagnostics 2>/dev/null \
            || echo "WARNING: cannot chown /data/diagnostics to the mapped uid/gid (bind-mount fs may not support it). Prefer 'docker run --user \$(id -u):\$(id -g)'." >&2
    fi
    RUNAS="runuser -u esurfing --"
    echo "WARNING: running under remapped uid/gid via runuser; SIGTERM grace is not forwarded to java. Prefer 'docker run --user \$(id -u):\$(id -g)' so java stays PID 1 and 'docker stop' flushes evidence cleanly." >&2
fi

run_java() {
    mode="$1"
    shift
    if [ "$mode" = env ]; then
        if [ -n "$RUNAS" ]; then
            printf '%s\n' "$ESURFING_PASSWORD" | exec $RUNAS java -jar /app/client.jar --password-stdin "$@"
        else
            printf '%s\n' "$ESURFING_PASSWORD" | exec java -jar /app/client.jar --password-stdin "$@"
        fi
    else
        if [ -n "$RUNAS" ]; then
            exec $RUNAS java -jar /app/client.jar "$@"
        else
            exec java -jar /app/client.jar "$@"
        fi
    fi
}

run_java "$PW_MODE" "$@"