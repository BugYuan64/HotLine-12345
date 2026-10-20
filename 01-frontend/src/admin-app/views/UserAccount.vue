<template>
  <!-- 管理端用户账号管理（需求 4 / API 5.2/5.6/5.8/5.9/5.10）：账号增删改查、启用/停用、角色与权限分配 -->
  <AdminLayout title="用户账号管理">
    <el-card shadow="never">
      <el-form inline class="filter-form">
        <el-form-item label="姓名">
          <el-input v-model="filters.name" clearable placeholder="姓名" style="width: 120px" @keyup.enter="load(1)" @clear="load(1)" />
        </el-form-item>
        <el-form-item label="工号">
          <el-input v-model="filters.jobNo" clearable placeholder="工号" style="width: 120px" @keyup.enter="load(1)" @clear="load(1)" />
        </el-form-item>
        <el-form-item label="用户类型">
          <el-select v-model="filters.userType" clearable placeholder="全部类型" style="width: 140px" @change="load(1)">
            <el-option v-for="o in userTypeOptions" :key="o.code" :label="o.name" :value="o.code" />
          </el-select>
        </el-form-item>
        <el-form-item label="状态">
          <el-select v-model="filters.status" clearable placeholder="全部状态" style="width: 120px" @change="load(1)">
            <el-option label="启用" value="ENABLED" />
            <el-option label="未启用" value="DISABLED" />
          </el-select>
        </el-form-item>
        <el-form-item>
          <el-button type="primary" @click="load(1)">查询</el-button>
          <el-button @click="resetFilters">重置</el-button>
        </el-form-item>
      </el-form>

      <div class="toolbar">
        <el-button type="primary" @click="openCreate">新增账号</el-button>
      </div>

      <el-table :data="rows" v-loading="loading">
        <el-table-column prop="id" label="ID" width="70" />
        <el-table-column prop="name" label="姓名" width="100" />
        <el-table-column prop="job_no" label="工号" width="120" />
        <el-table-column prop="company" label="所属单位" min-width="160" show-overflow-tooltip />
        <el-table-column label="用户类型" width="120">
          <template #default="{ row }">{{ userTypeLabel(row.user_type) }}</template>
        </el-table-column>
        <el-table-column prop="region" label="辖区/部门" width="140" show-overflow-tooltip />
        <el-table-column label="状态" width="90">
          <template #default="{ row }">
            <el-tag :type="row.status === 'ENABLED' ? 'success' : 'danger'" size="small">
              {{ row.status === 'ENABLED' ? '启用' : '未启用' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="280" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" size="small" @click="openEdit(row)">编辑</el-button>
            <el-button link type="primary" size="small" @click="openRole(row)">角色分配</el-button>
            <el-button link :type="row.status === 'ENABLED' ? 'warning' : 'success'" size="small" @click="toggleStatus(row)">
              {{ row.status === 'ENABLED' ? '停用' : '启用' }}
            </el-button>
            <el-button link type="danger" size="small" @click="handleDelete(row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
      <el-pagination class="pagination" layout="total, prev, pager, next" :total="total"
        :page-size="pageSize" :current-page="page" @current-change="load" />
    </el-card>

    <!-- 新增/编辑账号 -->
    <el-dialog v-model="formVisible" :title="form.id ? '编辑账号' : '新增账号'" width="520px">
      <el-form ref="formRef" :model="form" :rules="rules" label-width="100px">
        <el-form-item label="姓名" prop="name">
          <el-input v-model="form.name" maxlength="50" placeholder="请输入姓名" />
        </el-form-item>
        <el-form-item label="工号" prop="jobNo">
          <el-input v-model="form.jobNo" :disabled="!!form.id" maxlength="20" placeholder="6-20位字母与数字组合" />
        </el-form-item>
        <el-form-item v-if="!form.id" label="密码" prop="password">
          <el-input v-model="form.password" type="password" show-password placeholder="8-20位密码" />
        </el-form-item>
        <el-form-item label="所属单位" prop="company">
          <el-input v-model="form.company" maxlength="100" placeholder="请输入所属单位" />
        </el-form-item>
        <el-form-item label="用户类型" prop="userType">
          <el-select v-model="form.userType" style="width: 100%">
            <el-option v-for="o in userTypeOptions" :key="o.code" :label="o.name" :value="o.code" />
          </el-select>
        </el-form-item>
        <el-form-item v-if="isDeptType" label="所属区县" prop="regionCode">
          <el-select v-model="form.regionCode" style="width: 100%" placeholder="请选择所属区县（56部门口径）">
            <el-option v-for="c in counties" :key="c.region_code" :label="c.region_name" :value="c.region_code" />
          </el-select>
        </el-form-item>
        <el-form-item v-else label="辖区/部门" prop="region">
          <el-input v-model="form.region" maxlength="100" placeholder="请输入辖区或部门" />
        </el-form-item>
        <el-form-item label="邮箱">
          <el-input v-model="form.email" placeholder="选填" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="formVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="handleSave">保存</el-button>
      </template>
    </el-dialog>

    <!-- 角色分配 -->
    <el-dialog v-model="roleVisible" title="角色分配" width="480px">
      <el-form label-width="100px">
        <el-form-item label="分配角色">
          <el-checkbox-group v-model="roleForm.roleIds" class="menu-checkbox-group">
            <el-checkbox v-for="r in roleList" :key="r.id" :label="r.id">{{ r.name }}</el-checkbox>
          </el-checkbox-group>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="roleVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="handleSaveRole">保存</el-button>
      </template>
    </el-dialog>
  </AdminLayout>
</template>

<script setup>
import { ref, reactive, computed, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import AdminLayout from '../components/AdminLayout.vue'
import { getUsers, createUser, updateUser, deleteUser, changeUserStatus, getUserRoles, assignUserRoles, getRoles } from '../api/user'
import { getPrimaries } from '../api/classify'
import { getCounties } from '../api/order'
import { captureContentScroll, restoreContentScroll } from '../../shared/contentScroll'

// 用户类型 = ADMIN(管理员) + STREET(街道办) + 8 大一级分类（动态来自 classify_primary，不再硬编码）
const primaries = ref([])
const counties = ref([])

/** 当前选择是否为 8 大分派部门（56部门口径：需要选择所属区县，区县+部门拼接名由后端自动生成） */
const isDeptType = computed(() => primaries.value.some((p) => p.code === form.userType))

const loadPrimaries = async () => {
  try { primaries.value = (await getPrimaries() || []).filter((p) => p.code) }
  catch { primaries.value = [] }
}

const loadCounties = async () => {
  try { counties.value = (await getCounties() || []).map((c) => ({ region_code: c.region_code, region_name: c.region_name })) }
  catch { counties.value = [] }
}

const userTypeMap = computed(() => {
  const m = { STREET: '街道办', ADMIN: '管理员' }
  primaries.value.forEach((p) => { m[p.code] = p.name })
  return m
})

const userTypeOptions = computed(() => [
  { code: 'STREET', name: '街道办' },
  { code: 'ADMIN', name: '管理员' },
  ...primaries.value.map((p) => ({ code: p.code, name: p.name }))
])

const userTypeLabel = (t) => userTypeMap.value[t] || t

const loading = ref(false)
const rows = ref([])
const total = ref(0)
const page = ref(1)
const pageSize = 20
const filters = reactive({ name: '', jobNo: '', userType: '', status: '' })

const load = async (p = page.value) => {
  page.value = p
  loading.value = true
  const y = captureContentScroll()
  try {
    const data = await getUsers({
      name: filters.name || undefined,
      job_no: filters.jobNo || undefined,
      user_type: filters.userType || undefined,
      status: filters.status || undefined,
      page: p,
      page_size: pageSize
    })
    rows.value = data.list || []
    total.value = data.total || 0
  } finally {
    loading.value = false
    await restoreContentScroll(y)
  }
}

const resetFilters = () => {
  filters.name = ''
  filters.jobNo = ''
  filters.userType = ''
  filters.status = ''
  load(1)
}

const saving = ref(false)
const formVisible = ref(false)
const formRef = ref()
const form = reactive({ id: null, name: '', jobNo: '', password: '', company: '', userType: '', region: '', regionCode: '', email: '' })
const rules = {
  name: [{ required: true, message: '姓名不能为空', trigger: 'blur' }],
  jobNo: [{ required: true, message: '工号不能为空', trigger: 'blur' }],
  password: [{ required: true, message: '密码不能为空', trigger: 'blur' }],
  company: [{ required: true, message: '所属单位不能为空', trigger: 'blur' }],
  userType: [{ required: true, message: '请选择用户类型', trigger: 'change' }],
  region: [{ required: true, message: '辖区/部门不能为空', trigger: 'blur' }],
  // 区县仅"新建部门账号"必填：编辑旧部门账号（原 region_code 为空）时不强制，保留原值
  regionCode: [{
    validator: (rule, value, callback) => {
      if (isDeptType.value && !form.id && !value) return callback(new Error('请选择所属区县'))
      callback()
    },
    trigger: 'change'
  }]
}

const openCreate = () => {
  Object.assign(form, { id: null, name: '', jobNo: '', password: '', company: '', userType: '', region: '', regionCode: '', email: '' })
  formVisible.value = true
}

const openEdit = (row) => {
  Object.assign(form, { id: row.id, name: row.name || '', jobNo: row.job_no || '', password: '', company: row.company || '', userType: row.user_type || '', region: row.region || '', regionCode: row.region_code || '', email: row.email || '' })
  formVisible.value = true
}

const handleSave = async () => {
  await formRef.value.validate()
  saving.value = true
  try {
    const body = {
      name: form.name, job_no: form.jobNo, company: form.company,
      user_type: form.userType, region: form.region, email: form.email || undefined
    }
    // 8大部门账号（56口径）：提交所属区县，后端自动生成「区县+部门」拼接名
    if (isDeptType.value) {
      body.region_code = form.regionCode
      body.region = undefined
    }
    if (form.id) {
      await updateUser(form.id, body)
    } else {
      body.password = form.password
      await createUser(body)
    }
    ElMessage.success('保存成功')
    formVisible.value = false
    load()
  } finally {
    saving.value = false
  }
}

const toggleStatus = async (row) => {
  const target = row.status === 'ENABLED' ? 'DISABLED' : 'ENABLED'
  await changeUserStatus(row.id, target)
  ElMessage.success(target === 'ENABLED' ? '已启用' : '已停用')
  load()
}

const handleDelete = async (row) => {
  try {
    await ElMessageBox.confirm(`确认删除账号 ${row.name}？`, '删除确认', { type: 'warning' })
  } catch { return }
  await deleteUser(row.id)
  ElMessage.success('已删除')
  load()
}

const roleVisible = ref(false)
const roleList = ref([])
const roleForm = reactive({ userId: null, roleIds: [] })

const openRole = async (row) => {
  roleForm.userId = row.id
  try {
    const data = await getUserRoles(row.id)
    roleForm.roleIds = (data.roles || []).map(r => r.id)
  } catch { roleForm.roleIds = [] }
  try { roleList.value = await getRoles() } catch { roleList.value = [] }
  roleVisible.value = true
}

const handleSaveRole = async () => {
  saving.value = true
  try {
    await assignUserRoles(roleForm.userId, roleForm.roleIds)
    ElMessage.success('角色分配成功')
    roleVisible.value = false
  } finally {
    saving.value = false
  }
}

onMounted(() => { load(); loadPrimaries() })
</script>

<style scoped>
.filter-form { margin-bottom: 4px; }
.toolbar { margin-bottom: 12px; display: flex; align-items: center; gap: 12px; }
.pagination { margin-top: 16px; justify-content: flex-end; }
.menu-checkbox-group { display: flex; flex-wrap: wrap; gap: 8px 16px; }
</style>