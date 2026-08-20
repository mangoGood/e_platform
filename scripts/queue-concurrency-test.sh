#!/bin/bash
# =============================================================================
# T03b 自验辅助：并发不超卖 / 令牌不超发 验证
#
# 用法: ./scripts/queue-concurrency-test.sh <并发数> [商品ID]
#
# 做三件事：
#   1. 记录压测前库存
#   2. 并发拉起 N 个完整协议客户端（提交 -> 202 轮询 -> 带票重发）
#   3. 全程以 100ms 间隔采样 ZCARD queue:permits:active，取峰值
#
# 判定：
#   - 库存扣减数 == 成功下单数         -> 没有超卖
#   - active ZSet 峰值 <= QUEUE_PERMITS -> 没有超发令牌
# =============================================================================
set -uo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

N="${1:-50}"
PRODUCT_ID="${2:-1}"
OUT_DIR="/tmp/qtest-$(date +%s)"
mkdir -p "$OUT_DIR"

MYSQL="docker exec e-platform-mysql mysql -uroot -proot -N -e"
REDIS="docker exec e-platform-redis redis-cli"

stock_of() {
    $MYSQL "select stock from e_platform.product where id=$PRODUCT_ID;" 2>/dev/null | tr -d '[:space:]'
}

STOCK_BEFORE="$(stock_of)"
$REDIS DEL queue:permits:active queue:waiting > /dev/null

echo "=== 并发不超卖验证 ==="
echo "并发数        : $N"
echo "商品ID        : $PRODUCT_ID"
echo "压测前库存    : $STOCK_BEFORE"
echo "结果目录      : $OUT_DIR"
echo

# --- 后台采样 active ZSet 大小，抓峰值 ---------------------------------------
SAMPLE_FILE="$OUT_DIR/active.samples"
(
    while :; do
        $REDIS ZCARD queue:permits:active 2>/dev/null >> "$SAMPLE_FILE"
        sleep 0.1
    done
) &
SAMPLER_PID=$!
# disown：把采样子进程移出作业表，否则 kill 它时 bash 会往终端打一行
# "Terminated: 15 ( while :; do ... )"，混在测试报告里很干扰阅读。
disown "$SAMPLER_PID" 2>/dev/null || true
trap 'kill $SAMPLER_PID 2>/dev/null || true' EXIT

# --- 并发拉起客户端 -----------------------------------------------------------
# 注意：这里只能 wait 客户端自己的 PID。
# 直接写裸 wait 会连上面那个死循环采样子进程一起等，永远不返回（实测卡了 12 分钟）。
START=$(date +%s)
CLIENT_PIDS=()
for i in $(seq 1 "$N"); do
    MAX_POLL=300 ./scripts/queue-client.sh "$i" "$OUT_DIR" &
    CLIENT_PIDS+=($!)
done
for pid in "${CLIENT_PIDS[@]}"; do
    wait "$pid" 2>/dev/null || true
done
END=$(date +%s)

kill $SAMPLER_PID 2>/dev/null || true

# --- 汇总 ---------------------------------------------------------------------
STOCK_AFTER="$(stock_of)"
count_result() { cat "$OUT_DIR"/*.result 2>/dev/null | grep -c "$1" | tr -d ' '; }
HTTP_OK_COUNT=$(count_result '^OK$')
FULL_COUNT=$(count_result '^QUEUE_FULL$')
TO_COUNT=$(count_result '^TIMEOUT$')
FAIL_COUNT=$(count_result '^FAIL')

# 订单服务把业务失败也包成 HTTP 200 + body.code=500（例如"商品库存不足"），
# 所以「成功下单数」必须看响应体里的 code，只数 HTTP 码会把失败单也算成功，
# 超卖断言就失去意义了。
BIZ_OK_COUNT=$(grep -l '"code":200' "$OUT_DIR"/*.body 2>/dev/null | wc -l | tr -d ' ')
OK_COUNT="$BIZ_OK_COUNT"
PEAK_ACTIVE=$(sort -n "$SAMPLE_FILE" 2>/dev/null | tail -1)
DEDUCTED=$((STOCK_BEFORE - STOCK_AFTER))

echo
echo "=== 结果 ==="
echo "耗时          : $((END - START))s"
echo "HTTP 200 数   : $HTTP_OK_COUNT"
echo "业务成功下单  : $OK_COUNT  (body.code==200)"
echo "队列已满(503) : $FULL_COUNT"
echo "排队超时(408) : $TO_COUNT"
echo "其它失败      : $FAIL_COUNT"
echo "压测后库存    : $STOCK_AFTER"
echo "库存扣减数    : $DEDUCTED"
echo "active 峰值   : $PEAK_ACTIVE  (配置 permits=${QUEUE_PERMITS:-?})"
echo "残留 active   : $($REDIS ZCARD queue:permits:active)"
echo "残留 waiting  : $($REDIS ZCARD queue:waiting)"
echo

if [ "$DEDUCTED" -eq "$OK_COUNT" ]; then
    echo "✅ 无超卖：库存扣减数($DEDUCTED) == 成功下单数($OK_COUNT)"
else
    echo "❌ 超卖/漏扣：库存扣减数($DEDUCTED) != 成功下单数($OK_COUNT)"
fi

if [ -n "${QUEUE_PERMITS:-}" ] && [ "$PEAK_ACTIVE" -le "$QUEUE_PERMITS" ]; then
    echo "✅ 无令牌超发：active 峰值($PEAK_ACTIVE) <= permits($QUEUE_PERMITS)"
fi

echo
echo "各失败样本（前 5 条）："
grep -l '^FAIL' "$OUT_DIR"/*.result 2>/dev/null | head -5 | while read -r f; do
    idx="$(basename "$f" .result)"
    echo "  #$idx $(cat "$f") body=$(head -c 200 "$OUT_DIR/$idx.body" 2>/dev/null)"
done
