# ESurfingDialer container packaging (diagnostic line / Todo 8).
#
# Build:
#   ./gradlew shadowJar
#   docker build -t esurfing-dialer:diagnostic .
#
# Runtime contract (全约定详见 docker/README.md):
#   - 始终使用 host 网络（`--network host`），否则校园网门户不可达；
#   - 诊断证据只写入 /data/diagnostics —— 必须 bind mount 到数据盘（iStoreOS
#     sda4，如 /mnt/sata1-4/docker/esurfing-dialer/diagnostics），严禁落到
#     sda3(2GB) 系统 overlay；
#   - 凭据经挂载 secret 文件（--password-file）或 stdin 输入，密码永不出现在
#     镜像层 / CMD / ENTRYPOINT 参数 / 构建产物中；ESURFING_PASSWORD 仅作
#     兜底且 `docker inspect` 可见（见 docker/README.md 第 9 节）。
FROM amazoncorretto:23

# 非 root 运行用户（UID/GID 可在运行时经 ESURFING_UID/ESURFING_GID 或
# `docker run --user` 配置；见 docker/README.md 第 8 节）。
# AL2023 最小镜像无 shadow-utils/util-linux（useradd/runuser），先补齐。
RUN dnf -y install shadow-utils util-linux >/dev/null && dnf clean all >/dev/null && \
    groupadd --system esurfing && \
    useradd --system --gid esurfing --home-dir /app --no-create-home esurfing

WORKDIR /app

COPY docker-entrypoint.sh /app/docker-entrypoint.sh
COPY build/libs/*-all.jar /app/client.jar

RUN chmod 0755 /app/docker-entrypoint.sh && \
    chmod 0644 /app/client.jar && \
    chown -R esurfing:esurfing /app

# 证据根目录：只应 bind mount 到数据盘（见 docker/README.md 第 10 节 iStoreOS
# 磁盘布局警告）。Dockerfile 不携带缺失文件系统下任何缺省路径。
VOLUME ["/data/diagnostics"]

# 非密钥便捷别名：ESURFING_USER（或旧 README 的 DIALER_USER）只承载登录账号。
ENV ESURFING_USER=""

STOPSIGNAL SIGTERM

ENTRYPOINT ["/app/docker-entrypoint.sh"]
CMD []