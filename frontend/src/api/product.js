import request from '@/utils/request'

export default {
  getProductList(params) {
    return request.get('/product/list', { params })
  },
  
  getProductById(id) {
    return request.get(`/product/${id}`)
  },
  
  getCategories() {
    return request.get('/category/all')
  },
  
  addProduct(data) {
    return request.post('/product/add', data)
  },
  
  updateProduct(id, data) {
    return request.put(`/product/${id}`, data)
  },
  
  deleteProduct(id) {
    return request.delete(`/product/${id}`)
  },
  
  getSellerProducts(sellerId) {
    return request.get(`/product/seller/${sellerId}`)
  },

  getProductsByIds(ids) {
    return request.get('/product/batch', { params: { ids: ids.join(',') } })
  },

  // ---------------------------------------------------------------------------
  // @deprecated 旧的一层扁平评论接口（/api/review）。
  //
  // 已被 `@/api/comment` 的三级评论接口取代，页面中的引用均已移除。
  // 这里<b>刻意保留函数本体</b>：后端 `ReviewController` 仍在作为兼容层运行，
  // 删掉它并不能让前端更干净，却会让「需要临时回退」时无路可走。
  //
  // 新代码请一律使用 commentApi.getProductComments / createComment / deleteComment。
  // ---------------------------------------------------------------------------

  /**
   * @deprecated 请改用 `commentApi.getProductComments(productId)`。
   * @param {number|string} productId 商品 id
   * @returns {Promise<object>} Result 包装体
   */
  getReviews(productId) {
    return request.get(`/review/product/${productId}`)
  },

  /**
   * @deprecated 请改用 `commentApi.createComment(data)`。
   * @param {object} data 评价内容
   * @returns {Promise<object>} Result 包装体
   */
  addReview(data) {
    return request.post('/review', data)
  },

  /**
   * @deprecated 请改用 `commentApi.deleteComment(id)`。
   * @param {number} id 评价 id
   * @returns {Promise<object>} Result 包装体
   */
  deleteReview(id) {
    return request.delete(`/review/${id}`)
  }
}
