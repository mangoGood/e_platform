#!/bin/bash

# 电商平台 - 停止所有服务脚本

PROJECT_DIR="$(cd "$(dirname "$0")" && pwd)"
LOG_DIR="$PROJECT_DIR/logs"

echo "========================================="
echo "  电商平台 - 停止所有服务"
echo "========================================="

stop_service() {
    local port=$1
    local name=$2
    local pid=$(lsof -i:$port -t 2>/dev/null)
    if [ -n "$pid" ]; then
        echo "  🛑 停止 $name (PID: $pid, 端口: $port)..."
        kill $pid 2>/dev/null
        # 等待进程退出
        local count=0
        while [ $count -lt 10 ]; do
            if [ -z "$(lsof -i:$port -t 2>/dev/null)" ]; then
                echo "  ✅ $name 已停止"
                return 0
            fi
            sleep 1
            count=$((count + 1))
        done
        # 强制杀死
        echo "  ⚠️  强制停止 $name..."
        kill -9 $pid 2>/dev/null
    else
        echo "  ⏭️  $name 未在运行 (端口: $port)"
    fi
}

# 按启动逆序停止
stop_service 8088 "网关服务"
stop_service 8089 "移动端BFF服务"
stop_service 8087 "订单服务"
stop_service 8086 "商品服务"
stop_service 8085 "用户服务"

echo ""
echo "=== Docker 基础设施 ==="
echo "  Docker 容器仍在运行，如需停止请执行:"
echo "    docker-compose down"
echo ""

echo "========================================="
echo "  所有服务已停止"
echo "========================================="
