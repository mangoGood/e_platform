<template>
  <div class="seller-products container page-container">
    <div class="page-header">
      <h2>商品管理</h2>
      <el-button type="primary" @click="showAddDialog">
        <el-icon><Plus /></el-icon>
        添加商品
      </el-button>
    </div>
    
    <div v-loading="loading" class="products-table">
      <el-table :data="products" stripe>
        <el-table-column prop="id" label="ID" width="80" />
        <el-table-column label="商品图片" width="100">
          <template #default="{ row }">
            <el-image
              :src="row.mainImage || 'https://via.placeholder.com/60'"
              style="width: 60px; height: 60px"
              fit="cover"
            />
          </template>
        </el-table-column>
        <el-table-column prop="name" label="商品名称" />
        <el-table-column prop="price" label="价格" width="120">
          <template #default="{ row }">
            <span class="price">{{ row.price }}</span>
          </template>
        </el-table-column>
        <el-table-column prop="stock" label="库存" width="100" />
        <el-table-column prop="sales" label="销量" width="100" />
        <el-table-column prop="status" label="状态" width="100">
          <template #default="{ row }">
            <el-tag :type="row.status === 1 ? 'success' : 'danger'">
              {{ row.status === 1 ? '上架' : '下架' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="200" fixed="right">
          <template #default="{ row }">
            <el-button size="small" @click="handleEdit(row)">编辑</el-button>
            <el-button size="small" type="danger" @click="handleDelete(row.id)">
              删除
            </el-button>
          </template>
        </el-table-column>
      </el-table>
    </div>
    
    <el-dialog
      v-model="dialogVisible"
      :title="isEdit ? '编辑商品' : '添加商品'"
      width="600px"
    >
      <el-form
        ref="productFormRef"
        :model="productForm"
        :rules="productRules"
        label-width="100px"
      >
        <el-form-item label="商品名称" prop="name">
          <el-input v-model="productForm.name" placeholder="请输入商品名称" />
        </el-form-item>
        
        <el-form-item label="商品分类" prop="categoryId">
          <el-select v-model="productForm.categoryId" placeholder="请选择分类">
            <el-option
              v-for="category in categories"
              :key="category.id"
              :label="category.name"
              :value="category.id"
            />
          </el-select>
        </el-form-item>
        
        <el-form-item label="商品价格" prop="price">
          <el-input-number
            v-model="productForm.price"
            :min="0"
            :precision="2"
            placeholder="请输入价格"
          />
        </el-form-item>
        
        <el-form-item label="原价">
          <el-input-number
            v-model="productForm.originalPrice"
            :min="0"
            :precision="2"
            placeholder="请输入原价"
          />
        </el-form-item>
        
        <el-form-item label="库存" prop="stock">
          <el-input-number
            v-model="productForm.stock"
            :min="0"
            placeholder="请输入库存"
          />
        </el-form-item>
        
        <el-form-item label="商品描述">
          <el-input
            v-model="productForm.description"
            type="textarea"
            :rows="4"
            placeholder="请输入商品描述"
          />
        </el-form-item>
        
        <el-form-item label="商品主图">
          <el-input v-model="productForm.mainImage" placeholder="请输入图片URL" />
        </el-form-item>
      </el-form>
      
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="submitting" @click="handleSubmit">
          确定
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, reactive, onMounted } from 'vue'
import { useUserStore } from '@/stores/user'
import productApi from '@/api/product'
import { ElMessage, ElMessageBox } from 'element-plus'

const userStore = useUserStore()

const loading = ref(false)
const submitting = ref(false)
const dialogVisible = ref(false)
const isEdit = ref(false)
const products = ref([])
const categories = ref([])
const productFormRef = ref(null)
const editProductId = ref(null)

const productForm = reactive({
  name: '',
  categoryId: null,
  price: 0,
  originalPrice: null,
  stock: 0,
  description: '',
  mainImage: ''
})

const productRules = {
  name: [
    { required: true, message: '请输入商品名称', trigger: 'blur' }
  ],
  categoryId: [
    { required: true, message: '请选择商品分类', trigger: 'change' }
  ],
  price: [
    { required: true, message: '请输入商品价格', trigger: 'blur' }
  ],
  stock: [
    { required: true, message: '请输入库存', trigger: 'blur' }
  ]
}

onMounted(async () => {
  await loadProducts()
  await loadCategories()
})

const loadProducts = async () => {
  loading.value = true
  try {
    const res = await productApi.getSellerProducts(userStore.userInfo?.userId)
    products.value = res.data || []
  } catch (error) {
    console.error('加载商品失败:', error)
  } finally {
    loading.value = false
  }
}

const loadCategories = async () => {
  try {
    const res = await productApi.getCategories()
    categories.value = res.data || []
  } catch (error) {
    console.error('加载分类失败:', error)
  }
}

const showAddDialog = () => {
  isEdit.value = false
  editProductId.value = null
  resetForm()
  dialogVisible.value = true
}

const handleEdit = (product) => {
  isEdit.value = true
  editProductId.value = product.id
  Object.assign(productForm, {
    name: product.name,
    categoryId: product.categoryId,
    price: product.price,
    originalPrice: product.originalPrice,
    stock: product.stock,
    description: product.description,
    mainImage: product.mainImage
  })
  dialogVisible.value = true
}

const handleDelete = async (productId) => {
  try {
    await ElMessageBox.confirm('确定要删除该商品吗？', '提示', {
      confirmButtonText: '确定',
      cancelButtonText: '取消',
      type: 'warning'
    })
    
    await productApi.deleteProduct(productId)
    ElMessage.success('删除成功')
    await loadProducts()
  } catch (error) {
    if (error !== 'cancel') {
      console.error('删除失败:', error)
    }
  }
}

const handleSubmit = async () => {
  if (!productFormRef.value) return
  
  await productFormRef.value.validate(async (valid) => {
    if (valid) {
      submitting.value = true
      try {
        if (isEdit.value) {
          await productApi.updateProduct(editProductId.value, productForm)
          ElMessage.success('修改成功')
        } else {
          await productApi.addProduct(productForm)
          ElMessage.success('添加成功')
        }
        
        dialogVisible.value = false
        await loadProducts()
      } catch (error) {
        console.error('提交失败:', error)
      } finally {
        submitting.value = false
      }
    }
  })
}

const resetForm = () => {
  Object.assign(productForm, {
    name: '',
    categoryId: null,
    price: 0,
    originalPrice: null,
    stock: 0,
    description: '',
    mainImage: ''
  })
  productFormRef.value?.resetFields()
}
</script>

<style lang="scss" scoped>
.seller-products {
  .page-header {
    display: flex;
    justify-content: space-between;
    align-items: center;
    margin-bottom: 20px;
    
    h2 {
      font-size: 24px;
      color: $color-text-primary;
    }
  }
  
  .products-table {
    background: $color-bg-card;
    border-radius: 8px;
    padding: 20px;
  }
}
</style>
