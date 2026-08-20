package com.ecommerce.product.job;

import com.ecommerce.product.mapper.ProductMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 商品评分全量校准任务。
 *
 * <h3>为什么同步更新之外还要这个</h3>
 * 主链路已经在 L1 的新增 / 改分 / 删除事务里调用 {@code refreshRating} 重算了，
 * 正常情况下评分<b>永远是准的</b>。但仍有几类情况会漏：
 * <ul>
 *   <li>DBA 或运营直接在数据库里改了 {@code comment.status}（隐藏违规评价）；</li>
 *   <li>migration 脚本批量清理了脏数据；</li>
 *   <li>将来新增的写入路径忘了调 {@code refreshRating}。</li>
 * </ul>
 * 这个任务每天凌晨用一条 {@code LEFT JOIN} 派生表把全表刷一遍，
 * 成本极低，却能让"评分错了且永远错下去"这件事不可能发生。
 *
 * <h3>为什么不用 MQ</h3>
 * 任务书明确要求本期不引入 RabbitMQ。评分聚合本来也不需要——
 * 它和评论写入天然在同一个库同一个事务里，用 MQ 反而把强一致降级成最终一致。
 */
@Component
public class RatingSyncJob {

    private static final Logger log = LoggerFactory.getLogger(RatingSyncJob.class);

    @Autowired
    private ProductMapper productMapper;

    /**
     * 每天 03:30 全量校准一次商品评分。
     *
     * <p>选凌晨是因为这条 UPDATE 会扫全表；本项目数据量下耗时可忽略，
     * 但保持"批量作业放低峰"的习惯。
     */
    @Scheduled(cron = "0 30 3 * * ?")
    public void syncAllRating() {
        long startedAt = System.currentTimeMillis();
        try {
            int affected = productMapper.refreshAllRating();
            log.info("商品评分全量校准完成：影响 {} 行，耗时 {} ms",
                    affected, System.currentTimeMillis() - startedAt);
        } catch (Exception e) {
            // 定时任务抛异常会被调度器吞掉且不再重试，这里显式记录，避免静默失败。
            log.error("商品评分全量校准失败", e);
        }
    }
}
