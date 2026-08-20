<template>
  <li class="comment-item">
    <!-- ============ L1 买家评价 ============ -->
    <div class="comment-main">
      <el-avatar :size="36" class="avatar">{{ initial(comment.nickname) }}</el-avatar>

      <div class="comment-body">
        <div class="comment-meta">
          <span class="nickname">{{ comment.nickname || '匿名用户' }}</span>
          <el-tag v-if="comment.mine" size="small" type="warning" effect="plain">我的</el-tag>
          <el-rate
            v-if="comment.rating"
            :model-value="comment.rating"
            disabled
            size="small"
            class="rate"
          />
          <span class="time">{{ formatTime(comment.createTime) }}</span>
        </div>

        <!-- 编辑态与展示态互斥 -->
        <div v-if="editing" class="edit-box">
          <el-rate v-if="comment.rating" v-model="editForm.rating" />
          <el-input
            v-model="editForm.content"
            type="textarea"
            :rows="3"
            maxlength="500"
            show-word-limit
            placeholder="修改评价内容"
          />
          <div class="edit-actions">
            <el-button size="small" @click="cancelEdit">取消</el-button>
            <el-button size="small" type="primary" :loading="submitting" @click="submitEdit">
              保存
            </el-button>
          </div>
        </div>
        <template v-else>
          <p class="content">{{ comment.content }}</p>

          <div v-if="imageList.length" class="image-list">
            <el-image
              v-for="(url, index) in imageList"
              :key="index"
              :src="url"
              :preview-src-list="imageList"
              :initial-index="index"
              fit="cover"
              class="thumb"
              preview-teleported
            />
          </div>
        </template>

        <!-- ============ L2 商家回复（至多一条，不递归） ============ -->
        <div v-if="comment.reply" class="seller-reply">
          <span class="seller-badge">商家回复</span>
          <span class="reply-text">{{ comment.reply.content }}</span>
          <span class="reply-time">{{ formatTime(comment.reply.createTime) }}</span>
        </div>

        <!-- ============ L3 追问列表（固定第二层，不递归） ============ -->
        <ul v-if="visibleAsks.length" class="ask-list">
          <li v-for="ask in visibleAsks" :key="ask.id" class="ask-item">
            <span class="ask-user">{{ ask.nickname || '匿名用户' }}</span>
            <span v-if="ask.replyToNickname" class="ask-target">
              回复 @{{ ask.replyToNickname }}
            </span>
            <span class="ask-sep">：</span>
            <span class="ask-content">{{ ask.content }}</span>
            <span class="ask-time">{{ formatTime(ask.createTime) }}</span>
            <span class="ask-ops">
              <el-button
                v-if="viewerContext.canAsk"
                link
                type="primary"
                size="small"
                @click="openAsk(ask)"
              >
                回复
              </el-button>
              <el-button
                v-if="ask.mine"
                link
                type="danger"
                size="small"
                @click="handleDelete(ask.id, '追问')"
              >
                删除
              </el-button>
            </span>
          </li>
        </ul>

        <div v-if="showExpandButton" class="expand-row">
          <el-button link type="primary" size="small" :loading="loadingAsks" @click="toggleAsks">
            {{ expanded ? '收起追问' : `查看全部 ${comment.askTotal} 条追问` }}
          </el-button>
        </div>

        <!-- ============ 操作区 ============ -->
        <div class="comment-actions">
          <!-- 卖家回复入口：仅当本人是该商品卖家且这条 L1 还没有回复过 -->
          <el-button
            v-if="canShowReplyButton"
            link
            type="primary"
            size="small"
            @click="toggleReplyBox"
          >
            {{ replyBoxVisible ? '取消回复' : '回复' }}
          </el-button>
          <span v-else-if="viewerContext.seller && comment.reply" class="hint">已回复</span>

          <el-button
            v-if="viewerContext.canAsk"
            link
            type="primary"
            size="small"
            @click="openAsk(null)"
          >
            追问
          </el-button>
          <span v-else-if="viewerContext.askDeniedReason" class="hint">
            {{ viewerContext.askDeniedReason }}
          </span>

          <template v-if="comment.mine && !editing">
            <el-button link size="small" @click="startEdit">编辑</el-button>
            <el-button link type="danger" size="small" @click="handleDelete(comment.id, '评价')">
              删除
            </el-button>
          </template>
        </div>

        <!-- 卖家回复输入框 -->
        <div v-if="replyBoxVisible" class="input-box">
          <el-input
            v-model="replyContent"
            type="textarea"
            :rows="2"
            maxlength="500"
            show-word-limit
            placeholder="以商家身份回复这条评价"
          />
          <div class="input-actions">
            <el-button size="small" @click="toggleReplyBox">取消</el-button>
            <el-button size="small" type="primary" :loading="submitting" @click="submitReply">
              发布回复
            </el-button>
          </div>
        </div>

        <!-- 追问输入框 -->
        <div v-if="askBoxVisible" class="input-box">
          <el-input
            v-model="askContent"
            type="textarea"
            :rows="2"
            maxlength="300"
            show-word-limit
            :placeholder="askPlaceholder"
          />
          <div class="input-actions">
            <el-button size="small" @click="closeAsk">取消</el-button>
            <el-button size="small" type="primary" :loading="submitting" @click="submitAsk">
              发布追问
            </el-button>
          </div>
        </div>
      </div>
    </div>
  </li>
</template>

<script setup>
import { computed, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import commentApi from '@/api/comment'

/**
 * 单条 L1 评价及其下挂的 L2 商家回复 + L3 追问。
 *
 * <h3>为什么这里没有递归组件</h3>
 * 后端 `CommentService#askL3` 会把任何对 L2/L3 的追问强制拍平到同一条 L1 之下
 * （`parentId` / `rootId` 都写成 L1 的 id），树深恒为 2。
 * 所以「L1 → reply / asks」两层平铺就够了，写递归只会凭空增加复杂度和爆栈风险。
 */

const props = defineProps({
  /** 一条 L1 评价的 CommentVO */
  comment: {
    type: Object,
    required: true
  },
  /** 服务端下发的浏览者上下文，决定按钮的显隐 */
  viewerContext: {
    type: Object,
    default: () => ({})
  }
})

const emit = defineEmits(['changed'])

const submitting = ref(false)
const loadingAsks = ref(false)
const expanded = ref(false)
/** 展开后从 `/comment/{rootId}/replies` 拉到的全量追问 */
const fullAsks = ref([])

const replyBoxVisible = ref(false)
const replyContent = ref('')

const askBoxVisible = ref(false)
const askContent = ref('')
/** 追问的目标：null 表示直接追问 L1，否则是被 @ 的那条 L3 */
const askTarget = ref(null)

const editing = ref(false)
const editForm = reactive({ content: '', rating: 5 })

/** 展开后用全量列表，否则用服务端给的预览（默认 3 条） */
const visibleAsks = computed(() => {
  if (expanded.value) {
    return fullAsks.value
  }
  return Array.isArray(props.comment.asks) ? props.comment.asks : []
})

/** 只有确实还有更多追问时才显示展开按钮，避免「查看全部 0 条」 */
const showExpandButton = computed(() => {
  const total = Number(props.comment.askTotal) || 0
  return props.comment.askHasMore || (expanded.value && total > 0)
})

/** 卖家回复按钮：有回复权限 且 这条 L1 还没被回复过 */
const canShowReplyButton = computed(() => props.viewerContext.canReply && !props.comment.reply)

/** `images` 后端存的是逗号分隔字符串，不是数组 */
const imageList = computed(() => {
  const raw = props.comment.images
  if (!raw || typeof raw !== 'string') {
    return []
  }
  return raw
    .split(',')
    .map((item) => item.trim())
    .filter(Boolean)
})

const askPlaceholder = computed(() => {
  if (askTarget.value) {
    return `回复 @${askTarget.value.nickname || '该用户'}`
  }
  return '向买家或商家追问，例如：用了多久？会不会掉漆？'
})

/**
 * 取昵称首字符作为头像占位。
 *
 * @param {string} nickname 昵称
 * @returns {string} 单个大写字符
 */
function initial(nickname) {
  if (!nickname) {
    return 'U'
  }
  return String(nickname).charAt(0).toUpperCase()
}

/**
 * 格式化后端 LocalDateTime。
 *
 * 后端序列化出来可能是 `2024-01-01T12:00:00` 或 `2024-01-01 12:00:00`，
 * 统一成 `YYYY-MM-DD HH:mm` 展示。
 *
 * @param {string} value 时间字符串
 * @returns {string} 展示用文本
 */
function formatTime(value) {
  if (!value) {
    return ''
  }
  return String(value).replace('T', ' ').slice(0, 16)
}

/**
 * 展开 / 收起全部追问。
 *
 * @returns {Promise<void>}
 */
async function toggleAsks() {
  if (expanded.value) {
    expanded.value = false
    return
  }
  loadingAsks.value = true
  try {
    const res = await commentApi.getReplies(props.comment.id, { pageNum: 1, pageSize: 50 })
    // 这里的 data 是 PageResult，同样要取 records，不能直接当数组用。
    fullAsks.value = res.data?.records || []
    expanded.value = true
  } finally {
    loadingAsks.value = false
  }
}

/**
 * 切换卖家回复输入框。
 *
 * @returns {void}
 */
function toggleReplyBox() {
  replyBoxVisible.value = !replyBoxVisible.value
  if (!replyBoxVisible.value) {
    replyContent.value = ''
  } else {
    askBoxVisible.value = false
  }
}

/**
 * 提交 L2 卖家回复。
 *
 * @returns {Promise<void>}
 */
async function submitReply() {
  const content = replyContent.value.trim()
  if (!content) {
    ElMessage.warning('请输入回复内容')
    return
  }
  submitting.value = true
  try {
    await commentApi.replyComment({ parentId: props.comment.id, content })
    ElMessage.success('回复成功')
    replyContent.value = ''
    replyBoxVisible.value = false
    emit('changed')
  } finally {
    // 失败提示由 request.js 统一弹出（后端中文文案），这里不重复 toast。
    submitting.value = false
  }
}

/**
 * 打开追问输入框。
 *
 * @param {object|null} target 被 @ 的 L3；null 表示直接追问 L1
 * @returns {void}
 */
function openAsk(target) {
  askTarget.value = target
  askBoxVisible.value = true
  replyBoxVisible.value = false
}

/**
 * 关闭追问输入框。
 *
 * @returns {void}
 */
function closeAsk() {
  askBoxVisible.value = false
  askContent.value = ''
  askTarget.value = null
}

/**
 * 提交 L3 追问。
 *
 * 注意入参字段是 `commentId`（后端 CommentAskDTO），不是 parentId。
 *
 * @returns {Promise<void>}
 */
async function submitAsk() {
  const content = askContent.value.trim()
  if (!content) {
    ElMessage.warning('请输入追问内容')
    return
  }
  submitting.value = true
  try {
    const commentId = askTarget.value ? askTarget.value.id : props.comment.id
    await commentApi.askComment({ commentId, content })
    ElMessage.success('追问成功')
    closeAsk()
    emit('changed')
  } finally {
    submitting.value = false
  }
}

/**
 * 进入编辑态。
 *
 * @returns {void}
 */
function startEdit() {
  editForm.content = props.comment.content || ''
  editForm.rating = props.comment.rating || 5
  editing.value = true
}

/**
 * 退出编辑态。
 *
 * @returns {void}
 */
function cancelEdit() {
  editing.value = false
}

/**
 * 提交编辑。
 *
 * @returns {Promise<void>}
 */
async function submitEdit() {
  const content = editForm.content.trim()
  if (!content) {
    ElMessage.warning('内容不能为空')
    return
  }
  submitting.value = true
  try {
    const payload = { content }
    if (props.comment.rating) {
      payload.rating = editForm.rating
    }
    await commentApi.updateComment(props.comment.id, payload)
    ElMessage.success('修改成功')
    editing.value = false
    emit('changed')
  } finally {
    submitting.value = false
  }
}

/**
 * 删除评论 / 追问。
 *
 * @param {number} id 目标 id
 * @param {string} label 用于确认文案的名称
 * @returns {Promise<void>}
 */
async function handleDelete(id, label) {
  try {
    await ElMessageBox.confirm(`确定删除这条${label}吗？`, '删除确认', {
      type: 'warning',
      confirmButtonText: '删除',
      cancelButtonText: '取消'
    })
  } catch {
    // 用户点了取消，直接返回，不能当成错误往外抛。
    return
  }
  await commentApi.deleteComment(id)
  ElMessage.success('已删除')
  emit('changed')
}
</script>

<style lang="scss" scoped>
.comment-item {
  padding: $space-5 0;
  border-bottom: 1px solid $color-border-lighter;

  &:last-child {
    border-bottom: none;
  }
}

.comment-main {
  display: flex;
  gap: $space-3;
}

.avatar {
  flex-shrink: 0;
  background: $gradient-brand;
  color: $color-text-inverse;
  font-weight: $font-weight-bold;
}

.comment-body {
  flex: 1;
  min-width: 0;
}

.comment-meta {
  display: flex;
  align-items: center;
  gap: $space-2;
  margin-bottom: $space-1;

  .nickname {
    font-weight: $font-weight-bold;
    color: $color-text-primary;
  }

  .rate {
    line-height: 1;
  }

  .time {
    margin-left: auto;
    color: $color-text-placeholder;
    font-size: $font-xs;
  }
}

.content {
  color: $color-text-regular;
  line-height: $line-height-base;
  word-break: break-word;
  white-space: pre-wrap;
}

.image-list {
  display: flex;
  flex-wrap: wrap;
  gap: $space-2;
  margin-top: $space-2;

  .thumb {
    width: 82px;
    height: 82px;
    border-radius: $radius-sm;
    border: 1px solid $color-border-lighter;
    cursor: zoom-in;
  }
}

.seller-reply {
  margin-top: $space-3;
  padding: $space-2 $space-3;
  background: $brand-50;
  border-left: 3px solid $color-primary;
  border-radius: 0 $radius-sm $radius-sm 0;
  font-size: $font-sm;
  line-height: $line-height-base;

  .seller-badge {
    color: $color-primary;
    font-weight: $font-weight-bold;
    margin-right: $space-2;
  }

  .reply-text {
    color: $color-text-regular;
    word-break: break-word;
  }

  .reply-time {
    display: block;
    margin-top: $space-1;
    color: $color-text-placeholder;
    font-size: $font-xs;
  }
}

.ask-list {
  margin-top: $space-3;
  padding: $space-2 $space-3;
  background: $color-bg-subtle;
  border-radius: $radius-sm;
  list-style: none;

  .ask-item {
    padding: $space-1 0;
    font-size: $font-sm;
    line-height: $line-height-base;
    color: $color-text-regular;
    word-break: break-word;

    .ask-user {
      color: $color-primary;
      font-weight: $font-weight-medium;
    }

    .ask-target {
      color: $color-text-secondary;
      margin-left: $space-1;
    }

    .ask-sep {
      color: $color-text-secondary;
    }

    .ask-time {
      margin-left: $space-2;
      color: $color-text-placeholder;
      font-size: $font-xs;
    }

    .ask-ops {
      margin-left: $space-1;
      opacity: 0;
      transition: opacity $transition-fast;
    }

    &:hover .ask-ops {
      opacity: 1;
    }
  }
}

.expand-row {
  margin-top: $space-1;
}

.comment-actions {
  display: flex;
  align-items: center;
  gap: $space-3;
  margin-top: $space-2;

  .hint {
    color: $color-text-placeholder;
    font-size: $font-xs;
  }
}

.input-box,
.edit-box {
  margin-top: $space-2;
  display: flex;
  flex-direction: column;
  gap: $space-2;
}

.input-actions,
.edit-actions {
  display: flex;
  justify-content: flex-end;
  gap: $space-2;
}
</style>
