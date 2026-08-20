# 电商平台系统

一个基于 Spring Boot 微服务 + Vue 3 + Android (Kotlin/Compose) 的全栈电商平台，支持买家和卖家两种用户角色，提供完整的购物流程。

## 技术栈

### 后端（Spring Boot 2.7 微服务）
- **Spring Boot 2.7.18** + **Spring Cloud 2021.0.8** - 微服务框架
- **传统 Servlet MVC**（非 WebFlux）
- **Spring Security + JWT** - 认证授权（access / refresh 双令牌）
- **MyBatis-Plus** - ORM 框架
- **MySQL 8.0** + **Druid** - 关系型数据库与连接池
- **Redis** - 缓存 / 登出黑名单 / RBAC 权限缓存 / 秒杀排队
- **OpenFeign** - 服务间调用（mobile BFF → 各微服务）
- **Hutool / Fastjson** - 工具库

> **网关实现说明**：`e-platform-gateway` 是**手写的 Spring MVC + RestTemplate 反向代理**，
> **不是** Spring Cloud Gateway，也不依赖 WebFlux。所有安全逻辑集中在
> `ProxyService` 的固定管线中（见「安全机制」章节）。

### Web 前端
- **Vue 3** + **Vue Router 4** + **Pinia**
- **Element Plus** - UI 组件库
- **Axios** - HTTP 客户端
- **Vite 4** - 构建工具

### Android 多端应用
- **Kotlin 1.9** + **Jetpack Compose** - 声明式 UI
- **Hilt** - 依赖注入
- **Retrofit + OkHttp** - 网络层
- **kotlinx.serialization** - JSON 序列化
- **DataStore** - 本地存储
- **Coil** - 图片加载
- **Navigation Compose** - 导航

### 基础设施
- **Docker Compose** - MySQL / Redis / RabbitMQ

## 项目结构

```
e_platform/
├── backend/                        # 后端微服务集群
│   ├── e-platform-common/          # 公共模块（JWT/Result/Redis/异常）
│   ├── e-platform-user/            # 用户服务        :8085
│   ├── e-platform-product/         # 商品服务        :8086
│   ├── e-platform-order/           # 订单服务        :8087（含购物车/地址）
│   ├── e-platform-mobile/          # 移动端 BFF      :8089（Feign 聚合层）
│   ├── e-platform-gateway/         # API 网关        :8088（代理 + 托管前端静态资源）
│   └── pom.xml                     # 父 POM
├── frontend/                       # Vue 3 Web 前端
│   └── src/
│       ├── api/                    # API 接口
│       ├── layouts/                # 布局组件
│       ├── router/                 # 路由
│       ├── stores/                 # Pinia 状态管理
│       └── views/                  # 页面
├── android/                        # Android 多模块项目
│   ├── shared-core/                # 共享库（网络/DI/模型/UI 组件）
│   ├── buyer-app/                  # 买家 App
│   ├── seller-app/                 # 卖家 App
│   └── settings.gradle.kts
├── database/init.sql               # 数据库初始化脚本
├── tests/                          # 测试脚本
│   ├── e2e_test.sh                 # 端到端功能测试
│   └── security_test.sh            # 安全回归测试（12 项断言）
├── docker-compose.yml              # 基础设施（MySQL/Redis/RabbitMQ）
├── start.sh                        # 一键启动脚本
├── stop.sh                         # 停止服务脚本
└── .env.example                    # 环境变量配置示例
```

## 架构概览

### 请求链路

```
Web 前端 ──→ 网关(:8088) ──→ 用户服务(:8085)
                          ──→ 商品服务(:8086)
                          ──→ 订单服务(:8087)

Android  ──→ 网关(:8088) ──→ 移动端 BFF(:8089) ──Feign──→ 用户/商品/订单服务
```

- **网关层**：统一入口，负责 JWT 认证、RBAC 鉴权、请求头清洗、HMAC 签名、
  秒杀排队、请求路由、CORS、托管前端静态资源
- **移动端 BFF**：为 Android 客户端提供聚合接口，通过 Feign 调用内部微服务
- **微服务层**：用户、商品、订单各自独立部署，拥有独立数据库表

> 对外**只暴露网关 :8088**，所有外部请求走 `/api/**` 前缀。
> 内部服务端口（8085/8086/8087/8089）不应对外开放；即使被直连，也会因缺少
> 网关签名而被拒绝（见「安全机制 → 内部信任链」）。**直连内部服务时路径没有 `/api` 前缀**
> （如 `http://localhost:8085/user/login`）。

### 端口分配

| 服务 | 端口 | 说明 |
|------|------|------|
| e-platform-user | 8085 | 用户注册/登录/信息 |
| e-platform-product | 8086 | 商品/分类/评价 |
| e-platform-order | 8087 | 订单/购物车/地址 |
| e-platform-gateway | 8088 | API 网关 + Web 前端 |
| e-platform-mobile | 8089 | 移动端 BFF |
| MySQL | 3306 | 数据库 |
| Redis | 6379 | 缓存 |
| RabbitMQ | 5672 / 15672 | 消息队列（预留） |

## 功能特性

### 用户功能
- 用户注册（买家/卖家）
- 用户登录（JWT Token 认证）
- 用户信息管理

### 商品功能
- 商品分类管理（树形结构）
- 商品上架/下架
- 商品列表查询（分页/分类/关键词搜索）
- 商品详情展示
- 商品评价（三级评论，见下）
- Redis 缓存优化

### 三级评论

评论体系分三种角色视角，但**树深恒为 2**（L1 为根，L2/L3 同为其子级）：

| 层级 | 含义 | type | parentId | rootId | 备注 |
|------|------|------|----------|--------|------|
| L1 | 买家评价 | 1 | 0 | 自身 id | `rating` 必填、`orderId` 非空 |
| L2 | 卖家回复 | 2 | L1.id | L1.id | 每条 L1 下卖家只能回复一次 |
| L3 | 第三方追问 | 3 | L1.id | L1.id | 任意登录用户可追问 |

> **强制拍平**：对 L2 或 L3 再次追问时，服务端会把新评论的 `parentId` / `rootId`
> **一律重写为所属的那条 L1**，而不是「被回复的那条」。因此不会出现 L4 及更深层级，
> 前端只需渲染「一条评价 + 其下若干回复/追问」的两层结构。

> `comment` 表是**逻辑删除**，直接查库统计时必须带 `deleted=0` 条件。

### 购物车功能
- 添加商品到购物车
- 修改购物车商品数量
- 删除购物车商品
- 清空购物车

### 订单功能
- 创建订单
- 订单支付
- 订单发货
- 确认收货
- 取消订单
- 订单列表查询（买家/卖家）
- 收货地址管理

### 卖家后台
- 卖家中心首页
- 商品管理（增删改查）
- 订单管理
- 数据统计

## 快速开始

### 环境要求
- JDK 17+
- Node.js 16+
- MySQL 8.0+
- Redis 6.0+
- Maven 3.6+
- Docker & Docker Compose
- Android Studio（构建 Android 端）

### 1. 配置环境变量

```bash
cp .env.example .env
# 编辑 .env，设置 JWT_SECRET（至少 64 字符）
# 生成密钥: openssl rand -base64 64
```

### 2. 一键启动（推荐）

```bash
# 启动所有服务（含 Docker 基础设施、前端构建、后端微服务）
./start.sh

# 停止所有服务
./stop.sh
```

启动脚本会自动完成：
1. 启动 Docker 基础设施（MySQL/Redis/RabbitMQ）
2. 构建前端（npm install + npm run build）
3. 构建并启动后端微服务（user → product → order → mobile → gateway）

### 3. 手动启动

#### 数据库初始化

数据库跑在 Docker 里，宿主机通常**没有** `mysql` / `redis-cli` 客户端，
一律通过 `docker exec` 操作：

```bash
# 导入初始化脚本
docker exec -i e-platform-mysql mysql -uroot -proot e_platform < database/init.sql

# 连进去手工查数据
docker exec -it e-platform-mysql mysql -uroot -proot e_platform

# Redis
docker exec -it e-platform-redis redis-cli
```

容器名：`e-platform-mysql`(3306, root/root)、`e-platform-redis`(6379)、`e-platform-rabbitmq`。

#### 后端启动

```bash
cd backend
mvn install -DskipTests

# 设置 JWT 密钥
export JWT_SECRET="your-secret-key-at-least-64-chars"

# 启动各服务
cd e-platform-user && mvn spring-boot:run
cd e-platform-product && mvn spring-boot:run
cd e-platform-order && mvn spring-boot:run
cd e-platform-mobile && mvn spring-boot:run
cd e-platform-gateway && mvn spring-boot:run
```

> ⚠️ **不要执行 `mvn clean`**。`target/` 下放着正在运行的 jar，
> `clean` 会把它清掉并导致运行中的服务异常（本项目已出过一次事故）。
> 需要重新构建时用 `mvn install -DskipTests` 即可。

#### Web 前端启动（开发模式）

```bash
cd frontend
npm install
npm run dev
# 访问 http://localhost:3000（开发模式）
# 或通过网关访问 http://localhost:8088（生产模式，前端打包进网关）
```

#### Android 端构建

```bash
cd android
./gradlew :buyer-app:assembleDebug    # 买家 App
./gradlew :seller-app:assembleDebug   # 卖家 App
```

> Android 模拟器默认连接 `http://10.0.2.2:8088`（映射宿主机 localhost:8088）

### 访问地址

| 端 | 地址 |
|----|------|
| Web 前端 | http://localhost:8088 |
| API 统一入口 | http://localhost:8088/api |

**测试账号（共 4 个，密码统一 `123456`）**：

| 用户名 | id | 角色 | 说明 |
|--------|----|------|------|
| `buyer1` | 1 | `ROLE_BUYER` | 买家 |
| `seller1` | 2 | `ROLE_SELLER` | 卖家，种子商品均属于该账号 |
| `admin` | 3 | `ROLE_BUYER` + `ROLE_ADMIN` | 管理员，**有删除豁免** |
| `seller2` | 4 | `ROLE_SELLER` | 卖家，名下无商品无订单 |

> 验证越权请用 **`seller2`**（与 `buyer1` 数据无任何关联）。
> 用 `admin` 会因为 `ROLE_ADMIN` 的豁免而得出错误结论。

## API 文档

> 下列路径为**服务内部路径**。经网关访问时统一加 `/api` 前缀
> （如 `POST /api/user/login`）；直连内部服务时**没有** `/api` 前缀。

### 用户服务 (8085)
- `POST /user/register` - 用户注册
- `POST /user/login` - 用户登录（下发 access + refresh 双令牌）
- `POST /user/refresh` - 刷新令牌，body `{"refreshToken":"..."}`，**轮换式**
- `POST /user/logout` - 登出（access token 加入黑名单）
- `GET /user/info/{userId}` - 获取用户信息（**带路径参数**）
- `GET /user/perms/{userId}` - 查询用户角色与权限码

### 商品服务 (8086)
- `GET /product/list` - 商品列表（分页/分类/搜索）
- `GET /product/{id}` - 商品详情
- `GET /product/batch` - 批量查询商品
- `GET /product/seller/{sellerId}` - 卖家商品列表
- `POST /product/add` - 添加商品
- `PUT /product/{id}` - 更新商品
- `DELETE /product/{id}` - 删除商品
- `GET /category/tree` - 分类树
- `GET /review/{productId}` - 商品评价（旧版兼容层）
- `PUT /product/{id}/deduct` - 扣减库存（**内部专用**，外部经网关一律 403）
- `PUT /product/{id}/restore` - 回补库存（**内部专用**，外部经网关一律 403）

#### 评论（三级评论，归属 product 服务）
- `GET /comment/product/{productId}` - 商品评论列表
- `GET /comment/{rootId}/replies` - 某条 L1 评价下的回复/追问
- `GET /comment/can-review` - 是否具备评价资格
- `POST /comment` - 发表 L1 买家评价
- `POST /comment/reply` - L2 卖家回复
- `POST /comment/ask` - L3 第三方追问

### 订单服务 (8087)
- `POST /order/create` - 创建订单（**受排队保护**）
- `POST /order/pay/{orderId}` - 支付订单
- `POST /order/cancel/{orderId}` - 取消订单
- `GET /order/{orderId}` - 订单详情
- `GET /order/user` - 买家订单列表
- `GET /order/seller` - 卖家订单列表
- `GET /order/items/{orderId}` - 订单条目
- 购物车：`POST /order/cart/add`、`PUT /order/cart`、
  `DELETE /order/cart/{productId}`、`DELETE /order/cart`、`GET /order/cart`
- `GET /address/list` - 地址列表

发货/收货**新旧接口并存**：

| 动作 | 旧接口 | 新接口 |
|------|--------|--------|
| 发货 | `POST /order/deliver/{orderId}` | `POST /order/{orderId}/ship` |
| 收货 | `POST /order/receive/{orderId}` | `POST /order/{orderId}/confirm` |

> ⚠️ **`GET /api/order/list` 这个端点不存在**。它会被 `GET /order/{orderId}` 捕获，
> `@PathVariable Long orderId` 解析字符串 `"list"` 失败 → 返回 **400 参数类型错误**，
> 而不是预期的 401/200。查买家订单列表请用 **`GET /api/order/user`**。

> ⚠️ **`POST /order/create` 收的是商品列表结构，不是平铺的 `productId`**：
> ```json
> { "items": [ { "productId": 1, "quantity": 2 } ],
>   "receiverName": "...", "receiverPhone": "...", "receiverAddress": "..." }
> ```

### 网关自身接口 (8088)
- `GET /api/queue/status?token=<uuid>` - 秒杀排队状态轮询（由网关本地处理，不转发）

### 移动端 BFF (8089)
- `POST /mobile/auth/login` - 移动端登录
- `POST /mobile/auth/register` - 移动端注册
- `GET /mobile/home` - 首页聚合数据
- `GET /mobile/products/**` - 商品列表/详情
- `GET /mobile/cart` - 购物车
- `POST /mobile/order/**` - 订单操作

> 所有外部请求统一通过网关(:8088)的 `/api/**` 前缀访问。

## 安全机制

### 网关安全管线

所有 `/api/**` 请求都会经过 `ProxyService` 中**顺序固定、不可绕过**的管线：

```
1. INTERNAL_DENY 检查   内部专用接口（/product/*/deduct|restore、**/internal/**）直接 403
2. TokenResolver       解析 JWT + 查 Redis 登出黑名单
3. RoutePermissionRegistry  路由权限查表，严格区分 401（没身份）/ 403（没权限）
4. QueueService        秒杀排队守卫
5. HeaderSanitizer     剥离客户端伪造的内部头
6. GatewaySigner       写入网关认定的可信身份 + HMAC 签名
7. RestTemplate 转发    透传下游真实状态码
```

### RBAC 角色权限模型

三个角色，共 13 个权限码。网关只做「有没有这张门票」的粗判，
「这条数据是不是你的」（归属校验）下沉到各服务的 Service 层。

| 角色 | 权限码数量 | 说明 |
|------|-----------|------|
| `ROLE_BUYER` | 7 | `user:read` `product:read` `cart:manage` `order:create` `order:read` `comment:create` `comment:ask` |
| `ROLE_SELLER` | 11 | 买家权限 + `product:write` `product:delete` `order:manage` `comment:reply` |
| `ROLE_ADMIN` | 13 | 全部权限，含 `admin:access` |

- 权限码按角色缓存在 Redis `rbac:role:{code}:perms`，由 user 服务启动时预热
- Redis 不可用时降级到 `StaticRolePermissions` 静态表，避免因缓存抖动全站 403
- 路由 → 权限的映射表在 `RoutePermissionRegistry`，**顺序敏感**（第一条命中即生效），
  兜底规则为 `AUTHENTICATED`，新接口忘记登记也不会裸奔

### JWT 双令牌（轮换式）

| 令牌 | 有效期 | 用途 |
|------|--------|------|
| access token | **2 小时**（`jwt.access-expiration=7200000`） | 访问业务接口 |
| refresh token | **7 天**（`jwt.refresh-expiration=604800000`） | 换取新的 access token |

- 登录 `POST /api/user/login` 一次性下发 `token` + `refreshToken` + `expiresIn`
- 刷新 `POST /api/user/refresh`，请求体 `{"refreshToken":"..."}`，
  响应 `data: {token, refreshToken, expiresIn}`
- **轮换（rotation）**：刷新成功后旧的 refresh token **立即作废**，
  同一个 refresh token 无法使用第二次（防重放）
- 刷新时角色**重新查库**而非沿用旧 Token，提权/降权在下一次刷新即刻生效
- 拿 refresh token 当访问凭证直接调业务接口会被拒（`typ` claim 校验）

### 登出黑名单

`POST /api/user/logout` 会把当前 access token 的 `jti` 写入 Redis
`auth:blacklist:{jti}`，TTL 为其剩余有效期。网关在管线第 2 步查询该黑名单，
命中即返回 401 —— **登出后原 access token 立即失效**，不必等自然过期。

> Redis 不可用时黑名单查询 **fail-open**（放行）：这是刻意的可用性取舍，
> Token 本身仍受 2 小时有效期约束。

### 请求头清洗（防伪造身份）

`HeaderSanitizer` 采用**前缀黑名单**而非枚举，客户端携带的以下前缀的头
**一律丢弃**，之后才由网关写入可信身份：

- `x-user-`（`X-User-Id` / `X-User-Type` / `X-User-Roles`）
- `x-gateway-`（`X-Gateway-Sign` / `X-Gateway-Ts`）
- `x-internal-`（`X-Internal-Token`）
- `x-queue-`（`X-Queue-Token`，网关在清洗前已读取，下游无需感知）

> `Authorization` **保留透传**：mobile BFF 仍需自行解析 JWT。它是客户端凭证，
> 下游对它的信任等级本就低于 `X-Gateway-Sign`。

### 内部信任链（HMAC-SHA256 签名）

微服务侧由 `GatewaySignatureInterceptor` 把守，放行规则按序判定：

1. `gateway.sign.enabled=false` → 全放行（仅供本地调试）
2. 白名单路径 `/actuator/**`、`/error` → 放行
3. `X-Internal-Token` 校验通过 → 放行（order → product 的 Feign 直连不经网关）
4. 其余：必须通过 **HMAC-SHA256 验签 + 时间窗校验**（默认容忍 300s）

因此**绕过网关直连 8085~8089 会被拒为 401 `非法请求来源`**，
携带自己编造的 `X-User-Id` 也无法冒充任何用户。

相关配置（`.env`，`start.sh` 会自动随机生成）：
`JWT_SECRET`、`GATEWAY_SIGN_SECRET`、`INTERNAL_TOKEN`。

### 其他

- BCrypt 密码加密；用户信息接口返回前抹除密码哈希
- CORS 跨域配置
- SQL 注入防护：MyBatis-Plus `LambdaQueryWrapper` 全参数绑定，Mapper 中无 `${}` 拼接

## 错误码语义

T02/T03 起，网关与各服务返回**真实 HTTP 状态码**，不再一律 200。

| 业务码 | HTTP 状态 | 典型场景 |
|--------|-----------|----------|
| 400 | 400 | 参数校验失败、畸形 JSON（`请求体格式错误，无法解析`）、路径参数类型不匹配 |
| 401 | 401 | 未登录、Token 失效/篡改、命中登出黑名单、网关签名校验失败、refresh 重放 |
| 403 | 403 | 权限码不足、归属校验失败、外部访问内部接口 |
| 404 | 404 | 资源不存在（如 `评论不存在或已被删除`） |
| 405 | 405 | HTTP 方法不被支持 |
| 408 | 408 | 排队等待超时 |
| 409 | 409 | 状态冲突（重复评价、重复发货） |
| 429 | 429 | 触发限流 |
| 202 | 202 | 秒杀已入队 |
| 503 | 503 | 队列已满 / 下游不可达 |
| **500** | **200** | **业务软失败（历史兼容）**：`body.code=500`，HTTP 仍为 200 |

> 最后一行是刻意保留的存量契约：`throw new BusinessException("商品不存在")`
> 这类单参构造仍走软失败分支，HTTP 200 + `body.code=500`，
> 以免打断存量客户端与 `tests/e2e_test.sh` 的 `assert_success` 断言。
> **新代码要让 HTTP 状态生效，必须显式传错误码**：
> `throw new BusinessException(ErrorCode.FORBIDDEN, "无权限修改该商品")`。

响应体统一结构：

```json
{ "code": 401, "message": "未登录，请先登录", "data": null, "success": false }
```

## 网关排队（秒杀流控）

基于 **Redis ZSet + Lua 脚本**实现的排队准入控制，只对**受保护路径**生效
（全站生效会给每个请求加一次 Redis 往返，排队本身就会变成延迟来源）。

配置位于 `backend/e-platform-gateway/src/main/resources/application.yml` 的 `queue:` 段：

| 配置项 | 默认值 | 含义 |
|--------|--------|------|
| `enabled` | `true` | 排队总开关 |
| `permits` | `50` | 并发放行名额 |
| `max-length` | `500` | 队列最大长度，超出返回 503 |
| `wait-timeout` | `60`（秒） | 排队等待超时，超时返回 408 |
| `permit-ttl` | `30`（秒） | 名额持有 TTL，防止请求异常退出后名额泄漏 |
| `poll-interval` | `2`（秒） | 建议客户端轮询间隔 |
| `protected-paths` | 见下 | 受保护路径白名单，格式 `METHOD:antPattern` |

当前受保护路径（**路径已剥离 `/api` 前缀**，与 `RoutePermissionRegistry` 口径一致）：

```yaml
protected-paths:
  - POST:/order/create
  - PUT:/product/*/deduct
```

### 交互流程

1. 请求命中受保护路径且名额已满 → 网关返回 **HTTP 202**，
   响应头带 **`X-Queue-Token: <uuid>`** 票据
2. 客户端持票据轮询 **`GET /api/queue/status?token=<uuid>`**（注意 URL 含 `?`，
   在 shell 里必须加引号）
3. 轮到时重发原请求并带上 `X-Queue-Token`，网关据此认定名额归属
4. 请求处理完毕（无论成功失败）名额都会在 `finally` 中归还

> **降级**：Redis 不可用时排队服务退化为 `NoopQueueService`，**直接放行**，
> 保证主链路可用性优先。

## 测试

### 端到端功能测试

```bash
bash tests/e2e_test.sh
```

覆盖用户注册/登录、商品浏览、收货地址、购物车、订单全流程、移动端 BFF 聚合、
网关基础安全拦截。

### 安全回归测试

```bash
bash tests/security_test.sh
```

**12 项安全断言**，是本项目的安全验收底线：

| # | 项目 | 期望 |
|---|------|------|
| 1 | 未登录访问受保护接口（`GET /api/order/user`） | 401 + 中文提示 |
| 2 | 越权访问他人订单详情 | 403 |
| 3 | 越权修改他人名下商品（改价/下架） | 403 |
| 4 | JWT 签名被篡改 | 401 |
| 5 | JWT 结构非法（`abc.def.ghi`） | 401 且不得 500 |
| 6 | 登出后 access token 立即失效 | 登出前 200 / 登出后 401 |
| 7 | 伪造 `X-User-Id: 3` 冒充 admin | 仍 403（头被清洗） |
| 8 | 伪造 `X-Internal-Token` 经网关提权 | 仍 403（头被清洗） |
| 9 | 绕过网关直连内部端口 | 401/403，不得裸奔返回数据 |
| 10 | SQL 注入（商品搜索） | 不 500 / 不返回全表 / 不回显 SQL 错误 |
| 11 | 响应体不回传密码字段 | 无密码值泄露 |
| 12 | refresh token 轮换防重放 | 第一次成功 / 第二次失败 |

脚本行为：

- 开头做**网关连通性预检**，不通直接 `exit 2` 并给出排查建议，不产生 12 个假 FAIL
- 连接失败（curl `000`）**自动重试一次**再判 FAIL，避免偶发抖动造成假红
- 每项失败会打印**实际 HTTP 码 + 实际响应体**
- 末尾输出 `通过 X / 失败 Y / 共 12`；全绿 `exit 0`，有失败 `exit 1`

> **前置条件**：5 个服务全部就绪（`/actuator/health` 返回 200）、
> Docker 基础设施运行中（脚本会用 `docker exec e-platform-mysql` 探测数据夹具）。
> 测试账号见「访问地址」章节。

### 排查测试脚本问题时的三个已知环境陷阱

1. **本机可能注入 `HTTP_PROXY`**：`curl localhost:8088` 不加 `--noproxy` 会被代理
   拦截返回 502 空 body，看起来像「服务全挂」。两个测试脚本都已在开头 `unset` 代理变量。
2. **zsh 会把 URL 里的 `?` 当 glob 吃掉**，命令静默不执行却看起来成功。
   含 `?` 的 URL **必须加引号**；脚本请用 `bash` 执行。
3. **判服务存活只能信 `curl /actuator/health`**：`lsof -iTCP` / `ps` 在受限环境下
   会返回假阴性（显示端口 closed 但服务其实活着）。

## 许可证

MIT License
