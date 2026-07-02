# 电商平台系统

一个基于 Spring Boot 微服务 + Vue 3 + Android (Kotlin/Compose) 的全栈电商平台，支持买家和卖家两种用户角色，提供完整的购物流程。

## 技术栈

### 后端（Spring Boot 2.7 微服务）
- **Spring Boot 2.7.18** + **Spring Cloud 2021.0.8** - 微服务框架
- **Spring Security + JWT** - 认证授权
- **MyBatis-Plus** - ORM 框架
- **MySQL 8.0** + **Druid** - 关系型数据库与连接池
- **Redis** - 缓存
- **OpenFeign** - 服务间调用
- **Hutool / Fastjson** - 工具库

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

- **网关层**：统一入口，负责 JWT 认证、请求路由、CORS、托管前端静态资源
- **移动端 BFF**：为 Android 客户端提供聚合接口，通过 Feign 调用内部微服务
- **微服务层**：用户、商品、订单各自独立部署，拥有独立数据库表

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
- 商品评价
- Redis 缓存优化

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

```bash
mysql -u root -p < database/init.sql
```

#### 后端启动

```bash
cd backend
mvn clean install -DskipTests

# 设置 JWT 密钥
export JWT_SECRET="your-secret-key-at-least-64-chars"

# 启动各服务
cd e-platform-user && mvn spring-boot:run
cd e-platform-product && mvn spring-boot:run
cd e-platform-order && mvn spring-boot:run
cd e-platform-mobile && mvn spring-boot:run
cd e-platform-gateway && mvn spring-boot:run
```

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
| 测试账号 | buyer1/123456（买家）、seller1/123456（卖家） |

## API 文档

### 用户服务 (8085)
- `POST /user/register` - 用户注册
- `POST /user/login` - 用户登录
- `GET /user/info/{userId}` - 获取用户信息

### 商品服务 (8086)
- `GET /product/list` - 商品列表（分页/分类/搜索）
- `GET /product/{id}` - 商品详情
- `GET /product/batch` - 批量查询商品
- `GET /product/seller/{sellerId}` - 卖家商品列表
- `POST /product/add` - 添加商品
- `PUT /product/{id}` - 更新商品
- `DELETE /product/{id}` - 删除商品
- `GET /category/tree` - 分类树
- `GET /review/{productId}` - 商品评价

### 订单服务 (8087)
- `POST /order/create` - 创建订单
- `POST /order/pay/{orderId}` - 支付订单
- `POST /order/deliver/{orderId}` - 发货
- `POST /order/receive/{orderId}` - 确认收货
- `POST /order/cancel/{orderId}` - 取消订单
- `GET /order/user` - 买家订单列表
- `GET /order/seller` - 卖家订单列表
- `POST /order/cart/add` - 添加购物车
- `GET /order/cart` - 购物车列表
- `GET /address/list` - 地址列表

### 移动端 BFF (8089)
- `POST /mobile/auth/login` - 移动端登录
- `POST /mobile/auth/register` - 移动端注册
- `GET /mobile/home` - 首页聚合数据
- `GET /mobile/products/**` - 商品列表/详情
- `GET /mobile/cart` - 购物车
- `POST /mobile/order/**` - 订单操作

> 所有外部请求统一通过网关(:8088)的 `/api/**` 前缀访问。

## 安全特性

- JWT Token 认证（密钥通过环境变量注入）
- BCrypt 密码加密
- CORS 跨域配置
- SQL 注入防护（MyBatis-Plus）
- 网关层拦截内部接口（`/deduct`、`/restore` 等仅限服务间调用）

## 许可证

MIT License
