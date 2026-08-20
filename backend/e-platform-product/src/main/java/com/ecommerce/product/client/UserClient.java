package com.ecommerce.product.client;

import com.ecommerce.common.result.Result;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

import java.io.Serializable;

/**
 * 用户服务 Feign 客户端（只读用户名）。
 *
 * <h3>为什么不是 {@code /user/internal/batch}</h3>
 * 架构文档设想的批量接口 <b>user 服务并不存在</b>，而本轮任务明确禁止改动 user 模块
 * （并行任务正在改 gateway/common/user，擅自动手会写冲突）。因此改为两级方案：
 * <ol>
 *   <li><b>主路径</b>：{@code comment.username} 昵称<b>快照</b>（migration-v3 新增列）。
 *       写入评论时落一次库，读取评论树时<b>零 RPC</b>，从根上消灭 N+1。</li>
 *   <li><b>兜底</b>：快照缺失（存量脏数据）时回源本客户端的 {@code /user/info/{userId}}，
 *       结果进 Redis 缓存（TTL 30 分钟），同一批查询内还会做进程内去重。</li>
 * </ol>
 *
 * <p>{@code /user/info/{userId}} 是既有接口，Feign 直连 8085 时由
 * {@code X-Internal-Token} 通过下游 {@code GatewaySignatureInterceptor} 的内部通道校验。
 */
@FeignClient(name = "e-platform-user-client", url = "${service.user.url:http://localhost:8085}",
        configuration = com.ecommerce.product.config.FeignConfig.class)
public interface UserClient {

    /**
     * 查询单个用户信息。
     *
     * @param userId 用户 id
     * @return 用户信息，用户不存在时 {@code data} 为 null
     */
    @GetMapping("/user/info/{userId}")
    Result<UserBrief> getUserInfo(@PathVariable("userId") Long userId);

    /** 用户信息的最小投影，只取渲染评论区需要的字段。 */
    class UserBrief implements Serializable {

        private static final long serialVersionUID = 1L;

        /** 用户 id。 */
        private Long id;

        /** 用户名。 */
        private String username;

        /** 头像 URL。 */
        private String avatar;

        public Long getId() {
            return id;
        }

        public void setId(Long id) {
            this.id = id;
        }

        public String getUsername() {
            return username;
        }

        public void setUsername(String username) {
            this.username = username;
        }

        public String getAvatar() {
            return avatar;
        }

        public void setAvatar(String avatar) {
            this.avatar = avatar;
        }
    }
}
