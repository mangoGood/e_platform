<template>
  <div class="cart-page container page-container">
    <div class="page-header">
      <h2>购物车</h2>
    </div>

    <div v-loading="loading" class="cart-content">
      <div v-if="cartItems.length === 0" class="empty-cart">
        <el-empty description="购物车是空的">
          <el-button type="primary" @click="router.push('/products')">去购物</el-button>
        </el-empty>
      </div>

      <div v-else class="cart-list">
        <div v-for="item in cartItems" :key="item.id" class="cart-item">
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
            <h3 class="item-name">{{ item.productName }}</h3>
            <p class="item-price-single">单价：¥{{ item.productPrice }}</p>
          </div>

          <div class="item-price">
            <span class="price-text">¥{{ lineTotal(item) }}</span>
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
            <el-button type="danger" text @click="handleRemove(item.productId)">删除</el-button>
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
          <el-button type="primary" size="large" @click="handleCheckout">结算</el-button>
        </div>
      </div>
    </div>

    <el-dialog
      v-model="checkoutVisible"
      title="确认订单"
      width="500px"
      :close-on-click-modal="false"
    >
      <div class="checkout-confirm">
        <div class="checkout-items">
          <div v-for="item in cartItems" :key="item.id" class="checkout-item">
            <span class="checkout-name">{{ item.productName }}</span>
            <span class="checkout-qty">x{{ item.quantity }}</span>
            <span class="checkout-price">¥{{ lineTotal(item) }}</span>
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
import { ElMessage, ElMessageBox } from 'element-plus'
import orderApi from '@/api/order'
import productApi from '@/api/product'
import { useCartStore } from '@/stores/cart'

const router = useRouter()
const cartStore = useCartStore()

const loading = ref(false)
const cartItems = ref([])
const checkoutVisible = ref(false)
const checkingOut = ref(false)
const orderForm = ref({
  receiverName: '',
  receiverPhone: '',
  receiverAddress: ''
})

const totalPrice = computed(() =>
  cartItems.value
    .reduce((total, item) => total + (Number(item.productPrice) || 0) * item.quantity, 0)
    .toFixed(2)
)

/**
 * 单行小计。
 *
 * @param {object} item 购物车项
 * @returns {string} 两位小数金额
 */
function lineTotal(item) {
  return ((Number(item.productPrice) || 0) * item.quantity).toFixed(2)
}

onMounted(loadCart)

/**
 * 加载购物车并补齐商品信息。
 *
 * @returns {Promise<void>}
 */
async function loadCart() {
  loading.value = true
  try {
    const res = await orderApi.getCart()
    const carts = res.data || []

    if (carts.length === 0) {
      cartItems.value = []
      cartStore.setItems([])
      return
    }

    // 批量查询商品信息，避免 N+1 问题
    const productIds = [...new Set(carts.map((c) => c.productId))]
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

    cartItems.value = carts.map((cart) => {
      const product = productMap[cart.productId]
      return {
        ...cart,
        productName: product ? product.name : `商品#${cart.productId}`,
        productPrice: product ? product.price : 0,
        productImage: product ? product.mainImage : '',
        productStock: product ? product.stock : 0
      }
    })
    // 顺手把顶栏角标同步成真实数量，避免两处各查一次。
    cartStore.setItems(cartItems.value)
  } catch (error) {
    console.error('加载购物车失败:', error)
  } finally {
    loading.value = false
  }
}

/**
 * 修改数量。
 *
 * @param {object} item 购物车项
 * @returns {Promise<void>}
 */
async function handleQuantityChange(item) {
  try {
    await orderApi.updateCart(item.productId, item.quantity)
  } catch (error) {
    console.error('更新失败:', error)
    // 后端拒绝（如超库存）时回读真实状态，避免界面停留在非法数量上。
    await loadCart()
  }
}

/**
 * 删除单项。
 *
 * @param {number} productId 商品 id
 * @returns {Promise<void>}
 */
async function handleRemove(productId) {
  try {
    await ElMessageBox.confirm('确定要删除该商品吗？', '提示', {
      confirmButtonText: '确定',
      cancelButtonText: '取消',
      type: 'warning'
    })
  } catch {
    return
  }
  await orderApi.removeFromCart(productId)
  ElMessage.success('已删除')
  await loadCart()
}

/**
 * 清空购物车。
 *
 * @returns {Promise<void>}
 */
async function handleClearCart() {
  try {
    await ElMessageBox.confirm('确定要清空购物车吗？', '提示', {
      confirmButtonText: '确定',
      cancelButtonText: '取消',
      type: 'warning'
    })
  } catch {
    return
  }
  await orderApi.clearCart()
  ElMessage.success('已清空')
  cartItems.value = []
  cartStore.clear()
}

/**
 * 打开结算弹窗。
 *
 * @returns {void}
 */
function handleCheckout() {
  checkoutVisible.value = true
}

/**
 * 提交订单并支付。
 *
 * 高峰期 `POST /order/create` 会返回 202 排队，等待与自动重发已在
 * `utils/request.js` 中统一处理，此处拿到的一定是最终结果。
 *
 * @returns {Promise<void>}
 */
async function confirmCheckout() {
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
    const items = cartItems.value.map((item) => ({
      productId: item.productId,
      quantity: item.quantity
    }))

    const createRes = await orderApi.createOrder({
      items,
      receiverName: orderForm.value.receiverName,
      receiverPhone: orderForm.value.receiverPhone,
      receiverAddress: orderForm.value.receiverAddress
    })

    const orderId = createRes.data?.id
    if (!orderId) {
      ElMessage.error('下单失败：未获取到订单号')
      return
    }

    await orderApi.payOrder(orderId)
    await orderApi.clearCart()

    checkoutVisible.value = false
    ElMessage.success('购买成功！订单已支付')
    cartItems.value = []
    cartStore.clear()

    router.push('/orders')
  } catch (error) {
    // 后端中文错误文案已由 request.js 统一弹出，这里不再重复 toast。
    console.error('结算失败:', error)
  } finally {
    checkingOut.value = false
  }
}
</script>

<style lang="scss" scoped>
.cart-page {
  .page-header {
    margin-bottom: $space-5;

    h2 {
      font-size: $font-2xl;
      font-weight: $font-weight-bold;
      color: $color-text-title;
    }
  }
}

.cart-content {
  @include card($space-5);
}

.empty-cart {
  padding: $space-16 0;
}

.cart-item {
  display: flex;
  align-items: center;
  padding: $space-5;
  border-bottom: 1px solid $color-border-lighter;

  &:last-child {
    border-bottom: none;
  }

  .item-image {
    width: 100px;
    height: 100px;
    margin-right: $space-5;
    flex-shrink: 0;

    .el-image {
      width: 100%;
      height: 100%;
      border-radius: $radius-sm;
      overflow: hidden;
    }
  }

  .item-info {
    flex: 1;
    min-width: 0;

    .item-name {
      font-size: $font-md;
      font-weight: $font-weight-medium;
      color: $color-text-primary;
      margin-bottom: $space-2;
      @include text-ellipsis;
    }

    .item-price-single {
      color: $color-text-secondary;
      font-size: $font-base;
    }
  }

  .item-price {
    width: 120px;
    text-align: center;

    .price-text {
      color: $color-price;
      font-weight: $font-weight-bold;
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

.cart-footer {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: $space-4 $space-5;
  background: $color-bg-muted;
  border-radius: $radius-md;
  margin-top: $space-5;

  .footer-right {
    display: flex;
    align-items: center;
    gap: $space-5;

    .total-label {
      color: $color-text-regular;
    }

    .total-price {
      font-size: $font-2xl;
      color: $color-price;
      font-weight: $font-weight-bold;
    }
    // 主色由 element-theme.scss 全局覆盖，无需 .el-button--primary 局部补丁。
  }
}

.checkout-confirm {
  .checkout-item {
    display: flex;
    align-items: center;
    gap: $space-3;
    padding: $space-2 0;

    .checkout-name {
      flex: 1;
      font-size: $font-base;
      color: $color-text-primary;
      @include text-ellipsis;
    }

    .checkout-qty {
      color: $color-text-secondary;
      font-size: $font-base;
    }

    .checkout-price {
      color: $color-price;
      font-weight: $font-weight-bold;
    }
  }

  .checkout-total {
    text-align: right;
    padding: $space-2 0;
    color: $color-text-regular;

    .total-price {
      font-size: $font-2xl;
      color: $color-price;
      font-weight: $font-weight-bold;
    }
  }
}

@media (max-width: 768px) {
  .cart-item {
    flex-wrap: wrap;
    gap: $space-2;

    .item-price,
    .item-quantity,
    .item-actions {
      width: auto;
    }
  }
}
</style>
