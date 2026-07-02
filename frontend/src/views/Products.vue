<template>
  <div class="products-page container page-container">
    <div class="page-header">
      <h2>商品列表</h2>
    </div>
    
    <div class="filter-section">
      <el-select
        v-model="selectedCategory"
        placeholder="选择分类"
        clearable
        @change="handleCategoryChange"
      >
        <el-option
           v-for="category in categories"
          :key="category.id"
          :label="category.name"
          :value="category.id"
        />
      </el-select>
    </div>
    
    <div v-loading="loading" class="products-grid">
      <div
        v-for="product in products"
        :key="product.id"
        class="product-card"
        @click="goToProduct(product.id)"
      >
        <div class="product-image">
          <el-image
            :src="product.mainImage || ''"
            fit="cover"
          >
            <template #error>
              <div class="image-placeholder">
                <el-icon :size="48"><ShoppingBag /></el-icon>
                <span>{{ product.name }}</span>
              </div>
            </template>
          </el-image>
        </div>
        <div class="product-info">
          <h3 class="product-name">{{ product.name }}</h3>
          <p class="product-desc">{{ product.description }}</p>
          <div class="product-bottom">
            <span class="price">{{ product.price }}</span>
            <span class="sales">已售 {{ product.sales }}</span>
          </div>
        </div>
      </div>
    </div>
    
    <div v-if="total > 0" class="pagination">
      <el-pagination
        v-model:current-page="currentPage"
        v-model:page-size="pageSize"
        :total="total"
        :page-sizes="[12, 24, 36, 48]"
        layout="total, sizes, prev, pager, next, jumper"
        @size-change="loadProducts"
        @current-change="loadProducts"
      />
    </div>
  </div>
</template>

<script setup>
import { ref, onMounted, watch } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { ShoppingBag } from '@element-plus/icons-vue'
import productApi from '@/api/product'

const router = useRouter()
const route = useRoute()

const loading = ref(false)
const products = ref([])
const categories = ref([])
const selectedCategory = ref(null)
const currentPage = ref(1)
const pageSize = ref(12)
const total = ref(0)

onMounted(async () => {
  await loadCategories()
  if (route.query.categoryId) {
    selectedCategory.value = Number(route.query.categoryId)
  }
  if (route.query.keyword) {
    keyword.value = route.query.keyword
  }
  await loadProducts()
})

watch(() => route.query, (newQuery) => {
  if (newQuery.categoryId) {
    selectedCategory.value = Number(newQuery.categoryId)
  }
  if (newQuery.keyword) {
    keyword.value = newQuery.keyword
  }
  loadProducts()
}, { deep: true })

const loadCategories = async () => {
  try {
    const res = await productApi.getCategories()
    categories.value = res.data
  } catch (error) {
    console.error('加载分类失败:', error)
  }
}

const loadProducts = async () => {
  loading.value = true
  try {
    const res = await productApi.getProductList({
      current: currentPage.value,
      size: pageSize.value,
      categoryId: selectedCategory.value,
      keyword: route.query.keyword
    })
    products.value = res.data.records
    total.value = res.data.total
  } catch (error) {
    console.error('加载商品失败:', error)
  } finally {
    loading.value = false
  }
}

const handleCategoryChange = () => {
  currentPage.value = 1
  loadProducts()
}

const goToProduct = (productId) => {
  router.push({ name: 'ProductDetail', params: { id: productId } })
}
</script>

<style lang="scss" scoped>
.products-page {
  .page-header {
    margin-bottom: 20px;
    
    h2 {
      font-size: 24px;
      color: #333;
    }
  }
  
  .filter-section {
    margin-bottom: 20px;
    padding: 20px;
    background: #fff;
    border-radius: 8px;
  }
  
  .products-grid {
    display: grid;
    grid-template-columns: repeat(4, 1fr);
    gap: 20px;
    min-height: 400px;
    
    .product-card {
      background: #fff;
      border-radius: 8px;
      overflow: hidden;
      cursor: pointer;
      transition: all 0.3s;
      
      &:hover {
        transform: translateY(-5px);
        box-shadow: 0 4px 20px rgba(0, 0, 0, 0.1);
      }
      
      .product-image {
        width: 100%;
        height: 200px;
        overflow: hidden;
        background: #f5f7fa;
        
        .el-image {
          width: 100%;
          height: 100%;
        }
        
        .image-placeholder {
          display: flex;
          flex-direction: column;
          align-items: center;
          justify-content: center;
          width: 100%;
          height: 200px;
          color: #c0c4cc;
          background: linear-gradient(135deg, #f5f7fa 0%, #e4e7ed 100%);
          
          span {
            margin-top: 8px;
            font-size: 12px;
            color: #909399;
            max-width: 80%;
            overflow: hidden;
            text-overflow: ellipsis;
            white-space: nowrap;
          }
        }
      }
      
      .product-info {
        padding: 15px;
        
        .product-name {
          font-size: 16px;
          margin-bottom: 8px;
          overflow: hidden;
          text-overflow: ellipsis;
          white-space: nowrap;
        }
        
        .product-desc {
          color: #999;
          font-size: 14px;
          margin-bottom: 10px;
          overflow: hidden;
          text-overflow: ellipsis;
          white-space: nowrap;
        }
        
        .product-bottom {
          display: flex;
          justify-content: space-between;
          align-items: center;
          
          .sales {
            color: #999;
            font-size: 12px;
          }
        }
      }
    }
  }
  
  .pagination {
    margin-top: 30px;
    display: flex;
    justify-content: center;
  }
}

@media (max-width: 1200px) {
  .products-grid {
    grid-template-columns: repeat(3, 1fr) !important;
  }
}

@media (max-width: 992px) {
  .products-grid {
    grid-template-columns: repeat(2, 1fr) !important;
  }
}

@media (max-width: 576px) {
  .products-grid {
    grid-template-columns: 1fr !important;
  }
}
</style>
