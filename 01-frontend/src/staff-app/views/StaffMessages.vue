<template>
  <!-- 部门端消息中心：复用共享布局 + 角色中立的消息内容组件 -->
  <StaffLayout title="消息中心">
    <MessageCenter :api="api" />
  </StaffLayout>
</template>

<script setup>
import StaffLayout from '../components/StaffLayout.vue'
import MessageCenter from '../components/MessageCenter.vue'
import * as api from '../api/staff-message'
</script>