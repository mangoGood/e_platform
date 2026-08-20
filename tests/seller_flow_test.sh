#!/bin/bash
###############################################################################
# 卖家侧全链路测试（遗留 3）
# 用法：bash tests/seller_flow_test.sh
#
# ---------------------------------------------------------------------------
# 为什么单开一个脚本，而不是并进 e2e_test.sh
# ---------------------------------------------------------------------------
# 理由与 inventory_test.sh 同源，外加一条本脚本独有的：
# 卖家链路需要**两个身份同时在场**（买家下单 + 卖家发货 + 买家确认），
# e2e 全程只持有一个买家 token，硬塞进去会让它的上下文变得混乱，
# 而"越权"这类断言恰恰要求身份边界清晰可读。
#
# ---------------------------------------------------------------------------
# 身份来源（已核实）
# ---------------------------------------------------------------------------
#   database/init.sql:171-173 种子用户：
#       buyer1  / user_type=1 / id=1
#       seller1 / user_type=2 / id=2
#   两者共用同一个 bcrypt hash，实测明文口令为 **123456**。
#   database/init.sql:175 起所有 9 个商品的 seller_id 均为 2，
#   因此**商品 1 的卖家就是 seller1**，用它发货即可。
#
#   本脚本的买家**不复用 buyer1**，而是每轮新注册一个：
#   buyer1 身上挂着历史订单，用它会让"卖家订单列表里能看到本单"这类断言
#   受历史数据分页影响而不稳定。新买家 = 干净上下文。
#
# ---------------------------------------------------------------------------
# 端点关系认定：ship/confirm  vs  deliver/receive
# ---------------------------------------------------------------------------
# 结论：**同一实现的两套 URL 别名，不是重复实现，也不是语义不同。**
# 证据链：
#   1) 静态：OrderService:230-232  deliverOrder(id,sellerId){ shipOrder(id,sellerId); }
#            OrderService:240-242  receiveOrder(id,userId) { confirmOrder(id,userId); }
#      —— 纯转调，方法体内没有任何独立逻辑。
#   2) 动态：实测两组端点在同一状态下返回**逐字相同**的状态码与文案
#      （例如已发货订单再发货，两组都是 409「当前订单状态为「待收货」，不能发货」）。
#
# 因此本脚本以 **/{orderId}/ship 与 /{orderId}/confirm 为主测对象**
# （它们带完整 Javadoc 状态机契约，是新版正式端点），
# 对 deliver/receive 只做**别名等价性抽查**（S9/S10）：
# 用一次越权 403 证明别名走的是同一套归属校验，而不是把整组用例复制一遍。
# 复制一遍既浪费时间，也会在将来废弃别名时制造双倍维护成本。
#
# 【给主理人的建议，非测试问题】deliver/receive 属于历史包袱，建议排期废弃：
# 保留两套 URL 指向同一逻辑，网关权限、限流、审计都得配两份，是长期负债。
#
# ---------------------------------------------------------------------------
# 覆盖清单
# ---------------------------------------------------------------------------
#   S1  买家下单成功（前置）
#   S2  买家越权发货 → 403
#   S3  卖家发货成功 → 200（待付款/待发货 → 待收货）
#   S4  重复发货 → 409（不做幂等静默成功）
#   S5  卖家越权替买家确认收货 → 403
#   S6  买家确认收货成功 → 200（待收货 → 已完成）
#   S7  重复确认收货 → 409
#   S8  卖家订单列表能查到本单，且状态为已完成(3)
#   S9  别名端点 deliver 与 ship 归属校验等价（买家打 deliver 同样 403）
#   S10 别名端点 receive 与 confirm 归属校验等价（卖家打 receive 同样 403）
#   S11 未发货直接确认收货 → 409（禁止 0→3 跳跃）
#   S12 全脚本库存净影响为零（幂等保障）
#
# ---------------------------------------------------------------------------
# 幂等性
# ---------------------------------------------------------------------------
# 卖家链路的终点是"已完成(3)"，该状态**不能**再走 cancelOrder 还库存
# （cancelOrder 只接受 status=0），所以本脚本无法靠业务接口回滚，
# 只能在收尾直连内部 restore 接口把库存补回来——这与 e2e 的方案 C 一致。
# 失效边界：kill -9 / 断电时 trap 不执行；restore 失败时 S12 会红并给出手工命令。
#
# 环境陷阱与端口说明见 tests/lib_http.sh 文件头。
###############################################################################
set -u

LIB_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=lib_http.sh
. "$LIB_DIR/lib_http.sh"

PRODUCT_ID=1
ORDER_QTY=1          # 卖家链路只验状态机，1 件足够，尽量少动库存
SELLER_USER="seller1"
SELLER_PASS="123456"

BASELINE=""
PENDING_ROLLBACK=0

load_internal_token || true

final_rollback() {
    if [ "$PENDING_ROLLBACK" -le 0 ]; then
        return 0
    fi
    restore_stock_direct "$PRODUCT_ID" "$PENDING_ROLLBACK" && PENDING_ROLLBACK=0
}
trap 'final_rollback >/dev/null 2>&1 || true' EXIT INT TERM

echo "=========================================="
echo "  卖家侧全链路测试"
echo "=========================================="
echo ""

echo -e "${BLUE}--- 预检 ---${NC}"
preflight

BASELINE="$(get_stock "$PRODUCT_ID")"
if [ -z "$BASELINE" ]; then
    echo -e "${RED}[ABORT]${NC} 读不到商品 ${PRODUCT_ID} 的库存，中止。"
    exit 2
fi
echo "       [基线] 商品 ${PRODUCT_ID} 库存 = ${BASELINE}"

# ---- 卖家身份 ----
SELLER_INFO="$(login_as "$SELLER_USER" "$SELLER_PASS")"
SELLER_TOKEN="$(printf '%s' "$SELLER_INFO" | cut -d' ' -f1)"
SELLER_ID="$(printf '%s' "$SELLER_INFO" | cut -d' ' -f2)"
if [ -z "$SELLER_TOKEN" ]; then
    echo -e "${RED}[ABORT]${NC} 卖家 ${SELLER_USER} 登录失败（口令应为 ${SELLER_PASS}，见 database/init.sql:173）。"
    exit 2
fi
SELLER_AUTH="Authorization: Bearer $SELLER_TOKEN"

# 核实商品归属：若商品 1 的 sellerId 不是当前卖家，后面所有发货断言都会
# 变成无意义的 403。与其让人对着一堆红去猜，不如在这里明确中止。
http_call GET "$GATEWAY/api/product/$PRODUCT_ID"
PRODUCT_SELLER="$(json_get "$RESP_BODY" data.sellerId)"
if [ "$PRODUCT_SELLER" != "$SELLER_ID" ]; then
    echo -e "${RED}[ABORT]${NC} 商品 ${PRODUCT_ID} 的 sellerId=${PRODUCT_SELLER}，与登录卖家 id=${SELLER_ID} 不符。"
    echo "  发货断言需要「订单所属卖家」身份，前提不成立，中止以免产生误导性的假红。"
    exit 2
fi
echo "       [就绪] 卖家 ${SELLER_USER} (id=${SELLER_ID}) == 商品 ${PRODUCT_ID} 的 sellerId"

# ---- 买家身份 ----
BUYER_INFO="$(new_buyer sel)"
BUYER_TOKEN="$(printf '%s' "$BUYER_INFO" | cut -d' ' -f1)"
BUYER_ID="$(printf '%s' "$BUYER_INFO" | cut -d' ' -f2)"
if [ -z "$BUYER_TOKEN" ]; then
    echo -e "${RED}[ABORT]${NC} 买家账号准备失败，无法继续。"
    exit 2
fi
BUYER_AUTH="Authorization: Bearer $BUYER_TOKEN"
ADDR_ID="$(create_address "$BUYER_TOKEN" "卖家链路测试")"
if [ -z "$ADDR_ID" ]; then
    echo -e "${RED}[ABORT]${NC} 收货地址准备失败，无法继续。"
    exit 2
fi
echo "       [就绪] 买家 id=${BUYER_ID} / addressId=${ADDR_ID}"
echo ""

###############################################################################
# S1 前置：买家下单
###############################################################################
echo -e "${BLUE}--- S. 卖家发货 / 买家收货状态机 ---${NC}"

http_call POST "$GATEWAY/api/order/create" \
    -H "Content-Type: application/json" \
    -H "$BUYER_AUTH" \
    -d "{\"addressId\":$ADDR_ID,\"items\":[{\"productId\":$PRODUCT_ID,\"quantity\":$ORDER_QTY}]}"
if [ "$RESP_CODE" = "200" ] && [ "$(json_get "$RESP_BODY" success)" = "True" ]; then
    PENDING_ROLLBACK=$ORDER_QTY
fi
assert_ok "S1 买家下单成功（前置）"
ORDER_ID="$(json_get "$RESP_BODY" data.0.id)"

if [ -z "$ORDER_ID" ]; then
    echo -e "${RED}[ABORT]${NC} S1 未能取得 orderId，后续状态机断言全部无从谈起，中止。"
    summary "卖家链路测试结果"
    exit 1
fi
echo "       [就绪] orderId=${ORDER_ID}"

###############################################################################
# S11 未发货直接确认收货 → 409（先测，因为此刻订单正处于「待付款」）
###############################################################################
# 【顺序说明】这条刻意排在发货之前执行：它要验的是"0/1 → 3 跳跃被禁止"，
# 必须在订单尚未发货时才有意义。若放到最后测，订单早已是已完成(3)，
# 拿到的 409 文案会变成「当前订单状态为「已完成」」——那验的是重复确认（S7），
# 不是禁止跳跃，属于自欺欺人的绿。编号保持 S11 是为了让报告里的清单按功能分组好读。
http_call POST "$GATEWAY/api/order/$ORDER_ID/confirm" -H "$BUYER_AUTH"
assert_error "S11 未发货直接确认收货返回 409（禁止 0→3 跳跃）" "409"

###############################################################################
# S2 买家越权发货 → 403
###############################################################################
# 网关侧 /order/** 统一挂 order:read，买卖双方都有该权限码，网关**拦不住**这个，
# 真正的边界在 OrderService.shipOrder 的归属校验（OrderController:68-71 有说明）。
# 所以这条断言实际在验"服务层兜住了网关兜不住的越权"，是分层防御的关键一环。
http_call POST "$GATEWAY/api/order/$ORDER_ID/ship" -H "$BUYER_AUTH"
assert_error "S2 买家越权发货返回 403" "403"

###############################################################################
# S9 别名端点 deliver 归属校验等价
###############################################################################
http_call POST "$GATEWAY/api/order/deliver/$ORDER_ID" -H "$BUYER_AUTH"
assert_error "S9 别名端点 deliver 越权同样返回 403（与 ship 等价）" "403"

###############################################################################
# S3 卖家发货 → 200
###############################################################################
http_call POST "$GATEWAY/api/order/$ORDER_ID/ship" -H "$SELLER_AUTH"
assert_ok "S3 卖家发货成功（→ 待收货）"

###############################################################################
# S4 重复发货 → 409
###############################################################################
http_call POST "$GATEWAY/api/order/$ORDER_ID/ship" -H "$SELLER_AUTH"
assert_error "S4 重复发货返回 409（不做幂等静默成功）" "409"

###############################################################################
# S5 卖家越权替买家确认收货 → 403
###############################################################################
http_call POST "$GATEWAY/api/order/$ORDER_ID/confirm" -H "$SELLER_AUTH"
assert_error "S5 卖家越权确认收货返回 403" "403"

###############################################################################
# S10 别名端点 receive 归属校验等价
###############################################################################
http_call POST "$GATEWAY/api/order/receive/$ORDER_ID" -H "$SELLER_AUTH"
assert_error "S10 别名端点 receive 越权同样返回 403（与 confirm 等价）" "403"

###############################################################################
# S6 买家确认收货 → 200
###############################################################################
http_call POST "$GATEWAY/api/order/$ORDER_ID/confirm" -H "$BUYER_AUTH"
assert_ok "S6 买家确认收货成功（→ 已完成）"

###############################################################################
# S7 重复确认收货 → 409
###############################################################################
http_call POST "$GATEWAY/api/order/$ORDER_ID/confirm" -H "$BUYER_AUTH"
assert_error "S7 重复确认收货返回 409" "409"

echo ""

###############################################################################
# S8 卖家订单列表
###############################################################################
echo -e "${BLUE}--- S8. 卖家订单列表 ---${NC}"

http_call GET "$GATEWAY/api/order/seller?current=1&size=20" -H "$SELLER_AUTH"
assert_ok "S8.1 卖家订单列表可访问"

# ---------------------------------------------------------------------------
# 【本节曾经写错过，留痕备忘 —— 别再改回去】
# ---------------------------------------------------------------------------
# 初版这里写的是"取第一页 20 条，本单是最新创建的必然在最前面"。
# 它**跑绿了**，但那是运气：实测首页 id 序列为
#     [11, 262, 20, 19, 18, 17, 16, 15, 14, 13, 12, 1, 10, 9, 8, 7, 6, 5, 4, 3]
# 本单(262) 恰好落在第 2 位。完全是巧合。
#
# 根因（已作为源码缺陷 BUG-1 上报）：orders.create_time **全表皆为 NULL**，
# 而 getOrdersWithItemsBySellerId 的排序是 orderByDesc(create_time)——
# 全部并列，等于没排序，MySQL 按存储/执行计划顺序返回，页与页之间没有稳定序。
# 这种情况下"新单在首页"这个前提根本不成立，翻页还会**真实漏行**。
#
# 实测（total=280 时，逐页翻到底后统计唯一 id 数）：
#     size=10（接口默认值） → 只看得到 224 单，**永久漏 56 单 = 20.0%**
#     size=20              → 只看得到 240 单，永久漏 40 单 = 14.3%
#     size=50              → 只看得到 279 单，漏 1 单
#     size=100             → 280 单，暂时不漏（一页装得下，没触发跨页重排）
# 同一 size 的两次独立扫描结果完全一致，说明这不是偶发抖动，是稳定复现的缺陷。
#
# 【S8.4 的已知脆弱性 —— 看到它红先别急着改测试】
# 本条用 size=100 全量翻页。当前数据量下不漏，但订单总数继续增长、
# 一页装不下之后，S8.4 有概率因 BUG-1 漏行而红。
# **那种红是真信号，不是测试脆**：它说明卖家已经开始丢单了。
# 正确的处理是去修 BUG-1（给 create_time 补自动填充 + 排序加 id 兜底），
# 而不是把 size 调更大来掩盖。
#
# 所以断言改成两条**不依赖任何排序**的确定性校验：
#   S8.2 走详情接口按主键精确读（卖家对自己店铺的订单有读权限）——
#        这条验的是"卖家侧能看到并且状态同步正确"，与排序无关，永远确定。
#   S8.3 全量翻页收集 id 后判定集合成员关系——
#        这条验的是"本单确实归属在卖家列表里"，同样与排序无关。
# 在 BUG-1 修好之前，任何依赖"第几页第几条"的写法都是定时假绿。
# ---------------------------------------------------------------------------

# S8.2 按主键精确读，与排序完全解耦
http_call GET "$GATEWAY/api/order/$ORDER_ID" -H "$SELLER_AUTH"
assert_ok "S8.2 卖家可按主键读取本店铺订单详情"
DETAIL_STATUS="$(json_get "$RESP_BODY" data.status)"
assert_eq "S8.3 卖家侧读到的订单状态为已完成(3)" "3" "$DETAIL_STATUS" \
    "S6 已确认收货，卖家侧必须同步读到 3，否则是读写不一致"

# S8.4 全量翻页判定集合成员关系，同样与排序解耦。
# 页数上限 MAX_PAGES 是防呆：万一 total 异常巨大或分页坏掉陷入死循环，
# 宁可判失败也不要让测试挂死。
PAGE_SIZE=100
MAX_PAGES=50
page=1
found="no"
scanned=0
while [ "$page" -le "$MAX_PAGES" ]; do
    http_call GET "$GATEWAY/api/order/seller?current=${page}&size=${PAGE_SIZE}" -H "$SELLER_AUTH"
    if [ "$RESP_CODE" != "200" ]; then
        break
    fi
    hit="$(printf '%s' "$RESP_BODY" | python3 -c "
import sys, json
try:
    d = json.load(sys.stdin)
except Exception:
    print('0 0'); sys.exit(0)
recs = (d.get('data') or {}).get('records') or []
hit = any(str(r.get('id')) == '$ORDER_ID' for r in recs)
print(('1' if hit else '0'), len(recs))
" 2>/dev/null)"
    got="$(printf '%s' "$hit" | cut -d' ' -f1)"
    cnt="$(printf '%s' "$hit" | cut -d' ' -f2)"
    scanned=$((scanned + cnt))
    if [ "$got" = "1" ]; then
        found="yes"
        break
    fi
    if [ -z "$cnt" ] || [ "$cnt" -lt "$PAGE_SIZE" ]; then
        break
    fi
    page=$((page + 1))
done

if [ "$found" = "yes" ]; then
    pass_case "S8.4 卖家订单列表（全量翻页 ${scanned} 条）包含本单 orderId=${ORDER_ID}"
else
    echo -e "${RED}[FAIL]${NC} S8.4 卖家订单列表（全量翻页 ${scanned} 条）包含本单 orderId=${ORDER_ID}"
    echo "       原因: 翻遍 ${page} 页共 ${scanned} 条仍未找到本单。"
    echo "       提示: 若 S8.2 绿而本条红，说明订单存在且卖家有权读，但列表查询把它漏了"
    echo "             —— 高度怀疑是 BUG-1（create_time 全 NULL 导致分页顺序不稳定）引发的翻页漏行。"
    FAIL=$((FAIL + 1))
fi

echo ""

###############################################################################
# S12 幂等收尾
###############################################################################
echo -e "${BLUE}--- S12. 幂等收尾 ---${NC}"

# 已完成(3) 的订单无法用 cancelOrder 还库存（只接受 status=0），
# 故只能直连内部 restore 复原。这是本脚本唯一动内部接口的地方。
NAME="S12 全脚本库存净影响为零（幂等保障）"
if [ "$PENDING_ROLLBACK" -le 0 ]; then
    FINAL_STOCK="$(get_stock "$PRODUCT_ID")"
    assert_eq "$NAME" "$BASELINE" "$FINAL_STOCK" "S1 未成功下单，库存本就不该变化"
elif final_rollback; then
    FINAL_STOCK="$(get_stock "$PRODUCT_ID")"
    assert_eq "$NAME" "$BASELINE" "$FINAL_STOCK" \
        "回滚调用已返回成功，若仍不等说明 restore 的实际增量与请求量不符"
else
    echo -e "${RED}[FAIL]${NC} ${NAME}"
    echo "       原因: 库存回滚调用失败，本轮已污染环境"
    echo "       手工复原: curl --noproxy '*' -X PUT '${PRODUCT_DIRECT}/product/${PRODUCT_ID}/restore?quantity=${PENDING_ROLLBACK}' -H 'X-Internal-Token: <见 .env>'"
    FAIL=$((FAIL + 1))
fi

summary "卖家链路测试结果"
if [ "$FAIL" -gt 0 ]; then
    exit 1
fi
exit 0
