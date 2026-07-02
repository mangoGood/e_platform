<template>
  <div class="product-detail-page container page-container">
    <div v-loading="loading" class="product-content">
      <div class="product-main">
        <div class="product-gallery">
          <el-image
            :src="product.mainImage || ''"
            fit="contain"
            class="main-image"
          >
            <template #error>
              <div class="image-placeholder large">
                <el-icon :size="80"><ShoppingBag /></el-icon>
                <span>{{ product.name }}</span>
              </div>
            </template>
          </el-image>
        </div>
        
        <div class="product-info">
          <h1 class="product-title">{{ product.name }}</h1>
          <p class="product-desc">{{ product.description }}</p>
          
          <div class="price-section">
            <span class="label">价格</span>
            <span class="price">{{ product.price }}</span>
            <span v-if="product.originalPrice" class="original-price">
              ¥{{ product.originalPrice }}
            </span>
          </div>
          
          <div class="sales-info">
            <span>销量：{{ product.sales }}</span>
            <span>库存：{{ product.stock }}</span>
          </div>
          
          <div class="quantity-section">
            <span class="label">数量</span>
            <el-input-number
              v-model="quantity"
              :min="1"
              :max="product.stock"
              size="large"
            />
          </div>
          
          <div class="action-buttons">
            <el-button
              type="primary"
              size="large"
              :disabled="product.stock === 0"
              @click="handleBuyNow"
            >
              立即购买
            </el-button>
            <el-button
              size="large"
              :disabled="product.stock === 0"
              @click="handleAddToCart"
            >
              <el-icon><ShoppingCart /></el-icon>
              加入购物车
            </el-button>
          </div>
        </div>
      </div>
      
      <div class="product-tabs">
        <el-tabs v-model="activeTab">
          <el-tab-pane label="商品详情" name="detail">
            <div class="detail-content">
              {{ product.description || '暂无详情' }}
            </div>
          </el-tab-pane>
          <el-tab-pane label="商品评论" name="comments">
            <div class="comments-content">
              <div v-if="reviews.length === 0">
                <el-empty description="暂无评论" />
              </div>
              <div v-else class="review-list">
                <div v-for="review in reviews" :key="review.id" class="review-item">
                  <div class="review-header">
                    <span class="review-user">用户#{{ review.userId }}</span>
                    <el-rate v-model="review.rating" disabled size="small" />
                    <span class="review-time">{{ review.createTime }}</span>
                  </div>
                  <div class="review-body">{{ review.content }}</div>
                </div>
              </div>
              <div v-if="userStore.isLoggedIn" class="review-form">
                <el-divider />
                <h4>发表评论</h4>
                <el-rate v-model="newReview.rating" />
                <el-input
                  v-model="newReview.content"
                  type="textarea"
                  :rows="3"
                  placeholder="请输入评论内容"
                  style="margin-top: 10px"
                />
                <el-button type="primary" size="small" style="margin-top: 10px" @click="submitReview">
                  提交评论
                </el-button>
              </div>
            </div>
          </el-tab-pane>
        </el-tabs>
      </div>
    </div>

    <el-dialog v-model="buyDialogVisible" title="确认购买" width="500px" :close-on-click-modal="false">
      <div class="buy-confirm">
        <div class="confirm-product">
          <span class="confirm-name">{{ product.name }}</span>
          <span class="confirm-qty">x{{ quantity }}</span>
          <span class="confirm-price">¥{{ (product.price * quantity).toFixed(2) }}</span>
        </div>
        <el-divider />
        <el-form :model="orderForm" label-width="80px">
          <el-form-item label="收货人">
            <el-input v-model="orderForm.receiverName" placeholder="请输入收货人姓名" />
          </el-form-item>
          <el-form-item label="联系电话">
            <el-input v-model="orderForm.receiverPhone" placeholder="请输入联系电话" />
          </el-form-item>
          <el-form-item label="收货地址">
            <el-input v-model="orderForm.receiverAddress" placeholder="请输入收货地址" />
          </el-form-item>
        </el-form>
        <div class="confirm-total">
          <span>合计：</span>
          <span class="total-price">¥{{ (product.price * quantity).toFixed(2) }}</span>
        </div>
      </div>
      <template #footer>
        <el-button @click="buyDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="buying" @click="confirmBuy">
          确认支付（测试）
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { useUserStore } from '@/stores/user'
import { ShoppingBag } from '@element-plus/icons-vue'
import productApi from '@/api/product'
import orderApi from '@/api/order'
import { ElMessage } from 'element-plus'

const router = useRouter()
const route = useRoute()
const userStore = useUserStore()

const loading = ref(false)
const product = ref({})
const quantity = ref(1)
const activeTab = ref('detail')
const reviews = ref([])
const newReview = ref({ rating: 5, content: '' })
const buyDialogVisible = ref(false)
const buying = ref(false)
const orderForm = ref({
  receiverName: '',
  receiverPhone: '',
  receiverAddress: ''
})

onMounted(async () => {
  await loadProduct()
  await loadReviews()
})

const loadProduct = async () => {
  loading.value = true
  try {
    const res = await productApi.getProductById(route.params.id)
    product.value = res.data
  } catch (error) {
    console.error('加载商品失败:', error)
  } finally {
    loading.value = false
  }
}

const loadReviews = async () => {
  try {
    const res = await productApi.getReviews(route.params.id)
    reviews.value = res.data || []
  } catch (error) {
    console.error('加载评论失败:', error)
  }
}

const submitReview = async () => {
  if (!newReview.value.content.trim()) {
    ElMessage.warning('请输入评论内容')
    return
  }
  try {
    await productApi.addReview({
      productId: product.value.id,
      rating: newReview.value.rating,
      content: newReview.value.content
    })
    ElMessage.success('评论成功')
    newReview.value = { rating: 5, content: '' }
    await loadReviews()
  } catch (error) {
    ElMessage.error('评论失败')
  }
}

const handleBuyNow = () => {
  if (!userStore.isLoggedIn) {
    ElMessage.warning('请先登录')
    router.push({ name: 'Login', query: { redirect: route.fullPath } })
    return
  }
  
  buyDialogVisible.value = true
}

const confirmBuy = async () => {
  if (!orderForm.value.receiverName) {
    ElMessage.warning('请输入收货人姓名')
    return
  }
  if (!orderForm.value.receiverPhone) {
    ElMessage.warning('请输入联系电话')
    return
  }
  if (!orderForm.value.receiverAddress) {
    ElMessage.warning('请输入收货地址')
    return
  }
  
  buying.value = true
  try {
    const createRes = await orderApi.createOrder({
      items: [{ productId: product.value.id, quantity: quantity.value }],
      receiverName: orderForm.value.receiverName,
      receiverPhone: orderForm.value.receiverPhone,
      receiverAddress: orderForm.value.receiverAddress
    })
    
    const orderId = createRes.data.id
    
    await orderApi.payOrder(orderId)
    
    buyDialogVisible.value = false
    ElMessage.success('购买成功！订单已支付')
    
    await loadProduct()
    
    router.push('/orders')
  } catch (error) {
    console.error('购买失败:', error)
    ElMessage.error(error.response?.data?.message || '购买失败，请重试')
  } finally {
    buying.value = false
  }
}

const handleAddToCart = async () => {
  if (!userStore.isLoggedIn) {
    ElMessage.warning('请先登录')
    router.push({ name: 'Login', query: { redirect: route.fullPath } })
    return
  }
  
  try {
    await orderApi.addToCart(product.value.id, quantity.value)
    ElMessage.success('已添加到购物车')
  } catch (error) {
    console.error('添加购物车失败:', error)
  }
}
</script>

<style lang="scss" scoped>
.product-detail-page {
  .product-content {
    background: #fff;
    border-radius: 8px;
    padding: 30px;
    
    .product-main {
      display: flex;
      gap: 40px;
      margin-bottom: 40px;
      
      .product-gallery {
        flex: 0 0 400px;
        
        .main-image {
          width: 400px;
          height: 400px;
          border: 1px solid #eee;
          border-radius: 8px;
          background: #f5f7fa;
        }
        
        .image-placeholder {
          display: flex;
          flex-direction: column;
          align-items: center;
          justify-content: center;
          width: 100%;
          height: 100%;
          color: #c0c4cc;
          background: linear-gradient(135deg, #f5f7fa 0%, #e4e7ed 100%);
          
          span {
            margin-top: 12px;
            font-size: 14px;
            color: #909399;
          }
          
          &.large {
            height: 400px;
          }
        }
      }
      
      .product-info {
        flex: 1;
        
        .product-title {
          font-size: 24px;
          margin-bottom: 15px;
          color: #333;
        }
        
        .product-desc {
          color: #999;
          margin-bottom: 20px;
          line-height: 1.6;
        }
        
        .price-section {
          background: #fff5f0;
          padding: 20px;
          border-radius: 8px;
          margin-bottom: 20px;
          
          .label {
            color: #999;
            margin-right: 20px;
          }
          
          .price {
            font-size: 28px;
            color: #ff6700;
            font-weight: bold;
            
            &::before {
              content: '¥';
              font-size: 18px;
            }
          }
          
          .original-price {
            color: #999;
            text-decoration: line-through;
            margin-left: 10px;
          }
        }
        
        .sales-info {
          color: #999;
          margin-bottom: 20px;
          
          span {
            margin-right: 20px;
          }
        }
        
        .quantity-section {
          margin-bottom: 30px;
          
          .label {
            display: inline-block;
            width: 60px;
            color: #666;
          }
        }
        
        .action-buttons {
          display: flex;
          gap: 15px;
          
          .el-button--primary {
            background-color: #ff6700;
            border-color: #ff6700;
            
            &:hover {
              background-color: #f25807;
              border-color: #f25807;
            }
          }
        }
      }
    }
    
    .product-tabs {
      .detail-content {
        padding: 20px;
        line-height: 1.8;
        color: #666;
      }
      
      .comments-content {
        padding: 20px;

        .review-list {
          .review-item {
            padding: 15px 0;
            border-bottom: 1px solid #f0f0f0;

            .review-header {
              display: flex;
              align-items: center;
              gap: 12px;
              margin-bottom: 8px;

              .review-user {
                font-weight: bold;
                color: #333;
              }

              .review-time {
                color: #999;
                font-size: 13px;
                margin-left: auto;
              }
            }

            .review-body {
              color: #666;
              line-height: 1.6;
            }
          }
        }

        .review-form {
          h4 {
            margin-bottom: 10px;
          }
        }
      }
    }
  }
}

.buy-confirm {
  .confirm-product {
    display: flex;
    align-items: center;
    gap: 15px;
    padding: 10px 0;
    
    .confirm-name {
      flex: 1;
      font-size: 16px;
      font-weight: bold;
    }
    
    .confirm-qty {
      color: #999;
    }
    
    .confirm-price {
      color: #ff6700;
      font-weight: bold;
      font-size: 18px;
    }
  }
  
  .confirm-total {
    text-align: right;
    padding: 10px 0;
    
    .total-price {
      font-size: 24px;
      color: #ff6700;
      font-weight: bold;
    }
  }
}

@media (max-width: 992px) {
  .product-main {
    flex-direction: column;
    
    .product-gallery {
      flex: none;
      
      .main-image {
        width: 100%;
        height: auto;
      }
    }
  }
}
</style>
