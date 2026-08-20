#!/bin/bash
# =============================================================================
# 电商平台 - 停止所有服务脚本
#
# 用法：
#     ./stop.sh                停止 5 个 Java 服务 + Docker 容器（保留数据卷）
#     ./stop.sh --with-data    额外清除数据卷（MySQL/Redis/RabbitMQ 数据全部丢失）
#     ./stop.sh --yes          配合 --with-data 使用，跳过二次确认
#     ./stop.sh --help         查看帮助
#
# 特性：幂等 —— 进程/容器不存在时只打印提示，退出码仍为 0。
# 兼容性：ASCII 直引号；仅使用 bash 3.2 (macOS 自带) 支持的语法。
# =============================================================================

PROJECT_DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$PROJECT_DIR" || exit 1

LOG_DIR="$PROJECT_DIR/logs"

WITH_DATA=0
ASSUME_YES=0

C_RED='\033[31m'
C_GREEN='\033[32m'
C_YELLOW='\033[33m'
C_BOLD='\033[1m'
C_OFF='\033[0m'

usage() {
    cat <<'USAGE'
电商平台停止脚本

用法：
  ./stop.sh [选项]

选项：
  --with-data    停止容器时一并删除数据卷（docker compose down -v），数据不可恢复
  --yes          与 --with-data 搭配，跳过交互确认（用于自动化脚本）
  -h, --help     显示本帮助
USAGE
}

while [ $# -gt 0 ]; do
    case "$1" in
        --with-data)
            WITH_DATA=1
            ;;
        --yes|-y)
            ASSUME_YES=1
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
printf "${C_BOLD}  电商平台 - 停止所有服务${C_OFF}\n"
printf "${C_BOLD}=========================================${C_OFF}\n"

# ---------------------------------------------------------------------------
# 1. 停止 Java 服务
#    优先使用 logs/{module}.pid，回退到按端口 lsof
# ---------------------------------------------------------------------------
printf "\n=== Java 微服务 ===\n"

# 等待指定端口释放，最长 $2 秒；释放返回 0，超时返回 1
wait_port_free() {
    local port="$1"
    local timeout="$2"
    local elapsed=0
    while [ "$elapsed" -lt "$timeout" ]; do
        if [ -z "$(lsof -nP -iTCP:"$port" -sTCP:LISTEN -t 2>/dev/null)" ]; then
            return 0
        fi
        sleep 1
        elapsed=$((elapsed + 1))
    done
    return 1
}

stop_service() {
    local port="$1"
    local name="$2"
    local module="$3"
    local pid_file="$LOG_DIR/${module}.pid"
    local pids=""
    local file_pid=""

    # 来源一：start.sh 写下的 pid 文件
    if [ -f "$pid_file" ]; then
        file_pid=$(cat "$pid_file" 2>/dev/null)
        if [ -n "$file_pid" ] && kill -0 "$file_pid" 2>/dev/null; then
            pids="$file_pid"
        fi
        rm -f "$pid_file"
    fi

    # 来源二：端口反查（覆盖手工启动 / pid 文件丢失的情况）
    local port_pids
    port_pids=$(lsof -nP -iTCP:"$port" -sTCP:LISTEN -t 2>/dev/null)
    if [ -n "$port_pids" ]; then
        pids=$(printf '%s\n%s\n' "$pids" "$port_pids" | sed '/^$/d' | sort -u | tr '\n' ' ')
    fi

    if [ -z "$pids" ]; then
        printf "  [SKIP] %s 未在运行 (端口 %s)\n" "$name" "$port"
        return 0
    fi

    printf "  [STOP] %s (PID:%s 端口:%s) ...\n" "$name" "$(printf '%s' "$pids" | tr -s ' ')" "$port"
    for p in $pids; do
        kill "$p" 2>/dev/null
    done

    if wait_port_free "$port" 10; then
        printf "  ${C_GREEN}[OK]${C_OFF}   %s 已停止\n" "$name"
        return 0
    fi

    printf "  ${C_YELLOW}[WARN]${C_OFF} %s 未在 10s 内退出，强制 kill -9\n" "$name"
    for p in $pids; do
        kill -9 "$p" 2>/dev/null
    done
    if wait_port_free "$port" 5; then
        printf "  ${C_GREEN}[OK]${C_OFF}   %s 已强制停止\n" "$name"
    else
        printf "  ${C_RED}[ERR]${C_OFF}  端口 %s 仍被占用，请手动检查：lsof -nP -iTCP:%s -sTCP:LISTEN\n" "$port" "$port"
    fi
    return 0
}

# 按启动逆序停止
stop_service 8088 "网关服务"    "e-platform-gateway"
stop_service 8089 "移动端BFF"   "e-platform-mobile"
stop_service 8087 "订单服务"    "e-platform-order"
stop_service 8086 "商品服务"    "e-platform-product"
stop_service 8085 "用户服务"    "e-platform-user"

# ---------------------------------------------------------------------------
# 2. 停止 Docker 基础设施
# ---------------------------------------------------------------------------
printf "\n=== Docker 基础设施 ===\n"

if ! command -v docker >/dev/null 2>&1; then
    printf "  [SKIP] 未安装 docker，跳过容器清理\n"
elif ! docker info >/dev/null 2>&1; then
    printf "  [SKIP] Docker 守护进程未运行，无需清理容器\n"
else
    if docker compose version >/dev/null 2>&1; then
        DC="docker compose"
    elif command -v docker-compose >/dev/null 2>&1; then
        DC="docker-compose"
    else
        DC=""
    fi

    if [ -z "$DC" ]; then
        printf "  ${C_YELLOW}[WARN]${C_OFF} 找不到 docker compose 命令，请手动执行 docker rm -f e-platform-mysql e-platform-redis e-platform-rabbitmq\n"
    elif [ ! -f "$PROJECT_DIR/docker-compose.yml" ]; then
        printf "  ${C_YELLOW}[WARN]${C_OFF} 找不到 docker-compose.yml，跳过\n"
    else
        if [ "$WITH_DATA" = "1" ]; then
            CONFIRMED=1
            if [ "$ASSUME_YES" != "1" ]; then
                printf "  ${C_RED}${C_BOLD}警告：--with-data 会删除 MySQL / Redis / RabbitMQ 的全部数据卷，且不可恢复。${C_OFF}\n"
                printf "  确认继续？输入 yes 回车："
                read -r answer
                if [ "$answer" != "yes" ]; then
                    CONFIRMED=0
                fi
            fi

            if [ "$CONFIRMED" = "1" ]; then
                printf "  [STOP] docker compose down -v（删除容器 + 数据卷）...\n"
                if $DC down -v --remove-orphans; then
                    printf "  ${C_GREEN}[OK]${C_OFF}   容器与数据卷已清除\n"
                else
                    printf "  ${C_RED}[ERR]${C_OFF}  docker compose down -v 执行失败，请手动检查\n"
                fi
            else
                printf "  [SKIP] 已取消数据卷删除，改为仅停止容器\n"
                if $DC down --remove-orphans; then
                    printf "  ${C_GREEN}[OK]${C_OFF}   容器已停止（数据卷保留）\n"
                else
                    printf "  ${C_RED}[ERR]${C_OFF}  docker compose down 执行失败，请手动检查\n"
                fi
            fi
        else
            printf "  [STOP] docker compose down（保留数据卷）...\n"
            if $DC down --remove-orphans; then
                printf "  ${C_GREEN}[OK]${C_OFF}   容器已停止，数据卷保留\n"
            else
                printf "  ${C_RED}[ERR]${C_OFF}  docker compose down 执行失败，请手动检查\n"
            fi
        fi
    fi
fi

printf "\n${C_BOLD}=========================================${C_OFF}\n"
if [ "$WITH_DATA" = "1" ]; then
    printf "${C_BOLD}  已停止全部服务（数据卷已按要求处理）${C_OFF}\n"
else
    printf "${C_BOLD}  已停止全部服务，数据保留${C_OFF}\n"
    printf "  如需连数据一起清除： ./stop.sh --with-data\n"
fi
printf "${C_BOLD}=========================================${C_OFF}\n"

exit 0
