#!/bin/bash
# =============================================================================
# 电商平台 - 一键启动脚本
#
# 用法：
#     ./start.sh                  完整启动（基础设施 + 迁移 + 构建 + 5 个服务）
#     ./start.sh --skip-build     跳过前后端构建，直接用现有 jar 启动
#     ./start.sh --skip-frontend  只跳过前端构建
#     ./start.sh --help           查看帮助
#
# 流程：
#     1. source scripts/env.sh（JDK 21 校验）
#     2. 环境检查 java / mvn / node / docker
#     3. .env 处理（缺失则从 .env.example 生成，随机密钥）
#     4. 端口预检
#     5. docker-compose up -d + 轮询等待健康
#     6. 执行 database/migration-v2.sql（幂等）
#     7. 构建前端 + 后端
#     8. 按序启动 user -> product -> order -> mobile -> gateway，逐个健康检查
#     9. 打印访问信息
#
# 兼容性：ASCII 直引号；仅使用 bash 3.2 (macOS 自带) 支持的语法。
# =============================================================================

PROJECT_DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$PROJECT_DIR" || exit 1

LOG_DIR="$PROJECT_DIR/logs"
ENV_FILE="$PROJECT_DIR/.env"
ENV_EXAMPLE="$PROJECT_DIR/.env.example"
MIGRATION_SQL="$PROJECT_DIR/database/migration-v2.sql"

MYSQL_CONTAINER="e-platform-mysql"
REDIS_CONTAINER="e-platform-redis"
RABBITMQ_CONTAINER="e-platform-rabbitmq"

# 基础设施就绪超时（秒）
MYSQL_READY_TIMEOUT=120
REDIS_READY_TIMEOUT=30
RABBITMQ_READY_TIMEOUT=60
# 单个 Java 服务健康检查超时（秒）
SERVICE_READY_TIMEOUT=90

SKIP_BUILD=0
SKIP_FRONTEND=0
CURRENT_STEP="初始化"

# ---------------------------------------------------------------------------
# 输出助手
# ---------------------------------------------------------------------------
C_RED='\033[31m'
C_GREEN='\033[32m'
C_YELLOW='\033[33m'
C_CYAN='\033[36m'
C_BOLD='\033[1m'
C_OFF='\033[0m'

step() {
    CURRENT_STEP="$1"
    printf "\n${C_BOLD}${C_CYAN}==> [%s] %s${C_OFF}\n" "$2" "$1"
}

ok() {
    printf "    ${C_GREEN}[OK]${C_OFF} %s\n" "$1"
}

info() {
    printf "    %s\n" "$1"
}

warn() {
    printf "    ${C_YELLOW}[WARN]${C_OFF} %s\n" "$1"
}

die() {
    printf "\n${C_RED}${C_BOLD}[FAILED]${C_OFF} ${C_RED}步骤「%s」失败：%s${C_OFF}\n" "$CURRENT_STEP" "$1" >&2
    printf "${C_RED}         日志目录：%s${C_OFF}\n" "$LOG_DIR" >&2
    printf "${C_RED}         清理命令：%s/stop.sh${C_OFF}\n\n" "$PROJECT_DIR" >&2
    exit 1
}

usage() {
    cat <<'USAGE'
电商平台一键启动脚本

用法：
  ./start.sh [选项]

选项：
  --skip-build      跳过前端和后端构建，直接使用现有 jar 启动
  --skip-frontend   仅跳过前端构建（后端仍会重新打包）
  -h, --help        显示本帮助

环境变量：
  JAVA_HOME_21      指定 JDK 21 路径（默认使用 scripts/env.sh 内置路径）
  MAVEN_BIN         指定 mvn 可执行文件路径
  QUEUE_PERMITS     抢购排队并发名额，演示排队时可设为 1

示例：
  ./start.sh
  ./start.sh --skip-build
  QUEUE_PERMITS=1 ./start.sh
USAGE
}

# ---------------------------------------------------------------------------
# 参数解析
# ---------------------------------------------------------------------------
while [ $# -gt 0 ]; do
    case "$1" in
        --skip-build)
            SKIP_BUILD=1
            ;;
        --skip-frontend)
            SKIP_FRONTEND=1
            ;;
        -h|--help)
            usage
            exit 0
            ;;
        *)
            printf "${C_RED}未知参数：%s${C_OFF}\n\n" "$1" >&2
            usage
            exit 1
            ;;
    esac
    shift
done

printf "${C_BOLD}=========================================${C_OFF}\n"
printf "${C_BOLD}  电商平台 - 一键启动${C_OFF}\n"
printf "${C_BOLD}=========================================${C_OFF}\n"

mkdir -p "$LOG_DIR" || exit 1

# ===========================================================================
# 步骤 1：加载统一环境（JDK 校验）
# ===========================================================================
step "加载构建环境并校验 JDK" "1/9"

[ -f "$PROJECT_DIR/scripts/env.sh" ] || die "缺少 scripts/env.sh"
# env.sh 校验失败会直接 exit 1
. "$PROJECT_DIR/scripts/env.sh"
ok "JDK ${JAVA_MAJOR} @ ${JAVA_HOME}"
ok "Maven ${MVN_CMD}"

# ===========================================================================
# 步骤 2：环境检查
# ===========================================================================
step "检查依赖工具" "2/9"

MISSING_TOOLS=""

[ -x "$JAVA_BIN" ] || MISSING_TOOLS="${MISSING_TOOLS} java(JDK21)"
[ -x "$MVN_CMD" ] || MISSING_TOOLS="${MISSING_TOOLS} maven"
command -v node >/dev/null 2>&1 || MISSING_TOOLS="${MISSING_TOOLS} node"
command -v npm >/dev/null 2>&1 || MISSING_TOOLS="${MISSING_TOOLS} npm"
command -v docker >/dev/null 2>&1 || MISSING_TOOLS="${MISSING_TOOLS} docker"
command -v curl >/dev/null 2>&1 || MISSING_TOOLS="${MISSING_TOOLS} curl"
command -v lsof >/dev/null 2>&1 || MISSING_TOOLS="${MISSING_TOOLS} lsof"
command -v openssl >/dev/null 2>&1 || MISSING_TOOLS="${MISSING_TOOLS} openssl"

if [ -n "$MISSING_TOOLS" ]; then
    die "以下工具缺失或不可执行：${MISSING_TOOLS}（请先安装后重试）"
fi

# docker compose 命令探测（v2 子命令优先，回退 v1 独立二进制）
if docker compose version >/dev/null 2>&1; then
    DC="docker compose"
elif command -v docker-compose >/dev/null 2>&1; then
    DC="docker-compose"
else
    die "找不到 docker compose（既没有 'docker compose' 子命令，也没有 docker-compose 可执行文件）"
fi

if ! docker info >/dev/null 2>&1; then
    die "Docker 守护进程未运行，请先启动 Docker Desktop"
fi

ok "java / maven / node $(node -v) / npm / docker / curl / openssl 均可用"
ok "compose 命令：${DC}"

# ===========================================================================
# 步骤 3：.env 处理
# ===========================================================================
step "准备 .env 配置" "3/9"

# 在 .env 中设置（或新增）一个键值，幂等
set_env_key() {
    local file="$1"
    local key="$2"
    local value="$3"
    if grep -q "^${key}=" "$file" 2>/dev/null; then
        # 用 | 作分隔符：base64 字符集为 A-Za-z0-9+/= ，不含 | 和 &
        sed "s|^${key}=.*|${key}=${value}|" "$file" > "${file}.tmp" || return 1
        mv "${file}.tmp" "$file" || return 1
    else
        printf '%s=%s\n' "$key" "$value" >> "$file" || return 1
    fi
    return 0
}

# 读取 .env 中某个键的值（不存在则返回空串）
get_env_key() {
    local file="$1"
    local key="$2"
    grep "^${key}=" "$file" 2>/dev/null | head -n 1 | cut -d= -f2-
}

# 判断某个密钥是否需要重新生成：为空、仍是占位符、或长度不足
needs_secret() {
    local value="$1"
    local min_len="$2"
    if [ -z "$value" ]; then
        return 0
    fi
    case "$value" in
        # .env.example 中的占位值，以及历史上硬编码进仓库的弱口令，一律视为需要重新生成
        please-generate*|changeme*|CHANGE_ME*|your-*|ePlatformInternalSecret2026)
            return 0
            ;;
    esac
    if [ "${#value}" -lt "$min_len" ]; then
        return 0
    fi
    return 1
}

ENV_CREATED=0
if [ ! -f "$ENV_FILE" ]; then
    [ -f "$ENV_EXAMPLE" ] || die "既没有 .env 也没有 .env.example，无法生成配置"
    cp "$ENV_EXAMPLE" "$ENV_FILE" || die "从 .env.example 复制生成 .env 失败"
    ENV_CREATED=1
    ok ".env 不存在，已从 .env.example 复制生成"
else
    info ".env 已存在，仅补齐缺失项"
fi

# JWT_SECRET：>= 64 字符（openssl rand -base64 48 产出 64 字符）
if needs_secret "$(get_env_key "$ENV_FILE" JWT_SECRET)" 64; then
    NEW_JWT_SECRET=$(openssl rand -base64 48 | tr -d '\n')
    [ -n "$NEW_JWT_SECRET" ] || die "openssl 生成 JWT_SECRET 失败"
    set_env_key "$ENV_FILE" JWT_SECRET "$NEW_JWT_SECRET" || die "写入 JWT_SECRET 失败"
    printf "    ${C_YELLOW}[GEN]${C_OFF} JWT_SECRET 已随机生成（%s 字符）\n" "${#NEW_JWT_SECRET}"
fi

# GATEWAY_SIGN_SECRET：>= 32 字符（openssl rand -base64 32 产出 44 字符）
if needs_secret "$(get_env_key "$ENV_FILE" GATEWAY_SIGN_SECRET)" 32; then
    NEW_SIGN_SECRET=$(openssl rand -base64 32 | tr -d '\n')
    [ -n "$NEW_SIGN_SECRET" ] || die "openssl 生成 GATEWAY_SIGN_SECRET 失败"
    set_env_key "$ENV_FILE" GATEWAY_SIGN_SECRET "$NEW_SIGN_SECRET" || die "写入 GATEWAY_SIGN_SECRET 失败"
    printf "    ${C_YELLOW}[GEN]${C_OFF} GATEWAY_SIGN_SECRET 已随机生成（%s 字符）\n" "${#NEW_SIGN_SECRET}"
fi

# INTERNAL_TOKEN：>= 24 字符
if needs_secret "$(get_env_key "$ENV_FILE" INTERNAL_TOKEN)" 24; then
    NEW_INTERNAL_TOKEN=$(openssl rand -base64 24 | tr -d '\n')
    [ -n "$NEW_INTERNAL_TOKEN" ] || die "openssl 生成 INTERNAL_TOKEN 失败"
    set_env_key "$ENV_FILE" INTERNAL_TOKEN "$NEW_INTERNAL_TOKEN" || die "写入 INTERNAL_TOKEN 失败"
    printf "    ${C_YELLOW}[GEN]${C_OFF} INTERNAL_TOKEN 已随机生成（%s 字符）\n" "${#NEW_INTERNAL_TOKEN}"
fi

# 载入 .env 到当前环境，后续 java 子进程继承
set -a
# shellcheck disable=SC1090
. "$ENV_FILE" || die "解析 .env 失败，请检查文件格式（KEY=VALUE，不要有空格）"
set +a

[ -n "$JWT_SECRET" ] || die "JWT_SECRET 为空，服务将无法启动"
[ -n "$GATEWAY_SIGN_SECRET" ] || die "GATEWAY_SIGN_SECRET 为空"

DB_USERNAME="${DB_USERNAME:-root}"
DB_PASSWORD="${DB_PASSWORD:-root}"
export DB_USERNAME DB_PASSWORD

ok "环境变量已加载（JWT_SECRET ${#JWT_SECRET} 字符 / GATEWAY_SIGN_SECRET ${#GATEWAY_SIGN_SECRET} 字符）"

# Spring Boot 宽松绑定会把 SERVER_PORT / SERVER__PORT 之类的环境变量映射到 server.port，
# 静默改掉服务端口。本脚本已用命令行参数 --server.port 覆盖，这里只做提示。
for hazard_var in SERVER_PORT SERVER__PORT MANAGEMENT_SERVER_PORT SPRING_PROFILES_ACTIVE; do
    eval "hazard_val=\${$hazard_var}"
    if [ -n "$hazard_val" ]; then
        warn "检测到环境变量 ${hazard_var}=${hazard_val}，可能影响 Spring Boot 配置（端口已由 --server.port 强制覆盖）"
    fi
done
if [ "$ENV_CREATED" = "1" ]; then
    warn "首次生成的密钥保存在 $ENV_FILE ，该文件已被 .gitignore 忽略，请勿提交"
fi

# ===========================================================================
# 步骤 4：端口预检
# ===========================================================================
step "端口占用预检" "4/9"

SERVICE_PORTS="8085 8086 8087 8088 8089"
INFRA_PORTS="3306 6379 5672"

port_holder() {
    lsof -nP -iTCP:"$1" -sTCP:LISTEN 2>/dev/null | tail -n +2 | head -n 1
}

container_running() {
    docker ps --filter "name=^/${1}\$" --filter "status=running" --format '{{.Names}}' 2>/dev/null | grep -q "^${1}\$"
}

PORT_CONFLICT=0
for port in $SERVICE_PORTS; do
    holder=$(port_holder "$port")
    if [ -n "$holder" ]; then
        pname=$(printf '%s' "$holder" | awk '{print $1}')
        ppid=$(printf '%s' "$holder" | awk '{print $2}')
        printf "    ${C_RED}[占用]${C_OFF} 端口 %s 已被 %s (PID %s) 占用\n" "$port" "$pname" "$ppid"
        PORT_CONFLICT=1
    fi
done

if [ "$PORT_CONFLICT" = "1" ]; then
    die "业务端口被占用。请先执行 ./stop.sh 释放端口，或手动结束上述进程后重试"
fi

# 基础设施端口：如果就是本项目的容器在占用，属于正常复用
for port in $INFRA_PORTS; do
    holder=$(port_holder "$port")
    if [ -n "$holder" ]; then
        case "$port" in
            3306) own_container="$MYSQL_CONTAINER" ;;
            6379) own_container="$REDIS_CONTAINER" ;;
            5672) own_container="$RABBITMQ_CONTAINER" ;;
            *)    own_container="" ;;
        esac
        if [ -n "$own_container" ] && container_running "$own_container"; then
            info "端口 ${port} 由本项目容器 ${own_container} 占用（复用）"
        else
            pname=$(printf '%s' "$holder" | awk '{print $1}')
            ppid=$(printf '%s' "$holder" | awk '{print $2}')
            printf "    ${C_RED}[占用]${C_OFF} 基础设施端口 %s 被外部进程 %s (PID %s) 占用\n" "$port" "$pname" "$ppid"
            PORT_CONFLICT=1
        fi
    fi
done

if [ "$PORT_CONFLICT" = "1" ]; then
    die "基础设施端口(3306/6379/5672)被非本项目进程占用，docker-compose 将无法绑定。请释放后重试"
fi

ok "8085-8089 / 3306 / 6379 / 5672 端口检查通过"

# ===========================================================================
# 步骤 5：启动基础设施并等待健康
# ===========================================================================
step "启动 Docker 基础设施 (MySQL / Redis / RabbitMQ)" "5/9"

$DC up -d mysql redis rabbitmq || die "docker compose up 失败"

# 轮询容器健康状态；无 healthcheck 的容器退化为「running 即可」
wait_container_healthy() {
    local name="$1"
    local timeout="$2"
    local elapsed=0
    local status=""
    local health=""

    printf "    等待 %s 就绪（最长 %ss）" "$name" "$timeout"
    while [ "$elapsed" -lt "$timeout" ]; do
        status=$(docker inspect --format '{{.State.Status}}' "$name" 2>/dev/null)
        if [ "$status" = "running" ]; then
            health=$(docker inspect --format '{{if .State.Health}}{{.State.Health.Status}}{{else}}none{{end}}' "$name" 2>/dev/null)
            if [ "$health" = "healthy" ] || [ "$health" = "none" ]; then
                printf " ${C_GREEN}就绪${C_OFF}\n"
                return 0
            fi
        elif [ "$status" = "exited" ] || [ "$status" = "dead" ]; then
            printf "\n"
            docker logs --tail 30 "$name" 2>&1 | sed 's/^/      | /'
            return 1
        fi
        printf "."
        sleep 2
        elapsed=$((elapsed + 2))
    done
    printf "\n"
    docker logs --tail 30 "$name" 2>&1 | sed 's/^/      | /'
    return 1
}

wait_container_healthy "$REDIS_CONTAINER" "$REDIS_READY_TIMEOUT" \
    || die "Redis 容器 ${REDIS_CONTAINER} 在 ${REDIS_READY_TIMEOUT}s 内未就绪"
wait_container_healthy "$MYSQL_CONTAINER" "$MYSQL_READY_TIMEOUT" \
    || die "MySQL 容器 ${MYSQL_CONTAINER} 在 ${MYSQL_READY_TIMEOUT}s 内未就绪"
wait_container_healthy "$RABBITMQ_CONTAINER" "$RABBITMQ_READY_TIMEOUT" \
    || die "RabbitMQ 容器 ${RABBITMQ_CONTAINER} 在 ${RABBITMQ_READY_TIMEOUT}s 内未就绪"

# 二次实探，确认服务端真的可应答（healthcheck 之外的独立验证）
if ! docker exec "$REDIS_CONTAINER" redis-cli ping 2>/dev/null | grep -q PONG; then
    die "Redis 容器已 running 但 redis-cli ping 未返回 PONG"
fi
ok "Redis PONG"

if ! docker exec "$MYSQL_CONTAINER" mysqladmin ping -h 127.0.0.1 -u"$DB_USERNAME" -p"$DB_PASSWORD" >/dev/null 2>&1; then
    die "MySQL 容器已 running 但 mysqladmin ping 失败（账号/密码：${DB_USERNAME}）"
fi
ok "MySQL alive"

# ===========================================================================
# 步骤 6：数据库迁移
# ===========================================================================
step "执行数据库迁移 migration-v2.sql（幂等）" "6/9"

[ -f "$MIGRATION_SQL" ] || die "找不到迁移脚本 ${MIGRATION_SQL}"

MIGRATION_LOG="$LOG_DIR/migration.log"
if docker exec -i "$MYSQL_CONTAINER" mysql -u"$DB_USERNAME" -p"$DB_PASSWORD" \
        < "$MIGRATION_SQL" > "$MIGRATION_LOG" 2>&1; then
    ok "migration-v2.sql 执行成功（日志：logs/migration.log）"
else
    printf "\n"
    tail -n 30 "$MIGRATION_LOG" | sed 's/^/      | /'
    die "数据库迁移失败，完整日志见 ${MIGRATION_LOG}"
fi

# ===========================================================================
# 步骤 7：构建
# ===========================================================================
step "构建前端与后端" "7/9"

if [ "$SKIP_BUILD" = "1" ]; then
    warn "已指定 --skip-build，跳过前后端构建，将使用现有 jar"
else
    # ---- 前端 ----
    if [ "$SKIP_FRONTEND" = "1" ]; then
        warn "已指定 --skip-frontend，跳过前端构建"
    else
        FRONTEND_LOG="$LOG_DIR/build-frontend.log"
        info "构建前端 frontend/ ..."
        if [ ! -d "$PROJECT_DIR/frontend/node_modules" ]; then
            info "首次构建，执行 npm install（可能耗时数分钟）..."
            ( cd "$PROJECT_DIR/frontend" && npm install --no-audit --no-fund ) > "$FRONTEND_LOG" 2>&1 \
                || { tail -n 30 "$FRONTEND_LOG" | sed 's/^/      | /'; die "npm install 失败，日志：${FRONTEND_LOG}"; }
        fi
        ( cd "$PROJECT_DIR/frontend" && npm run build ) >> "$FRONTEND_LOG" 2>&1 \
            || { tail -n 30 "$FRONTEND_LOG" | sed 's/^/      | /'; die "前端构建失败，日志：${FRONTEND_LOG}"; }
        ok "前端构建完成 -> frontend/dist"
    fi

    # ---- 后端 ----
    BACKEND_LOG="$LOG_DIR/build-backend.log"
    info "构建后端 backend/（mvn clean package -DskipTests）..."
    "$MVN_CMD" -f "$PROJECT_DIR/backend/pom.xml" clean package -DskipTests > "$BACKEND_LOG" 2>&1 \
        || { tail -n 40 "$BACKEND_LOG" | sed 's/^/      | /'; die "后端构建失败，日志：${BACKEND_LOG}"; }
    ok "后端构建完成（日志：logs/build-backend.log）"
fi

# ===========================================================================
# 步骤 8：按序启动 5 个服务
# ===========================================================================
step "按序启动微服务" "8/9"

# 定位模块可执行 jar（排除 *-sources / *-javadoc / *.original）
find_service_jar() {
    local module="$1"
    local jar=""
    jar=$(ls -1 "$PROJECT_DIR/backend/${module}/target/${module}"-*.jar 2>/dev/null \
            | grep -v -- '-sources.jar$' \
            | grep -v -- '-javadoc.jar$' \
            | head -n 1)
    printf '%s' "$jar"
}

# 轮询 actuator health 端点
wait_service_healthy() {
    local name="$1"
    local port="$2"
    local timeout="$3"
    local elapsed=0
    local body=""

    printf "    等待 %s 健康检查通过（最长 %ss）" "$name" "$timeout"
    while [ "$elapsed" -lt "$timeout" ]; do
        # 进程若已退出，立即失败，不必等满超时
        if [ -f "$LOG_DIR/${name}.pid" ]; then
            local spid
            spid=$(cat "$LOG_DIR/${name}.pid" 2>/dev/null)
            if [ -n "$spid" ] && ! kill -0 "$spid" 2>/dev/null; then
                printf "\n"
                return 2
            fi
        fi
        body=$(curl -fs --max-time 3 "http://localhost:${port}/actuator/health" 2>/dev/null)
        case "$body" in
            *'"status":"UP"'*)
                printf " ${C_GREEN}UP${C_OFF}\n"
                return 0
                ;;
        esac
        printf "."
        sleep 2
        elapsed=$((elapsed + 2))
    done
    printf "\n"
    return 1
}

start_service() {
    local name="$1"      # e-platform-user
    local label="$2"     # 用户服务
    local port="$3"

    local jar
    jar=$(find_service_jar "$name")
    if [ -z "$jar" ] || [ ! -f "$jar" ]; then
        die "找不到 ${name} 的可执行 jar（backend/${name}/target/）。请去掉 --skip-build 重新构建"
    fi

    info "启动 ${label} (${name}, 端口 ${port})"
    info "  jar : $(basename "$jar")"
    info "  log : logs/${name}.log"

    : > "$LOG_DIR/${name}.log"
    # 显式用命令行参数钉死端口：Spring Boot 的属性优先级中命令行参数高于 OS 环境变量，
    # 可避免外部环境里形如 SERVER_PORT / SERVER__PORT 的变量（宽松绑定会映射到 server.port）
    # 悄悄劫持端口，导致健康检查永远探测不到。
    nohup "$JAVA_BIN" -jar "$jar" --server.port="$port" > "$LOG_DIR/${name}.log" 2>&1 &
    echo $! > "$LOG_DIR/${name}.pid"

    wait_service_healthy "$name" "$port" "$SERVICE_READY_TIMEOUT"
    local rc=$?
    if [ "$rc" = "0" ]; then
        ok "${label} 已就绪 http://localhost:${port}/actuator/health"
        return 0
    fi

    printf "\n${C_RED}    ---- %s 最后 50 行日志 ----${C_OFF}\n" "$name"
    tail -n 50 "$LOG_DIR/${name}.log" | sed 's/^/      | /'
    printf "${C_RED}    -------------------------------${C_OFF}\n"
    if [ "$rc" = "2" ]; then
        die "${label} 进程已退出（启动失败），日志：logs/${name}.log"
    fi
    die "${label} 在 ${SERVICE_READY_TIMEOUT}s 内未通过健康检查，日志：logs/${name}.log"
}

start_service "e-platform-user"    "用户服务"     8085
start_service "e-platform-product" "商品服务"     8086
start_service "e-platform-order"   "订单服务"     8087
start_service "e-platform-mobile"  "移动端BFF"    8089
start_service "e-platform-gateway" "网关服务"     8088

# ===========================================================================
# 步骤 9：完成
# ===========================================================================
step "启动完成" "9/9"

cat <<INFO

=========================================================
  电商平台已启动
=========================================================

  Web 访问        : http://localhost:8088
  网关健康检查    : http://localhost:8088/actuator/health

  服务端口
    用户服务      : 8085   logs/e-platform-user.log
    商品服务      : 8086   logs/e-platform-product.log
    订单服务      : 8087   logs/e-platform-order.log
    移动端 BFF    : 8089   logs/e-platform-mobile.log
    API 网关      : 8088   logs/e-platform-gateway.log

  基础设施
    MySQL         : localhost:3306  (库 e_platform, root/root)
    Redis         : localhost:6379
    RabbitMQ      : localhost:5672  控制台 http://localhost:15672 (guest/guest)

  测试账号（密码均为 123456）
    buyer1        : 买家
    seller1       : 卖家
    admin         : 管理员

  停止服务
    ./stop.sh              停止服务与容器，保留数据
    ./stop.sh --with-data  停止并清除数据卷

=========================================================
INFO
