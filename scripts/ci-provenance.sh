#!/usr/bin/env bash
# ci-provenance.sh — 生成非敏感的构建溯源元数据（CI build.yml 第 2 道门）。
#
# 输出 provenance.json，包含：commit SHA / 短 SHA / git describe、JAR 文件名与
# SHA-256、构建时间、Java/Gradle 版本、基于 build 定义文件的依赖指纹，以及
# 显式排除（-e）的 artifact 路径（sensitive/.omo/logs 等默认排除——CI artifact
# 永不携带这些内容）。字段全部为非敏感元数据：不含密码/SMS/ticket/cookie/密钥。
#
# 用法:
#   ci-provenance.sh -o <out-json> [-j <jar-path>] [-e <排除路径>]...
#
#   默认 out: build/artifacts/provenance.json
#   默认 jar: build/libs/ESurfingDialer-*-all.jar（shadowJar 产物）
#   -e 可重复；默认排除: sensitive .omo logs
#   GITHUB_SHA / GITHUB_REF_NAME 环境变量可选（GitHub Actions 注入）；
#   未提供时回退到 git rev-parse HEAD / 当前分支。
#
# 本脚本不删除任何文件（只写 out-json 与一个同名 .tmp 然后 move 覆盖）。

set -u

SCRIPT_DIR=$(cd "$(dirname "$0")" && pwd)
REPO_ROOT=$(cd "$SCRIPT_DIR/.." && pwd)

OUT_JSON="$REPO_ROOT/build/artifacts/provenance.json"
JAR_GLOB="$REPO_ROOT/build/libs/ESurfingDialer-*-all.jar"
EXCLUDES=()

usage() {
    echo "usage: ci-provenance.sh -o <out-json> [-j <jar>] [-e <excluded-path>]..." >&2
    exit 2
}

while [ $# -gt 0 ]; do
    case "$1" in
        -o) OUT_JSON="${2:?missing -o value}"; shift 2 ;;
        -j | --jar) JAR_GLOB="${2:?missing -j value}"; shift 2 ;;
        -e | --exclude) EXCLUDES+=("${2:?missing -e value}"); shift 2 ;;
        -h | --help) usage ;;
        *) echo "unknown argument: $1" >&2; usage ;;
    esac
done

# 未显式给 -e 时使用默认排除集；显式给出后以给定值为准（不叠加默认，避免重复）。
if [ ${#EXCLUDES[@]} -eq 0 ]; then
    EXCLUDES=(sensitive .omo logs)
fi

# --- 定位 JAR（glob 展开到唯一匹配）---
JAR=""
for candidate in $JAR_GLOB; do
    [ -f "$candidate" ] && JAR="$candidate"
done
if [ -z "$JAR" ]; then
    echo "ERROR: no shadowJar artifact matching: $JAR_GLOB (run './gradlew shadowJar' first)" >&2
    exit 1
fi

# --- Git 身份（环境优先，GitHub Actions 注入；本地回退 git）---
if [ -n "${GITHUB_SHA:-}" ]; then
    COMMIT_SHA="$GITHUB_SHA"
else
    COMMIT_SHA=$(git -C "$REPO_ROOT" rev-parse HEAD 2>/dev/null) || {
        echo "ERROR: no GITHUB_SHA env and not a git repository" >&2
        exit 1
    }
fi
COMMIT_SHORT=${COMMIT_SHA:0:12}
if [ -n "${GITHUB_REF_NAME:-}" ]; then
    BRANCH="$GITHUB_REF_NAME"
else
    BRANCH=$(git -C "$REPO_ROOT" branch --show-current 2>/dev/null || printf "detached")
fi
GIT_DESCRIBE=$(git -C "$REPO_ROOT" describe --tags --always --dirty 2>/dev/null || printf "$COMMIT_SHORT")

# --- 运行时/构建环境 ---
BUILD_TIMESTAMP=$(date -u +%Y-%m-%dT%H:%M:%SZ)
JAVA_VERSION=$(java -version 2>&1 | head -1 | tr -d '"')
GRADLE_VERSION=""
if [ -f "$REPO_ROOT/gradle/wrapper/gradle-wrapper.properties" ]; then
    GRADLE_VERSION=$(sed -n 's/.*distributionUrl=.*gradle-\([0-9.]*\)-.*/\1/p' \
        "$REPO_ROOT/gradle/wrapper/gradle-wrapper.properties")
fi
[ -z "$GRADLE_VERSION" ] && GRADLE_VERSION=$("$REPO_ROOT/gradlew" --version 2>/dev/null | awk '/^Gradle /{print $2}')

# --- JAR 指纹 ---
JAR_BASENAME=$(basename "$JAR")
JAR_SHA256=$(sha256sum "$JAR" | cut -d' ' -f1)
JAR_SIZE_BYTES=$(stat -c %s "$JAR" 2>/dev/null || wc -c < "$JAR")

# --- 依赖/构建定义指纹：对 build 定义文件逐个 sha256 再聚合 ---
DEPS_FILES="gradle/wrapper/gradle-wrapper.properties gradle.properties build.gradle.kts settings.gradle.kts"
DEPS_FINGERPRINT="$(
    : > /tmp/esurfing-deps-fp.$$
    for rel in $DEPS_FILES; do
        f="$REPO_ROOT/$rel"
        [ -f "$f" ] && printf '%s  %s\n' "$(sha256sum "$f" | cut -d' ' -f1)" "$rel" >> /tmp/esurfing-deps-fp.$$
    done
    sha256sum /tmp/esurfing-deps-fp.$$ | cut -d' ' -f1
)"

# --- 输出 provenance.json / xxx.tmp 后原子 move 覆盖 ---
EXCLUDES_JSON=$(printf '%s\n' "${EXCLUDES[@]}" | sed 's/^/"/; s/$/"/' | paste -sd, -)
OUT_DIR=$(dirname "$OUT_JSON")
mkdir -p "$OUT_DIR"

{
    echo "{"
    echo "  \"schema\": \"esurfing-provenance/1\","
    echo "  \"generator\": \"scripts/ci-provenance.sh\","
    echo "  \"commitSha\": \"$COMMIT_SHA\","
    echo "  \"commitShortSha\": \"$COMMIT_SHORT\","
    echo "  \"branch\": \"$BRANCH\","
    echo "  \"gitDescribe\": \"$GIT_DESCRIBE\","
    echo "  \"jarFile\": \"$JAR_BASENAME\","
    echo "  \"jarSha256\": \"$JAR_SHA256\","
    echo "  \"jarSizeBytes\": $JAR_SIZE_BYTES,"
    echo "  \"buildTimestampIso\": \"$BUILD_TIMESTAMP\","
    echo "  \"javaVersion\": \"$JAVA_VERSION\","
    echo "  \"gradleVersion\": \"$GRADLE_VERSION\","
    echo "  \"dependencyFingerprintSha256\": \"$DEPS_FINGERPRINT\","
    echo "  \"excludedPaths\": [$EXCLUDES_JSON]"
    echo "}"
} > "$OUT_JSON.tmp"
mv -f "$OUT_JSON.tmp" "$OUT_JSON"

echo "provenance written: $OUT_JSON"
echo "  commit=$COMMIT_SHORT jar=$JAR_BASENAME sha256=$JAR_SHA256"
exit 0