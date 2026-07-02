import request from '@/utils/request'

export default {
  createOrder(data) {
    return request.post('/order/create', data)
  },
  
  payOrder(orderId) {
    return request.post(`/order/pay/${orderId}`)
  },
  
  deliverOrder(orderId) {
    return request.post(`/order/deliver/${orderId}`)
  },
  
  receiveOrder(orderId) {
    return request.post(`/order/receive/${orderId}`)
  },
  
  cancelOrder(orderId) {
    return request.post(`/order/cancel/${orderId}`)
  },
  
  getOrderById(orderId) {
    return request.get(`/order/${orderId}`)
  },
  
  getUserOrders(params = {}) {
    return request.get('/order/user', { params })
  },
  
  getSellerOrders(params = {}) {
    return request.get('/order/seller', { params })
  },
  
  getOrderItems(orderId) {
    return request.get(`/order/items/${orderId}`)
  },
  
  addToCart(productId, quantity) {
    return request.post('/order/cart/add', null, {
      params: { productId, quantity }
    })
  },
  
  updateCart(productId, quantity) {
    return request.put('/order/cart', null, {
      params: { productId, quantity }
    })
  },
  
  removeFromCart(productId) {
    return request.delete(`/order/cart/${productId}`)
  },
  
  clearCart() {
    return request.delete('/order/cart')
  },
  
  getCart() {
    return request.get('/order/cart')
  },

  // 收货地址
  getAddresses() {
    return request.get('/address/list')
  },

  getAddress(id) {
    return request.get(`/address/${id}`)
  },

  addAddress(data) {
    return request.post('/address', data)
  },

  updateAddress(data) {
    return request.put('/address', data)
  },

  deleteAddress(id) {
    return request.delete(`/address/${id}`)
  },

  setDefaultAddress(id) {
    return request.put(`/address/default/${id}`)
  }
}
