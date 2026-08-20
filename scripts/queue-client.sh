#!/bin/bash
# =============================================================================
# T03b 自验辅助：完整排队协议客户端（提交 -> 202 轮询 -> 带票重发）
#
# 用法: ./scripts/queue-client.sh <序号> <结果目录>
# 输出: <结果目录>/<序号>.result 内容为 OK / QUEUE_FULL / TIMEOUT / FAIL:<http码>
#
# 这就是前端 / Android 需要实现的客户端逻辑，可直接作为对接参考。
# =============================================================================
IDX="$1"
OUT_DIR="$2"
GATEWAY="${GATEWAY:-http://localhost:8088}"
TOKEN_FILE="${TOKEN_FILE:-/tmp/qtok.txt}"
BODY_FILE="${BODY_FILE:-/tmp/order_body.json}"
MAX_POLL="${MAX_POLL:-40}"

JWT=$(cat "$TOKEN_FILE")

submit() {
    # $1 = 排队票据（可为空）
    if [ -n "$1" ]; then
        curl -s -o "$OUT_DIR/$IDX.body" -w '%{http_code}' -X POST "$GATEWAY/api/order/create" \
            -H "Authorization: Bearer $JWT" -H "X-Queue-Token: $1" \
            -H 'Content-Type: application/json' -d @"$BODY_FILE"
    else
        curl -s -o "$OUT_DIR/$IDX.body" -w '%{http_code}' -X POST "$GATEWAY/api/order/create" \
            -H "Authorization: Bearer $JWT" \
            -H 'Content-Type: application/json' -d @"$BODY_FILE"
    fi
}

CODE=$(submit "")

if [ "$CODE" = "200" ]; then
    echo "OK" > "$OUT_DIR/$IDX.result"; exit 0
fi
if [ "$CODE" = "503" ]; then
    echo "QUEUE_FULL" > "$OUT_DIR/$IDX.result"; exit 0
fi
if [ "$CODE" != "202" ]; then
    echo "FAIL:$CODE" > "$OUT_DIR/$IDX.result"; exit 0
fi

# 202：取出票据与建议轮询间隔，进入轮询
QTOKEN=$(sed -n 's/.*"queueToken":"\([^"]*\)".*/\1/p' "$OUT_DIR/$IDX.body")
INTERVAL=$(sed -n 's/.*"pollInterval":\([0-9]*\).*/\1/p' "$OUT_DIR/$IDX.body")
[ -z "$INTERVAL" ] && INTERVAL=2

for _ in $(seq 1 "$MAX_POLL"); do
    sleep "$INTERVAL"
    PCODE=$(curl -s -o "$OUT_DIR/$IDX.poll" -w '%{http_code}' "$GATEWAY/api/queue/status?token=$QTOKEN")
    if [ "$PCODE" = "408" ]; then
        echo "TIMEOUT" > "$OUT_DIR/$IDX.result"; exit 0
    fi
    if [ "$PCODE" = "200" ]; then
        # 已晋升，带票重发原请求
        CODE=$(submit "$QTOKEN")
        if [ "$CODE" = "200" ]; then
            echo "OK" > "$OUT_DIR/$IDX.result"
        else
            echo "FAIL:$CODE" > "$OUT_DIR/$IDX.result"
        fi
        exit 0
    fi
done

echo "TIMEOUT" > "$OUT_DIR/$IDX.result"
