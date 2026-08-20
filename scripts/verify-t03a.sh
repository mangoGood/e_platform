#!/usr/bin/env bash
# =============================================================================
# T03a 验收脚本：三级评论 + 订单状态流转 + product 存量 Bug 修复
#
# 全部断言基于「真实 HTTP 状态码」（curl -w '%{http_code}'），而不是响应体里的 code 字段。
# 这正是本轮修复的重点之一：原先 Result.error(403,...) 让 HTTP 恒为 200，越权测试形同虚设。
#
# 用法： bash scripts/verify-t03a.sh
# 前置： docker 中的 mysql/redis 已就绪，5 个后端服务已启动，migration-v2/v3 已执行
# 特性： 可重复执行（每轮开始清理测试商品下的评论数据）
#
# 【踩坑记录】所有 JSON 请求体必须先赋值给变量再传参。
# 直接写成 "$(http POST /x "$TOKEN" "{\"a\":1,\"b\":2}")" 时，
# bash 会对命令替换内的 {a,b} 做花括号展开，把一个 JSON 拆成多个参数、发出多次请求。
# =============================================================================
set -uo pipefail

GW="${GW:-http://localhost:8088/api}"
PRODUCT_SVC="${PRODUCT_SVC:-http://localhost:8086}"
BODY_FILE=/tmp/t03a_body.json

PASS=0
FAIL=0
declare -a FAILED=()

c_ok()  { printf '\033[32m%s\033[0m' "$1"; }
c_bad() { printf '\033[31m%s\033[0m' "$1"; }

sql()  { docker exec -i e-platform-mysql mysql -uroot -proot --skip-column-names -B e_platform -e "$1" 2>/dev/null; }
sql1() { sql "$1" | tr '\t' '|' | head -1; }

assert() {
    local id="$1" desc="$2" expect="$3" actual="$4"
    if [ "$expect" = "$actual" ]; then
        PASS=$((PASS + 1))
        printf '  [%-4s] %-50s 期望=%-12s 实际=%-12s %s\n' "$id" "$desc" "$expect" "$actual" "$(c_ok PASS)"
    else
        FAIL=$((FAIL + 1))
        FAILED+=("[$id] $desc  期望=$expect 实际=$actual")
        printf '  [%-4s] %-50s 期望=%-12s 实际=%-12s %s\n' "$id" "$desc" "$expect" "$actual" "$(c_bad FAIL)"
    fi
}

# http <方法> <路径> [token] [body] -> 回显 HTTP 状态码；响应体写入 $BODY_FILE
http() {
    local method="$1" path="$2" token="${3:-}" body="${4:-}"
    local args=(-s -o "$BODY_FILE" -w '%{http_code}' -X "$method" "${GW}${path}")
    [ -n "$token" ] && args+=(-H "Authorization: Bearer ${token}")
    [ -n "$body" ] && args+=(-H 'Content-Type: application/json' -d "$body")
    curl "${args[@]}"
}

# jq <python 表达式，以 d 为根>；表达式经 argv 传入，规避 shell 引号吞噬
jq() { python3 -c 'import sys,json
try:
    d=json.load(open(sys.argv[1]))
    v=eval(sys.argv[2])
    print("" if v is None else v)
except Exception as e:
    print("ERR:%s" % e)' "$BODY_FILE" "$1"; }

login() {
    local j="{\"username\":\"$1\",\"password\":\"$2\"}"
    curl -s -X POST "${GW}/user/login" -H 'Content-Type: application/json' -d "$j" -o "$BODY_FILE" >/dev/null
    jq "d['data']['token']"
}

echo "============================================================================="
echo " T03a 验收 · $(date '+%Y-%m-%d %H:%M:%S')"
echo "============================================================================="

echo
echo "[准备] 登录与建号"
BUYER=$(login buyer1 123456)
SELLER=$(login seller1 123456)
ADMIN=$(login admin 123456)
REG='{"username":"seller2","password":"123456","email":"seller2@example.com","phone":"13900139002","userType":2}'
curl -s -X POST "${GW}/user/register" -H 'Content-Type: application/json' -d "$REG" >/dev/null
SELLER2=$(login seller2 123456)
for n in BUYER SELLER ADMIN SELLER2; do
    v="${!n}"
    if [ -z "$v" ] || [ "${v:0:3}" = "ERR" ]; then echo "  !! ${n} 登录失败，终止"; exit 1; fi
    echo "  ${n} token ok"
done

PRODUCT_ID=1        # iPhone 15 Pro，seller_id=2
SELLER_ID=2
BUYER_ID=1          # buyer1
REAL_TOKEN=$(grep -E '^INTERNAL_TOKEN=' .env | cut -d= -f2-)

# --- 复位测试数据，保证脚本可重复执行 ---------------------------------------
# 1) 清掉测试商品下的全部评论
sql "DELETE FROM comment WHERE product_id=${PRODUCT_ID};" >/dev/null
# 2) 评分归零
sql "UPDATE product SET rating_avg=0.00, rating_count=0 WHERE id=${PRODUCT_ID};" >/dev/null
# 3) 把 buyer1 名下含该商品的历史订单统一置为「已取消」。
#    不置位的话，上一轮跑完留下的「已完成」订单会让本轮的"未购买不能评价"用例直接通过购买校验。
sql "UPDATE orders o JOIN order_item oi ON oi.order_id=o.id SET o.status=4 WHERE o.user_id=${BUYER_ID} AND oi.product_id=${PRODUCT_ID};" >/dev/null

echo
echo "----- Bug-1 分页失效 --------------------------------------------------------"
CODE=$(http GET "/product/list?pageNum=1&pageSize=3")
CNT=$(jq "len(d['data']['records'])")
TOT=$(jq "d['data']['total']")
IDS1=$(jq "[r['id'] for r in d['data']['records']]")
assert "1a" "GET /product/list?pageNum=1&pageSize=3 状态码" "200" "$CODE"
assert "1b" "返回条数严格等于 pageSize" "3" "$CNT"
assert "1c" "total 不再恒为 0" "true" "$([ "${TOT:-0}" -gt 0 ] 2>/dev/null && echo true || echo false)"
http GET "/product/list?pageNum=2&pageSize=3" >/dev/null
IDS2=$(jq "[r['id'] for r in d['data']['records']]")
assert "1d" "第 2 页数据与第 1 页不同（真实翻页）" "true" "$([ "$IDS1" != "$IDS2" ] && echo true || echo false)"
http GET "/product/list?current=1&size=2" >/dev/null
assert "1e" "旧参数名 current/size 仍兼容" "2" "$(jq "len(d['data']['records'])")"
echo "       └ total=${TOT}  第1页ids=${IDS1}  第2页ids=${IDS2}"

echo
echo "----- Bug-2 越权响应码不真实 ------------------------------------------------"
ADD_PRODUCT='{"categoryId":1,"name":"越权测试商品","price":9.9,"stock":1}'
assert "2a" "buyer1 POST /product/add 真实 HTTP 403" "403" "$(http POST "/product/add" "$BUYER" "$ADD_PRODUCT")"
assert "2b" "admin(非卖家) POST /product/add 真实 HTTP" "403" "$(http POST "/product/add" "$ADMIN" "$ADD_PRODUCT")"
# 只统计真正的代码行：排除 target、排除以 * 或 // 开头的注释行（Javadoc 里写了"禁止这么做"）
GREP_CNT=$(grep -rn 'Result\.error(403' backend --include='*.java' 2>/dev/null \
           | grep -v '/target/' | grep -vE ':[0-9]+: *(\*|//|/\*)' | wc -l | tr -d ' ')
assert "2c" "源码中不再有 Result.error(403 的实际调用" "0" "$GREP_CNT"

echo
echo "----- Bug-3 内部令牌硬编码弱口令 --------------------------------------------"
assert "3a" "硬编码弱口令直连 8086 被拒" "401" \
    "$(curl -s -o /dev/null -w '%{http_code}' -X PUT "${PRODUCT_SVC}/product/${PRODUCT_ID}/restore?quantity=0" -H 'X-Internal-Token: ePlatformInternalSecret2026')"
assert "3b" "无令牌直连 8086 被拒" "401" \
    "$(curl -s -o /dev/null -w '%{http_code}' -X PUT "${PRODUCT_SVC}/product/${PRODUCT_ID}/restore?quantity=0")"
assert "3c" "经网关访问内部接口被拒（INTERNAL_DENY）" "403" "$(http PUT "/product/${PRODUCT_ID}/deduct?quantity=0" "$SELLER")"
assert "3d" "配置里的真实令牌可正常通行" "200" \
    "$(curl -s -o /dev/null -w '%{http_code}' -X PUT "${PRODUCT_SVC}/product/${PRODUCT_ID}/restore?quantity=0" -H "X-Internal-Token: ${REAL_TOKEN}")"
CFG_CNT=$(grep -rEn 'internal\.token:[^}]*ePlatform|INTERNAL_TOKEN:[^}]*ePlatform' backend --include='*.java' --include='*.yml' 2>/dev/null \
          | grep -v '/target/' | grep -vE '^\S+:[0-9]+: *\*' | wc -l | tr -d ' ')
assert "3e" "配置项中不再有弱口令默认值（注释除外）" "0" "$CFG_CNT"

echo
echo "----- 评价资格：未购买 / 未收货 ---------------------------------------------"
J_L1="{\"productId\":${PRODUCT_ID},\"rating\":5,\"content\":\"没买过也想评价试试看\"}"
assert "4a" "未购买发表 L1" "403" "$(http POST "/comment" "$BUYER" "$J_L1")"
echo "       └ 文案：$(jq "d['message']")"

# ---------------------------------------------------------------------------
# 建单夹具：优先走真实下单接口；被网关秒杀排队拦截（HTTP 202）时退化为直接播种。
#
# 背景：T03b 的网关排队机制上线后，POST /order/create 返回 202 + queueToken，
# 且当前队列不出队（position 恒定、重投 token 另发新号），订单永远建不出来。
# 订单创建只是本脚本的「前置夹具」，不属于 T03a 验收项本身，
# 因此这里按 OrderService.createOrder 的等价语义直接播种，
# 保证 T03a 的 82 条断言在排队缺陷修复前仍可独立验证。
# 待排队机制修复后，本分支会自动走回真实接口，无需改脚本。
# ---------------------------------------------------------------------------
J_ORDER="{\"items\":[{\"productId\":${PRODUCT_ID},\"quantity\":1}],\"receiverName\":\"验收\",\"receiverPhone\":\"13800138000\",\"receiverAddress\":\"深圳市南山区\"}"
ORDER_HTTP=$(http POST "/order/create" "$BUYER" "$J_ORDER")
ORDER_ID=$(jq "d['data'][0]['id']")

case "${ORDER_HTTP}/${ORDER_ID}" in
    202/*|*/|*/ERR:*)
        ORDER_SRC="SQL 播种（/order/create 返回 ${ORDER_HTTP}，被网关排队拦截）"
        ORDER_NO="T03A$(date +%s)$$"
        sql "INSERT INTO orders (order_no, user_id, seller_id, total_amount, pay_amount, freight_amount,
                 status, receiver_name, receiver_phone, receiver_address, deleted)
             SELECT '${ORDER_NO}', ${BUYER_ID}, p.seller_id, p.price, p.price, 0,
                    0, '验收', '13800138000', '深圳市南山区', 0
             FROM product p WHERE p.id = ${PRODUCT_ID};"
        ORDER_ID=$(sql1 "SELECT id FROM orders WHERE order_no='${ORDER_NO}'")
        sql "INSERT INTO order_item (order_id, product_id, product_name, product_image, price, quantity, total_amount)
             SELECT ${ORDER_ID}, p.id, p.name, p.image, p.price, 1, p.price
             FROM product p WHERE p.id = ${PRODUCT_ID};"
        ;;
    *)
        ORDER_SRC="真实接口 POST /order/create"
        ;;
esac
echo "       └ 已创建订单 orderId=${ORDER_ID}, 初始 status=0 待付款（来源：${ORDER_SRC}）"

J_L1B="{\"productId\":${PRODUCT_ID},\"rating\":5,\"content\":\"下单了但还没收货就来评价\"}"
assert "4b" "已下单但未收货，发表 L1" "403" "$(http POST "/comment" "$BUYER" "$J_L1B")"
echo "       └ 文案：$(jq "d['message']")"
http GET "/comment/can-review?productId=${PRODUCT_ID}" "$BUYER" >/dev/null
assert "4c" "can-review 与写接口判定一致" "False" "$(jq "d['data']['canReview']")"

echo
echo "----- 订单状态机 ------------------------------------------------------------"
assert "5a" "未发货直接确认收货（禁止 0->3 跳跃）" "409" "$(http POST "/order/${ORDER_ID}/confirm" "$BUYER")"
assert "5b" "买家冒充卖家发货" "403" "$(http POST "/order/${ORDER_ID}/ship" "$BUYER")"
assert "5c" "卖家发货" "200" "$(http POST "/order/${ORDER_ID}/ship" "$SELLER")"
assert "5d" "卖家重复发货" "409" "$(http POST "/order/${ORDER_ID}/ship" "$SELLER")"
assert "5e" "卖家冒充买家确认收货" "403" "$(http POST "/order/${ORDER_ID}/confirm" "$SELLER")"
assert "5f" "买家确认收货" "200" "$(http POST "/order/${ORDER_ID}/confirm" "$BUYER")"
assert "5g" "重复确认收货" "409" "$(http POST "/order/${ORDER_ID}/confirm" "$BUYER")"
assert "5h" "DB 订单状态已流转到已完成" "3" "$(sql1 "SELECT status FROM orders WHERE id=${ORDER_ID}")"

echo
echo "----- L1 买家评价 -----------------------------------------------------------"
J_L1OK="{\"productId\":${PRODUCT_ID},\"rating\":5,\"content\":\"确认收货之后来评价，手感非常好\"}"
assert "6a" "确认收货后发表 L1" "200" "$(http POST "/comment" "$BUYER" "$J_L1OK")"
L1_ID=$(jq "d['data']['id']")
echo "       └ L1 id=${L1_ID}  脱敏昵称=$(jq "d['data']['nickname']")"
assert "6b" "L1 结构 type|parent_id|root_id" "1|0|${L1_ID}" "$(sql1 "SELECT type,parent_id,root_id FROM comment WHERE id=${L1_ID}")"
assert "6c" "L1 rating|order_id 均非空" "5|${ORDER_ID}" \
    "$(sql1 "SELECT IFNULL(rating,'NULL'),IFNULL(order_id,'NULL') FROM comment WHERE id=${L1_ID}")"
assert "6d" "L1 昵称快照已落库" "buyer1" "$(sql1 "SELECT IFNULL(username,'NULL') FROM comment WHERE id=${L1_ID}")"
J_DUP="{\"productId\":${PRODUCT_ID},\"rating\":4,\"content\":\"再评价一次看看能不能过\"}"
assert "6e" "同一订单重复评价" "409" "$(http POST "/comment" "$BUYER" "$J_DUP")"
J_SELF="{\"productId\":${PRODUCT_ID},\"rating\":5,\"content\":\"卖家给自己刷个五星好评\"}"
assert "6f" "卖家评价自己的商品" "403" "$(http POST "/comment" "$SELLER" "$J_SELF")"
J_SHORT="{\"productId\":${PRODUCT_ID},\"rating\":5,\"content\":\"好\"}"
assert "6g" "评价内容不足 5 字" "400" "$(http POST "/comment" "$BUYER" "$J_SHORT")"

echo
echo "----- L2 卖家回复 -----------------------------------------------------------"
J_R1="{\"parentId\":${L1_ID},\"content\":\"感谢您的支持，欢迎再次光临\"}"
assert "7a" "卖家回复 L1" "200" "$(http POST "/comment/reply" "$SELLER" "$J_R1")"
L2_ID=$(jq "d['data']['id']")
J_R2="{\"parentId\":${L1_ID},\"content\":\"再回复一条试试能不能过\"}"
assert "7b" "卖家重复回复同一条 L1" "409" "$(http POST "/comment/reply" "$SELLER" "$J_R2")"
assert "7c" "L2 结构 type|parent_id|root_id" "2|${L1_ID}|${L1_ID}" "$(sql1 "SELECT type,parent_id,root_id FROM comment WHERE id=${L2_ID}")"
assert "7d" "L2 rating|order_id 均为 NULL" "NULL|NULL" \
    "$(sql1 "SELECT IFNULL(rating,'NULL'),IFNULL(order_id,'NULL') FROM comment WHERE id=${L2_ID}")"
J_R3="{\"parentId\":${L1_ID},\"content\":\"我是别家卖家来插一嘴\"}"
assert "7e" "其他店铺卖家回复本店评价（跨店）" "403" "$(http POST "/comment/reply" "$SELLER2" "$J_R3")"
J_R4="{\"parentId\":${L1_ID},\"content\":\"我不是卖家但我想回复\"}"
assert "7f" "买家冒充卖家回复" "403" "$(http POST "/comment/reply" "$BUYER" "$J_R4")"
assert "7g" "逻辑删除后允许重新回复：先删" "200" "$(http DELETE "/comment/${L2_ID}" "$SELLER")"
J_R5="{\"parentId\":${L1_ID},\"content\":\"上一条回复删掉了，重新回复一次\"}"
assert "7h" "逻辑删除后允许重新回复：再回" "200" "$(http POST "/comment/reply" "$SELLER" "$J_R5")"
L2_ID=$(jq "d['data']['id']")

echo
echo "----- L3 第三方追问 ---------------------------------------------------------"
J_A1="{\"commentId\":${L1_ID},\"content\":\"请问续航怎么样？\"}"
assert "8a" "游客追问" "401" "$(http POST "/comment/ask" "" "$J_A1")"
assert "8b" "登录用户对 L1 追问" "200" "$(http POST "/comment/ask" "$ADMIN" "$J_A1")"
L3_ID=$(jq "d['data']['id']")
assert "8c" "L3 结构 type|parent_id|root_id（挂 L1）" "3|${L1_ID}|${L1_ID}" \
    "$(sql1 "SELECT type,parent_id,root_id FROM comment WHERE id=${L3_ID}")"
J_A2="{\"commentId\":${L2_ID},\"content\":\"想问下商家保修政策是怎样的\"}"
assert "8d" "对 L2 追问（目标是卖家回复）" "200" "$(http POST "/comment/ask" "$ADMIN" "$J_A2")"
L3B_ID=$(jq "d['data']['id']")
assert "8e" "对 L2 追问仍挂 L1，不产生第 4 层" "3|${L1_ID}|${L1_ID}" \
    "$(sql1 "SELECT type,parent_id,root_id FROM comment WHERE id=${L3B_ID}")"
assert "8f" "被回复者只落在 reply_to_user_id/username" "${SELLER_ID}|seller1" \
    "$(sql1 "SELECT IFNULL(reply_to_user_id,'NULL'),IFNULL(reply_to_username,'NULL') FROM comment WHERE id=${L3B_ID}")"
J_A3="{\"commentId\":${L3B_ID},\"content\":\"我也想知道保修政策\"}"
http POST "/comment/ask" "$BUYER" "$J_A3" >/dev/null
L3C_ID=$(jq "d['data']['id']")
assert "8g" "对 L3 再追问仍挂 L1（树深恒为 2）" "3|${L1_ID}|${L1_ID}" \
    "$(sql1 "SELECT type,parent_id,root_id FROM comment WHERE id=${L3C_ID}")"
assert "8h" "全表不存在挂在非 L1 之下的评论" "0" \
    "$(sql1 "SELECT COUNT(*) FROM comment WHERE parent_id<>0 AND parent_id NOT IN (SELECT id FROM (SELECT id FROM comment WHERE type=1) t)")"
LONG=$(python3 -c 'print("追"*201)')
J_A4="{\"commentId\":${L1_ID},\"content\":\"${LONG}\"}"
assert "8i" "追问超长（>200 字）" "400" "$(http POST "/comment/ask" "$ADMIN" "$J_A4")"

echo
echo "----- 评论树查询（游客可读 / 脱敏 / viewerContext）--------------------------"
assert "9a" "游客读取评论树" "200" "$(http GET "/comment/product/${PRODUCT_ID}")"
assert "9b" "L1 条数" "1" "$(jq "len(d['data']['comments']['records'])")"
assert "9c" "L1 下挂着唯一的卖家回复" "True" "$(jq "d['data']['comments']['records'][0]['reply'] is not None")"
assert "9d" "追问预览默认 3 条" "3" "$(jq "len(d['data']['comments']['records'][0]['asks'])")"
assert "9e" "追问总数统计正确" "3" "$(jq "d['data']['comments']['records'][0]['askTotal']")"
assert "9f" "追问按时间正序（对话感）" "True" \
    "$(jq "[a['id'] for a in d['data']['comments']['records'][0]['asks']] == sorted([a['id'] for a in d['data']['comments']['records'][0]['asks']])")"
assert "9g" "用户名脱敏 buyer1 -> b****1" "b****1" "$(jq "d['data']['comments']['records'][0]['nickname']")"
assert "9h" "卖家回复脱敏 seller1 -> s*****1" "s*****1" "$(jq "d['data']['comments']['records'][0]['reply']['nickname']")"
assert "9i" "游客 canAsk=false" "False" "$(jq "d['data']['viewerContext']['canAsk']")"
echo "       └ 游客提示：$(jq "d['data']['viewerContext']['askDeniedReason']")"
assert "9j" "游客 loggedIn=false" "False" "$(jq "d['data']['viewerContext']['loggedIn']")"

http GET "/comment/product/${PRODUCT_ID}" "$SELLER" >/dev/null
assert "9k" "卖家视角 seller|canReply" "True|True" \
    "$(jq "str(d['data']['viewerContext']['seller'])+'|'+str(d['data']['viewerContext']['canReply'])")"
assert "9l" "卖家视角 canReview=false" "False" "$(jq "d['data']['viewerContext']['canReview']")"
echo "       └ 卖家提示：$(jq "d['data']['viewerContext']['reviewDeniedReason']")"

http GET "/comment/product/${PRODUCT_ID}" "$BUYER" >/dev/null
assert "9m" "买家视角 mine=true（自己的评价）" "True" "$(jq "d['data']['comments']['records'][0]['mine']")"
assert "9n" "买家视角 canReview=false（已评价过）" "False" "$(jq "d['data']['viewerContext']['canReview']")"
echo "       └ 买家提示：$(jq "d['data']['viewerContext']['reviewDeniedReason']")"

assert "9o" "追问分页展开（游客可读）" "200" "$(http GET "/comment/${L1_ID}/replies?pageNum=1&pageSize=2")"
assert "9p" "追问分页 total 正确" "3" "$(jq "d['data']['total']")"
assert "9q" "追问分页页大小生效" "2" "$(jq "len(d['data']['records'])")"
http GET "/comment/product/${PRODUCT_ID}?sort=rating" >/dev/null
assert "9r" "按评分排序可用" "1" "$(jq "len(d['data']['comments']['records'])")"

echo
echo "----- 评分聚合 --------------------------------------------------------------"
assert "10a" "写入 L1 后 product 评分同步更新" "5.00|1" "$(sql1 "SELECT rating_avg,rating_count FROM product WHERE id=${PRODUCT_ID}")"
http GET "/comment/product/${PRODUCT_ID}" >/dev/null
assert "10b" "summary.ratingAvg 与 DB 一致" "5.0" "$(jq "d['data']['summary']['ratingAvg']")"
assert "10c" "五星分布计数" "1" "$(jq "d['data']['summary']['distribution']['5']")"
J_EDIT='{"content":"用了两天发现发热有点明显，改成三星","rating":3}'
assert "10d" "24 小时内可编辑自己的评价" "200" "$(http PUT "/comment/${L1_ID}" "$BUYER" "$J_EDIT")"
assert "10e" "改分后 product 评分同步" "3.00|1" "$(sql1 "SELECT rating_avg,rating_count FROM product WHERE id=${PRODUCT_ID}")"
assert "10f" "他人不可编辑该评价" "403" "$(http PUT "/comment/${L1_ID}" "$SELLER" "$J_EDIT")"

echo
echo "----- 删除与级联可见性 ------------------------------------------------------"
assert "11a" "卖家删除买家的 L1 评价" "403" "$(http DELETE "/comment/${L1_ID}" "$SELLER")"
assert "11b" "第三方删除他人的卖家回复" "403" "$(http DELETE "/comment/${L2_ID}" "$BUYER")"
assert "11c" "作者本人删除自己的 L1" "200" "$(http DELETE "/comment/${L1_ID}" "$BUYER")"
http GET "/comment/product/${PRODUCT_ID}" >/dev/null
assert "11d" "L1 删除后评论树为空（级联隐藏）" "0" "$(jq "len(d['data']['comments']['records'])")"
assert "11e" "子级记录仍在（逻辑删除，未物理级联）" "3" \
    "$(sql1 "SELECT COUNT(*) FROM comment WHERE id IN (${L2_ID},${L3_ID},${L3B_ID}) AND deleted=0")"
assert "11f" "已删 L1 的追问列表不可访问" "404" "$(http GET "/comment/${L1_ID}/replies")"
assert "11g" "删除 L1 后 product 评分归零" "0.00|0" "$(sql1 "SELECT rating_avg,rating_count FROM product WHERE id=${PRODUCT_ID}")"

echo
echo "----- 存量 /review 兼容层 ---------------------------------------------------"
assert "12a" "GET /review/product/{id} 游客可读" "200" "$(http GET "/review/product/${PRODUCT_ID}")"
J_OLD="{\"productId\":${PRODUCT_ID},\"rating\":5,\"content\":\"通过老接口绕过校验试试看\"}"
assert "12b" "POST /review 也走购买校验（后门已封）" "409" "$(http POST "/review" "$BUYER" "$J_OLD")"
echo "       └ 文案：$(jq "d['message']")"
assert "12c" "POST /review 游客" "401" "$(http POST "/review" "" "$J_OLD")"

echo
echo "============================================================================="
printf ' 结果： %s 通过 / %s 失败 / 共 %s 项\n' \
    "$(c_ok "$PASS")" "$([ "$FAIL" -gt 0 ] && c_bad "$FAIL" || echo 0)" "$((PASS + FAIL))"
echo "============================================================================="
if [ "$FAIL" -gt 0 ]; then
    echo "失败明细："
    for r in "${FAILED[@]}"; do echo "  $r"; done
    exit 1
fi
exit 0
