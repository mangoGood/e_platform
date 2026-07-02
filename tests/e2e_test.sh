#!/bin/bash
###############################################################################
# 电商平台端到端测试脚本
# 覆盖：用户注册/登录、商品浏览、购物车、收货地址、订单全流程、
#       移动端BFF聚合、网关安全（认证拦截、内部接口隔离）
# 用法：bash tests/e2e_test.sh
###############################################################################
set -u

GATEWAY="http://localhost:8088"
PASS=0
FAIL=0
TOKEN=""
USER_ID=""

# 颜色输出
GREEN='\033[0;32m'
RED='\033[0;31m'
YELLOW='\033[0;33m'
NC='\033[0m'

# 断言函数：检查响应JSON中 success 字段是否为 true
assert_success() {
    local name="$1"
    local resp="$2"
    local success=$(echo "$resp" | python3 -c "import sys,json; print(json.load(sys.stdin).get('success', False))" 2>/dev/null)
    if [ "$success" = "True" ]; then
        echo -e "${GREEN}[PASS]${NC} $name"
        PASS=$((PASS+1))
    else
        echo -e "${RED}[FAIL]${NC} $name"
        echo "  响应: $resp"
        FAIL=$((FAIL+1))
    fi
}

# 断言函数：检查HTTP状态码
assert_status() {
    local name="$1"
    local expected="$2"
    local actual="$3"
    if [ "$expected" = "$actual" ]; then
        echo -e "${GREEN}[PASS]${NC} $name"
        PASS=$((PASS+1))
    else
        echo -e "${RED}[FAIL]${NC} $name (期望 $expected, 实际 $actual)"
        FAIL=$((FAIL+1))
    fi
}

# 断言函数：检查响应中包含指定字段值
assert_field() {
    local name="$1"
    local resp="$2"
    local field="$3"
    local expected="$4"
    local actual=$(echo "$resp" | python3 -c "import sys,json; d=json.load(sys.stdin); print(d.get('$field',''))" 2>/dev/null)
    if [ "$actual" = "$expected" ]; then
        echo -e "${GREEN}[PASS]${NC} $name"
        PASS=$((PASS+1))
    else
        echo -e "${RED}[FAIL]${NC} $name (字段 $field 期望 $expected, 实际 $actual)"
        FAIL=$((FAIL+1))
    fi
}

echo "=========================================="
echo "  电商平台端到端测试"
echo "=========================================="
echo ""

###############################################################################
# 1. 用户注册
###############################################################################
echo "--- 1. 用户模块 ---"
TS=$(date +%s)
USERNAME="e2e_user_${TS}"
RESP=$(curl -s -X POST "$GATEWAY/api/user/register" \
    -H "Content-Type: application/json" \
    -d "{\"username\":\"$USERNAME\",\"password\":\"Test@1234\",\"phone\":\"138${TS:0:8}\",\"email\":\"${USERNAME}@test.com\",\"userType\":1}")
assert_success "1.1 用户注册" "$RESP"

###############################################################################
# 2. 用户登录
###############################################################################
RESP=$(curl -s -X POST "$GATEWAY/api/user/login" \
    -H "Content-Type: application/json" \
    -d "{\"username\":\"$USERNAME\",\"password\":\"Test@1234\"}")
assert_success "2.1 用户登录" "$RESP"
TOKEN=$(echo "$RESP" | python3 -c "import sys,json; print(json.load(sys.stdin).get('data',{}).get('token',''))" 2>/dev/null)
USER_ID=$(echo "$RESP" | python3 -c "import sys,json; print(json.load(sys.stdin).get('data',{}).get('userId',''))" 2>/dev/null)
if [ -n "$TOKEN" ]; then
    echo -e "${GREEN}[PASS]${NC} 2.2 获取Token"
    PASS=$((PASS+1))
else
    echo -e "${RED}[FAIL]${NC} 2.2 获取Token"
    FAIL=$((FAIL+1))
fi

###############################################################################
# 3. 商品浏览（公开读路径，无需Token也可，但携带Token测试）
###############################################################################
echo ""
echo "--- 2. 商品模块 ---"
RESP=$(curl -s "$GATEWAY/api/product/list?pageNum=1&pageSize=3" \
    -H "Authorization: Bearer $TOKEN")
assert_success "3.1 商品列表分页" "$RESP"

RESP=$(curl -s "$GATEWAY/api/product/1" \
    -H "Authorization: Bearer $TOKEN")
assert_success "3.2 商品详情" "$RESP"

RESP=$(curl -s "$GATEWAY/api/category/tree" \
    -H "Authorization: Bearer $TOKEN")
assert_success "3.3 分类树" "$RESP"

###############################################################################
# 4. 收货地址
###############################################################################
echo ""
echo "--- 3. 收货地址模块 ---"
RESP=$(curl -s -X POST "$GATEWAY/api/address" \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer $TOKEN" \
    -d '{"receiverName":"测试用户","receiverPhone":"13800000001","province":"北京市","city":"北京市","district":"海淀区","detailAddress":"中关村大街1号","isDefault":1}')
assert_success "4.1 添加收货地址" "$RESP"
ADDR_ID=$(echo "$RESP" | python3 -c "import sys,json; print(json.load(sys.stdin).get('data',{}).get('id',''))" 2>/dev/null)

RESP=$(curl -s "$GATEWAY/api/address/list" \
    -H "Authorization: Bearer $TOKEN")
assert_success "4.2 地址列表" "$RESP"

###############################################################################
# 5. 购物车
###############################################################################
echo ""
echo "--- 4. 购物车模块 ---"
RESP=$(curl -s -X POST "$GATEWAY/api/order/cart/add?productId=1&quantity=2" \
    -H "Authorization: Bearer $TOKEN")
assert_success "5.1 添加购物车" "$RESP"

RESP=$(curl -s "$GATEWAY/api/order/cart" \
    -H "Authorization: Bearer $TOKEN")
assert_success "5.2 查看购物车" "$RESP"

RESP=$(curl -s -X PUT "$GATEWAY/api/order/cart?productId=1&quantity=3" \
    -H "Authorization: Bearer $TOKEN")
assert_success "5.3 修改购物车数量" "$RESP"

###############################################################################
# 6. 订单流程
###############################################################################
echo ""
echo "--- 5. 订单模块 ---"
RESP=$(curl -s -X POST "$GATEWAY/api/order/create" \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer $TOKEN" \
    -d "{\"addressId\":$ADDR_ID,\"items\":[{\"productId\":1,\"quantity\":2}]}")
assert_success "6.1 创建订单" "$RESP"
ORDER_ID=$(echo "$RESP" | python3 -c "import sys,json; d=json.load(sys.stdin); print(d.get('data',[{}])[0].get('id',''))" 2>/dev/null)

RESP=$(curl -s "$GATEWAY/api/order/user" \
    -H "Authorization: Bearer $TOKEN")
assert_success "6.2 用户订单列表" "$RESP"

RESP=$(curl -s "$GATEWAY/api/order/$ORDER_ID" \
    -H "Authorization: Bearer $TOKEN")
assert_success "6.3 订单详情" "$RESP"

RESP=$(curl -s -X POST "$GATEWAY/api/order/pay/$ORDER_ID" \
    -H "Authorization: Bearer $TOKEN")
assert_success "6.4 支付订单" "$RESP"

###############################################################################
# 7. 移动端BFF聚合
###############################################################################
echo ""
echo "--- 6. 移动端BFF模块 ---"
RESP=$(curl -s "$GATEWAY/api/mobile/home" \
    -H "Authorization: Bearer $TOKEN")
assert_success "7.1 移动端首页聚合" "$RESP"

###############################################################################
# 8. 网关安全测试
###############################################################################
echo ""
echo "--- 7. 网关安全模块 ---"
# 未登录访问受保护接口
HTTP_CODE=$(curl -s -o /dev/null -w "%{http_code}" "$GATEWAY/api/order/cart")
assert_status "8.1 未登录访问受保护接口返回401" "401" "$HTTP_CODE"

# 无效Token访问
RESP=$(curl -s "$GATEWAY/api/order/cart" -H "Authorization: Bearer invalidtoken")
assert_field "8.2 无效Token返回401" "$RESP" "code" "401"

# 外部访问内部接口 deduct
RESP=$(curl -s -X POST "$GATEWAY/api/product/deduct?productId=1&quantity=1" \
    -H "Authorization: Bearer $TOKEN")
assert_field "8.3 内部接口deduct被拦截403" "$RESP" "code" "403"

# 外部访问内部接口 restore
RESP=$(curl -s -X POST "$GATEWAY/api/product/restore?productId=1&quantity=1" \
    -H "Authorization: Bearer $TOKEN")
assert_field "8.4 内部接口restore被拦截403" "$RESP" "code" "403"

# 正常商品查询不被误伤
RESP=$(curl -s "$GATEWAY/api/product/1" -H "Authorization: Bearer $TOKEN")
assert_success "8.5 正常商品查询不被误拦截" "$RESP"

###############################################################################
# 测试结果汇总
###############################################################################
echo ""
echo "=========================================="
echo -e "  测试结果: ${GREEN}通过 $PASS${NC} / ${RED}失败 $FAIL${NC}"
echo "=========================================="
if [ "$FAIL" -gt 0 ]; then
    exit 1
fi
exit 0
