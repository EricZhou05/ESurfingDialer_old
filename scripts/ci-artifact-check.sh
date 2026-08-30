#!/usr/bin/env bash
# ci-artifact-check.sh — 上传前安全门（CI build.yml 第 3 道门）。
#
# 扫描即将作为 GitHub Actions artifact 上传的内容（JAR、测试报告、provenance
# JSON 等），命中以下任一项立即 exit 1 阻断上传（默认不命中则通过）：
#
#   1. 路径含有名为 `sensitive` 的组件（现场敏感 metadata / raw 证据目录）;
#   2. 文件名为 `*.bin` 或 `algo_dump_*`（现场二进制 dump / ZSM 原字节转储）;
#   3. 内容命中 canary 哨兵前缀 `CAnary-`（本仓库测试假凭据的唯一前缀，出现即
#      秘密泄漏回归）;
#   4. 内容命中未脱敏大陆手机号格式 `xxx-xxxx-xxxx`（evidence 只允许屏蔽后形态）;
#   5. 内容命中 `algo_dump_`（dump 文件名出现在任何文本内）;
#   6. 内容命中真实 KeyData key/IV 的十六进制串（从 checked-out 的 KeyData.kt
#      数组字面量现算，脚本不内嵌任何真实密钥值）。
#
# 用法:
#   ci-artifact-check.sh <path-or-dir...>     # 至少一个要上传的路径
#
# 本脚本只读扫描，绝不修改/删除任何文件。
#
# 设计说明（为何扫描通过不等于密钥外泄）:
#   类文件里 byteArrayOf/intArrayOf 的密钥以字节指令操作数存在、不以连续文本
#   存在，因此对最终 shadowJar 的健康扫描不会误报，同时又能拦下“密钥明文以
#   文本形式出现在测试报告 / provenance / 资源文件”的任何泄漏。

set -u

SCRIPT_DIR=$(cd "$(dirname "$0")" && pwd)
REPO_ROOT=$(cd "$SCRIPT_DIR/.." && pwd)

[ $# -ge 1 ] || { echo "ERROR: usage: ci-artifact-check.sh <path-or-dir...>" >&2; exit 2; }

BLOCKED=0
HITS=""

block() { # block <file-or-path> <pattern-name> <detail>
    BLOCKED=1
    HITS="$HITS  - $1  [$2] $3"$'\n'
}

# --- 0) 真实 KeyData key/IV 十六进制（从源码现算；不内嵌任何密钥值）---
KEYDATA_SRC="$REPO_ROOT/src/main/kotlin/com/rsplwe/esurfing/cipher/KeyData.kt"
REAL_KEY_HEX=""
if [ -f "$KEYDATA_SRC" ]; then
    REAL_KEY_HEX=$(
        awk '{
            line=$0;
            if (line ~ /0x[0-9a-fA-F][0-9a-fA-F]/) {
                h="";
                while (1) {
                    if (match(line, /0x[0-9a-fA-F]{8}/)) {
                        h=h substr(line, RSTART+2, 8);
                        line=substr(line, RSTART+RLENGTH);
                    } else if (match(line, /0x[0-9a-fA-F]{2}/)) {
                        h=h substr(line, RSTART+2, 2);
                        line=substr(line, RSTART+RLENGTH);
                    } else break;
                }
                if (length(h) >= 32) print tolower(h);
            }
        }' "$KEYDATA_SRC"
    )
fi

# --- 1) 收集待上传文件 ---
FILES=""
for p in "$@"; do
    if [ -d "$p" ]; then
        FILES="$FILES$(find "$p" -type f -print)"$'\n'
    elif [ -f "$p" ]; then
        FILES="$FILES$p"$'\n'
    else
        echo "WARNING: upload path does not exist (skipped): $p" >&2
    fi
done

# --- 2) 路径级检查 ---
while IFS= read -r f; do
    [ -n "$f" ] || continue
    has_sensitive=0
    in_binary_dir=0
    for comp in $(printf '%s' "$f" | tr '/' ' '); do
        [ "$comp" = "sensitive" ] && has_sensitive=1
        [ "$comp" = "binary" ] && in_binary_dir=1
    done
    if [ "$has_sensitive" -eq 1 ]; then
        block "$f" "sensitive-path" "path component named 'sensitive' must never be uploaded"
    fi
    base=$(basename "$f")
    case "$base" in
        algo_dump_*)
            block "$f" "site-dump-algo" "unknown-algo 'algo_dump_*' dump must never be uploaded" ;;
        *.bin)
            # raw evidence dumps (`*-raw-<n>.bin` under evidence runs) must never
            # be uploaded; Gradle's own `.../binary/results.bin` test-result
            # serialization is NOT a site dump and is uploaded legitimately.
            if [ "$in_binary_dir" -eq 0 ]; then
                block "$f" "site-dump-bin" "raw .bin evidence dump must never be uploaded"
            fi
            ;;
    esac
done <<EOF
$FILES
EOF

# --- 3) 内容级检查（二进制安全：grep -a）---
while IFS= read -r f; do
    [ -n "$f" ] || continue

    if grep -aq "CAnary-" "$f" 2>/dev/null; then
        block "$f" "canary-sentinel" "content contains a 'CAnary-' canary secret sentinel (leak regression)"
    fi
    if grep -aqE "[0-9]{3}-[0-9]{4}-[0-9]{4}" "$f" 2>/dev/null; then
        block "$f" "unmasked-mobile" "content contains an unmasked xxx-xxxx-xxxx phone number (evidence must mask it)"
    fi
    if grep -aq "algo_dump_" "$f" 2>/dev/null; then
        block "$f" "site-dump-marker" "content mentions the site-dump 'algo_dump_' naming"
    fi
    if [ -n "$REAL_KEY_HEX" ]; then
        for h in $REAL_KEY_HEX; do
            if grep -aqi "$h" "$f" 2>/dev/null; then
                block "$f" "real-keydata-hex" "content contains a real KeyData key/IV hex sequence"
            fi
        done
    fi
done <<EOF
$FILES
EOF

if [ "$BLOCKED" -eq 1 ]; then
    echo "BLOCKED: artifact check failed — refusing to upload (any check failure blocks artifact collection)." >&2
    printf '%b' "$HITS" >&2
    exit 1
fi

echo "OK: artifact scan passed — no sensitive/ path, no canary sentinel, no real KeyData hex, no site dump found."
exit 0