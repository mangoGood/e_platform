#!/bin/bash
###############################################################################
# 电商平台端到端测试脚本
# 覆盖：用户注册/登录、商品浏览、购物车、收货地址、订单全流程、
#       移动端BFF聚合、网关安全（认证拦截、内部接口隔离）
# 用法：bash tests/e2e_test.sh
#
# ---------------------------------------------------------------------------
# T03d 对齐改造（2026-08-03，QA）
# ---------------------------------------------------------------------------
# 背景：T03d「错误码语义收尾」让接口返回真实 HTTP 状态码（401/403/404/409/400），
#       而不再一律 HTTP 200 + body.code。本脚本原写于 T03d 之前，存在两类问题：
#
#   1) 【端点过时】8.3/8.4 打的是 POST /api/product/deduct?productId=N，
#      而真实内部接口早已改为 PUT /api/product/{id}/deduct?quantity=N
#      （ProductController:126/134）。网关的 INTERNAL_DENY 规则匹配的是
#      "/product/*/deduct"，旧路径少一个路径段，压根匹配不上规则，
#      直接穿透到 product 服务，撞上 /product/{id} 只支持 GET/PUT/DELETE
#      → 405。也就是说这两条断言从来没有真正验证过内部接口隔离。
#
#   2) 【只断言 body，不断言 HTTP 码】原 assert_success 只看 body.success，
#      HTTP 状态码完全不看。而 GlobalExceptionHandler 的兜底分支至今仍返回
#      HTTP 200 + code 500（T02 兼容契约，刻意保留），因此"HTTP 码悄悄变了"
#      这类回归本脚本一条都拦不住 —— 属于典型的假绿。
#
# 改造要点：所有断言一律**同时**校验 HTTP 状态码与 body，口径与
#          tests/security_test.sh 保持一致。断言的原始意图与条数（21 条）不变。
#
# ---------------------------------------------------------------------------
# 环境陷阱规避（与 security_test.sh 同源，勿删）
# ---------------------------------------------------------------------------
#   - 本机注入了 HTTP_PROXY，curl 不加 --noproxy 打 localhost 有被代理拦成
#     502 空 body 的风险，看起来像"服务全挂"。脚本开头统一 unset 代理变量，
#     且每次 curl 都带 --noproxy '*'。
#   - zsh 会把 URL 里的 ? 当 glob 吃掉导致命令静默不执行。本脚本用 bash 执行，
#     且所有含 ? 的 URL 一律加引号。
#   - 兼容 macOS 自带 bash 3.2，不使用关联数组等 bash 4+ 特性。
#
# ---------------------------------------------------------------------------
# 幂等改造（2026-08-03，QA）—— 遗留 6
# ---------------------------------------------------------------------------
# 【问题】6.1 写死 {"productId":1,"quantity":2}，每跑一轮真实扣减商品 1 库存 2 件，
#         且全流程只到「支付」为止，没有任何一步把库存还回去。种子库存 100，
#         跑到本次改造前已经掉到 87。约 50 轮后 6.1 会因「商品库存不足」而假红，
#         而且那时的红是环境被脚本自己耗干造成的，排查方向会被彻底带偏。
#         这是个定时炸弹，必须先拆，否则本轮新增的库存/卖家用例只会加速耗尽。
#
# 【选型】候选三条路，逐条权衡：
#
#   A. 跑前动态挑一个库存充足的商品
#      —— 只是把炸弹推远，没拆。所有商品迟早一起见底，而且"每轮打不同商品"
#         会让失败不可复现，反而更难查。**否决**。
#
#   B. 用例末尾走业务接口取消订单来回滚（cancelOrder 内部会 restoreStock）
#      —— 语义最干净，但**走不通**：6.4 已经把订单支付到 status=1，
#         而 cancelOrder 只允许 status=0（OrderService:495 "只能取消待付款的订单"）。
#         要用它就得删掉 6.4 支付断言，等于为了修幂等牺牲一条真实覆盖。**否决**。
#
#   C. 记录库存基线 → 跑完直连内部接口 restore → 断言回到基线   ← **本脚本采用**
#      —— 不动任何既有断言的语义，且"回滚"这件事本身被 9.1 断言守住：
#         万一哪天 restore 接口坏了，9.1 会红，而不是悄悄漏一点库存。
#         等于顺带给内部 restore 接口加了一条真实覆盖。
#
# 【实现要点】
#   - 库存**读**走网关（GET /api/product/1）；库存**写**（restore）必须直连
#     product 服务 http://localhost:8086。原因有二：
#       1) 经网关 /api/product/1/restore 会被 INTERNAL_DENY 规则 403 拦掉
#          —— 这正是 8.4 要验证的行为，不能绕；
#       2) 直连还需要 X-Internal-Token 头，否则会被
#          GatewaySignatureInterceptor 拦成 401「非法请求来源」。
#   - 【重要】product 服务在 **8086**，不是 8085（8085 是 user 服务）。
#     实测 application.yml + lsof 确认：8085=user 8086=product 8087=order
#     8088=gateway 8089=mobile-bff。打错端口的话 restore 会静默拿 404，
#     库存照漏不误。
#   - 用 trap ... EXIT INT TERM 挂回滚，让 Ctrl-C / set -e 中途退出也能还库存。
#
# 【失效边界 —— 以下情况回滚不会发生，库存仍会漏】
#   1) 进程被 SIGKILL（kill -9）或机器断电：trap 收不到信号，回滚不执行。
#   2) restore 调用自身失败（product 服务在下单后、回滚前挂掉；INTERNAL_TOKEN
#      被改动或 .env 缺失）：此时 9.1 会红并打印实际库存，属于"响亮的失败"，
#      不会静默漏 —— 这是选 C 而不是 A 的主要理由。
#   3) 本脚本每轮仍会新增 1 个用户 + 1 个地址 + 1 条订单，这些行不会被清理。
#      它们只是数据堆积，不影响脚本可重复执行（用户名带时间戳不撞唯一索引），
#      故本轮不处理；若将来要做，应由独立的数据清理脚本负责，不塞进 e2e。
###############################################################################
set -u

# ---- 彻底摘掉代理，避免 502 空 body 假象 ----
unset HTTP_PROXY HTTPS_PROXY http_proxy https_proxy ALL_PROXY all_proxy

GATEWAY="http://localhost:8088"
# 内部接口直连地址（见上方"幂等改造"注释：8086 才是 product 服务）
PRODUCT_DIRECT="http://localhost:8086"
TIMEOUT=15

PASS=0
FAIL=0
TOTAL_EXPECTED=22

TOKEN=""
USER_ID=""

# ---- 幂等相关状态 ----
E2E_PRODUCT_ID=1        # 6.1 下单使用的商品
E2E_ORDER_QTY=2         # 6.1 下单数量（保持原值，不改变既有断言语义）
STOCK_BASELINE=""       # 下单前的库存基线
STOCK_DEDUCTED=0        # 已扣减、待回滚的数量；回滚成功后清零，供 trap 判断

# 从 .env 读服务间令牌。product 服务的 restore 用它做鉴权，
# 同时它还能绕过 GatewaySignatureInterceptor 的直连 401。
PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
INTERNAL_TOKEN=""
if [ -f "$PROJECT_ROOT/.env" ]; then
    INTERNAL_TOKEN="$(grep '^INTERNAL_TOKEN=' "$PROJECT_ROOT/.env" 2>/dev/null | head -1 | cut -d= -f2-)"
fi

# 颜色输出
GREEN='\033[0;32m'
RED='\033[0;31m'
YELLOW='\033[0;33m'
BLUE='\033[0;34m'
NC='\033[0m'

# 全局响应变量，由 http_call 写入
RESP_CODE=""
RESP_BODY=""

###############################################################################
# 基础工具
###############################################################################

# 发起一次 HTTP 调用，连接失败（curl 返回 000）时自动重试一次，防偶发抖动造成假红。
# 用法：http_call <METHOD> <URL> [额外 curl 参数...]
# 结果：RESP_CODE / RESP_BODY
http_call() {
    local method="$1"
    local url="$2"
    shift 2
    local extra_args=("$@")
    local attempt=1
    local body_file
    body_file="$(mktemp -t e2e_resp.XXXXXX)"

    while : ; do
        RESP_CODE="$(curl -s --noproxy '*' --max-time "$TIMEOUT" \
            -X "$method" "$url" \
            ${extra_args[@]+"${extra_args[@]}"} \
            -o "$body_file" -w '%{http_code}' 2>/dev/null)"
        if [ -z "$RESP_CODE" ]; then
            RESP_CODE="000"
        fi
        # 000 = 连不上/超时，重试一次
        if [ "$RESP_CODE" != "000" ] || [ "$attempt" -ge 2 ]; then
            break
        fi
        attempt=$((attempt + 1))
        sleep 1
    done

    RESP_BODY="$(cat "$body_file" 2>/dev/null)"
    rm -f "$body_file"
}

# 从 JSON 中取字段，支持点路径与数字下标：json_get "$RESP_BODY" data.0.id
json_get() {
    local raw="$1"
    local path="$2"
    printf '%s' "$raw" | python3 -c "
import sys, json
try:
    d = json.load(sys.stdin)
except Exception:
    print('')
    sys.exit(0)
for k in '$path'.split('.'):
    if isinstance(d, dict):
        d = d.get(k)
    elif isinstance(d, list) and k.isdigit() and int(k) < len(d):
        d = d[int(k)]
    else:
        d = None
    if d is None:
        print('')
        sys.exit(0)
print(d)
" 2>/dev/null
}

# 打印失败详情：实际 HTTP 码 + 实际响应体（截断防刷屏）
dump_actual() {
    echo "       实际 HTTP: ${RESP_CODE}"
    local body="$RESP_BODY"
    if [ ${#body} -gt 600 ]; then
        body="$(printf '%s' "$body" | cut -c1-600)...(已截断)"
    fi
    if [ -z "$body" ]; then
        body="<空响应体>"
    fi
    echo "       实际响应: ${body}"
}

pass_case() {
    echo -e "${GREEN}[PASS]${NC} $1"
    PASS=$((PASS + 1))
}

fail_case() {
    echo -e "${RED}[FAIL]${NC} $1"
    if [ -n "${2:-}" ]; then
        echo "       原因: $2"
    fi
    dump_actual
    FAIL=$((FAIL + 1))
}

# 断言"成功调用"：HTTP 必须 200 **且** body.success 必须 true。
#
# 为什么必须两个都查：
#   - 只查 body.success：HTTP 码从 200 变成别的（例如某天 404 被正确对齐）时察觉不到。
#   - 只查 HTTP 200：GlobalExceptionHandler 兜底分支返回 HTTP 200 + code 500
#     的"软失败"会被当成成功放过去。
# 两者结合才能既守住成功语义、又守住状态码语义。
assert_ok() {
    local name="$1"
    local success
    success="$(json_get "$RESP_BODY" success)"
    if [ "$RESP_CODE" != "200" ]; then
        fail_case "$name" "期望 HTTP 200，实际 HTTP ${RESP_CODE}"
        return
    fi
    if [ "$success" != "True" ]; then
        fail_case "$name" "HTTP 200 正确，但 body.success 不为 true（软失败：code=$(json_get "$RESP_BODY" code)）"
        return
    fi
    pass_case "$name"
}

# 断言"失败调用"：HTTP 状态码 **且** body.code 必须同时等于期望值。
#
# T03d 之后二者应当恒等。刻意不做"二选一"的宽松匹配：
# 只要出现 HTTP 与 body 不一致，就说明状态码对齐出现了回归，必须红。
assert_error() {
    local name="$1"
    local expected="$2"
    local body_code
    body_code="$(json_get "$RESP_BODY" code)"
    if [ "$RESP_CODE" != "$expected" ]; then
        fail_case "$name" "期望 HTTP ${expected}，实际 HTTP ${RESP_CODE}"
        return
    fi
    if [ "$body_code" != "$expected" ]; then
        fail_case "$name" "HTTP ${expected} 正确，但 body.code=${body_code}（期望 ${expected}，HTTP 与 body 不一致）"
        return
    fi
    pass_case "$name"
}

###############################################################################
# 幂等保障：库存基线读取与回滚
###############################################################################

# 读商品库存。走网关，因为直连 product 的 GET 会被
# GatewaySignatureInterceptor 拦成 401「非法请求来源」。
# 失败返回空串，调用方判空。
read_stock() {
    local pid="$1"
    http_call GET "$GATEWAY/api/product/$pid"
    if [ "$RESP_CODE" != "200" ]; then
        echo ""
        return 1
    fi
    json_get "$RESP_BODY" data.stock
}

# 把 6.1 扣掉的库存还回去。幂等：靠 STOCK_DEDUCTED 计数，重复调用不会多还。
# 既被 trap 调用（异常退出路径），也在第 9 节被显式调用（正常路径）。
restore_deducted_stock() {
    if [ "$STOCK_DEDUCTED" -le 0 ]; then
        return 0
    fi
    if [ -z "$INTERNAL_TOKEN" ]; then
        echo -e "${YELLOW}[WARN]${NC} INTERNAL_TOKEN 为空（检查 $PROJECT_ROOT/.env），"
        echo "       无法回滚 ${STOCK_DEDUCTED} 件库存，本轮已污染环境。"
        return 1
    fi
    http_call PUT "$PRODUCT_DIRECT/product/$E2E_PRODUCT_ID/restore?quantity=$STOCK_DEDUCTED" \
        -H "X-Internal-Token: $INTERNAL_TOKEN"
    if [ "$RESP_CODE" = "200" ] && [ "$(json_get "$RESP_BODY" success)" = "True" ]; then
        STOCK_DEDUCTED=0
        return 0
    fi
    echo -e "${YELLOW}[WARN]${NC} 库存回滚失败（HTTP ${RESP_CODE}）：${RESP_BODY}"
    return 1
}

# 异常退出兜底。正常路径下第 9 节已回滚完毕，此处因计数为 0 而空转。
# 覆盖 Ctrl-C / TERM / 中途 exit；覆盖不到 kill -9（见文件头"失效边界"）。
trap 'restore_deducted_stock >/dev/null 2>&1 || true' EXIT INT TERM

echo "=========================================="
echo "  电商平台端到端测试（${TOTAL_EXPECTED} 项）"
echo "=========================================="
echo ""

###############################################################################
# 预检：网关连通性
###############################################################################
echo -e "${BLUE}--- 预检 ---${NC}"
http_call GET "$GATEWAY/actuator/health"
if [ "$RESP_CODE" != "200" ]; then
    echo -e "${RED}[ABORT]${NC} 网关 $GATEWAY 不可达（/actuator/health 返回 ${RESP_CODE}）"
    echo ""
    echo "  已自动重试一次仍失败。请先确认网关进程存活后再跑本脚本，"
    echo "  否则 21 项断言会全部变成无意义的假 FAIL。"
    echo "  注意：本脚本已 unset 代理变量，此处失败不是 HTTP_PROXY 造成的。"
    exit 2
fi
echo -e "${GREEN}[OK]${NC} 网关健康检查通过 ($GATEWAY)"
echo ""

###############################################################################
# 1. 用户注册
###############################################################################
echo -e "${BLUE}--- 1. 用户模块 ---${NC}"
TS=$(date +%s)
USERNAME="e2e_user_${TS}"
http_call POST "$GATEWAY/api/user/register" \
    -H "Content-Type: application/json" \
    -d "{\"username\":\"$USERNAME\",\"password\":\"Test@1234\",\"phone\":\"138${TS:0:8}\",\"email\":\"${USERNAME}@test.com\",\"userType\":1}"
assert_ok "1.1 用户注册"

###############################################################################
# 2. 用户登录
###############################################################################
http_call POST "$GATEWAY/api/user/login" \
    -H "Content-Type: application/json" \
    -d "{\"username\":\"$USERNAME\",\"password\":\"Test@1234\"}"
assert_ok "2.1 用户登录"
TOKEN="$(json_get "$RESP_BODY" data.token)"
USER_ID="$(json_get "$RESP_BODY" data.userId)"

NAME="2.2 获取Token"
if [ -n "$TOKEN" ]; then
    pass_case "$NAME"
else
    fail_case "$NAME" "登录响应里没有 data.token（注意字段名是 token，不是 accessToken）"
fi

# 后续所有请求共用的认证头
AUTH="Authorization: Bearer $TOKEN"

###############################################################################
# 3. 商品浏览（公开读路径，无需Token也可，但携带Token测试）
###############################################################################
echo ""
echo -e "${BLUE}--- 2. 商品模块 ---${NC}"
http_call GET "$GATEWAY/api/product/list?pageNum=1&pageSize=3" -H "$AUTH"
assert_ok "3.1 商品列表分页"

http_call GET "$GATEWAY/api/product/1" -H "$AUTH"
assert_ok "3.2 商品详情"

http_call GET "$GATEWAY/api/category/tree" -H "$AUTH"
assert_ok "3.3 分类树"

###############################################################################
# 4. 收货地址
###############################################################################
echo ""
echo -e "${BLUE}--- 3. 收货地址模块 ---${NC}"
http_call POST "$GATEWAY/api/address" \
    -H "Content-Type: application/json" \
    -H "$AUTH" \
    -d '{"receiverName":"测试用户","receiverPhone":"13800000001","province":"北京市","city":"北京市","district":"海淀区","detailAddress":"中关村大街1号","isDefault":1}'
assert_ok "4.1 添加收货地址"
ADDR_ID="$(json_get "$RESP_BODY" data.id)"

http_call GET "$GATEWAY/api/address/list" -H "$AUTH"
assert_ok "4.2 地址列表"

###############################################################################
# 5. 购物车
###############################################################################
echo ""
echo -e "${BLUE}--- 4. 购物车模块 ---${NC}"
http_call POST "$GATEWAY/api/order/cart/add?productId=1&quantity=2" -H "$AUTH"
assert_ok "5.1 添加购物车"

http_call GET "$GATEWAY/api/order/cart" -H "$AUTH"
assert_ok "5.2 查看购物车"

http_call PUT "$GATEWAY/api/order/cart?productId=1&quantity=3" -H "$AUTH"
assert_ok "5.3 修改购物车数量"

###############################################################################
# 6. 订单流程
###############################################################################
echo ""
echo -e "${BLUE}--- 5. 订单模块 ---${NC}"

# 【幂等】下单前先记录库存基线，第 9 节回滚后要比对回到这个值。
STOCK_BASELINE="$(read_stock "$E2E_PRODUCT_ID")"
if [ -z "$STOCK_BASELINE" ]; then
    echo -e "${YELLOW}[WARN]${NC} 未能读到商品 $E2E_PRODUCT_ID 的库存基线，9.1 将无法校验回滚。"
else
    echo "       [基线] 商品 $E2E_PRODUCT_ID 下单前库存 = $STOCK_BASELINE"
fi

# ADDR_ID 取不到时直接判前置失败，不要把空串拼进 JSON —— 那会变成畸形 body，
# T03d 之后返回 400，报出来的是"参数错误"而不是真正的原因，徒增排查成本。
NAME="6.1 创建订单"
if [ -z "$ADDR_ID" ]; then
    RESP_CODE="-"; RESP_BODY="<未发起请求>"
    fail_case "$NAME" "前置条件失败：4.1 未能取得 addressId"
    ORDER_ID=""
else
    http_call POST "$GATEWAY/api/order/create" \
        -H "Content-Type: application/json" \
        -H "$AUTH" \
        -d "{\"addressId\":$ADDR_ID,\"items\":[{\"productId\":$E2E_PRODUCT_ID,\"quantity\":$E2E_ORDER_QTY}]}"
    # 只有确实下单成功才登记待回滚数量。失败时不登记，避免"没扣却还"把库存还多。
    if [ "$RESP_CODE" = "200" ] && [ "$(json_get "$RESP_BODY" success)" = "True" ]; then
        STOCK_DEDUCTED=$E2E_ORDER_QTY
    fi
    assert_ok "$NAME"
    ORDER_ID="$(json_get "$RESP_BODY" data.0.id)"
fi

http_call GET "$GATEWAY/api/order/user" -H "$AUTH"
assert_ok "6.2 用户订单列表"

# 同理：ORDER_ID 为空时 GET /api/order/ 会退化成别的路由，产生误导性的假红。
NAME="6.3 订单详情"
if [ -z "$ORDER_ID" ]; then
    RESP_CODE="-"; RESP_BODY="<未发起请求>"
    fail_case "$NAME" "前置条件失败：6.1 未能取得 orderId"
else
    http_call GET "$GATEWAY/api/order/$ORDER_ID" -H "$AUTH"
    assert_ok "$NAME"
fi

NAME="6.4 支付订单"
if [ -z "$ORDER_ID" ]; then
    RESP_CODE="-"; RESP_BODY="<未发起请求>"
    fail_case "$NAME" "前置条件失败：6.1 未能取得 orderId"
else
    http_call POST "$GATEWAY/api/order/pay/$ORDER_ID" -H "$AUTH"
    assert_ok "$NAME"
fi

###############################################################################
# 7. 移动端BFF聚合
###############################################################################
echo ""
echo -e "${BLUE}--- 6. 移动端BFF模块 ---${NC}"
http_call GET "$GATEWAY/api/mobile/home" -H "$AUTH"
assert_ok "7.1 移动端首页聚合"

###############################################################################
# 8. 网关安全测试
###############################################################################
echo ""
echo -e "${BLUE}--- 7. 网关安全模块 ---${NC}"
# 8.1 未登录访问受保护接口
http_call GET "$GATEWAY/api/order/cart"
assert_error "8.1 未登录访问受保护接口返回401" "401"

# 8.2 无效Token访问
http_call GET "$GATEWAY/api/order/cart" -H "Authorization: Bearer invalidtoken"
assert_error "8.2 无效Token返回401" "401"

# 8.3 外部访问内部接口 deduct
#
# 端点契约（ProductController:126）：PUT /product/{id}/deduct?quantity=N
# 网关 RoutePermissionRegistry:101 用 INTERNAL_DENY + ANY_METHOD 匹配
# "/product/*/deduct"，且该判定位于 ProxyService 管线第 1 步 —— 先于一切认证，
# 所以无论带不带合法 token 都应当 403。
# 这里刻意**携带合法 token**：能证明"已登录的外部用户同样进不去"，
# 比匿名请求是更强的断言。
http_call PUT "$GATEWAY/api/product/1/deduct?quantity=1" -H "$AUTH"
assert_error "8.3 内部接口deduct被拦截403" "403"

# 8.4 外部访问内部接口 restore
http_call PUT "$GATEWAY/api/product/1/restore?quantity=1" -H "$AUTH"
assert_error "8.4 内部接口restore被拦截403" "403"

# 8.5 正常商品查询不被误伤
# 这一条与 3.2 请求相同但意图不同：3.2 验证功能可用性，8.5 验证
# INTERNAL_DENY 的通配规则 "/product/*/deduct" 没有过度拦截 "/product/{id}"。
http_call GET "$GATEWAY/api/product/1" -H "$AUTH"
assert_ok "8.5 正常商品查询不被误拦截"

###############################################################################
# 9. 幂等收尾：回滚 6.1 扣减的库存并校验
###############################################################################
echo ""
echo -e "${BLUE}--- 8. 幂等收尾 ---${NC}"

# 这条断言是"本脚本可无限重跑"的**验收标准本身**：
# 只要它绿，就说明本轮消耗的库存已全额归还，下一轮的起点与本轮完全一致。
# 它同时也是内部 restore 接口的一条真实功能覆盖 —— 接口坏了这里就红。
NAME="9.1 库存回滚至基线（幂等保障）"
if [ -z "$STOCK_BASELINE" ]; then
    RESP_CODE="-"; RESP_BODY="<未取得库存基线>"
    fail_case "$NAME" "前置条件失败：下单前未能读到库存基线"
elif [ "$STOCK_DEDUCTED" -le 0 ]; then
    # 6.1 没成功 → 没扣库存 → 无需回滚。校验库存确实没动，
    # 顺带守住"下单失败不应产生副作用"。
    STOCK_NOW="$(read_stock "$E2E_PRODUCT_ID")"
    if [ "$STOCK_NOW" = "$STOCK_BASELINE" ]; then
        pass_case "${NAME}（6.1 未成功下单，库存未动，无需回滚）"
    else
        RESP_CODE="-"; RESP_BODY="基线=${STOCK_BASELINE} 当前=${STOCK_NOW}"
        fail_case "$NAME" "6.1 未成功下单，但库存仍发生了变化（下单失败产生了副作用）"
    fi
else
    if restore_deducted_stock; then
        STOCK_NOW="$(read_stock "$E2E_PRODUCT_ID")"
        if [ "$STOCK_NOW" = "$STOCK_BASELINE" ]; then
            pass_case "${NAME}（${STOCK_BASELINE} → 下单 -${E2E_ORDER_QTY} → 回滚 → ${STOCK_NOW}）"
        else
            RESP_CODE="-"; RESP_BODY="基线=${STOCK_BASELINE} 回滚后=${STOCK_NOW}"
            fail_case "$NAME" "回滚调用返回成功，但库存未回到基线（差 $((STOCK_BASELINE - STOCK_NOW)) 件）"
        fi
    else
        fail_case "$NAME" "库存回滚调用失败，本轮已污染环境，请手动执行：
       curl --noproxy '*' -X PUT '$PRODUCT_DIRECT/product/$E2E_PRODUCT_ID/restore?quantity=$STOCK_DEDUCTED' -H 'X-Internal-Token: <见 .env>'"
    fi
fi

###############################################################################
# 测试结果汇总
###############################################################################
echo ""
echo "=========================================="
echo -e "  测试结果: ${GREEN}通过 $PASS${NC} / ${RED}失败 $FAIL${NC} / 共 $TOTAL_EXPECTED"
echo "=========================================="
if [ "$FAIL" -gt 0 ]; then
    exit 1
fi
exit 0
