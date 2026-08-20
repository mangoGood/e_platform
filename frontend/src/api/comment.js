import request from '@/utils/request'

/**
 * 三级评论接口（后端 `CommentController`，前缀 `/api/comment`）。
 *
 * <h3>三级结构的铁律</h3>
 * 树深恒为 2：L1 买家评价 → 其下挂<b>最多一条</b> L2 卖家回复 + N 条 L3 追问。
 * 对 L2/L3 再追问会被服务端强制拍平到同一条 L1 之下，不会产生第 4 层。
 * 因此渲染组件<b>不要写递归</b>，固定两层即可。
 *
 * <h3>响应结构（getProductComments）</h3>
 * <pre>
 * data.comments      分页对象 { records, total, size, current, pages }
 * data.summary       { ratingAvg, ratingCount, distribution: {"1".."5"} }
 * data.viewerContext { loggedIn, userId, seller,
 *                      canReview, reviewDeniedReason,
 *                      canReply,  replyDeniedReason,
 *                      canAsk,    askDeniedReason }
 * </pre>
 * ⚠️ `data` 是对象不是数组。直接 `res.data || []` 拿到的是对象，
 * v-for 遍历不报错也渲染不出东西，`length` 为 undefined 连空态都不显示 ——
 * 一片空白且无任何报错。务必显式取 `res.data.comments.records`。
 */
export default {
  /**
   * 查询商品评论树（游客可读）。
   *
   * @param {number|string} productId 商品 id
   * @param {{pageNum?: number, pageSize?: number, sort?: string, askSize?: number}} params 分页与排序
   * @returns {Promise<object>} Result 包装体
   */
  getProductComments(productId, params = {}) {
    return request.get(`/comment/product/${productId}`, { params })
  },

  /**
   * 展开某条 L1 之下的全部追问（游客可读，时间正序）。
   *
   * @param {number} rootId L1 评论 id
   * @param {{pageNum?: number, pageSize?: number}} params 分页
   * @returns {Promise<object>} Result 包装体，data 为 PageResult<CommentVO>
   */
  getReplies(rootId, params = {}) {
    return request.get(`/comment/${rootId}/replies`, { params })
  },

  /**
   * 预检「我能否评价该商品」。
   *
   * @param {number|string} productId 商品 id
   * @returns {Promise<object>} Result 包装体，data 为 {canReview, reason, orderId, ...}
   */
  canReview(productId) {
    return request.get('/comment/can-review', { params: { productId } })
  },

  /**
   * 发表 L1 买家评价。
   *
   * @param {{productId: number, rating: number, content: string, images?: string}} data 入参
   * @returns {Promise<object>} Result 包装体
   */
  createComment(data) {
    return request.post('/comment', data)
  },

  /**
   * 发表 L2 卖家回复。
   *
   * @param {{parentId: number, content: string}} data 入参，parentId 为 L1 评价 id
   * @returns {Promise<object>} Result 包装体
   */
  replyComment(data) {
    return request.post('/comment/reply', data)
  },

  /**
   * 发表 L3 第三方追问。
   *
   * ⚠️ 字段名是 `commentId` 而不是 `parentId`。
   * 这一点与任务书里写的不一致，以后端 `CommentAskDTO` 为准
   * （`CommentService#askL3` 读的是 `dto.getCommentId()`），
   * 传 parentId 会被解析成 null 并触发 400。
   *
   * @param {{commentId: number, content: string}} data 入参
   * @returns {Promise<object>} Result 包装体
   */
  askComment(data) {
    return request.post('/comment/ask', data)
  },

  /**
   * 编辑评论（发表后 24 小时内，仅作者本人）。
   *
   * @param {number} id 评论 id
   * @param {{content?: string, rating?: number, images?: string}} data 入参
   * @returns {Promise<object>} Result 包装体
   */
  updateComment(id, data) {
    return request.put(`/comment/${id}`, data)
  },

  /**
   * 删除评论（逻辑删除，仅作者本人或管理员）。
   *
   * @param {number} id 评论 id
   * @returns {Promise<object>} Result 包装体
   */
  deleteComment(id) {
    return request.delete(`/comment/${id}`)
  }
}
