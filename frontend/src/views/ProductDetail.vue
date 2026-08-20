<template>
  <div class="product-detail-page container page-container">
    <div v-loading="loading" class="product-content">
      <div class="product-main">
        <div class="product-gallery">
          <el-image :src="product.mainImage || ''" fit="contain" class="main-image">
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
            <el-input-number v-model="quantity" :min="1" :max="product.stock || 1" size="large" />
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
            <el-button size="large" :disabled="product.stock === 0" @click="handleAddToCart">
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
              <!-- 三级评论区已整体拆成独立组件，详情页只负责放置 -->
              <CommentSection v-if="route.params.id" :product-id="route.params.id" />
            </div>
          </el-tab-pane>
        </el-tabs>
      </div>
    </div>

    <el-dialog
      v-model="buyDialogVisible"
      title="确认购买"
      width="500px"
      :close-on-click-modal="false"
    >
      <div class="buy-confirm">
        <div class="confirm-product">
          <span class="confirm-name">{{ product.name }}</span>
          <span class="confirm-qty">x{{ quantity }}</span>
          <span class="confirm-price">¥{{ totalAmount }}</span>
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
          <span class="total-price">¥{{ totalAmount }}</span>
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
import { computed, ref, onMounted } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { ShoppingBag, ShoppingCart } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import { useUserStore } from '@/stores/user'
import { useCartStore } from '@/stores/cart'
import productApi from '@/api/product'
import orderApi from '@/api/order'
import CommentSection from '@/components/comment/CommentSection.vue'

const router = useRouter()
const route = useRoute()
const userStore = useUserStore()
const cartStore = useCartStore()

const loading = ref(false)
const product = ref({})
const quantity = ref(1)
const activeTab = ref('detail')
const buyDialogVisible = ref(false)
const buying = ref(false)
const orderForm = ref({
  receiverName: '',
  receiverPhone: '',
  receiverAddress: ''
})

const totalAmount = computed(() => {
  const price = Number(product.value.price) || 0
  return (price * quantity.value).toFixed(2)
})

onMounted(loadProduct)

/**
 * 加载商品详情。
 *
 * @returns {Promise<void>}
 */
async function loadProduct() {
  loading.value = true
  try {
    const res = await productApi.getProductById(route.params.id)
    product.value = res.data || {}
  } catch (error) {
    console.error('加载商品失败:', error)
  } finally {
    loading.value = false
  }
}

/**
 * 立即购买：先校验登录，再打开确认弹窗。
 *
 * @returns {void}
 */
function handleBuyNow() {
  if (!userStore.isLoggedIn) {
    ElMessage.warning('请先登录')
    router.push({ name: 'Login', query: { redirect: route.fullPath } })
    return
  }
  buyDialogVisible.value = true
}

/**
 * 下单并支付。
 *
 * 下单接口可能返回 HTTP 202「排队中」，排队等待与自动重发已在
 * `utils/request.js` 里统一处理，这里拿到的一定是最终结果，无需特殊分支。
 *
 * @returns {Promise<void>}
 */
async function confirmBuy() {
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

    const orderId = createRes.data?.id
    if (!orderId) {
      ElMessage.error('下单失败：未获取到订单号')
      return
    }

    await orderApi.payOrder(orderId)

    buyDialogVisible.value = false
    ElMessage.success('购买成功！订单已支付')

    await loadProduct()
    router.push('/orders')
  } catch (error) {
    // 不再 ElMessage.error(error.response?.data?.message ...)：
    // request.js 的错误拦截器已经把后端中文文案弹过一次了，
    // 这里再弹就是同一条消息连续出现两个 toast。
    console.error('购买失败:', error)
  } finally {
    buying.value = false
  }
}

/**
 * 加入购物车，并同步顶栏角标。
 *
 * @returns {Promise<void>}
 */
async function handleAddToCart() {
  if (!userStore.isLoggedIn) {
    ElMessage.warning('请先登录')
    router.push({ name: 'Login', query: { redirect: route.fullPath } })
    return
  }

  try {
    await orderApi.addToCart(product.value.id, quantity.value)
    ElMessage.success('已添加到购物车')
    await cartStore.refresh()
  } catch (error) {
    console.error('添加购物车失败:', error)
  }
}
</script>

<style lang="scss" scoped>
.product-detail-page {
  .product-content {
    @include card($space-8);
  }
}

.product-main {
  display: flex;
  gap: $space-10;
  margin-bottom: $space-10;
}

.product-gallery {
  flex: 0 0 400px;

  .main-image {
    width: 400px;
    height: 400px;
    border: 1px solid $color-border-lighter;
    border-radius: $radius-md;
    background: $color-bg-subtle;
    overflow: hidden;
  }

  .image-placeholder.large {
    height: 400px;
  }
}

.product-info {
  flex: 1;
  min-width: 0;

  .product-title {
    font-size: $font-2xl;
    font-weight: $font-weight-bold;
    color: $color-text-title;
    margin-bottom: $space-3;
    line-height: $line-height-tight;
  }

  .product-desc {
    color: $color-text-secondary;
    margin-bottom: $space-5;
    line-height: $line-height-base;
  }

  .price-section {
    display: flex;
    align-items: baseline;
    gap: $space-2;
    background: $brand-50;
    padding: $space-5;
    border-radius: $radius-md;
    margin-bottom: $space-5;

    .label {
      color: $color-text-secondary;
      margin-right: $space-3;
      font-size: $font-base;
    }

    .price {
      font-size: $font-3xl;
      color: $color-price;
      font-weight: $font-weight-bold;

      &::before {
        content: '¥';
        font-size: $font-lg;
        margin-right: 2px;
      }
    }

    .original-price {
      color: $color-text-placeholder;
      text-decoration: line-through;
    }
  }

  .sales-info {
    color: $color-text-secondary;
    font-size: $font-base;
    margin-bottom: $space-5;

    span {
      margin-right: $space-5;
    }
  }

  .quantity-section {
    display: flex;
    align-items: center;
    margin-bottom: $space-8;

    .label {
      display: inline-block;
      width: 60px;
      color: $color-text-regular;
    }
  }

  .action-buttons {
    display: flex;
    gap: $space-3;
    // 主色已由 element-theme.scss 统一覆盖 --el-color-primary，
    // 这里不再需要 .el-button--primary 的局部补丁。
  }
}

.product-tabs {
  margin-top: $space-8;

  .detail-content {
    padding: $space-5;
    line-height: $line-height-loose;
    color: $color-text-regular;
    white-space: pre-wrap;
  }

  .comments-content {
    padding: $space-5 $space-2;
  }
}

.buy-confirm {
  .confirm-product {
    display: flex;
    align-items: center;
    gap: $space-3;
    padding: $space-2 0;

    .confirm-name {
      flex: 1;
      font-size: $font-md;
      font-weight: $font-weight-medium;
      @include text-ellipsis;
    }

    .confirm-qty {
      color: $color-text-secondary;
    }

    .confirm-price {
      color: $color-price;
      font-weight: $font-weight-bold;
      font-size: $font-lg;
    }
  }

  .confirm-total {
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

@media (max-width: 992px) {
  .product-main {
    flex-direction: column;
    gap: $space-6;
  }

  .product-gallery {
    flex: none;

    .main-image {
      width: 100%;
      height: 320px;
    }

    .image-placeholder.large {
      height: 320px;
    }
  }
}
</style>
