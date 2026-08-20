package com.ecommerce.product.vo;

import java.io.Serializable;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 商品评分汇总。
 *
 * <p>{@code ratingAvg / ratingCount} 直接读 {@code product} 表的聚合列——
 * 它们在 L1 写入/删除的<b>同一个事务</b>里被重算写入，读取时零聚合开销。
 * {@code distribution} 则实时 {@code GROUP BY rating}，星级柱状图的数据量很小。
 */
public class RatingSummaryVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 平均分，保留两位小数，无评价时为 0.00。 */
    private BigDecimal ratingAvg = BigDecimal.ZERO;

    /** 有效评价数（L1）。 */
    private Integer ratingCount = 0;

    /** 星级分布：key 为 1-5，value 为条数，五个键恒存在（无数据补 0）。 */
    private Map<Integer, Integer> distribution = defaultDistribution();

    /**
     * @return 1-5 星均为 0 的初始分布
     */
    private static Map<Integer, Integer> defaultDistribution() {
        Map<Integer, Integer> map = new LinkedHashMap<>();
        for (int star = 1; star <= 5; star++) {
            map.put(star, 0);
        }
        return map;
    }

    public BigDecimal getRatingAvg() {
        return ratingAvg;
    }

    public void setRatingAvg(BigDecimal ratingAvg) {
        this.ratingAvg = ratingAvg == null ? BigDecimal.ZERO : ratingAvg;
    }

    public Integer getRatingCount() {
        return ratingCount;
    }

    public void setRatingCount(Integer ratingCount) {
        this.ratingCount = ratingCount == null ? 0 : ratingCount;
    }

    public Map<Integer, Integer> getDistribution() {
        return distribution;
    }

    public void setDistribution(Map<Integer, Integer> distribution) {
        this.distribution = distribution == null ? defaultDistribution() : distribution;
    }
}
