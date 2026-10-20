<!--
  市民端共享布局：与管理端同款 Cursor 极简白底 + 左侧分组导航（slot 模式）
  - 风格：黑底品牌 Logo (#18181b) + 白底侧边栏 + 黑底激活态
  - 菜单项按市民端业务分组（常用功能 / 个人服务）
-->
<template>
  <div class="citizen-shell">
    <!-- ========== 左侧导航 ========== -->
    <aside class="aside" :class="{ collapsed }">
      <!-- 折叠/展开按钮（吸附侧边栏右缘，面板式图标：左半实心=侧边栏展开） -->
      <button class="collapse-btn" :title="collapsed ? '展开侧边栏' : '收起侧边栏（保留图标）'" @click="toggleCollapse">
        <svg class="panel-icon" :class="{ mirrored: collapsed }" viewBox="0 0 24 24" width="16" height="16" aria-hidden="true">
          <!-- 圆角矩形外框 -->
          <rect x="3.2" y="4.2" width="17.6" height="15.6" rx="3.2" fill="none" stroke="currentColor" stroke-width="1.8" />
          <!-- 中线 -->
          <path d="M10 4.2v15.6" stroke="currentColor" stroke-width="1.8" fill="none" />
          <!-- 左半实心填充（折叠时 CSS 镜像到右侧） -->
          <path d="M10 4.2 H6.4 A3.2 3.2 0 0 0 3.2 7.4 V16.6 A3.2 3.2 0 0 0 6.4 19.8 H10 Z" fill="currentColor" stroke="none" />
        </svg>
      </button>

      <!-- 品牌 Logo -->
      <div class="brand">
        <div class="brand-icon">民</div>
        <div class="brand-text">12345 市民端</div>
      </div>

      <!-- 用户卡片 -->
      <div class="user-card">
        <div class="user-avatar">{{ (auth.maskedPhone || '民').slice(-1) }}</div>
        <div class="user-info">
          <div class="user-name">{{ auth.maskedPhone || '市民朋友' }}</div>
          <div class="user-role">
            {{ auth.realnameVerified ? '已实名' : '未实名' }}
          </div>
        </div>
      </div>

      <!-- 分组菜单 -->
      <nav class="nav">
        <!-- ① 常用功能 -->
        <div class="nav-group">
          <div class="nav-group-label">常用功能</div>
          <router-link v-for="item in menuGroups.main" :key="item.index"
            :to="item.index"
            class="nav-item" :data-title="item.label"
            :class="{ active: activeMenu === item.index }">
            <el-icon class="nav-icon"><component :is="item.icon" /></el-icon>
            <span class="nav-label">{{ item.label }}</span>
          </router-link>
        </div>

        <!-- ② 个人服务 -->
        <div class="nav-group">
          <div class="nav-group-label">个人服务</div>
          <router-link v-for="item in menuGroups.me" :key="item.index"
            :to="item.index"
            class="nav-item" :data-title="item.label"
            :class="{ active: activeMenu === item.index }">
            <el-icon class="nav-icon"><component :is="item.icon" /></el-icon>
            <span class="nav-label">{{ item.label }}</span>
          </router-link>
        </div>
      </nav>

      <!-- 底部退出 -->
      <div class="aside-footer">
        <button class="logout-btn" @click="handleLogout">
          <el-icon><SwitchButton /></el-icon>
          <span>退出登录</span>
        </button>
      </div>
    </aside>

    <!-- ========== 右侧主区 ========== -->
    <div class="main-area">
      <!-- 顶部栏（极简面包屑，与管理端一致） -->
      <header class="topbar">
        <div class="breadcrumb">
          <span class="bc-current">{{ title }}</span>
        </div>
        <div class="topbar-right">
          <span class="admin-name">{{ auth.maskedPhone || '市民朋友' }}</span>
        </div>
      </header>

      <!-- 内容区（slot 模式） -->
      <main class="content">
        <slot />
      </main>
    </div>

    <!-- 右下角可拖动智能助手球（市民任意页面可咨询，独立 citizen 知识库） -->
    <ChatFloatBall scope="citizen" />
  </div>
</template>

<script setup>
import { computed, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { useAuthStore } from '../stores/auth'
import { logout } from '../api/auth'
import ChatFloatBall from '../../shared/ChatFloatBall.vue'
import {
  HomeFilled, EditPen, Search, ChatDotRound, User,
  Notification, SwitchButton
} from '@element-plus/icons-vue'

defineProps({
  title: { type: String, default: '市民首页' }
})

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()

const activeMenu = computed(() => route.path)

/** 侧边栏折叠：点击按钮隐藏标签文字，仅保留图标（状态持久化） */
const COLLAPSE_KEY = 'citizen_sidebar_collapsed'
const collapsed = ref(localStorage.getItem(COLLAPSE_KEY) === '1')
const toggleCollapse = () => {
  collapsed.value = !collapsed.value
  localStorage.setItem(COLLAPSE_KEY, collapsed.value ? '1' : '0')
}

/** 分组菜单配置 */
const menuGroups = {
  main: [
    { index: '/home',          label: '市民首页', icon: HomeFilled },
    { index: '/submit-appeal', label: '提交诉求', icon: EditPen },
    { index: '/my-appeals',    label: '办理进度', icon: Search }
  ],
  me: [
    { index: '/chat',    label: '智能助手', icon: ChatDotRound },
    { index: '/notices', label: '通知公告', icon: Notification },
    { index: '/my',      label: '个人中心', icon: User }
  ]
}

const handleLogout = async () => {
  try { await logout() } catch { /* ignore */ }
  auth.logout()
  ElMessage.success('已退出登录')
  router.push('/login')
}
</script>

<style scoped>
/* ========== 全局容器（与管理端完全一致） ========== */
.citizen-shell {
  display: flex;
  height: 100vh;
  width: 100vw;
  background: #f2f2f2;   /* 边缘背景色 */
  font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', 'PingFang SC', 'Microsoft YaHei', sans-serif;
}

/* ========== 左侧导航（灰色边缘调） ========== */
.aside {
  position: relative;
  width: 220px;
  min-width: 220px;
  background: #f2f2f2;
  border-right: 1px solid #e5e5e5;
  display: flex;
  flex-direction: column;
  overflow: visible;
  transition: width 0.25s ease, min-width 0.25s ease;
}

/* 折叠/展开按钮（吸附侧边栏右缘的圆形按钮） */
.collapse-btn {
  position: absolute;
  top: 18px;
  right: -12px;
  width: 24px;
  height: 24px;
  border-radius: 50%;
  background: #ffffff;
  border: 1px solid #e5e5e5;
  box-shadow: 0 1px 4px rgba(0, 0, 0, 0.10);
  display: flex;
  align-items: center;
  justify-content: center;
  cursor: pointer;
  color: #71717a;
  z-index: 30;
  transition: all 0.15s;
}
.collapse-btn:hover {
  color: #18181b;
  border-color: #d4d4d8;
  transform: scale(1.08);
}
/* 面板式图标：折叠时水平镜像（实心半边翻到右侧，呼应收起状态） */
.panel-icon { display: block; transition: transform 0.25s ease; }
.panel-icon.mirrored { transform: scaleX(-1); }

/* ===== 折叠态：仅做透明度渐隐，元素尺寸/占位完全不变 —— 垂直方向零位移，杜绝跳动 ===== */
.aside.collapsed { width: 64px; min-width: 64px; }
.aside.collapsed .brand { justify-content: center; padding: 18px 8px 14px; }
.aside.collapsed .brand-text,
.aside.collapsed .user-info,
.aside.collapsed .nav-group-label,
.aside.collapsed .nav-label,
.aside.collapsed .logout-btn span { opacity: 0; }
.aside.collapsed .user-card { justify-content: center; padding: 14px 8px; }
.aside.collapsed .nav-item { justify-content: flex-start; padding: 8px 12px; }
/* 折叠态悬浮黑色气泡：显示菜单标题（CSS content:attr 实现，pointer-events 防遮挡点击） */
.aside.collapsed .nav-item { position: relative; }
.aside.collapsed .nav-item:hover::after {
  content: attr(data-title);
  position: absolute;
  left: calc(100% + 12px);
  top: 50%;
  transform: translateY(-50%);
  background: #18181b;
  color: #fff;
  padding: 6px 12px;
  border-radius: 6px;
  font-size: 12px;
  white-space: nowrap;
  box-shadow: 0 4px 12px rgba(0, 0, 0, 0.25);
  z-index: 3000;
  pointer-events: none;
}
.aside.collapsed .nav-item:hover::before {
  content: '';
  position: absolute;
  left: calc(100% + 4px);
  top: 50%;
  transform: translateY(-50%);
  border: 5px solid transparent;
  border-right-color: #18181b;
  z-index: 3000;
  pointer-events: none;
}
/* 折叠态放开菜单容器裁剪：允许黑色气泡溢出侧栏右缘显示（折叠态菜单项少，无需纵向滚动） */
.aside.collapsed .nav { overflow: visible; }
.aside.collapsed .nav-group + .nav-group {
  border-top: 1px solid #ececec;
  margin-top: 10px;
  padding-top: 10px;
}

/* 折叠/展开过渡：文字与标题只渐隐渐现（0.25s 与宽度动画同步），不改尺寸 —— 平滑无跳动 */
.brand-text, .user-info, .nav-group-label, .nav-label, .logout-btn span {
  transition: opacity 0.25s ease;
  white-space: nowrap;
}

/* 品牌 Logo（黑色方块，与管理端一致） */
.brand {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 18px 20px 14px;
  border-bottom: 1px solid #f4f4f2;
}
.brand-icon {
  width: 32px;
  height: 32px;
  border-radius: 8px;
  background: #18181b;
  color: #fff;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 15px;
  font-weight: 700;
  flex-shrink: 0;
}
.brand-text {
  font-size: 15px;
  font-weight: 700;
  color: #18181b;
  letter-spacing: 0.3px;
}

/* 用户卡片（浅灰头像） */
.user-card {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 14px 20px;
  border-bottom: 1px solid #f4f4f2;
}
.user-avatar {
  width: 34px;
  height: 34px;
  border-radius: 10px;
  background: #f4f4f2;
  color: #18181b;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 14px;
  font-weight: 700;
  flex-shrink: 0;
}
.user-info { min-width: 0; }
.user-name {
  font-size: 13px;
  font-weight: 600;
  color: #18181b;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.user-role {
  font-size: 11px;
  color: #8e8e93;
  margin-top: 1px;
}

/* 分组菜单 */
.nav {
  flex: 1;
  overflow-y: auto;
  padding: 10px 10px;
}
.nav::-webkit-scrollbar { width: 4px; }
.nav::-webkit-scrollbar-thumb { background: #e5e5e5; border-radius: 2px; }

.nav-group { margin-bottom: 16px; }
.nav-group-label {
  font-size: 12px;
  font-weight: 600;
  color: #a1a1aa;
  text-transform: uppercase;
  letter-spacing: 0.8px;
  padding: 4px 10px 6px;
}

.nav-item {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 8px 12px;
  margin: 1px 0;
  /* 固定行高：折叠/展开两模式图标同一水平线，动画零垂直位移 */
  height: 38px;
  border-radius: 8px;
  font-size: 14px;
  color: #52525b;
  text-decoration: none;
  transition: background 0.15s ease, color 0.15s ease;
  cursor: pointer;
  position: relative;
}
.nav-item:hover { background: #f4f4f2; color: #18181b; }
.nav-item.active { background: #18181b; color: #fff; font-weight: 500; }
.nav-item.active .nav-icon { color: #fff; }
.nav-icon { font-size: 15px; color: #8e8e93; flex-shrink: 0; }
.nav-label { white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }

/* 底部退出（极简描边按钮） */
.aside-footer {
  padding: 12px 14px;
  border-top: 1px solid #f4f4f2;
}
.logout-btn {
  width: 100%;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 6px;
  padding: 8px;
  background: transparent;
  border: 1px solid #e5e5e5;
  border-radius: 8px;
  font-size: 12.5px;
  color: #71717a;
  cursor: pointer;
  transition: all 0.15s;
}
.logout-btn:hover {
  background: #f4f4f2;
  border-color: #d4d4d8;
  color: #18181b;
}

/* ========== 右侧主区 ========== */
.main-area {
  flex: 1;
  display: flex;
  flex-direction: column;
  overflow: hidden;
}

/* 顶部栏（灰色边缘调） */
.topbar {
  height: 52px;
  background: #f2f2f2;
  border-bottom: 1px solid #e5e5e5;
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 0 28px;
  flex-shrink: 0;
}
.breadcrumb { display: flex; align-items: center; gap: 6px; }
.bc-current { font-size: 14px; font-weight: 600; color: #18181b; }
.topbar-right { display: flex; align-items: center; gap: 12px; }
.admin-name { font-size: 12.5px; color: #8e8e93; }

/* 内容区（主要内容：两色中更白的 #FAFAFA） */
.content {
  flex: 1;
  overflow-y: auto;
  padding: 20px;
  background: #fafafa;
}
</style>
