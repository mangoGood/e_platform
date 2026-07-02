import request from '@/utils/request'

export default {
  register(data) {
    return request.post('/user/register', data)
  },
  
  login(data) {
    return request.post('/user/login', data)
  },
  
  getUserInfo(userId) {
    return request.get(`/user/info/${userId}`)
  }
}
