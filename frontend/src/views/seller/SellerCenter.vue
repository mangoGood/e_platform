<template>
  <div class="seller-center container page-container">
    <div class="page-header">
      <h2>卖家中心</h2>
    </div>
    
    <div class="dashboard">
      <el-row :gutter="20">
        <el-col :span="6">
          <div class="stat-card">
            <div class="stat-icon stat-icon--brand">
              <el-icon size="30"><Goods /></el-icon>
            </div>
            <div class="stat-info">
              <div class="stat-value">{{ stats.productCount }}</div>
              <div class="stat-label">商品数量</div>
            </div>
          </div>
        </el-col>
        
        <el-col :span="6">
          <div class="stat-card">
            <div class="stat-icon stat-icon--success">
              <el-icon size="30"><Document /></el-icon>
            </div>
            <div class="stat-info">
              <div class="stat-value">{{ stats.orderCount }}</div>
              <div class="stat-label">订单数量</div>
            </div>
          </div>
        </el-col>
        
        <el-col :span="6">
          <div class="stat-card">
            <div class="stat-icon stat-icon--warning">
              <el-icon size="30"><Money /></el-icon>
            </div>
            <div class="stat-info">
              <div class="stat-value">¥{{ stats.totalSales }}</div>
              <div class="stat-label">总销售额</div>
            </div>
          </div>
        </el-col>
        
        <el-col :span="6">
          <div class="stat-card">
            <div class="stat-icon stat-icon--danger">
              <el-icon size="30"><User /></el-icon>
            </div>
            <div class="stat-info">
              <div class="stat-value">{{ stats.buyerCount }}</div>
              <div class="stat-label">买家数量</div>
            </div>
          </div>
        </el-col>
      </el-row>
      
      <div class="quick-actions">
        <h3>快捷操作</h3>
        <el-row :gutter="20">
          <el-col :span="6">
            <el-card shadow="hover" class="action-card" @click="$router.push('/seller/products')">
              <el-icon size="40"><Goods /></el-icon>
              <p>商品管理</p>
            </el-card>
          </el-col>
          <el-col :span="6">
            <el-card shadow="hover" class="action-card" @click="$router.push('/seller/orders')">
              <el-icon size="40"><Document /></el-icon>
              <p>订单管理</p>
            </el-card>
          </el-col>
          <el-col :span="6">
            <el-card shadow="hover" class="action-card">
              <el-icon size="40"><DataAnalysis /></el-icon>
              <p>数据分析</p>
            </el-card>
          </el-col>
          <el-col :span="6">
            <el-card shadow="hover" class="action-card">
              <el-icon size="40"><Setting /></el-icon>
              <p>店铺设置</p>
            </el-card>
          </el-col>
        </el-row>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { useUserStore } from '@/stores/user'
import productApi from '@/api/product'
import orderApi from '@/api/order'

const userStore = useUserStore()

const stats = ref({
  productCount: 0,
  orderCount: 0,
  totalSales: 0,
  buyerCount: 0
})

onMounted(async () => {
  await loadStats()
})

const loadStats = async () => {
  try {
    const productsRes = await productApi.getSellerProducts(userStore.userInfo?.userId)
    stats.value.productCount = productsRes.data?.length || 0
    
    const ordersRes = await orderApi.getSellerOrders()
    const orders = ordersRes.data || []
    stats.value.orderCount = orders.length
    stats.value.totalSales = orders.reduce((sum, order) => sum + (order.totalAmount || 0), 0)
  } catch (error) {
    console.error('加载统计数据失败:', error)
  }
}
</script>

<style lang="scss" scoped>
.seller-center {
  .page-header {
    margin-bottom: 30px;
    
    h2 {
      font-size: 24px;
      color: $color-text-primary;
    }
  }
  
  .dashboard {
    .stat-card {
      background: $color-bg-card;
      border-radius: 8px;
      padding: 20px;
      display: flex;
      align-items: center;
      gap: 20px;
      box-shadow: $shadow-sm;
      
      .stat-icon {
        width: 60px;
        height: 60px;
        border-radius: 50%;
        display: flex;
        align-items: center;
        justify-content: center;
        color: $color-text-inverse;

        // 原先四个卡片用内联 style 写死了 EP 默认蓝 #409eff 等色值，
        // 既绕过了主题系统，也让「全站不出现默认蓝」无法成立。
        &--brand   { background: $color-primary; }
        &--success { background: $color-success; }
        &--warning { background: $color-warning; }
        &--danger  { background: $color-danger; }
      }
      
      .stat-info {
        .stat-value {
          font-size: 24px;
          font-weight: bold;
          color: $color-text-primary;
        }
        
        .stat-label {
          color: $color-text-secondary;
          margin-top: 5px;
        }
      }
    }
    
    .quick-actions {
      margin-top: 40px;
      
      h3 {
        font-size: 20px;
        margin-bottom: 20px;
        color: $color-text-primary;
      }
      
      .action-card {
        text-align: center;
        cursor: pointer;
        transition: all 0.3s;
        
        &:hover {
          transform: translateY(-5px);
        }
        
        .el-icon {
          color: $color-primary;
          margin-bottom: 10px;
        }
        
        p {
          font-size: 16px;
          color: $color-text-regular;
        }
      }
    }
  }
}
</style>
