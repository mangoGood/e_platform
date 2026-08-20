package com.ecommerce.product.vo;

import com.ecommerce.common.result.PageResult;

import java.io.Serializable;

/**
 * 商品评论区的完整响应。
 *
 * <p>一次请求把三样东西给全，避免详情页开三个接口：
 * <ol>
 *   <li>{@code comments}：L1 分页列表，每条 L1 内嵌其 L2 回复与 L3 追问预览；</li>
 *   <li>{@code summary}：评分汇总（平均分 / 总数 / 星级分布）；</li>
 *   <li>{@code viewerContext}：当前浏览者能做什么、不能做什么及原因。</li>
 * </ol>
 */
public class CommentTreeVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** L1 分页列表（每条内含 L2/L3）。 */
    private PageResult<CommentVO> comments;

    /** 评分汇总。 */
    private RatingSummaryVO summary = new RatingSummaryVO();

    /** 浏览者上下文。 */
    private ViewerContextVO viewerContext = new ViewerContextVO();

    public PageResult<CommentVO> getComments() {
        return comments;
    }

    public void setComments(PageResult<CommentVO> comments) {
        this.comments = comments;
    }

    public RatingSummaryVO getSummary() {
        return summary;
    }

    public void setSummary(RatingSummaryVO summary) {
        this.summary = summary == null ? new RatingSummaryVO() : summary;
    }

    public ViewerContextVO getViewerContext() {
        return viewerContext;
    }

    public void setViewerContext(ViewerContextVO viewerContext) {
        this.viewerContext = viewerContext == null ? new ViewerContextVO() : viewerContext;
    }
}
