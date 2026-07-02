<template>
  <div class="cart-page container page-container">
    <div class="page-header">
      <h2>购物车</h2>
    </div>
    
    <div v-loading="loading" class="cart-content">
      <div v-if="cartItems.length === 0" class="empty-cart">
        <el-empty description="购物车是空的">
          <el-button type="primary" @click="$router.push('/products')">
            去购物
          </el-button>
        </el-empty>
      </div>
      
      <div v-else class="cart-list">
        <div
          v-for="item in cartItems"
          :key="item.id"
          class="cart-item"
        >
          <div class="item-image">
            <el-image :src="item.productImage || ''" fit="cover">
              <template #error>
                <div class="image-placeholder">
                  <el-icon :size="32"><ShoppingBag /></el-icon>
                </div>
              </template>
            </el-image>
          </div>
          
          <div class="item-info">
            <h3 class="item-name">{{ item.productName || '商品#' + item.productId }}</h3>
            <p class="item-price-single">单价：¥{{ item.productPrice }}</p>
          </div>
          
          <div class="item-price">
            <span class="price">¥{{ (item.productPrice * item.quantity).toFixed(2) }}</span>
          </div>
          
          <div class="item-quantity">
            <el-input-number
              v-model="item.quantity"
              :min="1"
              :max="item.productStock || 99"
              size="small"
              @change="handleQuantityChange(item)"
            />
          </div>
          
          <div class="item-actions">
            <el-button
              type="danger"
              text
              @click="handleRemove(item.productId)"
            >
              删除
            </el-button>
          </div>
        </div>
      </div>
      
      <div v-if="cartItems.length > 0" class="cart-footer">
        <div class="footer-left">
          <el-button text @click="handleClearCart">清空购物车</el-button>
        </div>
        
        <div class="footer-right">
          <span class="total-label">合计：</span>
          <span class="total-price">¥{{ totalPrice }}</span>
          <el-button
            type="primary"
            size="large"
            @click="handleCheckout"
          >
            结算
          </el-button>
        </div>
      </div>
    </div>

    <el-dialog v-model="checkoutVisible" title="确认订单" width="500px" :close-on-click-modal="false">
      <div class="checkout-confirm">
        <div class="checkout-items">
          <div v-for="item in cartItems" :key="item.id" class="checkout-item">
            <span class="checkout-name">{{ item.productName }}</span>
            <span class="checkout-qty">x{{ item.quantity }}</span>
            <span class="checkout-price">¥{{ (item.productPrice * item.quantity).toFixed(2) }}</span>
          </div>
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
        <div class="checkout-total">
          <span>合计：</span>
          <span class="total-price">¥{{ totalPrice }}</span>
        </div>
      </div>
      <template #footer>
        <el-button @click="checkoutVisible = false">取消</el-button>
        <el-button type="primary" :loading="checkingOut" @click="confirmCheckout">
          确认支付（测试）
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { ShoppingBag } from '@element-plus/icons-vue'
import orderApi from '@/api/order'
import productApi from '@/api/product'
import { ElMessage, ElMessageBox } from 'element-plus'

const router = useRouter()
const loading = ref(false)
const cartItems = ref([])
const checkoutVisible = ref(false)
const checkingOut = ref(false)
const orderForm = ref({
  receiverName: '',
  receiverPhone: '',
  receiverAddress: ''
})

const totalPrice = computed(() => {
  return cartItems.value.reduce((total, item) => {
    return total + (item.productPrice || 0) * item.quantity
  }, 0).toFixed(2)
})

onMounted(async () => {
  await loadCart()
})

const loadCart = async () => {
  loading.value = true
  try {
    const res = await orderApi.getCart()
    const carts = res.data || []
    
    if (carts.length === 0) {
      cartItems.value = []
      return
    }

    // 批量查询商品信息，避免 N+1 问题
    const productIds = [...new Set(carts.map(c => c.productId))]
    const productMap = {}
    try {
      const batchRes = await productApi.getProductsByIds(productIds)
      const products = batchRes.data || []
      for (const p of products) {
        productMap[p.id] = p
      }
    } catch (e) {
      console.error('批量查询商品失败:', e)
    }

    const enrichedItems = carts.map(cart => {
      const product = productMap[cart.productId]
      return {
        ...cart,
        productName: product ? product.name : '商品#' + cart.productId,
        productPrice: product ? product.price : 0,
        productImage: product ? product.mainImage : '',
        productStock: product ? product.stock : 0
      }
    })
    cartItems.value = enrichedItems
  } catch (error) {
    console.error('加载购物车失败:', error)
  } finally {
    loading.value = false
  }
}

const handleQuantityChange = async (item) => {
  try {
    await orderApi.updateCart(item.productId, item.quantity)
  } catch (error) {
    console.error('更新失败:', error)
  }
}

const handleRemove = async (productId) => {
  try {
    await ElMessageBox.confirm('确定要删除该商品吗？', '提示', {
      confirmButtonText: '确定',
      cancelButtonText: '取消',
      type: 'warning'
    })
    
    await orderApi.removeFromCart(productId)
    ElMessage.success('已删除')
    await loadCart()
  } catch (error) {
    if (error !== 'cancel') {
      console.error('删除失败:', error)
    }
  }
}

const handleClearCart = async () => {
  try {
    await ElMessageBox.confirm('确定要清空购物车吗？', '提示', {
      confirmButtonText: '确定',
      cancelButtonText: '取消',
      type: 'warning'
    })
    
    await orderApi.clearCart()
    ElMessage.success('已清空')
    cartItems.value = []
  } catch (error) {
    if (error !== 'cancel') {
      console.error('清空失败:', error)
    }
  }
}

const handleCheckout = () => {
  checkoutVisible.value = true
}

const confirmCheckout = async () => {
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
  
  checkingOut.value = true
  try {
    const items = cartItems.value.map(item => ({
      productId: item.productId,
      quantity: item.quantity
    }))
    
    const createRes = await orderApi.createOrder({
      items,
      receiverName: orderForm.value.receiverName,
      receiverPhone: orderForm.value.receiverPhone,
      receiverAddress: orderForm.value.receiverAddress
    })
    
    const orderId = createRes.data.id
    
    await orderApi.payOrder(orderId)
    
    await orderApi.clearCart()
    
    checkoutVisible.value = false
    ElMessage.success('购买成功！订单已支付')
    cartItems.value = []
    
    router.push('/orders')
  } catch (error) {
    console.error('结算失败:', error)
    ElMessage.error(error.response?.data?.message || '结算失败，请重试')
  } finally {
    checkingOut.value = false
  }
}
</script>

<style lang="scss" scoped>
.cart-page {
  .page-header {
    margin-bottom: 20px;
    
    h2 {
      font-size: 24px;
      color: #333;
    }
  }
  
  .cart-content {
    background: #fff;
    border-radius: 8px;
    padding: 20px;
    
    .empty-cart {
      padding: 60px 0;
    }
    
    .cart-list {
      .cart-item {
        display: flex;
        align-items: center;
        padding: 20px;
        border-bottom: 1px solid #f0f0f0;
        
        &:last-child {
          border-bottom: none;
        }
        
        .item-image {
          width: 100px;
          height: 100px;
          margin-right: 20px;
          
          .el-image {
            width: 100%;
            height: 100%;
            border-radius: 8px;
          }
          
          .image-placeholder {
            display: flex;
            align-items: center;
            justify-content: center;
            width: 100%;
            height: 100%;
            background: linear-gradient(135deg, #f5f7fa 0%, #e4e7ed 100%);
            color: #c0c4cc;
            border-radius: 8px;
          }
        }
        
        .item-info {
          flex: 1;
          
          .item-name {
            font-size: 16px;
            margin-bottom: 8px;
          }
          
          .item-price-single {
            color: #999;
            font-size: 14px;
          }
        }
        
        .item-price {
          width: 120px;
          text-align: center;
          
          .price {
            color: #ff6700;
            font-weight: bold;
          }
        }
        
        .item-quantity {
          width: 150px;
          text-align: center;
        }
        
        .item-actions {
          width: 80px;
          text-align: center;
        }
      }
    }
    
    .cart-footer {
      display: flex;
      justify-content: space-between;
      align-items: center;
      padding: 20px;
      background: #f5f5f5;
      border-radius: 8px;
      margin-top: 20px;
      
      .footer-right {
        display: flex;
        align-items: center;
        gap: 20px;
        
        .total-label {
          color: #666;
        }
        
        .total-price {
          font-size: 24px;
          color: #ff6700;
          font-weight: bold;
        }
        
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
}

.checkout-confirm {
  .checkout-items {
    .checkout-item {
      display: flex;
      align-items: center;
      gap: 15px;
      padding: 8px 0;
      
      .checkout-name {
        flex: 1;
        font-size: 14px;
      }
      
      .checkout-qty {
        color: #999;
        font-size: 14px;
      }
      
      .checkout-price {
        color: #ff6700;
        font-weight: bold;
      }
    }
  }
  
  .checkout-total {
    text-align: right;
    padding: 10px 0;
    
    .total-price {
      font-size: 24px;
      color: #ff6700;
      font-weight: bold;
    }
  }
}
</style>
