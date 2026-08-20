#!/bin/bash
# =============================================================================
# T03b 自验辅助脚本：只重启网关（不碰 user/product/order/mobile）
#
# 用法:  ./scripts/queue-restart-gateway.sh [QUEUE_PERMITS] [QUEUE_MAX_LENGTH] [QUEUE_ENABLED]
# 例:    ./scripts/queue-restart-gateway.sh 1 2 true
#
# 为什么单独写一个脚本而不用 start.sh：
#   start.sh 会重启全部 5 个服务，而 product/order 此刻正被另一位工程师改动，
#   重启它们可能把对方的调试现场冲掉。排队逻辑只在网关内，单独重启网关即可。
# =============================================================================
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

export JAVA_HOME=/Users/finn/Library/Java/JavaVirtualMachines/ms-21.0.9/Contents/Home
export PATH="$JAVA_HOME/bin:$PATH"

# 清除宿主环境里可能存在的 SERVER__PORT / SERVER__HOST：
# Spring Boot 的 relaxed binding 会把它们解析成 server.port / server.host，
# 实测某些 IDE / Agent 宿主会注入 SERVER__PORT=0，导致网关随机端口启动。
unset SERVER__PORT SERVER__HOST SERVER_PORT SERVER_HOST

# 调用方通过环境变量传进来的覆盖值要先存下来：
# 下面 source .env 时 set -a 会把 .env 里的同名变量原样覆盖掉，
# 不先备份的话「命令行临时覆盖」就永远失效（实测踩过一次）。
OVERRIDE_WAIT_TIMEOUT="${QUEUE_WAIT_TIMEOUT:-}"
OVERRIDE_PERMIT_TTL="${QUEUE_PERMIT_TTL:-}"
OVERRIDE_POLL_INTERVAL="${QUEUE_POLL_INTERVAL:-}"

# 载入 .env（JWT_SECRET / GATEWAY_SIGN_SECRET 等）
set -a
# shellcheck disable=SC1091
source "$ROOT_DIR/.env"
set +a

export QUEUE_PERMITS="${1:-50}"
export QUEUE_MAX_LENGTH="${2:-500}"
export QUEUE_ENABLED="${3:-true}"
export QUEUE_WAIT_TIMEOUT="${OVERRIDE_WAIT_TIMEOUT:-${QUEUE_WAIT_TIMEOUT:-60}}"
export QUEUE_PERMIT_TTL="${OVERRIDE_PERMIT_TTL:-${QUEUE_PERMIT_TTL:-30}}"
export QUEUE_POLL_INTERVAL="${OVERRIDE_POLL_INTERVAL:-${QUEUE_POLL_INTERVAL:-2}}"

JAR="$ROOT_DIR/backend/e-platform-gateway/target/e-platform-gateway-1.0.0.jar"
LOG="$ROOT_DIR/logs/e-platform-gateway.log"
mkdir -p "$ROOT_DIR/logs"

# 只杀监听 8088 的进程，避免误伤其它服务
OLD_PID="$(lsof -nP -iTCP:8088 -sTCP:LISTEN -t 2>/dev/null || true)"
if [ -n "$OLD_PID" ]; then
    kill "$OLD_PID" 2>/dev/null || true
    for _ in $(seq 1 30); do
        kill -0 "$OLD_PID" 2>/dev/null || break
        sleep 0.3
    done
    kill -9 "$OLD_PID" 2>/dev/null || true
fi

# --server.port 用命令行参数显式指定：命令行参数在 Spring 的属性源里优先级最高，
# 即便宿主环境又注入了什么端口变量也压不过它。
nohup java -jar "$JAR" --server.port=8088 > "$LOG" 2>&1 &
NEW_PID=$!

for _ in $(seq 1 60); do
    if curl -sf http://localhost:8088/actuator/health > /dev/null 2>&1; then
        echo "网关已就绪 pid=$NEW_PID PERMITS=$QUEUE_PERMITS MAX_LENGTH=$QUEUE_MAX_LENGTH ENABLED=$QUEUE_ENABLED WAIT_TIMEOUT=$QUEUE_WAIT_TIMEOUT POLL_INTERVAL=$QUEUE_POLL_INTERVAL"
        exit 0
    fi
    sleep 1
done

echo "网关启动超时，最后 30 行日志："
tail -30 "$LOG"
exit 1
