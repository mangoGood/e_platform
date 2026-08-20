<template>
  <section v-loading="loading" class="comment-section">
    <!-- ============ 评分汇总 ============ -->
    <div class="summary">
      <div class="summary-score">
        <span class="score">{{ displayAvg }}</span>
        <el-rate :model-value="Number(displayAvg)" disabled allow-half size="small" />
        <span class="score-count">共 {{ summary.ratingCount || 0 }} 条评价</span>
      </div>

      <div class="summary-dist">
        <div v-for="star in [5, 4, 3, 2, 1]" :key="star" class="dist-row">
          <span class="dist-label">{{ star }} 星</span>
          <div class="dist-track">
            <div class="dist-fill" :style="{ width: distPercent(star) + '%' }" />
          </div>
          <span class="dist-count">{{ distributionOf(star) }}</span>
        </div>
      </div>
    </div>

    <!-- ============ 发表评价 ============ -->
    <div class="review-editor">
      <template v-if="viewerContext.canReview">
        <h4 class="editor-title">发表评价</h4>
        <div class="editor-rate">
          <span class="editor-label">评分</span>
          <el-rate v-model="newReview.rating" show-text :texts="RATE_TEXTS" />
        </div>
        <el-input
          v-model="newReview.content"
          type="textarea"
          :rows="3"
          maxlength="500"
          show-word-limit
          placeholder="说说这件商品怎么样，可以帮到其他买家"
        />
        <div class="editor-actions">
          <el-button type="primary" :loading="submitting" @click="submitReview">
            提交评价
          </el-button>
        </div>
      </template>

      <!-- 不能评价时把服务端给的原因原样展示，而不是把入口静默藏掉 -->
      <el-alert
        v-else-if="viewerContext.reviewDeniedReason"
        :title="viewerContext.reviewDeniedReason"
        type="info"
        :closable="false"
        show-icon
      />
      <el-alert
        v-else-if="!viewerContext.loggedIn"
        title="登录后可发表评价"
        type="info"
        :closable="false"
        show-icon
      />
    </div>

    <!-- ============ 评论列表 ============ -->
    <div class="list-toolbar">
      <span class="list-title">全部评价（{{ total }}）</span>
      <el-radio-group v-model="sort" size="small" @change="handleSortChange">
        <el-radio-button value="latest">最新</el-radio-button>
        <el-radio-button value="rating">评分优先</el-radio-button>
      </el-radio-group>
    </div>

    <el-empty v-if="!loading && comments.length === 0" description="还没有评价，来做第一个吧" />

    <ul v-else class="comment-list">
      <CommentItem
        v-for="item in comments"
        :key="item.id"
        :comment="item"
        :viewer-context="viewerContext"
        @changed="reload"
      />
    </ul>

    <div v-if="total > pageSize" class="pager">
      <el-pagination
        v-model:current-page="pageNum"
        :page-size="pageSize"
        :total="total"
        layout="prev, pager, next"
        background
        @current-change="loadComments"
      />
    </div>
  </section>
</template>

<script setup>
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import commentApi from '@/api/comment'
import CommentItem from './CommentItem.vue'

/**
 * 三级评论区容器。
 *
 * 数据全部来自 `GET /api/comment/product/{id}` 一个接口：
 * 列表 + 评分汇总 + 浏览者上下文一次给全，不再前端二次拼装。
 *
 * <h3>踩过的坑</h3>
 * 该接口的 `data` 是 `CommentTreeVO` <b>对象</b>，不是数组。
 * 改造前的代码写的是 `reviews.value = res.data || []`，
 * 拿到的是对象 → `v-for` 遍历对象的可枚举属性、`.length` 为 undefined，
 * 结果既不报错也渲染不出任何内容，还连「暂无评论」的空态都不显示 ——
 * 页面一片空白。这里必须显式取 `res.data.comments.records`。
 */

const props = defineProps({
  /** 商品 id */
  productId: {
    type: [Number, String],
    required: true
  }
})

const RATE_TEXTS = ['很差', '较差', '一般', '满意', '很好']

const loading = ref(false)
const submitting = ref(false)

const comments = ref([])
const total = ref(0)
const pageNum = ref(1)
const pageSize = ref(10)
const sort = ref('latest')

const summary = ref({ ratingAvg: 0, ratingCount: 0, distribution: {} })
const viewerContext = ref({
  loggedIn: false,
  seller: false,
  canReview: false,
  canReply: false,
  canAsk: false
})

const newReview = reactive({ rating: 5, content: '' })

/** 平均分保留一位小数；后端给的是 BigDecimal，序列化后可能是字符串。 */
const displayAvg = computed(() => {
  const value = Number(summary.value.ratingAvg) || 0
  return value.toFixed(1)
})

/**
 * 取某个星级的评价条数。
 *
 * 后端 `Map<Integer,Integer>` 序列化成 JSON 后 key 是字符串，
 * 用数字下标取不到，必须转成字符串。
 *
 * @param {number} star 星级 1-5
 * @returns {number} 条数
 */
function distributionOf(star) {
  const dist = summary.value.distribution || {}
  return Number(dist[String(star)] ?? dist[star]) || 0
}

/**
 * 某星级占比，用于分布条宽度。
 *
 * @param {number} star 星级 1-5
 * @returns {number} 0-100 的百分比
 */
function distPercent(star) {
  const count = Number(summary.value.ratingCount) || 0
  if (count === 0) {
    return 0
  }
  return Math.round((distributionOf(star) / count) * 100)
}

/**
 * 拉取评论树。
 *
 * @returns {Promise<void>}
 */
async function loadComments() {
  if (!props.productId) {
    return
  }
  loading.value = true
  try {
    const res = await commentApi.getProductComments(props.productId, {
      pageNum: pageNum.value,
      pageSize: pageSize.value,
      sort: sort.value
    })
    const data = res.data || {}
    // ⚠️ 关键：data.comments 是 PageResult，真正的数组在 records 里。
    const page = data.comments || {}
    comments.value = Array.isArray(page.records) ? page.records : []
    total.value = Number(page.total) || 0
    summary.value = data.summary || { ratingAvg: 0, ratingCount: 0, distribution: {} }
    viewerContext.value = data.viewerContext || {}
  } catch (error) {
    // 错误提示由 request.js 统一处理，这里只保证 UI 不残留脏数据。
    console.error('加载评论失败:', error)
    comments.value = []
  } finally {
    loading.value = false
  }
}

/**
 * 任何写操作后重新拉取（回复 / 追问 / 删除 / 编辑都会改变树形结构）。
 *
 * @returns {Promise<void>}
 */
async function reload() {
  await loadComments()
}

/**
 * 切换排序后回到第一页。
 *
 * @returns {Promise<void>}
 */
async function handleSortChange() {
  pageNum.value = 1
  await loadComments()
}

/**
 * 提交 L1 买家评价。
 *
 * @returns {Promise<void>}
 */
async function submitReview() {
  const content = newReview.content.trim()
  if (!content) {
    ElMessage.warning('请输入评价内容')
    return
  }
  if (!newReview.rating) {
    ElMessage.warning('请先选择评分')
    return
  }
  submitting.value = true
  try {
    await commentApi.createComment({
      productId: Number(props.productId),
      rating: newReview.rating,
      content
    })
    ElMessage.success('评价成功')
    newReview.content = ''
    newReview.rating = 5
    pageNum.value = 1
    await loadComments()
  } finally {
    // 403「不能评价自己出售的商品」这类业务错误由 request.js 弹后端中文文案，
    // 这里再弹一次就成了重复 toast，所以不写 catch。
    submitting.value = false
  }
}

watch(
  () => props.productId,
  () => {
    pageNum.value = 1
    loadComments()
  }
)

onMounted(loadComments)

defineExpose({ reload })
</script>

<style lang="scss" scoped>
.comment-section {
  min-height: 160px;
}

.summary {
  display: flex;
  gap: $space-10;
  align-items: center;
  padding: $space-5 $space-6;
  background: $color-bg-subtle;
  border-radius: $radius-md;
  margin-bottom: $space-6;
}

.summary-score {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: $space-1;
  flex-shrink: 0;
  min-width: 130px;

  .score {
    font-size: $font-4xl;
    font-weight: $font-weight-bold;
    color: $color-primary;
    line-height: 1;
  }

  .score-count {
    color: $color-text-secondary;
    font-size: $font-xs;
  }
}

.summary-dist {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: $space-1;

  .dist-row {
    display: flex;
    align-items: center;
    gap: $space-2;
    font-size: $font-xs;
    color: $color-text-secondary;
  }

  .dist-label {
    width: 32px;
    flex-shrink: 0;
  }

  .dist-track {
    flex: 1;
    height: 6px;
    border-radius: $radius-pill;
    background: $gray-200;
    overflow: hidden;
  }

  .dist-fill {
    height: 100%;
    border-radius: $radius-pill;
    background: $color-primary;
    transition: width $transition-base;
  }

  .dist-count {
    width: 32px;
    text-align: right;
    flex-shrink: 0;
  }
}

.review-editor {
  margin-bottom: $space-6;

  .editor-title {
    font-size: $font-md;
    font-weight: $font-weight-bold;
    color: $color-text-primary;
    margin-bottom: $space-3;
  }

  .editor-rate {
    display: flex;
    align-items: center;
    gap: $space-3;
    margin-bottom: $space-3;
  }

  .editor-label {
    color: $color-text-regular;
    font-size: $font-base;
  }

  .editor-actions {
    margin-top: $space-3;
    display: flex;
    justify-content: flex-end;
  }
}

.list-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding-bottom: $space-3;
  border-bottom: 1px solid $color-border-light;

  .list-title {
    font-size: $font-md;
    font-weight: $font-weight-bold;
    color: $color-text-primary;
  }
}

.comment-list {
  list-style: none;
}

.pager {
  display: flex;
  justify-content: center;
  margin-top: $space-6;
}

@media (max-width: 768px) {
  .summary {
    flex-direction: column;
    gap: $space-4;
    align-items: stretch;
  }
}
</style>
