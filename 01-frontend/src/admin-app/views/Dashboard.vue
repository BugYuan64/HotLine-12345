<template>
  <!-- 系统总览工作台：Cursor 极简风 + 纯 SVG 折线图（零依赖） -->
  <AdminLayout title="系统总览">
    <!-- 指标卡 -->
    <div class="metric-row">
      <div class="metric-card clickable" @click="router.push('/admin/appeals/parent')">
        <div class="metric-head">
          <div class="metric-label">诉求总数</div>
          <span class="metric-tag">近7日</span>
        </div>
        <div class="metric-value">{{ summary.appeal_total || 0 }}</div>
        <div class="metric-foot">近 7 日新增诉求</div>
      </div>
      <div class="metric-card clickable" @click="router.push('/admin/orders')">
        <div class="metric-head">
          <div class="metric-label">工单总数</div>
          <span class="metric-tag">{{ periodText }}</span>
        </div>
        <div class="metric-value">{{ summary.order_total || 0 }}</div>
        <div class="metric-foot">{{ periodText }}生成工单</div>
      </div>
      <div class="metric-card">
        <div class="metric-head">
          <div class="metric-label">已分派工单</div>
          <span class="metric-tag">{{ periodText }}</span>
        </div>
        <div class="metric-value">{{ summary.assigned || 0 }}</div>
        <div class="metric-foot">{{ periodText }}已分派工单数</div>
      </div>
      <div class="metric-card clickable" @click="router.push('/admin/stats')">
        <div class="metric-head">
          <div class="metric-label">分派完成率</div>
          <span class="metric-tag ok">已达成</span>
        </div>
        <div class="metric-value">{{ ((summary.dispatch_rate || 0) * 100).toFixed(1) }}%</div>
        <div class="metric-foot">已分派工单占比</div>
      </div>
    </div>

    <!-- 待我介入：散落在各列表的待办事项聚合（API todo/summary） -->
    <div class="panel todo-panel">
      <div class="panel-head">
        <div class="panel-title">待我介入</div>
        <div class="panel-sub">工单 / 预警 / 敏感事件中待处理的事项</div>
      </div>
      <div v-if="hasTodo" class="todo-row">
        <div v-for="t in todoCards" :key="t.key" class="todo-card" :class="{ active: (todo[t.key] || 0) > 0 }" @click="goTodo(t)">
          <div class="todo-label">{{ t.label }}</div>
          <div class="todo-value">{{ todo[t.key] || 0 }}</div>
        </div>
      </div>
      <div v-else class="empty">当前无待办事项</div>
    </div>

    <!-- 上方：分类分布（全宽） -->
    <div class="panel" style="margin-bottom:16px">
      <div class="panel-head">
        <div class="panel-title">分类分布</div>
        <div class="panel-sub">按当前周期诉求分类占比</div>
      </div>
      <div class="cat-list cat-list-wide">
        <div v-for="c in categoryDistribution" :key="c.category" class="cat-row">
          <div class="cat-left">
            <span class="cat-dot" :style="{ background: categoryColor(c.category) }"></span>
            <span class="cat-name">{{ categoryLabel(c.category) }}</span>
          </div>
          <div class="cat-right">
            <div class="cat-track"><div class="cat-fill"
              :style="{ width: pct(c) + '%', background: categoryColor(c.category) }"></div></div>
            <span class="cat-cnt">{{ c.cnt }}</span>
            <span class="cat-pct">{{ pct(c) }}%</span>
          </div>
        </div>
        <div v-if="!categoryDistribution.length" class="empty">暂无分类数据</div>
      </div>
    </div>

    <!-- 分类柱状图面板已移除：与趋势折线图合并为下方"工单量与完成量"可切换面板 -->

    <!-- 工单量与完成量：折线图/柱状图点击切换（同一日期时间线 + 周期切换） -->
    <div class="panel chart-panel" style="margin-bottom:16px" v-loading="summaryLoading">
      <div class="panel-head chart-head">
        <div>
          <div class="panel-title">工单量与完成量</div>
          <div class="panel-sub">近 {{ seriesData.length }} 日受理与办结走势</div>
        </div>
        <div class="chart-tools">
          <div class="bar-legend">
            <span class="legend-item"><span class="legend-dot" :style="{ background: BAR_COLOR_TOTAL }"></span>工单量</span>
            <span class="legend-item"><span class="legend-dot" :style="{ background: BAR_COLOR_DONE }"></span>完成量</span>
          </div>
          <!-- 单按钮切换：文字显示「点击后切换到的图表」，点击切换折线图/柱状图 -->
          <div class="type-toggle">
            <button
              type="button"
              class="button"
              :class="{ active: chartType === 'line' }"
              @click="chartType = chartType === 'line' ? 'bar' : 'line'"
            >
              <div class="button-top">{{ chartType === 'line' ? '柱状图' : '折线图' }}</div>
              <div class="button-bottom"></div>
              <div class="button-base"></div>
            </button>
          </div>
          <!-- period 切换下拉（单按钮） -->
        <div class="period-bar">
          <el-select
            v-model="activePeriod"
            size="small"
            class="period-select"
            @change="v => (activePeriod = v)">
            <el-option
              v-for="opt in PERIOD_OPTIONS"
              :key="opt.key"
              :value="opt.key"
              :label="opt.label" />
          </el-select>
        </div>
      </div>
      </div>
      <!-- 柱状图模式：每日一组（左柱工单量 + 右柱完成量），X 轴为日期 -->
      <div class="svg-wrap" v-if="chartType === 'bar'">
        <svg :viewBox="viewBox" class="chart-svg" preserveAspectRatio="none">
          <!-- Y 轴网格 -->
          <g>
            <line v-for="(y, i) in gridYs" :key="'bgy' + i"
              :x1="PAD_L" :x2="W - PAD_R" :y1="y" :y2="y"
              stroke="#f1f1ef" stroke-width="1" />
          </g>
          <!-- 每日分组柱子 -->
          <g v-for="(g, i) in dateBarGroups" :key="'bg' + i">
            <rect :x="g.totalX" :y="g.totalY" :width="g.barW" :height="g.totalH" rx="2" :fill="BAR_COLOR_TOTAL" />
            <rect :x="g.doneX" :y="g.doneY" :width="g.barW" :height="g.doneH" rx="2" :fill="BAR_COLOR_DONE" />
            <!-- 日期较多时隐藏柱顶数值，避免重叠 -->
            <template v-if="showBarValue">
              <text :x="g.totalX + g.barW / 2" :y="g.totalY - 5" text-anchor="middle" font-size="9" fill="#52525b">{{ g.total }}</text>
              <text :x="g.doneX + g.barW / 2" :y="g.doneY - 5" text-anchor="middle" font-size="9" fill="#52525b">{{ g.done }}</text>
            </template>
          </g>
          <!-- X 轴日期标签（与折线图共用同一条时间线） -->
          <g>
            <text v-for="(l, i) in xLabelPositions" :key="'bxl' + i"
              :x="l.x" :y="H - 8" text-anchor="middle" font-size="10" fill="#a1a1aa">{{ l.label }}</text>
          </g>
          <!-- Y 轴单位（左上角，与折线图一致） -->
          <text :x="PAD_L - 6" :y="PAD_T - 12" text-anchor="end" font-size="10" fill="#a1a1aa">单位：件</text>
          <!-- Y 轴刻度 -->
          <g>
            <text v-for="(l, i) in gridLabels" :key="'byl' + i"
              :x="PAD_L - 6" :y="gridYs[i] + 3" text-anchor="end" font-size="10" fill="#a1a1aa">{{ l }}</text>
          </g>
        </svg>
      </div>
      <!-- 折线图模式：双折线（工单量/完成量），与柱状图共用同一时间线 -->
      <div class="svg-wrap" v-else>
        <svg :viewBox="viewBox" class="chart-svg" preserveAspectRatio="none">
          <!-- 渐变填充区（工单量折线下方） -->
          <defs>
            <linearGradient id="areaFill" x1="0" y1="0" x2="0" y2="1">
              <stop offset="0%" stop-color="#93c5fd" stop-opacity="0.14" />
              <stop offset="100%" stop-color="#93c5fd" stop-opacity="0.01" />
            </linearGradient>
          </defs>
          <!-- Y 轴基线（网格线） -->
          <g class="grid">
            <line v-for="(y, i) in gridYs" :key="i"
              :x1="PAD_L" :x2="W - PAD_R" :y1="y" :y2="y"
              stroke="#f1f1ef" stroke-width="1" />
          </g>
          <!-- 渐变填充区（折线闭合到底部） -->
          <path :d="areaPath" fill="url(#areaFill)" />
          <!-- 工单量折线 -->
          <polyline :points="linePoints"
            fill="none" :stroke="BAR_COLOR_TOTAL" stroke-width="2"
            stroke-linejoin="round" stroke-linecap="round" />
          <!-- 完成量折线 -->
          <polyline :points="doneLinePoints"
            fill="none" :stroke="BAR_COLOR_DONE" stroke-width="2"
            stroke-linejoin="round" stroke-linecap="round" />
          <!-- 工单量数据点 + 悬停 -->
          <g v-for="(pt, i) in pointPositions" :key="'tp' + i">
            <circle :cx="pt.x" :cy="pt.y" r="3.5" :fill="BAR_COLOR_TOTAL" />
            <circle :cx="pt.x" :cy="pt.y" r="12" fill="transparent" class="hover-hit"
              @mouseenter="onPointHover(i, pt)" @mouseleave="onPointLeave" />
          </g>
          <!-- 完成量数据点 + 悬停 -->
          <g v-for="(pt, i) in donePositions" :key="'dp' + i">
            <circle :cx="pt.x" :cy="pt.y" r="3.5" :fill="BAR_COLOR_DONE" />
            <circle :cx="pt.x" :cy="pt.y" r="12" fill="transparent" class="hover-hit"
              @mouseenter="onPointHover(i, pt)" @mouseleave="onPointLeave" />
          </g>
          <!-- 悬停辅助线 -->
          <g v-if="hoverIdx >= 0 && hoverPos">
            <line :x1="hoverPos.x" :x2="hoverPos.x"
              :y1="PAD_T" :y2="H - PAD_B" stroke="#d4d4d8" stroke-width="1" stroke-dasharray="3 3" />
            <circle :cx="hoverPos.x" :cy="hoverPos.y"
              r="6" fill="#fff" stroke="#18181b" stroke-width="2" />
          </g>
          <!-- X 轴日期标签（与柱状图共用） -->
          <g class="x-labels">
            <text v-for="(l, i) in xLabelPositions" :key="i"
              :x="l.x" :y="H - 8" text-anchor="middle"
              font-size="10" fill="#a1a1aa">
              {{ l.label }}
            </text>
          </g>
          <!-- Y 轴单位（左上角，与柱状图一致） -->
          <text :x="PAD_L - 6" :y="PAD_T - 12" text-anchor="end" font-size="10" fill="#a1a1aa">单位：件</text>
          <!-- Y 轴刻度 -->
          <g class="y-labels">
            <text v-for="(y, i) in gridLabels" :key="i"
              :x="PAD_L - 6" :y="gridYs[i] + 3" text-anchor="end"
              font-size="10" fill="#a1a1aa">
              {{ gridLabels[i] }}
            </text>
          </g>
        </svg>
        <!-- Tooltip -->
        <div v-if="hoverIdx >= 0"
          class="chart-tooltip"
          :style="{ left: tooltipLeft + 'px', top: tooltipTop + 'px' }">
          <div class="tt-date">{{ seriesData[hoverIdx]?.d }}</div>
          <div class="tt-row"><span class="tt-dot" :style="{ background: BAR_COLOR_TOTAL }"></span>工单量 {{ seriesData[hoverIdx]?.total }} 件</div>
          <div class="tt-row"><span class="tt-dot" :style="{ background: BAR_COLOR_DONE }"></span>完成量 {{ seriesData[hoverIdx]?.done }} 件</div>
        </div>
      </div>
    </div>

    <!-- AI 概要 -->
    <div class="panel summary-panel" v-if="summary.ai_summary">
      <div class="panel-title">AI 统计概要</div>
      <div class="summary-text">{{ summary.ai_summary }}</div>
    </div>

    <!-- 下半部分：近 30 天诉求 + 近 30 天工单 -->
    <div class="grid-2" style="margin-top:16px">
      <!-- 最新诉求（数据源为子诉求，按创建时间倒序取最近8条；诉求总数见顶部指标卡按父诉求统计） -->
      <div class="panel list-panel">
        <div class="panel-head">
          <div class="panel-title">最新诉求</div>
          <div class="panel-sub">共 {{ recentAppeals.length }} 条</div>
        </div>
        <div class="list-body" v-loading="listLoading">
          <div v-if="!recentAppeals.length && !listLoading" class="list-empty">暂无诉求数据</div>
          <div v-for="row in recentAppeals" :key="'a'+row.id" class="list-row">
            <div class="list-row-head">
              <span class="list-no">{{ row.appeal_no }}</span>
              <el-tag :type="orderStatusType(row.status)" size="small">{{ appealStatusLabel(row.status) }}</el-tag>
            </div>
            <div class="list-title" :title="row.title">{{ row.title }}</div>
            <div class="list-meta">
              <span>{{ row.broad_flag === 1 ? '秦皇岛市' : ([row.district, row.street].filter(Boolean).join(' ') || '-') }}</span>
              <span class="dot">·</span>
              <span>{{ (row.create_time || '').toString().slice(5, 16).replace('T', ' ') }}</span>
            </div>
          </div>
        </div>
      </div>

      <!-- 工单（周期动态 + 分页） -->
      <div class="panel list-panel">
        <div class="panel-head">
          <div class="panel-title">{{ activePeriod === 'WEEK' ? '近7天' : activePeriod === 'HALF_MONTH' ? '近15天' : '近30天' }}工单</div>
          <div class="panel-sub">共 {{ orderTotal }} 条</div>
        </div>
        <div class="list-body" v-loading="listLoading">
          <div v-if="!recentOrders.length && !listLoading" class="list-empty">暂无工单数据</div>
          <div v-for="row in recentOrders" :key="'o'+row.id" class="list-row">
            <div class="list-row-head">
              <span class="list-no">{{ row.order_no }}</span>
              <span class="level-dot" :style="{ background: orderLevelColor(row.level) }"></span>
              <span class="level-lbl">{{ orderLevelLabel(row.level) }}</span>
              <el-tag :type="orderStatusType(row.status)" size="small" style="margin-left:auto">{{ orderStatusLabel(row.status) }}</el-tag>
            </div>
            <div class="list-title" :title="row.title">{{ row.title }}</div>
            <div class="list-meta">
              <span>{{ categoryLabel(row.category) }}</span>
              <span class="dot">·</span>
              <span>{{ row.region || '-' }}</span>
              <span class="dot">·</span>
              <span>{{ (row.create_time || '').toString().slice(5, 16).replace('T', ' ') }}</span>
            </div>
          </div>
        </div>
        <!-- 分页 -->
        <div class="panel-pagination" v-if="orderTotal > orderPageSize">
          <el-pagination
            v-model:current-page="orderPage"
            v-model:page-size="orderPageSize"
            :page-sizes="[5, 8, 10]"
            :total="orderTotal"
            size="small"
            background
            layout="prev, pager, next, total"
            @current-change="reloadOrders"
            @size-change="reloadOrders" />
        </div>
      </div>
    </div>
  </AdminLayout>
</template>

<script setup>
import { captureContentScroll, restoreContentScroll } from '../../shared/contentScroll'
import { reactive, computed, onMounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import AdminLayout from '../components/AdminLayout.vue'
import { getStatsSummary } from '../api/stats'
import { ORDER_STATUS } from '../../shared/dict/orderStatus'
import { getAppeals } from '../api/appeal'
import { getOrders } from '../api/order'
import { getPrimaries } from '../api/classify'
import { getTodoSummary } from '../api/todo'

// ========== 布局常量（单一真值） ==========
const W = 720, H = 400  // 图表缩小三分之一（原 600 → 400）
const PAD_L = 48, PAD_R = 16, PAD_T = 22, PAD_B = 28  // PAD_T 留出单位标签行
const PLOT_W = W - PAD_L - PAD_R
const PLOT_H = H - PAD_T - PAD_B

// 周期枚举（后端新增 HALF_MONTH 支持）
const PERIOD_OPTIONS = [
  { key: 'WEEK',      label: '近7天' },
  { key: 'HALF_MONTH', label: '近15天' },
  { key: 'MONTH',     label: '近30天' }
]
const activePeriod = ref('MONTH')

// 总览卡动态时间口径文案（与 activePeriod 联动，保证卡片数字与标签一致）
const periodText = computed(() =>
  ({ WEEK: '近7天', HALF_MONTH: '近15天', MONTH: '近30天' })[activePeriod.value] || '近30天'
)

// 加载状态
const summaryLoading = ref(false)

// ========== period → start_date 字符串（yyyy-MM-dd HH:mm:ss） ==========
const periodToStartDate = (period) => {
  const now = new Date()
  const days = { WEEK: 7, HALF_MONTH: 15, MONTH: 30 }[period] || 1
  const start = new Date(now.getTime() - days * 86400000)
  const pad = n => String(n).padStart(2, '0')
  return `${start.getFullYear()}-${pad(start.getMonth()+1)}-${pad(start.getDate())} 00:00:00`
}

// ========== 工单分页状态 ==========
const orderPage = ref(1)
const orderPageSize = ref(8)
const orderTotal = ref(0)
const listLoading = ref(false)

// 分类 → 名称/颜色映射（动态来自 classify_primary，避免硬编码旧分类）
const primaries = ref([])
const primaryMap = reactive({})
const CATEGORY_COLORS = ['#6ee7b7', '#93c5fd', '#c4b5fd', '#f9a8d4', '#fcd34d', '#86efac', '#fca5a5', '#a5b4fc']

const loadClassifyDict = async () => {
  try {
    const list = await getPrimaries()
    primaries.value = (list || []).filter((p) => p.code)
    Object.keys(primaryMap).forEach((k) => delete primaryMap[k])
    primaries.value.forEach((p) => { primaryMap[p.code] = p.name })
  } catch { /* 忽略，仅影响展示 */ }
}

const categoryColor = (c) => {
  const idx = primaries.value.findIndex((p) => p.code === c)
  return idx >= 0 ? CATEGORY_COLORS[idx % CATEGORY_COLORS.length] : '#d4d4d8'
}
const categoryLabel = (c) => primaryMap[c] || c || '未分类'

const summary = reactive({
  appeal_total: 0, order_total: 0, pending_confirm: 0, pending_update: 0,
  assigned: 0, dispatch_rate: 0, category_distribution: [], category_done_distribution: [],
  level_distribution: [], trend: [], ai_summary: ''
})

// ========== 待我介入（待办聚合卡片 / API todo/summary） ==========
const router = useRouter()
const todo = reactive({
  reassign_orders: 0, pending_assign: 0, final_audit: 0,
  warning_review: 0, warning_urgent: 0, sensitive_events: 0
})
const todoCards = [
  { key: 'reassign_orders',  label: '待重分工单',     path: '/admin/orders',     query: { status: 'PENDING_REASSIGN' } },
  { key: 'pending_assign',   label: '待管理端分派',   path: '/admin/orders',     query: { status: 'PENDING_DISPATCH' } },
  { key: 'final_audit',      label: '待全局终审',     path: '/admin/final-audit' },
  { key: 'warning_review',   label: '待审核预警',     path: '/admin/warnings',   query: { tab: 'record' } },
  { key: 'warning_urgent',   label: '紧急预警超时',   path: '/admin/warnings',   query: { tab: 'record' } },
  { key: 'sensitive_events', label: '未处理敏感事件', path: '/admin/warnings',   query: { tab: 'sensitive' } }
]
const hasTodo = computed(() => todoCards.some((t) => (todo[t.key] || 0) > 0))
const goTodo = (t) => router.push({ path: t.path, query: t.query })
const loadTodo = async () => {
  try {
    Object.assign(todo, (await getTodoSummary()) || {})
  } catch { /* 不阻断 */ }
}

// ========== 图表类型切换 + 统一时间轴（折线/柱状同源数据） ==========
const chartType = ref('line') // 'line' 折线图 | 'bar' 柱状图
const BAR_COLOR_TOTAL = '#93c5fd' // 工单量 - 淡蓝
const BAR_COLOR_DONE = '#6ee7b7'  // 完成量 - 青绿

// 完成量趋势（后端按办结时间 close_time 日聚合）
const trendDone = computed(() => summary.trend_done || [])

// 统一时间轴：取工单量与完成量日期并集并排序，保证两种图共用同一条时间线
const trendDates = computed(() => {
  const set = new Set()
  for (const t of (summary.trend || [])) set.add(t.d)
  for (const t of trendDone.value) set.add(t.d)
  return [...set].sort()
})

// 按日期对齐的双序列：{ d 日期, total 工单量, done 完成量 }
const seriesData = computed(() => {
  const totalMap = Object.fromEntries((summary.trend || []).map(t => [t.d, t.cnt || 0]))
  const doneMap = Object.fromEntries(trendDone.value.map(t => [t.d, t.cnt || 0]))
  return trendDates.value.map(d => ({ d, total: totalMap[d] || 0, done: doneMap[d] || 0 }))
})

// Y 轴最大值（两序列取大者，留 20% 余量）
const maxVal = computed(() => {
  const m = Math.max(...seriesData.value.flatMap(s => [s.total, s.done]), 1)
  return Math.ceil(m * 1.2)
})

// 柱状图模式：每日一组（左柱工单量 + 右柱完成量）
const showBarValue = computed(() => seriesData.value.length <= 15) // 日期过多时隐藏柱顶数值避免重叠
const dateBarGroups = computed(() => {
  const data = seriesData.value
  if (!data.length) return []
  const slotW = PLOT_W / data.length
  const barW = Math.max(3, Math.min(12, slotW / 3.2))
  return data.map((s, i) => {
    const cx = PAD_L + slotW * i + slotW / 2
    const mk = (val) => {
      const h = (val / maxVal.value) * PLOT_H
      return { y: PAD_T + PLOT_H - h, h }
    }
    const t = mk(s.total), d = mk(s.done)
    return { ...s, barW,
      totalX: cx - barW - 1.5, totalY: t.y, totalH: t.h,
      doneX: cx + 1.5, doneY: d.y, doneH: d.h }
  })
})

// 近7天诉求 + 工单列表
const recentAppeals = ref([])
const recentOrders = ref([])

// 状态/类别显示辅助（状态映射统一走 shared/dict/orderStatus，与四端口径一致）
const orderStatusLabel = (s) => ORDER_STATUS[s]?.label || s
const orderStatusType = (s) => ORDER_STATUS[s]?.color || 'info'
const orderLevelLabel = (l) => ({ '一级': '一级', '二级': '二级', '三级': '三级', HIGH: '一级', MEDIUM: '二级', LOW: '三级' }[l] || (l || '-'))
const orderLevelColor = (l) => ({ '一级': '#ef4444', '二级': '#f59e0b', '三级': '#9ca3af', HIGH: '#ef4444', MEDIUM: '#f59e0b', LOW: '#9ca3af' }[l] || '#9ca3af')
const appealStatusLabel = (s) => ({ PENDING_AI: '待AI检测', CHILDREN_GENERATED: '已拆分', ORDERED: '已生成工单', PROCESSING: '处理中' }[s] || s)

const categoryDistribution = computed(() =>
  [...(summary.category_distribution || [])].sort((a, b) => b.cnt - a.cnt)
)
// 分类占比（最大余数法）：各行先向下取整，再将与 100 的差额按小数余数从大到小逐行 +1，
// 保证各行百分比之和恒等于 100（逐行独立四舍五入会出现 101%/102% 之和不为 100 的问题）
const categoryPct = computed(() => {
  const rows = categoryDistribution.value
  const total = rows.reduce((s, c) => s + (c.cnt || 0), 0)
  if (total <= 0) return {}
  const map = {}
  const rems = rows.map(c => {
    const exact = ((c.cnt || 0) / total) * 100
    map[c.category] = Math.floor(exact)
    return { category: c.category, rem: exact - Math.floor(exact) }
  })
  let gap = 100 - Object.values(map).reduce((s, v) => s + v, 0)
  rems.sort((a, b) => b.rem - a.rem)
  for (let i = 0; gap > 0 && i < rems.length; i++, gap--) map[rems[i].category]++
  return map
})
const pct = (c) => categoryPct.value[c?.category] ?? 0

// ========== SVG 坐标映射（单一真值） ==========
// SVG viewBox 标准格式：x y width height
const viewBox = `0 0 ${W} ${H}`
const hoverIdx = ref(-1)
const hoverPos = ref(null) // 悬停点坐标（辅助线/圆环定位）
const onPointHover = (i, pt) => { hoverIdx.value = i; hoverPos.value = { x: pt.x, y: pt.y } }
const onPointLeave = () => { hoverIdx.value = -1; hoverPos.value = null }

// 等距步长（折线/柱状共用，不包含 PAD_L/PAD_R，直接用 PAD_L + idx * step）
const xStep = computed(() => {
  const n = seriesData.value.length
  if (!n) return 0
  return n === 1 ? PLOT_W / 2 : PLOT_W / (n - 1)
})

// 工单量折线坐标
const pointPositions = computed(() =>
  seriesData.value.map((s, i) => ({
    x: PAD_L + i * xStep.value,
    y: PAD_T + PLOT_H - ((s.total || 0) / maxVal.value) * PLOT_H
  }))
)

// 完成量折线坐标
const donePositions = computed(() =>
  seriesData.value.map((s, i) => ({
    x: PAD_L + i * xStep.value,
    y: PAD_T + PLOT_H - ((s.done || 0) / maxVal.value) * PLOT_H
  }))
)

// 折线 polyline points（工单量/完成量）
const linePoints = computed(() =>
  pointPositions.value.map(p => `${p.x},${p.y}`).join(' ')
)
const doneLinePoints = computed(() =>
  donePositions.value.map(p => `${p.x},${p.y}`).join(' ')
)

// 渐变填充区（折线闭合到底部）
const areaPath = computed(() => {
  if (!pointPositions.value.length) return ''
  const pts = pointPositions.value
  const bottomY = PAD_T + PLOT_H
  let d = `M ${pts[0].x} ${bottomY}`
  pts.forEach(p => { d += ` L ${p.x} ${p.y}` })
  d += ` L ${pts[pts.length - 1].x} ${bottomY} Z`
  return d
})

// X 轴日期标签（只显示关键日避免拥挤；折线/柱状共用同一条时间线）
const xLabelPositions = computed(() => {
  const data = seriesData.value
  if (!data.length) return []
  const k = Math.max(1, Math.floor(data.length / 5))
  const labels = []
  data.forEach((s, i) => {
    if (i % k === 0 || i === data.length - 1) {
      const raw = s.d || ''
      labels.push({ x: PAD_L + i * xStep.value, label: raw.length >= 10 ? raw.slice(5) : raw }) // "09-08"
    }
  })
  return labels
})

// Y 轴网格（4 行）
const gridYs = computed(() => {
  const count = 4
  const arr = []
  for (let i = 0; i <= count; i++) {
    arr.push(PAD_T + (PLOT_H / count) * i)
  }
  return arr
})
const gridLabels = computed(() => {
  const count = 4
  const arr = []
  for (let i = count; i >= 0; i--) {
    arr.push(Math.round((maxVal.value / count) * i))
  }
  return arr
})

// Tooltip 位置（跟随悬停点）
const tooltipLeft = computed(() => {
  if (hoverIdx.value < 0 || !hoverPos.value) return 0
  // clamp 到容器内
  const raw = hoverPos.value.x - 40
  return Math.max(4, Math.min(raw, W - 120))
})
const tooltipTop = computed(() => {
  if (hoverIdx.value < 0 || !hoverPos.value) return 0
  // 显示在点上方
  return hoverPos.value.y - 56
})

// ========== 重新加载 summary（period 切换时调用） ==========
const reloadSummary = async () => {
  summaryLoading.value = true
  try {
    const data = await getStatsSummary({ period: activePeriod.value })
    Object.assign(summary, data || {})
  } catch { /* 不阻断 */ }
  summaryLoading.value = false
}

// ========== 重新加载工单列表（带 start_date 过滤 + 分页） ==========
const reloadOrders = async () => {
  listLoading.value = true
  const y = captureContentScroll()
  try {
    const params = {
      page: orderPage.value,
      page_size: orderPageSize.value,
      start_date: periodToStartDate(activePeriod.value)
    }
    const data = await getOrders(params)
    recentOrders.value = data?.list || []
    orderTotal.value = data?.total || 0
  } catch { /* 不阻断 */ }
  listLoading.value = false
    await restoreContentScroll(y)
}

// ========== 加载数据（并行） ==========
onMounted(async () => {
  loadClassifyDict()
  loadTodo()
  try {
    const [summaryData, appealsData, ordersData] = await Promise.allSettled([
      getStatsSummary({ period: activePeriod.value }),
      getAppeals({ scope: 'child', page: 1, page_size: 8 }),
      getOrders({ page: 1, page_size: 8, start_date: periodToStartDate(activePeriod.value) })
    ])
    if (summaryData.status === 'fulfilled') Object.assign(summary, summaryData.value || {})
    if (appealsData.status === 'fulfilled') recentAppeals.value = appealsData.value?.list || []
    if (ordersData.status === 'fulfilled') {
      recentOrders.value = ordersData.value?.list || []
      orderTotal.value = ordersData.value?.total || 0
    }
  } catch { /* 不阻断 */ }
})

// period 变化 → summary + orders（带 start_date 过滤）都刷新
watch(activePeriod, async () => {
  orderPage.value = 1
  await reloadSummary()
  await reloadOrders()
})
</script>

<style scoped>
/* ========== 指标卡 ========== */
.metric-row {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 16px;
  margin-bottom: 16px;
}
.metric-card {
  background: #fff;
  border: 1px solid #ececec;
  border-radius: 10px;
  padding: 16px 18px;
  display: flex;
  flex-direction: column;
  gap: 4px;
}
.metric-card.clickable { cursor: pointer; transition: all .15s; }
.metric-card.clickable:hover { border-color: #cbd5e1; box-shadow: 0 4px 12px rgba(0,0,0,.06); }
.metric-head { display: flex; justify-content: space-between; align-items: center; }
.metric-label { font-size: 13px; color: #71717a; }
.metric-tag {
  font-size: 11px;
  padding: 2px 8px;
  border-radius: 4px;
  background: #f4f4f2;
  color: #71717a;
  letter-spacing: 0.3px;
}
.metric-tag.warn { background: #fef3c7; color: #b45309; }
.metric-tag.ok   { background: #dcfce7; color: #15803d; }

/* ========== 待我介入 ========== */
.todo-panel { margin-bottom: 16px; }
.todo-row { display: grid; grid-template-columns: repeat(6, 1fr); gap: 12px; }
.todo-card {
  border: 1px solid #ececec;
  border-radius: 8px;
  padding: 12px 14px;
  cursor: pointer;
  background: #fafafa;
  transition: all .15s;
}
.todo-card:hover { border-color: #cbd5e1; background: #fff; }
.todo-card.active { background: #fff7ed; border-color: #fdba74; }
.todo-label { font-size: 12px; color: #71717a; margin-bottom: 6px; }
.todo-value { font-size: 24px; font-weight: 600; color: #1f2937; line-height: 1; }
.todo-card.active .todo-value { color: #ea580c; }
.metric-value {
  font-size: 28px;
  font-weight: 700;
  color: #18181b;
  font-variant-numeric: tabular-nums;
}
.metric-value.warn { color: #b45309; }
.metric-foot { font-size: 13px; color: #a1a1aa; }

/* ========== 两栏网格 ========== */
.grid-2 {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 16px;
  margin-bottom: 16px;
}

/* ========== 通用面板 ========== */
.panel {
  background: #fff;
  border: 1px solid #ececec;
  border-radius: 10px;
  padding: 16px 18px;
}
.panel-head { margin-bottom: 12px; }
.panel-title { font-size: 14px; font-weight: 700; color: #18181b; }
.panel-sub { font-size: 13px; color: #a1a1aa; margin-top: 2px; }

/* ========== SVG 折线图 ========== */
.chart-panel { padding-right: 8px; }
.svg-wrap {
  position: relative;
  width: 100%;
  height: 400px; /* 缩小三分之一（原 600 → 400），与 viewBox H 保持一致 */
}
/* 图表面板头部：标题 + 图例/切换按钮/周期下拉 同行 */
.chart-head {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  gap: 12px;
  flex-wrap: wrap;
}
.chart-tools {
  display: flex;
  align-items: center;
  gap: 14px;
  flex-wrap: wrap;
}
.bar-legend {
  display: flex;
  gap: 14px;
  align-items: center;
}
.legend-item {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  font-size: 12px;
  color: #71717a;
}
.legend-dot {
  width: 10px;
  height: 10px;
  border-radius: 3px;
  display: inline-block;
}

/* period 切换下拉（单按钮） */
.period-bar {
  display: inline-flex;
}
.period-select {
  width: 110px;
}

/* ========== 3D 切换按钮（Uiverse.io by njesenberger 样式，改为灰色） ========== */
.type-toggle {
  display: inline-flex;
  gap: 10px;
  align-items: flex-end;
}
.button {
  -webkit-appearance: none;
  appearance: none;
  position: relative;
  border-width: 0;
  padding: 0 4px 8px;
  min-width: 5em;
  box-sizing: border-box;
  background: transparent;
  font: inherit;
  font-size: 13px;
  cursor: pointer;
}
.button-top {
  display: flex;
  align-items: center;
  justify-content: center;
  position: relative;
  z-index: 0;
  padding: 6px 14px;
  transform: translateY(0);
  text-align: center;
  color: #fff;
  text-shadow: 0 -1px rgba(0, 0, 0, .25);
  transition-property: transform;
  transition-duration: .2s;
  -webkit-user-select: none;
  user-select: none;
}
/* 按下 / 选中态：按钮下沉 */
.button:active .button-top,
.button.active .button-top {
  transform: translateY(6px);
}
.button-top::after {
  content: '';
  position: absolute;
  z-index: -1;
  border-radius: 4px;
  width: 100%;
  height: 100%;
  box-sizing: content-box;
  /* 灰色渐变（原粉色改灰） */
  background-image: radial-gradient(#9ca3af, #6b7280);
  box-shadow: inset 0 0 0 1px rgba(255, 255, 255, .2), 0 1px 2px 1px rgba(255, 255, 255, .2);
  transition-property: border-radius, padding, width, transform;
  transition-duration: .2s;
}
.button:active .button-top::after {
  border-radius: 6px;
  padding: 0 2px;
}
.button-bottom {
  position: absolute;
  z-index: -1;
  bottom: 4px;
  left: 4px;
  border-radius: 8px / 16px 16px 8px 8px;
  padding-top: 6px;
  width: calc(100% - 8px);
  height: calc(100% - 10px);
  box-sizing: content-box;
  /* 灰色底座（原 #803 改深灰） */
  background-color: #52525b;
  background-image:
    radial-gradient(4px 8px at 4px calc(100% - 8px), rgba(255, 255, 255, .25), transparent),
    radial-gradient(4px 8px at calc(100% - 4px) calc(100% - 8px), rgba(255, 255, 255, .25), transparent),
    radial-gradient(16px at -4px 0, white, transparent),
    radial-gradient(16px at calc(100% + 4px) 0, white, transparent);
  box-shadow: 0px 2px 3px 0px rgba(0, 0, 0, 0.3), inset 0 -1px 3px 3px rgba(0, 0, 0, .3);
  transition-property: border-radius, padding-top;
  transition-duration: .2s;
}
.button:active .button-bottom,
.button.active .button-bottom {
  border-radius: 10px 10px 8px 8px / 8px;
  padding-top: 0;
}
.button-base {
  position: absolute;
  z-index: -2;
  top: 4px;
  left: 0;
  border-radius: 12px;
  width: 100%;
  height: calc(100% - 4px);
  background-color: rgba(0, 0, 0, .15);
  box-shadow: 0 1px 1px 0 rgba(255, 255, 255, .75), inset 0 2px 2px rgba(0, 0, 0, .25);
}
/* 选中态：加深灰以示区别 */
.button.active .button-top::after {
  background-image: radial-gradient(#6b7280, #4b5563);
}
.panel-pagination {
  display: flex;
  justify-content: flex-end;
  padding: 10px 0 0;
}
.chart-svg {
  width: 100%;
  height: 100%;
  display: block;
}
.chart-svg .hover-hit { cursor: pointer; }

.chart-tooltip {
  position: absolute;
  background: #18181b;
  color: #fff;
  font-size: 12px;
  padding: 6px 10px;
  border-radius: 6px;
  pointer-events: none;
  box-shadow: 0 4px 12px rgba(24, 24, 27, 0.2);
}
.tt-date { color: #a1a1aa; font-size: 11px; }
.tt-row { display: flex; align-items: center; gap: 6px; font-weight: 600; margin-top: 2px; }
.tt-dot { width: 8px; height: 8px; border-radius: 2px; display: inline-block; flex-shrink: 0; }

/* ========== 分类列表 ========== */
.cat-list { display: flex; flex-direction: column; gap: 12px; }
.cat-row {
  display: grid;
  grid-template-columns: 70px 1fr 32px;
  gap: 10px;
  align-items: center;
}
/* 全宽版分类列表：一行多列 + 百分比显示 */
.cat-list-wide .cat-row {
  grid-template-columns: 100px 1fr 40px 44px;
}
.cat-left { display: flex; align-items: center; gap: 8px; }
.cat-dot { width: 8px; height: 8px; border-radius: 50%; flex-shrink: 0; }
.cat-name { font-size: 13px; color: #52525b; }
.cat-pct { font-size: 12px; color: #a1a1aa; text-align: right; }
.cat-track {
  height: 6px;
  background: #f1f1ef;
  border-radius: 3px;
  overflow: hidden;
}
.cat-fill {
  height: 100%;
  border-radius: 3px;
  transition: width 0.4s ease;
}
.cat-cnt { font-size: 13px; font-weight: 600; color: #18181b; text-align: right; font-variant-numeric: tabular-nums; }

/* ========== AI 概要 ========== */
.summary-text {
  font-size: 13px;
  color: #52525b;
  line-height: 1.6;
  margin-top: 6px;
}

.empty { font-size: 12px; color: #a1a1aa; padding: 6px 0; }

/* ========== 近 7 天诉求/工单列表 ========== */
.list-panel { padding-bottom: 10px; }
.list-body { display: flex; flex-direction: column; gap: 0; }
.list-empty { font-size: 13px; color: #a1a1aa; padding: 24px 0; text-align: center; }
.list-row {
  padding: 10px 0;
  border-bottom: 1px solid #f4f4f2;
}
.list-row:last-child { border-bottom: none; }
.list-row-head {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 4px;
}
.list-no {
  font-size: 12px;
  font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
  color: #71717a;
  font-weight: 500;
}
.level-dot {
  width: 7px; height: 7px; border-radius: 50%; flex-shrink: 0;
}
.level-lbl { font-size: 12px; color: #52525b; }
.list-title {
  font-size: 13px;
  color: #18181b;
  font-weight: 500;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  margin-bottom: 3px;
}
.list-meta {
  font-size: 12px;
  color: #a1a1aa;
  display: flex;
  gap: 6px;
  align-items: center;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.list-meta .dot { color: #d4d4d8; }

/* ========== 响应式（仅 768px 以下才单列） ========== */
@media (max-width: 768px) {
  .metric-row { grid-template-columns: repeat(2, 1fr); }
  .grid-2 { grid-template-columns: 1fr; }
}
@media (max-width: 560px) {
  .metric-row { grid-template-columns: 1fr; }
}
</style>
