#!/bin/bash
###############################################################################
# tests/lib_http.sh —— 测试公共库（被 inventory_test.sh / seller_flow_test.sh source）
#
# ---------------------------------------------------------------------------
# 为什么有这个文件，以及为什么 e2e_test.sh 不用它
# ---------------------------------------------------------------------------
# inventory_test.sh 与 seller_flow_test.sh 是本轮新增的两个专项脚本，二者都需要
# 同一套东西：摘代理的 curl 封装、JSON 取值、HTTP+body 双断言、登录取 token、
# 读库存、直连内部接口回滚库存。把这 ~200 行在两个文件里各抄一遍，
# 等于给自己埋了「改一处忘另一处」的坑，所以抽出来。
#
# e2e_test.sh 刻意**不**改成 source 本库：它当前 21 条断言全绿，是"系统是否还活着"
# 的门禁脚本，本轮对它的授权改动只有「修幂等」这一项。把它的工具函数抽走属于
# 纯重构，收益是少几十行重复、代价是让一个已验证稳定的门禁脚本产生大 diff，
# 不划算。等哪天 e2e 本身要大改时再一起并进来。
#
# ---------------------------------------------------------------------------
# 环境陷阱（与 e2e_test.sh / security_test.sh 同源，勿删）
# ---------------------------------------------------------------------------
#   - 本机注入了 HTTP_PROXY。当前对 localhost 恰好透传，但这是休眠地雷：
#     一旦代理策略变化，所有 curl 会被拦成 502 空 body，看起来像"服务全挂"。
#     故此处 unset 全部代理变量，且每次 curl 都显式带 --noproxy '*'，双保险。
#   - zsh 会把 URL 里的 ? * 当 glob 吃掉导致命令静默不执行。本库用 bash 执行，
#     且所有含 ? 的 URL 一律加引号。
#   - 兼容 macOS 自带 bash 3.2：不使用关联数组、declare -A、${var^^} 等 4+ 特性。
#   - 【CJK 陷阱，本轮实际踩到】双引号串里 "$VAR（中文）" 会被 bash 解析成变量名
#     "VAR（"，配合 set -u 直接报 "unbound variable" 而不是打印内容。
#     原因是变量名边界只在 ASCII 非标识符字符处断开，全角括号/中文不算边界。
#     **规则：只要变量后面紧跟中文或全角标点，一律写 ${VAR}**。
#     回归检查一行搞定：
#       python3 -c "import re,sys;[print(f'{n}:{l}') for n,l in enumerate(open(sys.argv[1]),1) if re.search(r'\\\$[A-Za-z_][A-Za-z0-9_]*(?=[^\x00-\x7F])',l)]" tests/xxx.sh
#
# ---------------------------------------------------------------------------
# 服务端口（2026-08-03 实测校正，重要）
# ---------------------------------------------------------------------------
# 交接说明里写的「product 服务直连 8085」是**错的**，会让 restore 全部打到 user
# 服务上拿 404。实测 application.yml + lsof 确认：
#     8085 = user      8086 = product    8087 = order
#     8088 = gateway   8089 = mobile-bff
# 内部接口 PUT /product/{id}/deduct|restore 在 **8086**。
###############################################################################

# ---- 彻底摘掉代理，避免 502 空 body 假象 ----
unset HTTP_PROXY HTTPS_PROXY http_proxy https_proxy ALL_PROXY all_proxy

GATEWAY="${GATEWAY:-http://localhost:8088}"
# 内部接口直连地址。经网关会被 INTERNAL_DENY 规则 403 拦掉（这正是 8.3/8.4 要验的），
# 所以库存回滚只能直连 product 服务本体。
PRODUCT_DIRECT="${PRODUCT_DIRECT:-http://localhost:8086}"
TIMEOUT="${TIMEOUT:-15}"

PASS=0
FAIL=0

GREEN='\033[0;32m'
RED='\033[0;31m'
YELLOW='\033[0;33m'
BLUE='\033[0;34m'
NC='\033[0m'

# 全局响应变量，由 http_call 写入
RESP_CODE=""
RESP_BODY=""

# 项目根目录（本库位于 <root>/tests/ 下）
LIB_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$LIB_DIR/.." && pwd)"

###############################################################################
# 内部令牌
###############################################################################
# product 服务的 deduct/restore 用 X-Internal-Token 做服务间鉴权
# （ProductController.assertInternalToken）。该头同时还能绕过
# GatewaySignatureInterceptor 的"非法请求来源"401——直连读 GET /product/1 会被
# 拦成 401，但带上 internal token 的写操作可以直接进去。
# 所以：**读库存走网关，写回滚走直连**，这不是随意选的，是被这两道校验逼出来的。
load_internal_token() {
    INTERNAL_TOKEN=""
    if [ -f "$PROJECT_ROOT/.env" ]; then
        INTERNAL_TOKEN="$(grep '^INTERNAL_TOKEN=' "$PROJECT_ROOT/.env" 2>/dev/null | head -1 | cut -d= -f2-)"
    fi
    if [ -z "$INTERNAL_TOKEN" ]; then
        echo -e "${YELLOW}[WARN]${NC} 未能从 $PROJECT_ROOT/.env 读到 INTERNAL_TOKEN，"
        echo "       库存回滚将失败，测试会污染环境。请检查 .env。"
        return 1
    fi
    return 0
}

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
    body_file="$(mktemp -t qa_resp.XXXXXX)"

    while : ; do
        RESP_CODE="$(curl -s --noproxy '*' --max-time "$TIMEOUT" \
            -X "$method" "$url" \
            ${extra_args[@]+"${extra_args[@]}"} \
            -o "$body_file" -w '%{http_code}' 2>/dev/null)"
        if [ -z "$RESP_CODE" ]; then
            RESP_CODE="000"
        fi
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
# 只查其一都会漏：只查 body 漏掉状态码回归；只查 HTTP 200 会把
# GlobalExceptionHandler 兜底的「HTTP 200 + code 500」软失败当成成功放过去。
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
# T03d 之后二者应当恒等；刻意不做"二选一"的宽松匹配，
# 一旦 HTTP 与 body 不一致就说明状态码对齐出现回归，必须红。
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

# 断言"软失败"：HTTP 200 + body.success=false + body.code=500。
#
# 这是 T02 遗留的兼容契约：GlobalExceptionHandler 对单参 BusinessException
# 一律返回 HTTP 200 + code 500。「商品库存不足」「订单状态不正确」
# 「只能取消待付款的订单」都走这一支。
#
# 断言现状而不是断言"应该是 409"，是为了不制造假红——
# 这几条的语义残留已在报告的缺陷清单里单独列出，等源码侧对齐后再改本断言。
assert_soft_fail() {
    local name="$1"
    local expect_msg="${2:-}"
    local success code msg
    success="$(json_get "$RESP_BODY" success)"
    code="$(json_get "$RESP_BODY" code)"
    msg="$(json_get "$RESP_BODY" message)"
    if [ "$RESP_CODE" != "200" ]; then
        fail_case "$name" "期望软失败 HTTP 200，实际 HTTP ${RESP_CODE}"
        return
    fi
    if [ "$success" = "True" ]; then
        fail_case "$name" "期望 body.success=false（业务被拒），实际为 true —— 业务校验没生效"
        return
    fi
    if [ "$code" != "500" ]; then
        fail_case "$name" "期望 body.code=500（T02 软失败契约），实际 code=${code}"
        return
    fi
    if [ -n "$expect_msg" ]; then
        case "$msg" in
            *"$expect_msg"*) ;;
            *)
                fail_case "$name" "message 未包含期望关键字「${expect_msg}」，实际「${msg}」"
                return
                ;;
        esac
    fi
    pass_case "$name"
}

# 断言两个标量相等（用于库存快照比对等纯数值校验，不涉及 HTTP）。
assert_eq() {
    local name="$1"
    local expected="$2"
    local actual="$3"
    local hint="${4:-}"
    if [ "$expected" = "$actual" ]; then
        pass_case "$name"
    else
        echo -e "${RED}[FAIL]${NC} $name"
        echo "       原因: 期望 ${expected}，实际 ${actual}"
        if [ -n "$hint" ]; then
            echo "       说明: $hint"
        fi
        FAIL=$((FAIL + 1))
    fi
}

###############################################################################
# 领域工具
###############################################################################

# 读商品库存。走网关，因为直连 product 的 GET 会被 GatewaySignatureInterceptor
# 拦成 401「非法请求来源」。失败时返回空串，调用方需自行判空。
get_stock() {
    local product_id="$1"
    http_call GET "$GATEWAY/api/product/$product_id"
    json_get "$RESP_BODY" data.stock
}

# 直连回滚库存（不经网关）。
# 返回 0 表示回滚成功，非 0 表示失败（调用方应把它当成环境污染事故来报）。
restore_stock_direct() {
    local product_id="$1"
    local quantity="$2"
    if [ -z "${INTERNAL_TOKEN:-}" ]; then
        return 1
    fi
    if [ "$quantity" -le 0 ] 2>/dev/null; then
        return 0
    fi
    http_call PUT "$PRODUCT_DIRECT/product/$product_id/restore?quantity=$quantity" \
        -H "X-Internal-Token: $INTERNAL_TOKEN"
    if [ "$RESP_CODE" = "200" ] && [ "$(json_get "$RESP_BODY" success)" = "True" ]; then
        return 0
    fi
    return 1
}

# 注册并登录一个全新买家，回显 "token userId"。
# 每次用时间戳+$$+RANDOM 造唯一用户名，避免并发/连跑撞唯一索引。
new_buyer() {
    local tag="${1:-qa}"
    local uniq="$(date +%s)$$${RANDOM}"
    local uname="${tag}_${uniq}"
    # phone 是 11 位且有唯一约束，用 13 + 9 位数字尾巴拼
    local phone="13$(printf '%s' "$uniq" | tail -c 9)"
    http_call POST "$GATEWAY/api/user/register" \
        -H "Content-Type: application/json" \
        -d "{\"username\":\"$uname\",\"password\":\"Test@1234\",\"phone\":\"$phone\",\"email\":\"${uname}@test.com\",\"userType\":1}"
    if [ "$RESP_CODE" != "200" ]; then
        echo ""
        return 1
    fi
    http_call POST "$GATEWAY/api/user/login" \
        -H "Content-Type: application/json" \
        -d "{\"username\":\"$uname\",\"password\":\"Test@1234\"}"
    local tk uid
    tk="$(json_get "$RESP_BODY" data.token)"
    uid="$(json_get "$RESP_BODY" data.userId)"
    if [ -z "$tk" ]; then
        echo ""
        return 1
    fi
    echo "$tk $uid"
    return 0
}

# 登录已有账号，回显 "token userId"。
login_as() {
    local uname="$1"
    local pwd="$2"
    http_call POST "$GATEWAY/api/user/login" \
        -H "Content-Type: application/json" \
        -d "{\"username\":\"$uname\",\"password\":\"$pwd\"}"
    local tk uid
    tk="$(json_get "$RESP_BODY" data.token)"
    uid="$(json_get "$RESP_BODY" data.userId)"
    if [ -z "$tk" ]; then
        echo ""
        return 1
    fi
    echo "$tk $uid"
    return 0
}

# 为指定 token 创建一个收货地址，回显 addressId。
create_address() {
    local token="$1"
    local label="${2:-QA测试}"
    http_call POST "$GATEWAY/api/address" \
        -H "Content-Type: application/json" \
        -H "Authorization: Bearer $token" \
        -d "{\"receiverName\":\"$label\",\"receiverPhone\":\"13800000001\",\"province\":\"北京市\",\"city\":\"北京市\",\"district\":\"海淀区\",\"detailAddress\":\"中关村大街1号\",\"isDefault\":1}"
    json_get "$RESP_BODY" data.id
}

# 网关连通性预检。不通直接 exit 2，避免后续断言全部变成无意义的假 FAIL。
preflight() {
    http_call GET "$GATEWAY/actuator/health"
    if [ "$RESP_CODE" != "200" ]; then
        echo -e "${RED}[ABORT]${NC} 网关 $GATEWAY 不可达（/actuator/health 返回 ${RESP_CODE}）"
        echo "  已自动重试一次仍失败。注意：本库已 unset 代理变量，此处失败不是 HTTP_PROXY 造成的。"
        exit 2
    fi
    http_call GET "$PRODUCT_DIRECT/actuator/health"
    if [ "$RESP_CODE" != "200" ]; then
        echo -e "${RED}[ABORT]${NC} product 服务 $PRODUCT_DIRECT 不可达（返回 ${RESP_CODE}）"
        echo "  库存回滚依赖直连该服务，不可达则测试会污染环境，故直接中止。"
        exit 2
    fi
    echo -e "${GREEN}[OK]${NC} 预检通过（网关 ${GATEWAY} / product ${PRODUCT_DIRECT}）"
}

# 统一收尾输出。
summary() {
    local title="$1"
    local total=$((PASS + FAIL))
    echo ""
    echo "=========================================="
    echo -e "  ${title}: ${GREEN}通过 $PASS${NC} / ${RED}失败 $FAIL${NC} / 共 $total"
    echo "=========================================="
}
