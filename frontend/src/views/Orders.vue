<template>
  <div class="orders-page container page-container">
    <div class="page-header">
      <h2>我的订单</h2>
    </div>
    
    <div v-loading="loading" class="orders-content">
      <div v-if="orders.length === 0" class="empty-orders">
        <el-empty description="暂无订单">
          <el-button type="primary" @click="$router.push('/products')">
            去购物
          </el-button>
        </el-empty>
      </div>
      
      <div v-else class="orders-list">
        <div
          v-for="order in orders"
          :key="order.id"
          class="order-card"
        >
          <div class="order-header">
            <span class="order-no">订单号：{{ order.orderNo }}</span>
            <span class="order-time">{{ order.createTime }}</span>
            <el-tag :type="getStatusType(order.status)">
              {{ getStatusText(order.status) }}
            </el-tag>
          </div>
          
          <div class="order-body">
            <div class="order-items">
              <div v-for="item in (order.items || [])" :key="item.id" class="order-item">
                <div class="item-image">
                  <el-image :src="item.productImage || ''" fit="cover">
                    <template #error>
                      <div class="image-placeholder">
                        <el-icon :size="24"><ShoppingBag /></el-icon>
                      </div>
                    </template>
                  </el-image>
                </div>
                <div class="item-info">
                  <span class="item-name">{{ item.productName }}</span>
                  <span class="item-qty">x{{ item.quantity }}</span>
                </div>
                <div class="item-amount">
                  ¥{{ item.totalAmount }}
                </div>
              </div>
            </div>
            <div class="order-summary">
              <p><strong>总金额：</strong><span class="price">¥{{ order.totalAmount }}</span></p>
              <p><strong>收货人：</strong>{{ order.receiverName }}</p>
              <p><strong>收货地址：</strong>{{ order.receiverAddress }}</p>
            </div>
          </div>
          
          <div class="order-footer">
            <el-button
              v-if="order.status === 0"
              type="primary"
              size="small"
              @click="handlePay(order.id)"
            >
              立即付款
            </el-button>
            <el-button
              v-if="order.status === 2"
              type="success"
              size="small"
              @click="handleReceive(order.id)"
            >
              确认收货
            </el-button>
            <el-button
              v-if="order.status === 0"
              size="small"
              @click="handleCancel(order.id)"
            >
              取消订单
            </el-button>
          </div>
        </div>
      </div>

      <div v-if="total > 0" class="pagination-wrapper">
        <el-pagination
          background
          layout="prev, pager, next"
          :total="total"
          :page-size="pageSize"
          :current-page="currentPage"
          @current-change="handlePageChange"
        />
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { ShoppingBag } from '@element-plus/icons-vue'
import orderApi from '@/api/order'
import { ElMessage, ElMessageBox } from 'element-plus'

const loading = ref(false)
const orders = ref([])
const currentPage = ref(1)
const pageSize = ref(10)
const total = ref(0)

onMounted(async () => {
  await loadOrders()
})

const loadOrders = async () => {
  loading.value = true
  try {
    const res = await orderApi.getUserOrders({ current: currentPage.value, size: pageSize.value })
    const pageData = res.data
    orders.value = pageData.records || []
    total.value = pageData.total || 0
  } catch (error) {
    console.error('加载订单失败:', error)
  } finally {
    loading.value = false
  }
}

const handlePageChange = (page) => {
  currentPage.value = page
  loadOrders()
}

const getStatusText = (status) => {
  const statusMap = {
    0: '待付款',
    1: '待发货',
    2: '待收货',
    3: '已完成',
    4: '已取消',
    5: '已退款'
  }
  return statusMap[status] || '未知'
}

const getStatusType = (status) => {
  const typeMap = {
    0: 'warning',
    1: 'info',
    2: '',
    3: 'success',
    4: 'danger',
    5: 'danger'
  }
  return typeMap[status] || ''
}

const handlePay = async (orderId) => {
  try {
    await orderApi.payOrder(orderId)
    ElMessage.success('支付成功')
    await loadOrders()
  } catch (error) {
    console.error('支付失败:', error)
  }
}

const handleReceive = async (orderId) => {
  try {
    await ElMessageBox.confirm('确认已收到货物吗？', '提示', {
      confirmButtonText: '确定',
      cancelButtonText: '取消',
      type: 'info'
    })
    
    await orderApi.receiveOrder(orderId)
    ElMessage.success('已确认收货')
    await loadOrders()
  } catch (error) {
    if (error !== 'cancel') {
      console.error('确认收货失败:', error)
    }
  }
}

const handleCancel = async (orderId) => {
  try {
    await ElMessageBox.confirm('确定要取消订单吗？取消后库存将恢复。', '提示', {
      confirmButtonText: '确定',
      cancelButtonText: '取消',
      type: 'warning'
    })
    
    await orderApi.cancelOrder(orderId)
    ElMessage.success('订单已取消')
    await loadOrders()
  } catch (error) {
    if (error !== 'cancel') {
      console.error('取消订单失败:', error)
    }
  }
}
</script>

<style lang="scss" scoped>
.orders-page {
  .page-header {
    margin-bottom: 20px;
    
    h2 {
      font-size: 24px;
      color: #333;
    }
  }
  
  .orders-content {
    .empty-orders {
      background: #fff;
      border-radius: 8px;
      padding: 60px 0;
    }
    
    .orders-list {
      .order-card {
        background: #fff;
        border-radius: 8px;
        margin-bottom: 20px;
        overflow: hidden;
        
        .order-header {
          display: flex;
          align-items: center;
          padding: 15px 20px;
          background: #f5f5f5;
          border-bottom: 1px solid #e8e8e8;
          
          .order-no {
            font-weight: bold;
            margin-right: 20px;
          }
          
          .order-time {
            color: #999;
            margin-right: auto;
          }
        }
        
        .order-body {
          padding: 20px;
          
          .order-items {
            margin-bottom: 15px;
            
            .order-item {
              display: flex;
              align-items: center;
              padding: 10px 0;
              border-bottom: 1px solid #f5f5f5;
              
              &:last-child {
                border-bottom: none;
              }
              
              .item-image {
                width: 60px;
                height: 60px;
                margin-right: 15px;
                
                .el-image {
                  width: 100%;
                  height: 100%;
                  border-radius: 4px;
                }
                
                .image-placeholder {
                  display: flex;
                  align-items: center;
                  justify-content: center;
                  width: 100%;
                  height: 100%;
                  background: linear-gradient(135deg, #f5f7fa 0%, #e4e7ed 100%);
                  color: #c0c4cc;
                  border-radius: 4px;
                }
              }
              
              .item-info {
                flex: 1;
                
                .item-name {
                  font-size: 14px;
                  margin-right: 10px;
                }
                
                .item-qty {
                  color: #999;
                  font-size: 13px;
                }
              }
              
              .item-amount {
                color: #ff6700;
                font-weight: bold;
              }
            }
          }
          
          .order-summary {
            p {
              margin-bottom: 8px;
              color: #666;
              font-size: 14px;
              
              .price {
                color: #ff6700;
                font-weight: bold;
                font-size: 16px;
              }
            }
          }
        }
        
        .order-footer {
          padding: 15px 20px;
          border-top: 1px solid #e8e8e8;
          text-align: right;
          
          .el-button + .el-button {
            margin-left: 10px;
          }
        }
      }
    }

    .pagination-wrapper {
      display: flex;
      justify-content: center;
      margin-top: 20px;
    }
  }
}
</style>
