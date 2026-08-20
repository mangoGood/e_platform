# e_platform 全量架构方案交付包

> AICoding 架构专家团（主理人：齐构成）产出 · 生成日期 2026-08-06 · 全部 Gate（G0–G6）通过

## 文档清单（按阶段门顺序）

| 阶段 | Gate | 文档 | 行数 | 核心裁决 |
|------|------|------|------|----------|
| Phase 1 资料摄入 | G1 ✅ | [material_digest.md](./material_digest.md) | 300 | 120 条出处摘要 + 26 条并列冲突 |
| Phase 2 行业调研 | G2 ✅ | [research_report.md](./research_report.md) | 561 | 6 家标杆 + 加权矩阵 + 8 风险 |
| Phase 3 高层架构 | G3 ✅ | [高层架构设计.md](./高层架构设计.md) | 853 | **IC-01 用户裁决②：本期升级 Spring Boot 3.x** |
| Phase 4 中游设计 | G4 ✅ | [系统设计.md](./系统设计.md) | 2258 | 11/11，Spring Boot 3.3.4 落地 |
| Phase 4 中游设计 | G4 ✅ | [UserStory.md](./UserStory.md) | 914 | **§6.2 用户裁决 A：演示级性能值冻结** |
| Phase 5 下游设计 | G5 ✅ | [部署设计.md](./部署设计.md) | 747 | 8/8 |
| Phase 5 下游设计 | G5 ✅ | [安全设计.md](./安全设计.md) | 656 | 9/9 |

## 全量统一口径（G6 合稿已锁定）

- **框架版本**：Spring Boot 3.3.4 / Spring Cloud 2023.0.3 / JDK 21 / Spring Security 6.3.x（IC-01 裁决）
- **服务端口（README 真值）**：网关 `e-platform-gateway`=8088（托管前端+API网关）｜`e-platform-user`=8085｜`e-platform-product`=8086｜`e-platform-order`=8087｜`e-platform-mobile`=8089｜MySQL=3306｜Redis=6379
- **Web 入口**：对齐 README/ARCH(A3)，**无独立 Nginx**，前端由网关 :8088 直接托管
- **安全组**：`sg-eplatform-dmz/app/data/redis/mgmt`
- **KMS**：`secboot-eplatform-demo`（演示自举）/ `kms-eplatform-prod` / `vault-eplatform-prod`（演进）
- **WAF**：MVP 不引入独立 WAF；公网暴露时前置腾讯云 WAF SaaS / ModSecurity v3 + CRS 3.3
- **密钥分级**：K1–K5（数据库密码 / 云 AKSK / 服务间密钥 / 用户密码 / API Key·JWT）
- **审计保留期**：业务操作/云资源/堡垒机 ≥180 天；WAF ≥90 天；WORM 不可篡改

## 本次增量范围（五件事）

1. 鉴权安全底座（RBAC + 细粒度接口鉴权 + Token 生命周期 + 网关 HMAC 签名信任链）
2. 三级评论体系（L1 购后评价 / L2 卖家回复 / L3 第三方追问）
3. 并发保护（下单/扣库存排队而非拒绝，Redis 信号量 + 等待队列）
4. 视觉升级（统一色彩令牌 / 骨架屏 / 响应式）
5. 一键启停交付链路修复（start.sh/stop.sh/docker-compose/APK 签名）

## 已知遗留（非阻塞，演进/预留项，标注待人工确认）

- KMS：云 KMS vs HashiCorp Vault 最终选型
- mTLS 启用时机
- O8 对象存储接入 AKSK/CAM
- 管理后台 MFA
