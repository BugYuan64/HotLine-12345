<template>
  <!-- 拼图滑块验证码弹窗（三端登录共用，与市民端登录交互一致） -->
  <el-dialog :model-value="modelValue" :show-close="true" :close-on-click-modal="false"
    width="360px" :center="true" :modal="true" class="slide-dialog" append-to-body
    @update:model-value="v => emit('update:modelValue', v)" @open="onOpen">
    <template #header>
      <div class="slide-dialog-title">请完成拼图验证</div>
    </template>
    <div class="puzzle-wrap">
      <!-- 背景画布（含缺口） -->
      <div class="puzzle-canvas-box">
        <canvas ref="bgCanvasRef" :width="canvasW" :height="canvasH" class="puzzle-bg"></canvas>
        <!-- 拼图块画布（可拖动） -->
        <canvas ref="pieceCanvasRef" :width="canvasW" :height="canvasH"
          class="puzzle-piece" :style="{ transform: 'translateX(' + pieceX + 'px)' }"></canvas>
        <!-- 刷新按钮 -->
        <button type="button" class="puzzle-refresh" @click="initPuzzle" title="换一张">
          <svg height="16" viewBox="0 0 24 24" width="16" xmlns="http://www.w3.org/2000/svg">
            <path d="M17.65 6.35A7.958 7.958 0 0012 4c-4.42 0-7.99 3.58-7.99 8s3.57 8 7.99 8c3.73 0 6.84-2.55 7.73-6h-2.08A5.99 5.99 0 0112 18c-3.31 0-6-2.69-6-6s2.69-6 6-6c1.66 0 3.14.69 4.22 1.78L13 11h7V4l-2.35 2.35z" fill="currentColor"/>
          </svg>
        </button>
        <!-- 成功蒙层 -->
        <div v-if="slideSuccess" class="puzzle-success-mask">
          <span>✓ 验证通过</span>
        </div>
        <div v-if="slideFail" class="puzzle-fail-mask">
          <span>✗ 验证失败</span>
        </div>
      </div>
      <!-- 滑动轨道 -->
      <div class="puzzle-track" ref="trackRef"
        @mousedown="handleTrackStart" @touchstart.prevent="handleTrackStart">
        <div class="puzzle-track-fill" :style="{ width: thumbFillWidth + 'px' }"></div>
        <div class="puzzle-track-hint" v-if="!slideSuccess && !slideFail">向右拖动滑块完成拼图</div>
        <div class="puzzle-track-hint success" v-else-if="slideSuccess">✓ 验证通过</div>
        <div class="puzzle-track-hint fail" v-else>✗ 验证失败，请重试</div>
        <div class="puzzle-thumb" :class="{ done: slideSuccess, fail: slideFail }"
          :style="{ transform: 'translateX(' + thumbX + 'px)' }"></div>
      </div>
    </div>
  </el-dialog>
</template>

<script setup>
// 滑动拼图验证码：v-model 控制显示，验证通过后 emit('success')，由父组件执行后续动作（登录/发验证码等）
import { ref, computed, nextTick, onBeforeUnmount } from 'vue'

const props = defineProps({
  modelValue: { type: Boolean, default: false }
})
const emit = defineEmits(['update:modelValue', 'success'])

const bgCanvasRef = ref(null)
const pieceCanvasRef = ref(null)
const trackRef = ref(null)

// 画布尺寸
const canvasW = 300
const canvasH = 155
const PIECE_SIZE = 50 // 拼图块边长
const PIECE_RADIUS = 9 // 拼图凸起半径

// 拼图状态
const targetX = ref(0)      // 缺口 X 坐标（正确位置）
const targetY = ref(0)      // 缺口 Y 坐标
const pieceX = ref(0)       // 拼图块当前 X 坐标（跟随滑块）
const trackProgress = ref(0) // 滑块百分比 0-100
const slideSuccess = ref(false)
const slideFail = ref(false)
// 轨道宽 300px、滑块宽 40px，可滑动 260px；像素定位避免 translateX 百分比相对自身宽度导致球条不一致
const THUMB_W = 40
const THUMB_TRAVEL = 300 - THUMB_W
const thumbX = computed(() => (trackProgress.value / 100) * THUMB_TRAVEL)
const thumbFillWidth = computed(() => (trackProgress.value / 100) * THUMB_TRAVEL + THUMB_W)
let trackDragging = false
let trackStartX = 0
let trackStartProgress = 0

/** 弹窗打开时初始化拼图（nextTick 确保 canvas 已挂载） */
const onOpen = () => {
  nextTick(() => { initPuzzle() })
}

/** 生成随机渐变背景图像 */
const generateBgImage = () => {
  const canvas = document.createElement('canvas')
  canvas.width = canvasW
  canvas.height = canvasH
  const ctx = canvas.getContext('2d')
  // 多彩渐变：模拟风景
  const colors = [
    ['#667eea', '#764ba2'], ['#f093fb', '#f5576c'], ['#4facfe', '#00f2fe'],
    ['#43e97b', '#38f9d7'], ['#fa709a', '#fee140'], ['#a8edea', '#fed6e3'],
    ['#ff9a9e', '#fecfef'], ['#a1c4fd', '#c2e9fb'], ['#fbc2eb', '#a6c1ee'],
    ['#84fab0', '#8fd3f4']
  ]
  const [c1, c2] = colors[Math.floor(Math.random() * colors.length)]
  const gradient = ctx.createLinearGradient(0, 0, canvasW, canvasH)
  gradient.addColorStop(0, c1)
  gradient.addColorStop(1, c2)
  ctx.fillStyle = gradient
  ctx.fillRect(0, 0, canvasW, canvasH)
  // 添加一些装饰性圆圈
  for (let i = 0; i < 8; i++) {
    ctx.beginPath()
    const x = Math.random() * canvasW
    const y = Math.random() * canvasH
    const r = 10 + Math.random() * 25
    ctx.arc(x, y, r, 0, Math.PI * 2)
    ctx.fillStyle = `rgba(255,255,255,${0.1 + Math.random() * 0.15})`
    ctx.fill()
  }
  // 加一些线条
  ctx.strokeStyle = 'rgba(255,255,255,0.08)'
  ctx.lineWidth = 2
  for (let i = 0; i < 5; i++) {
    ctx.beginPath()
    ctx.moveTo(Math.random() * canvasW, 0)
    ctx.lineTo(Math.random() * canvasW, canvasH)
    ctx.stroke()
  }
  return canvas
}

/** 绘制拼图块路径（带凸起的拼图形状） */
const drawPiecePath = (ctx, x, y) => {
  const s = PIECE_SIZE
  const r = PIECE_RADIUS
  ctx.beginPath()
  ctx.moveTo(x, y)
  // 上边（凸起）
  ctx.lineTo(x + s / 2 - r, y)
  ctx.arc(x + s / 2, y, r, Math.PI, 0, true)
  ctx.lineTo(x + s, y)
  // 右边
  ctx.lineTo(x + s, y + s / 2 - r)
  ctx.arc(x + s, y + s / 2, r, -Math.PI / 2, Math.PI / 2, false)
  ctx.lineTo(x + s, y + s)
  // 下边
  ctx.lineTo(x, y + s)
  // 左边
  ctx.closePath()
}

/** 初始化拼图：随机位置、绘制背景缺口和拼图块 */
const initPuzzle = () => {
  slideSuccess.value = false
  slideFail.value = false
  trackProgress.value = 0
  pieceX.value = 0

  // 随机缺口位置（右半区）
  targetX.value = Math.floor(canvasW * 0.45 + Math.random() * (canvasW * 0.35))
  targetY.value = Math.floor(Math.random() * (canvasH - PIECE_SIZE - 20) + 10)
  // 确保右边有足够空间放拼图
  if (targetX.value + PIECE_SIZE + 10 > canvasW) {
    targetX.value = canvasW - PIECE_SIZE - 15
  }

  const bgCanvas = bgCanvasRef.value
  const pieceCanvas = pieceCanvasRef.value
  if (!bgCanvas || !pieceCanvas) return

  const bgCtx = bgCanvas.getContext('2d')
  const pieceCtx = pieceCanvas.getContext('2d')

  // 生成背景
  const bgSource = generateBgImage()
  bgCtx.clearRect(0, 0, canvasW, canvasH)
  bgCtx.drawImage(bgSource, 0, 0)

  // 在背景上画缺口（深色填充 + 路径裁剪）
  drawPiecePath(bgCtx, targetX.value, targetY.value)
  bgCtx.save()
  bgCtx.fillStyle = 'rgba(0,0,0,0.5)'
  bgCtx.fill()
  // 缺口边缘
  bgCtx.strokeStyle = 'rgba(255,255,255,0.6)'
  bgCtx.lineWidth = 1
  bgCtx.stroke()
  bgCtx.restore()

  // 绘制拼图块：画布等大，拼图块放在左侧(0, targetY)，内容从缺口位置(targetX)采样
  pieceCtx.clearRect(0, 0, canvasW, canvasH)
  pieceCtx.save()
  drawPiecePath(pieceCtx, 0, targetY.value)
  pieceCtx.clip()
  // 偏移 -targetX，使 (0, targetY) 显示原图 (targetX, targetY) 处的内容
  pieceCtx.drawImage(bgSource, -targetX.value, 0)
  pieceCtx.restore()

  // 拼图块描边
  pieceCtx.save()
  drawPiecePath(pieceCtx, 0, targetY.value)
  pieceCtx.strokeStyle = 'rgba(255,255,255,0.8)'
  pieceCtx.lineWidth = 1
  pieceCtx.stroke()
  pieceCtx.restore()
}

/** 滑块拖拽逻辑 */
const handleTrackStart = (e) => {
  if (slideSuccess.value) return
  if (slideFail.value) { initPuzzle(); return }
  trackDragging = true
  const clientX = e.touches ? e.touches[0].clientX : e.clientX
  trackStartX = clientX
  trackStartProgress = trackProgress.value
  document.addEventListener('mousemove', handleTrackMove)
  document.addEventListener('mouseup', handleTrackEnd)
  document.addEventListener('touchmove', handleTrackMove, { passive: false })
  document.addEventListener('touchend', handleTrackEnd)
}

const handleTrackMove = (e) => {
  if (!trackDragging) return
  e.preventDefault?.()
  const clientX = e.touches ? e.touches[0].clientX : e.clientX
  const trackWidth = trackRef.value ? trackRef.value.offsetWidth - 40 : 300
  const deltaX = clientX - trackStartX
  let newProgress = trackStartProgress + (deltaX / trackWidth) * 100
  newProgress = Math.max(0, Math.min(100, newProgress))
  trackProgress.value = newProgress
  // 拼图块跟随移动：映射进度到 canvas 移动距离
  const maxMove = canvasW - PIECE_SIZE - PIECE_RADIUS
  pieceX.value = (newProgress / 100) * maxMove
}

const handleTrackEnd = () => {
  if (!trackDragging) return
  trackDragging = false
  document.removeEventListener('mousemove', handleTrackMove)
  document.removeEventListener('mouseup', handleTrackEnd)
  document.removeEventListener('touchmove', handleTrackMove)
  document.removeEventListener('touchend', handleTrackEnd)

  // 校验：拼图块 X 与缺口 X 是否对齐（容差 5px）
  const diff = Math.abs(pieceX.value - targetX.value)
  if (diff <= 5) {
    // 成功：吸附到正确位置
    pieceX.value = targetX.value
    const maxMove = canvasW - PIECE_SIZE - PIECE_RADIUS
    trackProgress.value = (targetX.value / maxMove) * 100
    slideSuccess.value = true
    setTimeout(() => {
      emit('update:modelValue', false)
      emit('success')
    }, 600)
  } else {
    // 失败：回弹 + 重置
    slideFail.value = true
    setTimeout(() => {
      initPuzzle()
    }, 800)
  }
}

onBeforeUnmount(() => {
  document.removeEventListener('mousemove', handleTrackMove)
  document.removeEventListener('mouseup', handleTrackEnd)
  document.removeEventListener('touchmove', handleTrackMove)
  document.removeEventListener('touchend', handleTrackEnd)
})
</script>

<style scoped>
/* ========== 拼图滑块验证码弹窗 ========== */
.slide-dialog :deep(.el-dialog) {
  border-radius: 12px;
  padding: 16px 20px 14px;
  width: 360px;
}
.slide-dialog :deep(.el-dialog__header) {
  padding: 0 0 10px;
  margin-right: 0;
}
.slide-dialog :deep(.el-dialog__body) {
  padding: 4px 0 2px;
}
.slide-dialog-title {
  font-size: 15px;
  font-weight: 600;
  color: #151717;
  text-align: center;
}
.puzzle-wrap {
  display: flex;
  flex-direction: column;
  gap: 12px;
}
/* 画布区 */
.puzzle-canvas-box {
  position: relative;
  width: 300px;
  height: 155px;
  margin: 0 auto;
  border-radius: 8px;
  overflow: hidden;
  box-shadow: 0 1px 3px rgba(0,0,0,0.08);
  background: #f1f5f9;
}
.puzzle-bg {
  display: block;
  width: 300px;
  height: 155px;
}
.puzzle-piece {
  position: absolute;
  top: 0;
  left: 0;
  width: 300px;
  height: 155px;
  pointer-events: none;
  filter: drop-shadow(2px 0 3px rgba(0,0,0,0.25));
  transition: transform 0.04s linear;
}
.puzzle-refresh {
  position: absolute;
  top: 8px;
  right: 8px;
  width: 28px;
  height: 28px;
  border-radius: 6px;
  background: rgba(255,255,255,0.85);
  border: none;
  cursor: pointer;
  display: flex;
  align-items: center;
  justify-content: center;
  color: #475569;
  transition: all 0.2s;
  backdrop-filter: blur(4px);
}
.puzzle-refresh:hover {
  background: #fff;
  color: #2d79f3;
  transform: rotate(45deg);
}
.puzzle-success-mask,
.puzzle-fail-mask {
  position: absolute;
  top: 0; left: 0; right: 0; bottom: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 18px;
  font-weight: 600;
  color: #fff;
  animation: fadeIn 0.2s ease;
}
.puzzle-success-mask {
  background: rgba(16, 185, 129, 0.5);
}
.puzzle-fail-mask {
  background: rgba(239, 68, 68, 0.5);
}
@keyframes fadeIn {
  from { opacity: 0; }
  to { opacity: 1; }
}
/* 滑块轨道 */
.puzzle-track {
  position: relative;
  width: 300px;
  height: 40px;
  margin: 0 auto;
  background: #f1f5f9;
  border: 1px solid #e2e8f0;
  border-radius: 20px;
  overflow: hidden;
  user-select: none;
}
.puzzle-track-fill {
  position: absolute;
  top: 0; left: 0;
  height: 100%;
  background: linear-gradient(90deg, #93c5fd, #2d79f3);
  border-radius: 20px;
  width: 0;
  transition: width 0.04s linear;
}
.puzzle-track-hint {
  position: absolute;
  top: 50%;
  left: 50%;
  transform: translate(-50%, -50%);
  font-size: 12px;
  color: #94a3b8;
  pointer-events: none;
  white-space: nowrap;
}
.puzzle-track-hint.success {
  color: #059669;
  font-weight: 600;
}
.puzzle-track-hint.fail {
  color: #dc2626;
  font-weight: 600;
}
.puzzle-thumb {
  position: absolute;
  top: 0;
  left: 0;
  width: 40px;
  height: 38px;
  margin: 1px;
  background: #fff;
  border: 1px solid #e2e8f0;
  border-radius: 19px;
  cursor: grab;
  box-shadow: 0 2px 6px rgba(0,0,0,0.1);
  transition: transform 0.04s linear, background 0.2s, border-color 0.2s;
  display: flex;
  align-items: center;
  justify-content: center;
}
.puzzle-thumb::after {
  content: '';
  width: 0;
  height: 0;
  border-left: 5px solid #94a3b8;
  border-top: 4px solid transparent;
  border-bottom: 4px solid transparent;
  margin-left: 3px;
}
.puzzle-thumb:active {
  cursor: grabbing;
}
.puzzle-thumb.done {
  background: #10b981;
  border-color: #10b981;
}
.puzzle-thumb.done::after {
  border-left-color: #fff;
}
.puzzle-thumb.fail {
  background: #ef4444;
  border-color: #ef4444;
}
.puzzle-thumb.fail::after {
  border-left-color: #fff;
}
</style>
