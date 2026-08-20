<template>
  <div class="home-page">
    <div class="banner">
      <el-carousel height="400px">
        <el-carousel-item v-for="item in banners" :key="item.id">
          <div class="banner-item" :class="`banner-item--${item.theme}`">
            <h2>{{ item.title }}</h2>
            <p>{{ item.desc }}</p>
          </div>
        </el-carousel-item>
      </el-carousel>
    </div>
    
    <div class="container page-container">
      <section class="category-section">
        <h2 class="section-title">商品分类</h2>
        <div class="category-grid">
          <div
            v-for="category in categories"
            :key="category.id"
            class="category-card"
            @click="goToProducts(category.id)"
          >
            <el-icon size="40"><Goods /></el-icon>
            <span>{{ category.name }}</span>
          </div>
        </div>
      </section>
      
      <section class="products-section">
        <h2 class="section-title">热门商品</h2>
        <div class="products-grid">
          <div
            v-for="product in products"
            :key="product.id"
            class="product-card"
            @click="goToProduct(product.id)"
          >
            <div class="product-image">
              <el-image
                :src="product.mainImage || 'https://via.placeholder.com/200'"
                fit="cover"
              />
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
      </section>
    </div>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import productApi from '@/api/product'

const router = useRouter()
const categories = ref([])
const products = ref([])

const banners = ref([
  // 用主题类名替代写死的 16 进制色：三张 banner 原先是橙 / 天蓝 / 玫红三个
  // 互不相干的色系，拼在一起像三个不同的站点。这里统一到品牌色的深浅变体。
  { id: 1, title: '新品上市', desc: '最新款电子产品，限时优惠', theme: 'primary' },
  { id: 2, title: '品质生活', desc: '精选家居好物，提升生活品质', theme: 'deep' },
  { id: 3, title: '时尚穿搭', desc: '潮流服饰，展现个性风采', theme: 'warm' }
])

onMounted(async () => {
  await loadCategories()
  await loadProducts()
})

const loadCategories = async () => {
  try {
    const res = await productApi.getCategories()
    categories.value = res.data.slice(0, 8)
  } catch (error) {
    console.error('加载分类失败:', error)
  }
}

const loadProducts = async () => {
  try {
    const res = await productApi.getProductList({ current: 1, size: 8 })
    products.value = res.data.records
  } catch (error) {
    console.error('加载商品失败:', error)
  }
}

const goToProducts = (categoryId) => {
  router.push({ name: 'Products', query: { categoryId } })
}

const goToProduct = (productId) => {
  router.push({ name: 'ProductDetail', params: { id: productId } })
}
</script>

<style lang="scss" scoped>
.home-page {
  .banner {
    .banner-item {
      height: 100%;
      display: flex;
      flex-direction: column;
      justify-content: center;
      align-items: center;
      color: $color-text-inverse;

      &--primary { background: $gradient-brand; }
      &--deep    { background: linear-gradient(135deg, $brand-600 0%, $brand-800 100%); }
      &--warm    { background: linear-gradient(135deg, $brand-400 0%, $brand-600 100%); }
      
      h2 {
        font-size: 48px;
        margin-bottom: 20px;
      }
      
      p {
        font-size: 20px;
      }
    }
  }
  
  .section-title {
    font-size: 28px;
    margin-bottom: 30px;
    padding-left: 15px;
    border-left: 4px solid $color-primary;
  }
  
  .category-section {
    margin-bottom: 50px;
    
    .category-grid {
      display: grid;
      grid-template-columns: repeat(8, 1fr);
      gap: 15px;
      
      .category-card {
        background: $color-bg-card;
        padding: 20px;
        border-radius: 8px;
        text-align: center;
        cursor: pointer;
        transition: all 0.3s;
        
        &:hover {
          transform: translateY(-5px);
          box-shadow: $shadow-md;
          color: $color-primary;
        }
        
        span {
          display: block;
          margin-top: 10px;
          font-size: 14px;
        }
      }
    }
  }
  
  .products-section {
    .products-grid {
      display: grid;
      grid-template-columns: repeat(4, 1fr);
      gap: 20px;
      
      .product-card {
        background: $color-bg-card;
        border-radius: 8px;
        overflow: hidden;
        transition: all 0.3s;
        
        &:hover {
          transform: translateY(-5px);
          box-shadow: $shadow-md;
        }
        
        .product-image {
          width: 100%;
          height: 200px;
          overflow: hidden;
          
          .el-image {
            width: 100%;
            height: 100%;
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
            color: $color-text-secondary;
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
              color: $color-text-secondary;
              font-size: 12px;
            }
          }
        }
      }
    }
  }
}

@media (max-width: 1200px) {
  .products-grid {
    grid-template-columns: repeat(3, 1fr) !important;
  }
}

@media (max-width: 992px) {
  .category-grid {
    grid-template-columns: repeat(4, 1fr) !important;
  }
  
  .products-grid {
    grid-template-columns: repeat(2, 1fr) !important;
  }
}

@media (max-width: 576px) {
  .category-grid {
    grid-template-columns: repeat(2, 1fr) !important;
  }
  
  .products-grid {
    grid-template-columns: 1fr !important;
  }
}
</style>
