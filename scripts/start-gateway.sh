#!/bin/bash
# 单独拉起网关服务（8088），供并行作业期间快速恢复网关使用。
#
# 为什么需要这个脚本：
#   1. 直接 `nohup java &` 后立刻返回，父 shell 会话被回收时子进程会被一并清理，
#      网关活不过一次工具调用。本脚本在起完进程后继续轮询健康检查（父进程多活一段
#      时间），期间 java 进程被 init 收养，从而能真正常驻。
#   2. 必须先加载 .env —— 网关依赖 ${JWT_SECRET} 等占位符，缺失会在 Bean 初始化阶段
#      抛 "Could not resolve placeholder 'JWT_SECRET'" 直接退出。
#   3. 必须用命令行参数 --server.port 钉死端口 —— 外部环境里形如 SERVER_PORT /
#      SERVER__PORT 的变量会被 Spring 宽松绑定映射到 server.port，把网关悄悄劫持到
#      随机端口（实测被劫持到过 52576），健康检查会永远探测不到。
#      Spring Boot 属性优先级中命令行参数高于 OS 环境变量，故可覆盖。
set -u

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT" || exit 1

PORT="${1:-8088}"
JAR="backend/e-platform-gateway/target/e-platform-gateway-1.0.0.jar"
LOG="logs/e-platform-gateway.log"
PIDFILE="logs/e-platform-gateway.pid"

[ -f "$JAR" ] || { echo "找不到网关 jar: $JAR"; exit 1; }
mkdir -p logs

# 清掉可能残留的旧进程，避免端口冲突
if [ -f "$PIDFILE" ]; then
    OLD=$(cat "$PIDFILE" 2>/dev/null)
    if [ -n "$OLD" ] && kill -0 "$OLD" 2>/dev/null; then
        echo "停止旧网关进程 PID=$OLD"
        kill "$OLD" 2>/dev/null
        sleep 2
    fi
fi

set -a
[ -f .env ] && . ./.env
set +a

: > "$LOG"
nohup java -jar "$JAR" --server.port="$PORT" >> "$LOG" 2>&1 &
NEW_PID=$!
echo "$NEW_PID" > "$PIDFILE"
echo "网关启动中 PID=$NEW_PID port=$PORT"

# 轮询健康检查。--noproxy localhost 是必须的：本机环境注入了 HTTP_PROXY，
# 不排除代理时 curl 会被拦截返回 502 空 body，误判为服务未就绪。
for i in $(seq 1 40); do
    code=$(curl -s --noproxy localhost -o /dev/null -m 2 -w "%{http_code}" \
           "http://localhost:${PORT}/actuator/health" 2>/dev/null)
    if [ "$code" = "200" ]; then
        echo "网关就绪 http://localhost:${PORT}/actuator/health （第 ${i} 次探测）"
        # 再驻留一段时间，确保子进程完成脱离、被 init 收养
        sleep 25
        echo "网关已脱离父进程，可常驻"
        exit 0
    fi
    if ! kill -0 "$NEW_PID" 2>/dev/null; then
        echo "网关进程已退出，最后 30 行日志："
        tail -30 "$LOG"
        exit 1
    fi
    sleep 2
done

echo "网关在 80s 内未通过健康检查，最后 30 行日志："
tail -30 "$LOG"
exit 1
