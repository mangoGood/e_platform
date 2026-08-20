#!/bin/bash
###############################################################################
# 电商平台安全回归测试脚本
#
# 覆盖 12 项安全断言：
#   1  未登录访问受保护接口                    → 401 + 中文提示
#   2  越权访问他人订单详情                    → 403
#   3  越权修改他人名下商品（改价/下架）        → 403
#   4  JWT 签名被篡改                          → 401
#   5  JWT 结构非法（abc.def.ghi）             → 401 且不得 500
#   6  登出后 access token 立即失效（黑名单）   → 登出前 200 / 登出后 401
#   7  伪造 X-User-Id 冒充 admin 提权          → 仍 403
#   8  伪造 X-Internal-Token 经网关提权         → 仍 403
#   9  绕过网关直连内部端口                     → 401/403
#   10 SQL 注入（商品搜索）                     → 不 500 / 不返回全表 / 不回显 SQL 错误
#   11 响应体不回传密码字段                     → 无密码值泄露
#   12 refresh token 轮换防重放                 → 第一次成功 / 第二次失败
#
# 用法：bash tests/security_test.sh
#
# 环境陷阱规避（血泪教训，勿删）：
#   - 本机可能注入 HTTP_PROXY，curl 不加 --noproxy 会被代理拦成 502 空 body，
#     看起来像"服务全挂"。脚本开头统一 unset 代理变量，且每次 curl 都带 --noproxy '*'。
#   - zsh 会把 URL 里的 ? 当 glob 吃掉导致命令静默不执行。本脚本用 bash 执行，
#     且所有含 ? 的 URL 一律加引号。
#   - 判服务存活只信 curl /actuator/health；lsof/ps 在沙箱里会假阴性。
#   - 兼容 macOS 自带 bash 3.2，不使用关联数组等 bash 4+ 特性。
###############################################################################
set -u

# ---- 彻底摘掉代理，避免 502 空 body 假象 ----
unset HTTP_PROXY HTTPS_PROXY http_proxy https_proxy ALL_PROXY all_proxy

GATEWAY="http://localhost:8088"
USER_SVC_DIRECT="http://localhost:8085"
TIMEOUT=15

PASS=0
FAIL=0
TOTAL_EXPECTED=12

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
    body_file="$(mktemp -t sec_resp.XXXXXX)"

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

# 从 JSON 中取字段，支持点路径：json_get "$RESP_BODY" data.token
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

# 判断字符串是否含中文字符
has_chinese() {
    printf '%s' "$1" | python3 -c "
import sys
s = sys.stdin.read()
print('yes' if any('\u4e00' <= c <= '\u9fff' for c in s) else 'no')
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

# 断言 HTTP 状态码等于期望值
assert_status() {
    local name="$1"
    local expected="$2"
    if [ "$RESP_CODE" = "$expected" ]; then
        pass_case "$name"
    else
        fail_case "$name" "期望 HTTP ${expected}，实际 HTTP ${RESP_CODE}"
    fi
}

# 断言 HTTP 状态码属于给定集合（空格分隔）
assert_status_in() {
    local name="$1"
    local expected_set="$2"
    local one
    for one in $expected_set; do
        if [ "$RESP_CODE" = "$one" ]; then
            pass_case "$name"
            return
        fi
    done
    fail_case "$name" "期望 HTTP 属于 [$expected_set]，实际 HTTP ${RESP_CODE}"
}

###############################################################################
# 预检：网关连通性 + 测试账号登录
###############################################################################
echo "=========================================="
echo "  电商平台安全回归测试（12 项）"
echo "=========================================="
echo ""
echo -e "${BLUE}--- 预检 ---${NC}"

http_call GET "$GATEWAY/actuator/health"
if [ "$RESP_CODE" != "200" ]; then
    echo -e "${RED}[ABORT]${NC} 网关 $GATEWAY 不可达（/actuator/health 返回 ${RESP_CODE}）"
    echo ""
    echo "  已自动重试一次仍失败。请先确认网关进程存活后再跑本脚本，"
    echo "  否则 12 项断言会全部变成无意义的假 FAIL。"
    echo ""
    echo "  排查建议："
    echo "    1) 查看网关日志: tail -50 logs/e-platform-gateway.log"
    echo "    2) 确认端口占用: curl --noproxy '*' -i http://localhost:8088/actuator/health"
    echo "    3) 其余服务健康检查: 8085/8086/8087/8089 的 /actuator/health"
    echo "  注意：本脚本已 unset 代理变量，此处失败不是 HTTP_PROXY 造成的。"
    exit 2
fi
echo -e "${GREEN}[OK]${NC} 网关健康检查通过 ($GATEWAY)"

# 登录取 token
login() {
    local username="$1"
    http_call POST "$GATEWAY/api/user/login" \
        -H "Content-Type: application/json" \
        -d "{\"username\":\"$username\",\"password\":\"123456\"}"
}

login "buyer1"
BUYER1_LOGIN_RAW="$RESP_BODY"
BUYER1_TOKEN="$(json_get "$RESP_BODY" data.token)"
if [ -z "$BUYER1_TOKEN" ]; then
    echo -e "${RED}[ABORT]${NC} buyer1 登录失败，无法继续（HTTP ${RESP_CODE}）"
    echo "       响应: $RESP_BODY"
    exit 2
fi
echo -e "${GREEN}[OK]${NC} buyer1 登录成功"

login "seller2"
SELLER2_TOKEN="$(json_get "$RESP_BODY" data.token)"
if [ -z "$SELLER2_TOKEN" ]; then
    echo -e "${RED}[ABORT]${NC} seller2 登录失败，无法继续（HTTP ${RESP_CODE}）"
    echo "       响应: $RESP_BODY"
    exit 2
fi
echo -e "${GREEN}[OK]${NC} seller2 登录成功"

# ---- 发现越权测试用的数据夹具 ----
# 目标订单：既不属于 buyer1（user_id<>1），也不由 buyer1 售出（seller_id<>1）
CROSS_ORDER_ID="$(docker exec e-platform-mysql mysql -uroot -proot -N -B e_platform \
    -e "SELECT id FROM orders WHERE user_id<>1 AND seller_id<>1 ORDER BY id LIMIT 1;" 2>/dev/null | tr -d '[:space:]')"
if [ -z "$CROSS_ORDER_ID" ]; then
    CROSS_ORDER_ID="2"
    echo -e "${YELLOW}[WARN]${NC} 未能从 DB 探测到跨租户订单，回退使用 orderId=$CROSS_ORDER_ID"
else
    echo -e "${GREEN}[OK]${NC} 越权测试目标订单 orderId=${CROSS_ORDER_ID}（不属于 buyer1）"
fi

# 目标商品：属于 seller1(id=2)，而 seller2(id=4) 名下无商品
VICTIM_PRODUCT_ID="$(docker exec e-platform-mysql mysql -uroot -proot -N -B e_platform \
    -e "SELECT id FROM product WHERE seller_id=2 ORDER BY id LIMIT 1;" 2>/dev/null | tr -d '[:space:]')"
if [ -z "$VICTIM_PRODUCT_ID" ]; then
    VICTIM_PRODUCT_ID="1"
    echo -e "${YELLOW}[WARN]${NC} 未能从 DB 探测到 seller1 的商品，回退使用 productId=$VICTIM_PRODUCT_ID"
else
    echo -e "${GREEN}[OK]${NC} 越权测试目标商品 productId=${VICTIM_PRODUCT_ID}（属于 seller1）"
fi

# 真实内部令牌：用真 token 做伪造尝试，才能证明网关确实在清洗而非"碰巧值不对"
REAL_INTERNAL_TOKEN=""
if [ -f "$(dirname "$0")/../.env" ]; then
    REAL_INTERNAL_TOKEN="$(grep -E '^INTERNAL_TOKEN=' "$(dirname "$0")/../.env" 2>/dev/null | head -1 | cut -d'=' -f2-)"
fi
if [ -z "$REAL_INTERNAL_TOKEN" ]; then
    REAL_INTERNAL_TOKEN="forged-internal-token"
fi

echo ""

###############################################################################
# 1. 未登录访问受保护接口
###############################################################################
echo -e "${BLUE}--- 1. 未登录访问受保护接口 ---${NC}"
# 注意：这里用 /api/order/user 而不是 /api/order/list。
# /api/order/list 端点不存在，会被 GET /order/{orderId} 捕获，
# @PathVariable Long orderId 解析 "list" 失败 → 400 参数类型错误，造成假红。
http_call GET "$GATEWAY/api/order/user"
NAME="1. 未登录访问 GET /api/order/user 返回 401 且提示为中文"
if [ "$RESP_CODE" != "401" ]; then
    fail_case "$NAME" "期望 HTTP 401，实际 HTTP $RESP_CODE"
else
    MSG="$(json_get "$RESP_BODY" message)"
    if [ "$(has_chinese "$MSG")" = "yes" ]; then
        pass_case "$NAME (message=\"$MSG\")"
    else
        fail_case "$NAME" "HTTP 401 正确，但 message 非中文: \"$MSG\""
    fi
fi
echo ""

###############################################################################
# 2. 越权访问他人订单详情
###############################################################################
echo -e "${BLUE}--- 2. 水平越权：订单详情 ---${NC}"
# 说明：种子数据中 seller2(id=4) 名下没有任何订单，因此这里取"既不属于 buyer1
# 也不由 buyer1 售出"的订单作为越权目标，语义等价于访问他人订单。
http_call GET "$GATEWAY/api/order/$CROSS_ORDER_ID" \
    -H "Authorization: Bearer $BUYER1_TOKEN"
assert_status "2. buyer1 访问他人订单详情(orderId=$CROSS_ORDER_ID) 返回 403" "403"
echo ""

###############################################################################
# 3. 越权修改他人名下商品
###############################################################################
echo -e "${BLUE}--- 3. 水平越权：商品改价/下架 ---${NC}"
# 送一份字段完整的合法 body，避免 @Valid 校验失败提前返回 400 把 403 掩盖掉
http_call PUT "$GATEWAY/api/product/$VICTIM_PRODUCT_ID" \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer $SELLER2_TOKEN" \
    -d '{"categoryId":1,"name":"越权改价测试","description":"security regression","price":0.01,"originalPrice":0.01,"stock":1,"mainImage":"","images":""}'
assert_status "3. seller2 修改 seller1 名下商品(productId=$VICTIM_PRODUCT_ID) 返回 403" "403"
echo ""

###############################################################################
# 4. JWT 签名被篡改
###############################################################################
echo -e "${BLUE}--- 4. JWT 签名篡改 ---${NC}"
# 把签名段（第三段）尾部几个字符换掉，构造验签失败的 token
TAMPERED_TOKEN="$(printf '%s' "$BUYER1_TOKEN" | python3 -c "
import sys
t = sys.stdin.read().strip()
parts = t.split('.')
if len(parts) == 3 and len(parts[2]) > 6:
    sig = parts[2]
    # 替换末尾 6 个字符，确保与原签名不同
    tail = ''.join('A' if c != 'A' else 'B' for c in sig[-6:])
    parts[2] = sig[:-6] + tail
print('.'.join(parts))
" 2>/dev/null)"
http_call GET "$GATEWAY/api/order/user" \
    -H "Authorization: Bearer $TAMPERED_TOKEN"
assert_status "4. 签名被篡改的 JWT 返回 401" "401"
echo ""

###############################################################################
# 5. JWT 结构非法
###############################################################################
echo -e "${BLUE}--- 5. JWT 结构非法 ---${NC}"
http_call GET "$GATEWAY/api/order/user" \
    -H "Authorization: Bearer abc.def.ghi"
NAME="5. 结构非法的 JWT(abc.def.ghi) 返回 401 且不得 500"
if [ "$RESP_CODE" = "401" ]; then
    pass_case "$NAME"
elif [ "$RESP_CODE" = "500" ]; then
    fail_case "$NAME" "返回 500，畸形 token 击穿到未捕获异常（严重）"
else
    fail_case "$NAME" "期望 HTTP 401，实际 HTTP $RESP_CODE"
fi
echo ""

###############################################################################
# 6. 登出后 access token 立即失效
###############################################################################
echo -e "${BLUE}--- 6. 登出黑名单 ---${NC}"
# 用一个独立会话做登出，避免污染主 buyer1 token
login "buyer1"
LOGOUT_TOKEN="$(json_get "$RESP_BODY" data.token)"
if [ -z "$LOGOUT_TOKEN" ]; then
    fail_case "6. 登出后原 access token 立即失效" "前置条件失败：无法取得用于登出的 token"
else
    # 6a 登出前应可用
    http_call GET "$GATEWAY/api/order/user" -H "Authorization: Bearer $LOGOUT_TOKEN"
    BEFORE_CODE="$RESP_CODE"
    BEFORE_BODY="$RESP_BODY"

    # 执行登出
    http_call POST "$GATEWAY/api/user/logout" -H "Authorization: Bearer $LOGOUT_TOKEN"
    LOGOUT_CODE="$RESP_CODE"
    LOGOUT_BODY="$RESP_BODY"

    # 6b 登出后应失效
    http_call GET "$GATEWAY/api/order/user" -H "Authorization: Bearer $LOGOUT_TOKEN"
    AFTER_CODE="$RESP_CODE"

    NAME="6. 登出后原 access token 立即失效（登出前 200 / 登出后 401）"
    if [ "$BEFORE_CODE" = "200" ] && [ "$AFTER_CODE" = "401" ]; then
        pass_case "$NAME"
    else
        fail_case "$NAME" "登出前=$BEFORE_CODE(期望200), 登出接口=$LOGOUT_CODE, 登出后=$AFTER_CODE(期望401)"
        echo "       登出前响应: $(printf '%s' "$BEFORE_BODY" | cut -c1-200)"
        echo "       登出接口响应: $(printf '%s' "$LOGOUT_BODY" | cut -c1-200)"
    fi
fi
echo ""

###############################################################################
# 7. 伪造 X-User-Id 冒充 admin
###############################################################################
echo -e "${BLUE}--- 7. 伪造身份头提权 ---${NC}"
# 选择 POST /api/product/add 作为提权靶点：网关规则要求 product:write，
# ROLE_BUYER 不持有该权限码，而 ROLE_ADMIN 持有。
# 若 X-User-Id / X-User-Roles 未被清洗，就会从 403 变成放行。
# 不用 /api/admin/**：该路径在 RoutePermissionRegistry 里有规则但 GatewayController
# 没有对应路由，Spring 会先返回 404，断言 403 会得到"测试写法造成的假红"。
http_call POST "$GATEWAY/api/product/add" \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer $BUYER1_TOKEN" \
    -H "X-User-Id: 3" \
    -H "X-User-Type: 1" \
    -H "X-User-Roles: ROLE_ADMIN" \
    -d '{"categoryId":1,"name":"伪造头提权测试","description":"security regression","price":1.00,"originalPrice":1.00,"stock":1,"mainImage":"","images":""}'
assert_status "7. buyer1 携伪造 X-User-Id:3 / X-User-Roles:ROLE_ADMIN 仍被拒 403" "403"
echo ""

###############################################################################
# 8. 伪造 X-Internal-Token 经网关提权
###############################################################################
echo -e "${BLUE}--- 8. 伪造内部令牌头 ---${NC}"
# 靶点是内部库存接口 PUT /product/{id}/deduct：
# 直连 product 服务时它只认 X-Internal-Token；经网关时该头必须被剥离，
# 且网关的 INTERNAL_DENY 规则会先行 403。这里刻意携带**真实**的内部令牌，
# 如果仍是 403，才能证明网关确实在清洗，而不是"碰巧令牌值不对"。
http_call PUT "$GATEWAY/api/product/$VICTIM_PRODUCT_ID/deduct?quantity=1" \
    -H "Authorization: Bearer $BUYER1_TOKEN" \
    -H "X-Internal-Token: $REAL_INTERNAL_TOKEN"
assert_status "8. 携真实 X-Internal-Token 经网关调用内部库存接口仍被拒 403" "403"
echo ""

###############################################################################
# 9. 绕过网关直连内部端口
###############################################################################
echo -e "${BLUE}--- 9. 绕过网关直连内部服务 ---${NC}"
# 直连内部服务时路径没有 /api 前缀
http_call GET "$USER_SVC_DIRECT/user/info/1"
assert_status_in "9. 直连 user 服务 :8085/user/info/1 被拒（401/403）" "401 403"
echo ""

###############################################################################
# 10. SQL 注入
###############################################################################
echo -e "${BLUE}--- 10. SQL 注入 ---${NC}"
# 基线：不带 keyword 时的总数
http_call GET "$GATEWAY/api/product/list?pageNum=1&pageSize=5"
BASELINE_TOTAL="$(json_get "$RESP_BODY" data.total)"
if [ -z "$BASELINE_TOTAL" ]; then
    BASELINE_TOTAL="-1"
fi

INJ_OK=1
INJ_REASON=""

# 逐个注入载荷
run_injection() {
    local label="$1"
    local encoded="$2"
    http_call GET "$GATEWAY/api/product/list?pageNum=1&pageSize=5&keyword=$encoded"
    local code="$RESP_CODE"
    local body="$RESP_BODY"
    local total
    total="$(json_get "$body" data.total)"

    # a) 不能 500
    if [ "$code" = "500" ]; then
        INJ_OK=0
        INJ_REASON="$INJ_REASON [$label 返回 500]"
        return
    fi
    # b) 不能回显 SQL 错误细节
    if printf '%s' "$body" | grep -qiE "SQLSyntaxError|SQLException|MySQLSyntaxErrorException|BadSqlGrammar|You have an error in your SQL syntax|JdbcSQLException"; then
        INJ_OK=0
        INJ_REASON="$INJ_REASON [$label 回显 SQL 错误]"
        return
    fi
    # c) 不能返回全表（注入成功的标志）
    if [ "$BASELINE_TOTAL" != "-1" ] && [ -n "$total" ] && [ "$total" = "$BASELINE_TOTAL" ] && [ "$BASELINE_TOTAL" != "0" ]; then
        INJ_OK=0
        INJ_REASON="$INJ_REASON [$label 返回全表 total=$total 与基线 $BASELINE_TOTAL 相同]"
        return
    fi
}

run_injection "' OR '1'='1" "%27%20OR%20%271%27%3D%271"
run_injection "1; DROP TABLE t--" "1%3B%20DROP%20TABLE%20t--"
run_injection "\" UNION SELECT" "%22%20UNION%20SELECT%20username%2Cpassword%20FROM%20user--"

# d) 表必须还在
TABLE_STILL_THERE="$(docker exec e-platform-mysql mysql -uroot -proot -N -B e_platform \
    -e "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='e_platform' AND table_name='product';" 2>/dev/null | tr -d '[:space:]')"
if [ "$TABLE_STILL_THERE" != "1" ]; then
    INJ_OK=0
    INJ_REASON="$INJ_REASON [product 表在注入后消失]"
fi

NAME="10. SQL 注入无效（不 500 / 不返回全表 / 不回显 SQL 错误 / 表未被删）"
if [ "$INJ_OK" = "1" ]; then
    pass_case "$NAME (基线 total=$BASELINE_TOTAL)"
else
    fail_case "$NAME" "$INJ_REASON"
fi
echo ""

###############################################################################
# 11. 响应体不回传密码字段
###############################################################################
echo -e "${BLUE}--- 11. 密码字段不外泄 ---${NC}"
# 检测口径分两层：
#   硬失败 = 真正泄露了凭证材料（password/salt 带非空值，或出现 BCrypt 串）
#   告警   = 仅出现 "password": null 这种空壳字段（无凭证泄露，但属于序列化卫生问题）
check_secret_leak() {
    printf '%s' "$1" | python3 -c "
import sys, re
raw = sys.stdin.read()
# 任何 BCrypt 哈希直接判定为泄露
if re.search(r'\\\$2[aby]\\\$\d{2}\\\$', raw):
    print('LEAK:检测到 BCrypt 哈希串')
    sys.exit(0)
# password / salt 字段带非空值
m = re.search(r'\"(password|salt|passwd|pwd)\"\s*:\s*(\"[^\"]+\"|[^,}\s\"][^,}]*)', raw, re.I)
if m and m.group(2).strip().lower() not in ('null', '\"\"'):
    print('LEAK:字段 %s 带值 %s' % (m.group(1), m.group(2)[:40]))
    sys.exit(0)
# 仅空壳字段
if re.search(r'\"(password|salt|passwd|pwd)\"\s*:\s*null', raw, re.I):
    print('EMPTY')
    sys.exit(0)
print('CLEAN')
" 2>/dev/null
}

LEAK_FOUND=0
LEAK_DETAIL=""
WARN_EMPTY=""

# 11a 登录响应
LOGIN_CHECK="$(check_secret_leak "$BUYER1_LOGIN_RAW")"
case "$LOGIN_CHECK" in
    LEAK:*) LEAK_FOUND=1; LEAK_DETAIL="$LEAK_DETAIL [登录响应 ${LOGIN_CHECK#LEAK:}]" ;;
    EMPTY)  WARN_EMPTY="$WARN_EMPTY [登录响应含空壳 password 字段]" ;;
esac

# 11b 用户信息接口
http_call GET "$GATEWAY/api/user/info/1" -H "Authorization: Bearer $BUYER1_TOKEN"
INFO_CODE="$RESP_CODE"
INFO_BODY="$RESP_BODY"
INFO_CHECK="$(check_secret_leak "$INFO_BODY")"
case "$INFO_CHECK" in
    LEAK:*) LEAK_FOUND=1; LEAK_DETAIL="$LEAK_DETAIL [用户信息接口 ${INFO_CHECK#LEAK:}]" ;;
    EMPTY)  WARN_EMPTY="$WARN_EMPTY [用户信息接口含空壳 password 字段]" ;;
esac

NAME="11. 登录响应与用户信息接口不回传密码/盐值"
if [ "$LEAK_FOUND" = "0" ]; then
    pass_case "$NAME"
    if [ -n "$WARN_EMPTY" ]; then
        echo -e "       ${YELLOW}[WARN]${NC} 未泄露凭证值，但存在空壳字段：$WARN_EMPTY"
        echo "       建议：为 User.password 加 @JsonIgnore，避免字段名出现在响应中"
    fi
else
    RESP_CODE="$INFO_CODE"
    RESP_BODY="$INFO_BODY"
    fail_case "$NAME" "$LEAK_DETAIL"
fi
echo ""

###############################################################################
# 12. refresh token 轮换防重放
###############################################################################
echo -e "${BLUE}--- 12. refresh token 轮换防重放 ---${NC}"
# 契约（已核对 UserController / RefreshTokenRequest / TokenPair）：
#   请求 POST /api/user/refresh  body {"refreshToken":"..."}
#   响应 data: {token, refreshToken, expiresIn}
login "buyer1"
REFRESH_TOKEN="$(json_get "$RESP_BODY" data.refreshToken)"

NAME="12. 同一 refresh token 刷新两次：第一次成功，第二次失败"
if [ -z "$REFRESH_TOKEN" ]; then
    fail_case "$NAME" "前置条件失败：登录响应里没有 data.refreshToken"
else
    http_call POST "$GATEWAY/api/user/refresh" \
        -H "Content-Type: application/json" \
        -d "{\"refreshToken\":\"$REFRESH_TOKEN\"}"
    FIRST_CODE="$RESP_CODE"
    FIRST_BODY="$RESP_BODY"
    FIRST_NEW_TOKEN="$(json_get "$FIRST_BODY" data.token)"

    http_call POST "$GATEWAY/api/user/refresh" \
        -H "Content-Type: application/json" \
        -d "{\"refreshToken\":\"$REFRESH_TOKEN\"}"
    SECOND_CODE="$RESP_CODE"

    if [ "$FIRST_CODE" = "200" ] && [ -n "$FIRST_NEW_TOKEN" ] && [ "$SECOND_CODE" = "401" ]; then
        pass_case "$NAME"
    else
        fail_case "$NAME" "第一次=$FIRST_CODE(期望200,且应下发新token), 第二次=$SECOND_CODE(期望401)"
        echo "       第一次响应: $(printf '%s' "$FIRST_BODY" | cut -c1-200)"
    fi
fi
echo ""

###############################################################################
# 汇总
###############################################################################
echo "=========================================="
echo -e "  安全测试结果: ${GREEN}通过 $PASS${NC} / ${RED}失败 $FAIL${NC} / 共 $TOTAL_EXPECTED"
echo "=========================================="
if [ "$FAIL" -gt 0 ]; then
    exit 1
fi
exit 0
