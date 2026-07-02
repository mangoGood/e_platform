<template>
  <div class="seller-center container page-container">
    <div class="page-header">
      <h2>卖家中心</h2>
    </div>
    
    <div class="dashboard">
      <el-row :gutter="20">
        <el-col :span="6">
          <div class="stat-card">
            <div class="stat-icon" style="background: #409eff">
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
            <div class="stat-icon" style="background: #67c23a">
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
            <div class="stat-icon" style="background: #e6a23c">
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
            <div class="stat-icon" style="background: #f56c6c">
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
      color: #333;
    }
  }
  
  .dashboard {
    .stat-card {
      background: #fff;
      border-radius: 8px;
      padding: 20px;
      display: flex;
      align-items: center;
      gap: 20px;
      box-shadow: 0 2px 8px rgba(0, 0, 0, 0.1);
      
      .stat-icon {
        width: 60px;
        height: 60px;
        border-radius: 50%;
        display: flex;
        align-items: center;
        justify-content: center;
        color: #fff;
      }
      
      .stat-info {
        .stat-value {
          font-size: 24px;
          font-weight: bold;
          color: #333;
        }
        
        .stat-label {
          color: #999;
          margin-top: 5px;
        }
      }
    }
    
    .quick-actions {
      margin-top: 40px;
      
      h3 {
        font-size: 20px;
        margin-bottom: 20px;
        color: #333;
      }
      
      .action-card {
        text-align: center;
        cursor: pointer;
        transition: all 0.3s;
        
        &:hover {
          transform: translateY(-5px);
        }
        
        .el-icon {
          color: #ff6700;
          margin-bottom: 10px;
        }
        
        p {
          font-size: 16px;
          color: #666;
        }
      }
    }
  }
}
</style>
