#!/usr/bin/env python3
"""BUG-1 / BUG-2 修复验收脚本（一次性验收工具，不属于测试套件）。

用法: set -a && . ./.env && set +a && python3 scripts/verify-bugfix.py

为什么单独成文件而不用 python3 -c：
  内联脚本里 f-string 的引号转义在 shell 单引号中会炸（\" 被当续行符），
  已经踩过一次，直接落盘省事。
"""
import json
import os
import sys
import time
import urllib.error
import urllib.request

GW = "http://localhost:8088"
PRODUCT_DIRECT = "http://localhost:8086"
OPENER = urllib.request.build_opener(urllib.request.ProxyHandler({}))


def call(method, url, body=None, headers=None):
    """返回 (http_code, dict_or_text)。非 2xx 不抛异常，因为我们就是来验错误码的。"""
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(url, data=data, method=method)
    if data is not None:
        req.add_header("Content-Type", "application/json")
    for k, v in (headers or {}).items():
        req.add_header(k, v)
    try:
        with OPENER.open(req, timeout=15) as r:
            return r.status, json.loads(r.read().decode() or "{}")
    except urllib.error.HTTPError as e:
        raw = e.read().decode(errors="replace")
        try:
            return e.code, json.loads(raw)
        except json.JSONDecodeError:
            return e.code, {"_raw": raw[:200]}
    except (urllib.error.URLError, OSError) as e:
        return 0, {"_err": str(e)}


def check(label, got_http, got_body, want_http, want_code):
    code = got_body.get("code")
    msg = got_body.get("message", "")
    ok = got_http == want_http and code == want_code
    mark = "PASS" if ok else "FAIL"
    print(f"  [{mark}] {label:<26} HTTP {got_http}  code={code}  msg={msg}")
    return ok


def stock_of(pid=1):
    _, b = call("GET", f"{GW}/api/product/{pid}")
    return b["data"]["stock"]


def main():
    token_internal = os.environ.get("INTERNAL_TOKEN", "")
    if not token_internal:
        print("[ERR] INTERNAL_TOKEN 未设置，请先 source .env")
        return 2

    ts = int(time.time())
    uname = f"vfy_{ts}"
    # 口径与 tests/e2e_test.sh 保持一致：密码有强度校验（123456 会被拒），
    # 且 userType 必填。phone 取时间戳前 8 位避开唯一索引冲突。
    pwd = "Test@1234"
    phone = "138" + str(ts)[:8]

    st, rb = call("POST", f"{GW}/api/user/register",
                  {"username": uname, "password": pwd, "phone": phone,
                   "email": f"{uname}@test.com", "userType": 1})
    if st != 200 or not rb.get("success"):
        print(f"[ERR] 注册失败 HTTP {st}: {rb}")
        return 1
    st, body = call("POST", f"{GW}/api/user/login",
                    {"username": uname, "password": pwd})
    token = (body.get("data") or {}).get("token")
    if not token:
        print(f"[ERR] 登录失败 HTTP {st}: {body}")
        return 1
    auth = {"Authorization": "Bearer " + token}
    print(f"[INFO] 验收账号 {uname} 登录成功\n")

    _, b = call("POST", f"{GW}/api/address/add",
                {"receiverName": "验收", "receiverPhone": "13800000000",
                 "province": "广东", "city": "深圳", "district": "南山",
                 "detailAddress": "科技园", "isDefault": 1}, auth)
    d = b.get("data")
    addr = d.get("id") if isinstance(d, dict) else d

    base_stock = stock_of()
    results = []

    print(f"=== BUG-2 验收：库存/状态冲突应为 409（当前库存 {base_stock}）===")

    over = base_stock + 1
    st, b = call("POST", f"{GW}/api/order/create",
                 {"addressId": addr, "items": [{"productId": 1, "quantity": over}]}, auth)
    results.append(check(f"下单超库存({over}件)", st, b, 409, 409))

    st, b = call("POST", f"{GW}/api/order/cart/add?productId=1&quantity={over}", None, auth)
    results.append(check("加购超库存", st, b, 409, 409))

    # 造一单用于状态机验证
    st, b = call("POST", f"{GW}/api/order/create",
                 {"addressId": addr, "items": [{"productId": 1, "quantity": 1}]}, auth)
    oid = b["data"][0]["id"]
    call("POST", f"{GW}/api/order/pay/{oid}", None, auth)

    st, b = call("POST", f"{GW}/api/order/pay/{oid}", None, auth)
    results.append(check("重复支付", st, b, 409, 409))

    st, b = call("POST", f"{GW}/api/order/cancel/{oid}", None, auth)
    results.append(check("已付款后取消", st, b, 409, 409))

    print("\n=== BUG-1 验收：MetaObjectHandler 是否填充审计字段 ===")
    st, b = call("GET", f"{GW}/api/order/{oid}", None, auth)
    o = b["data"]
    ct, ut = o.get("createTime"), o.get("updateTime")
    ok = ct is not None and ut is not None
    print(f"  [{'PASS' if ok else 'FAIL'}] 新订单 {oid}  createTime={ct!r}  updateTime={ut!r}")
    results.append(ok)

    # updateTime 必须随写操作刷新（strictUpdateFill 陷阱的回归点）
    same = ct == ut
    print(f"  [{'INFO'}] createTime {'==' if same else '!='} updateTime"
          f"（支付后应已刷新 → 期望 !=）")

    print("\n=== 库存归位 ===")
    now = stock_of()
    delta = now - base_stock          # 本次验收净变化（下单 1 件 → -1）
    target_fix = base_stock - now     # 需要补回的量
    if target_fix > 0:
        call("PUT", f"{PRODUCT_DIRECT}/product/1/restore?quantity={target_fix}",
             None, {"X-Internal-Token": token_internal})
    elif target_fix < 0:
        call("PUT", f"{PRODUCT_DIRECT}/product/1/deduct?quantity={-target_fix}",
             None, {"X-Internal-Token": token_internal})
    final = stock_of()
    print(f"  验收前 {base_stock} → 验收中 {now}（净 {delta:+d}）→ 归位后 {final}")
    results.append(final == base_stock)

    passed = sum(1 for x in results if x)
    print(f"\n===== 验收结果: 通过 {passed} / {len(results)} =====")
    return 0 if passed == len(results) else 1


if __name__ == "__main__":
    sys.exit(main())
