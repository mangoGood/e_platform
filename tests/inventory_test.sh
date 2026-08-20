#!/bin/bash
###############################################################################
# 库存链路专项测试（遗留 4）
# 用法：bash tests/inventory_test.sh
#
# ---------------------------------------------------------------------------
# 为什么单开一个脚本，而不是并进 e2e_test.sh
# ---------------------------------------------------------------------------
# e2e_test.sh 的定位是**门禁冒烟**：一条主干路径走通、每个模块摸一下，
# 失败即"系统坏了"。它的价值在于快和稳，不在于深。
#
# 库存链路要验的是**状态迁移的副作用**：每条用例都得"读基线 → 触发 → 读快照 →
# 比对 → 复原"，天然是有状态、要 setup/teardown、要造边界数据的。把这类用例塞进
# e2e 会带来三个问题：
#   1) e2e 从"5 秒摸一遍"变成"几十秒跑状态机"，门禁失去快速反馈的意义；
#   2) 库存用例失败时，e2e 的红无法区分是"系统挂了"还是"库存语义变了"，
#      定位成本上升；
#   3) 库存用例需要反复扣/还同一个商品，与 e2e 自己的库存基线互相干扰。
# 所以分家：e2e 管"活着没"，本脚本管"库存账对不对"。
#
# ---------------------------------------------------------------------------
# 覆盖清单
# ---------------------------------------------------------------------------
#   A 组 内部接口隔离的**实质**验证（补 e2e 8.3/8.4 的空断言）
#     A1 外部 deduct 被 403
#     A2 外部 deduct 被拦后库存**未被扣减**   ← e2e 8.3 缺的就是这条
#     A3 外部 restore 被 403
#     A4 外部 restore 被拦后库存**未被增加**   ← e2e 8.4 缺的就是这条
#   B 组 正向库存链路
#     B1 下单成功
#     B2 下单后库存精确扣减 N 件
#     B3 取消订单成功
#     B4 取消后库存精确回滚 N 件（回到基线）
#   C 组 超卖防护（非破坏性构造：查当前库存 N，下单 N+1）
#     C1 下单 N+1 件被拒绝
#     C2 超卖被拒后库存分毫未动
#     C3 超卖被拒后不产生订单（无脏数据）
#
# ---------------------------------------------------------------------------
# 幂等性
# ---------------------------------------------------------------------------
# 本脚本对库存**净影响为零**：
#   - B 组自己用 cancelOrder 把库存还回去（走真实业务接口，这本身就是被测行为）；
#   - C 组按设计根本不该扣库存；
#   - A 组按设计根本不该改库存；
#   - 收尾再做一次"总账核对"（D1），基线不平就红，并用 trap 兜底强制复原。
# 失效边界：kill -9 / 断电时 trap 不执行；若 B3 取消失败，D1 会红并提示手工复原命令。
#
# 环境陷阱与端口说明见 tests/lib_http.sh 文件头。
###############################################################################
set -u

LIB_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=lib_http.sh
. "$LIB_DIR/lib_http.sh"

PRODUCT_ID=1        # iPhone 15 Pro，seller_id=2，种子库存 100
ORDER_QTY=3         # B 组下单数量。取 3（不同于 e2e 的 2）以便日志里一眼区分是谁动的库存

BASELINE=""         # 全脚本的库存基线
PENDING_ROLLBACK=0  # 已扣减但尚未通过业务接口还回的数量，供 trap 兜底

load_internal_token || true

# 兜底回滚：仅在业务路径（取消订单）没能把库存还回去时才动内部接口。
# 正常路径下 PENDING_ROLLBACK 已被 B4 清零，这里空转。
emergency_rollback() {
    if [ "$PENDING_ROLLBACK" -le 0 ]; then
        return 0
    fi
    restore_stock_direct "$PRODUCT_ID" "$PENDING_ROLLBACK" && PENDING_ROLLBACK=0
}
trap 'emergency_rollback >/dev/null 2>&1 || true' EXIT INT TERM

echo "=========================================="
echo "  库存链路专项测试"
echo "=========================================="
echo ""

echo -e "${BLUE}--- 预检 ---${NC}"
preflight

BASELINE="$(get_stock "$PRODUCT_ID")"
if [ -z "$BASELINE" ]; then
    echo -e "${RED}[ABORT]${NC} 读不到商品 $PRODUCT_ID 的库存，后续断言全部无意义，直接中止。"
    exit 2
fi
echo "       [基线] 商品 $PRODUCT_ID 库存 = $BASELINE"

# 准备买家与地址
BUYER_INFO="$(new_buyer inv)"
BUYER_TOKEN="$(printf '%s' "$BUYER_INFO" | cut -d' ' -f1)"
if [ -z "$BUYER_TOKEN" ]; then
    echo -e "${RED}[ABORT]${NC} 买家账号准备失败，无法继续。"
    exit 2
fi
BUYER_AUTH="Authorization: Bearer $BUYER_TOKEN"
ADDR_ID="$(create_address "$BUYER_TOKEN" "库存测试")"
if [ -z "$ADDR_ID" ]; then
    echo -e "${RED}[ABORT]${NC} 收货地址准备失败，无法继续。"
    exit 2
fi
echo "       [就绪] 买家 token / addressId=$ADDR_ID"
echo ""

###############################################################################
# A 组：内部接口隔离的实质验证
###############################################################################
echo -e "${BLUE}--- A. 内部接口隔离（403 + 库存未动）---${NC}"

# 【这组用例存在的理由】
# e2e 8.3/8.4 只断言"外部打 deduct/restore 会拿到 403"，**没有验证库存真的没被动**。
# 这是个真实的盲区：网关的 INTERNAL_DENY 判定位于 ProxyService 管线第 1 步，
# 当前是"先拦截、后转发"，所以库存确实不会动。但这个顺序是实现细节 ——
# 哪天有人把鉴权挪到转发之后（或加了个先落日志再拦的切面），
# 403 照样返回，库存却已经被扣了，而 e2e 8.3/8.4 全绿。
# 下面 A2/A4 就是补这个洞：不看它怎么拦的，只看**钱有没有少**。

STOCK_BEFORE="$(get_stock "$PRODUCT_ID")"

http_call PUT "$GATEWAY/api/product/$PRODUCT_ID/deduct?quantity=5" -H "$BUYER_AUTH"
assert_error "A1 外部访问内部接口 deduct 返回 403" "403"

STOCK_AFTER="$(get_stock "$PRODUCT_ID")"
assert_eq "A2 deduct 被拦后库存未被扣减" "$STOCK_BEFORE" "$STOCK_AFTER" \
    "若此条红而 A1 绿，说明网关变成了「先转发再拦截」，403 只是遮羞布，库存已被真实扣减"

STOCK_BEFORE="$(get_stock "$PRODUCT_ID")"

http_call PUT "$GATEWAY/api/product/$PRODUCT_ID/restore?quantity=5" -H "$BUYER_AUTH"
assert_error "A3 外部访问内部接口 restore 返回 403" "403"

STOCK_AFTER="$(get_stock "$PRODUCT_ID")"
assert_eq "A4 restore 被拦后库存未被增加" "$STOCK_BEFORE" "$STOCK_AFTER" \
    "restore 方向同样要守：外部若能凭空加库存，等于开了刷库存的后门"

echo ""

###############################################################################
# B 组：正向库存链路（下单扣减 / 取消回滚）
###############################################################################
echo -e "${BLUE}--- B. 正向链路（下单扣减 / 取消回滚）---${NC}"

STOCK_BEFORE="$(get_stock "$PRODUCT_ID")"

http_call POST "$GATEWAY/api/order/create" \
    -H "Content-Type: application/json" \
    -H "$BUYER_AUTH" \
    -d "{\"addressId\":$ADDR_ID,\"items\":[{\"productId\":$PRODUCT_ID,\"quantity\":$ORDER_QTY}]}"
if [ "$RESP_CODE" = "200" ] && [ "$(json_get "$RESP_BODY" success)" = "True" ]; then
    PENDING_ROLLBACK=$ORDER_QTY
fi
assert_ok "B1 下单成功"
ORDER_ID="$(json_get "$RESP_BODY" data.0.id)"

STOCK_AFTER="$(get_stock "$PRODUCT_ID")"
assert_eq "B2 下单后库存精确扣减 ${ORDER_QTY} 件" "$((STOCK_BEFORE - ORDER_QTY))" "$STOCK_AFTER" \
    "扣多了=多扣钱，扣少了=超卖入口；必须精确相等，不能只判「变小了」"

NAME="B3 取消订单成功"
if [ -z "$ORDER_ID" ]; then
    RESP_CODE="-"; RESP_BODY="<未发起请求>"
    fail_case "$NAME" "前置条件失败：B1 未能取得 orderId"
else
    http_call POST "$GATEWAY/api/order/cancel/$ORDER_ID" -H "$BUYER_AUTH"
    if [ "$RESP_CODE" = "200" ] && [ "$(json_get "$RESP_BODY" success)" = "True" ]; then
        # 业务接口已把库存还回去了，撤销 trap 的兜底职责
        PENDING_ROLLBACK=0
    fi
    assert_ok "$NAME"
fi

STOCK_AFTER="$(get_stock "$PRODUCT_ID")"
assert_eq "B4 取消后库存精确回滚至基线" "$STOCK_BEFORE" "$STOCK_AFTER" \
    "cancelOrder 内部对每个 orderItem 调 restoreStock（OrderService:499-502），回滚量必须与扣减量严格相等"

echo ""

###############################################################################
# C 组：超卖防护
###############################################################################
echo -e "${BLUE}--- C. 超卖防护 ---${NC}"

# 【非破坏性构造】不把库存打到 0（那会毁掉环境、也让后续用例无法运行），
# 而是先读当前库存 N，再下单 N+1 件。N+1 必然越界，但因为**预期会被拒绝**，
# 库存实际不会有任何变化，环境保持原样。

STOCK_N="$(get_stock "$PRODUCT_ID")"
OVER_QTY=$((STOCK_N + 1))
echo "       [构造] 当前库存 N=${STOCK_N}，尝试下单 N+1=${OVER_QTY} 件"

# 超卖前先记下订单总数，C3 要用它判断有没有产生脏订单
http_call GET "$GATEWAY/api/order/user" -H "$BUYER_AUTH"
ORDERS_BEFORE="$(json_get "$RESP_BODY" data.total)"

http_call POST "$GATEWAY/api/order/create" \
    -H "Content-Type: application/json" \
    -H "$BUYER_AUTH" \
    -d "{\"addressId\":$ADDR_ID,\"items\":[{\"productId\":$PRODUCT_ID,\"quantity\":$OVER_QTY}]}"

# 【断言口径说明 —— BUG-2 修复后已对齐，勿再改回 soft_fail】
# 上一轮这里断言的是 HTTP 200 + code 500（如实断言当时的现状），并在注释里留了
# "等源码对齐后再把这条改成 assert_error 409"。源码侧现已对齐：
# OrderService:101 改为双参 BusinessException(ErrorCode.CONFLICT, ...)，
# GlobalExceptionHandler 的 HTTP_ALIGNED_CODES 白名单本就含 409，
# 因此 HTTP 与 body.code 现在都应为 409。
# assert_error 会同时校验 HTTP 状态码与 body.code 是否一致，
# 任何一侧回退到 200/500 都会立刻变红。
assert_error "C1 下单 N+1 件被拒绝（库存不足）" 409

STOCK_AFTER="$(get_stock "$PRODUCT_ID")"
assert_eq "C2 超卖被拒后库存分毫未动" "$STOCK_N" "$STOCK_AFTER" \
    "createOrder 是先校验全部商品库存再逐个扣减（OrderService:97-103），校验不过应当一件都不扣"

http_call GET "$GATEWAY/api/order/user" -H "$BUYER_AUTH"
ORDERS_AFTER="$(json_get "$RESP_BODY" data.total)"
assert_eq "C3 超卖被拒后未产生脏订单" "$ORDERS_BEFORE" "$ORDERS_AFTER" \
    "下单失败却留下订单行 = 脏数据；@Transactional 必须把 order/order_item 一起回滚"

echo ""

###############################################################################
# D 组：总账核对
###############################################################################
echo -e "${BLUE}--- D. 总账核对 ---${NC}"

# 本脚本设计上对库存净影响为零。这条是"账本对不对"的最后一道关：
# 只要它绿，本脚本就可以无限重跑。
FINAL_STOCK="$(get_stock "$PRODUCT_ID")"
if [ "$FINAL_STOCK" = "$BASELINE" ]; then
    assert_eq "D1 全脚本库存净影响为零（幂等保障）" "$BASELINE" "$FINAL_STOCK"
else
    echo -e "${RED}[FAIL]${NC} D1 全脚本库存净影响为零（幂等保障）"
    echo "       原因: 期望 ${BASELINE}，实际 ${FINAL_STOCK}（漏了 $((BASELINE - FINAL_STOCK)) 件）"
    echo "       手工复原: curl --noproxy '*' -X PUT '$PRODUCT_DIRECT/product/$PRODUCT_ID/restore?quantity=$((BASELINE - FINAL_STOCK))' -H 'X-Internal-Token: <见 .env>'"
    FAIL=$((FAIL + 1))
fi

summary "库存链路测试结果"
if [ "$FAIL" -gt 0 ]; then
    exit 1
fi
exit 0
