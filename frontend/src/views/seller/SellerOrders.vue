<template>
  <div class="seller-orders container page-container">
    <div class="page-header">
      <h2>订单管理</h2>
    </div>
    
    <div v-loading="loading" class="orders-list">
      <div v-if="orders.length === 0" class="empty-orders">
        <el-empty description="暂无订单" />
      </div>
      
      <div v-else>
        <div v-for="order in orders" :key="order.id" class="order-card">
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
                <div class="item-amount">¥{{ item.totalAmount }}</div>
              </div>
            </div>
            <div class="order-info-row">
              <span><strong>买家ID：</strong>{{ order.userId }}</span>
              <span><strong>总金额：</strong><span class="price">¥{{ order.totalAmount }}</span></span>
              <span><strong>收货人：</strong>{{ order.receiverName }}</span>
              <span><strong>电话：</strong>{{ order.receiverPhone }}</span>
              <span><strong>地址：</strong>{{ order.receiverAddress }}</span>
            </div>
          </div>
          
          <div class="order-footer">
            <el-button
              v-if="order.status === 1"
              type="primary"
              size="small"
              @click="handleDeliver(order.id)"
            >
              发货
            </el-button>
          </div>
        </div>
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

onMounted(async () => {
  await loadOrders()
})

const loadOrders = async () => {
  loading.value = true
  try {
    const res = await orderApi.getSellerOrders()
    orders.value = res.data || []
  } catch (error) {
    console.error('加载订单失败:', error)
  } finally {
    loading.value = false
  }
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

const handleDeliver = async (orderId) => {
  try {
    await ElMessageBox.confirm('确定要发货吗？', '提示', {
      confirmButtonText: '确定',
      cancelButtonText: '取消',
      type: 'info'
    })
    
    await orderApi.deliverOrder(orderId)
    ElMessage.success('发货成功')
    await loadOrders()
  } catch (error) {
    if (error !== 'cancel') {
      console.error('发货失败:', error)
    }
  }
}
</script>

<style lang="scss" scoped>
.seller-orders {
  .page-header {
    margin-bottom: 20px;
    
    h2 {
      font-size: 24px;
      color: #333;
    }
  }
  
  .orders-list {
    .empty-orders {
      background: #fff;
      border-radius: 8px;
      padding: 60px 0;
    }
    
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
        
        .order-info-row {
          display: flex;
          flex-wrap: wrap;
          gap: 20px;
          font-size: 14px;
          color: #666;
          
          .price {
            color: #ff6700;
            font-weight: bold;
          }
        }
      }
      
      .order-footer {
        padding: 15px 20px;
        border-top: 1px solid #e8e8e8;
        text-align: right;
      }
    }
  }
}
</style>
