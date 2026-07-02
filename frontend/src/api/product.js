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

  getReviews(productId) {
    return request.get(`/review/product/${productId}`)
  },

  addReview(data) {
    return request.post('/review', data)
  },

  deleteReview(id) {
    return request.delete(`/review/${id}`)
  }
}
