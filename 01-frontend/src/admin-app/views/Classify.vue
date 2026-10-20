<template>
  <!-- 分类规则维护（需求 2.3 分类规则维护页 / API 7.1~7.7）：关键词/规则/样本 CRUD + 在线测试 + 导入导出 + 准确率 -->
  <AdminLayout title="分类规则维护">
    <el-tabs v-model="activeTab">
      <!-- ==================== 一级分类管理（需求 10.3(1)） ==================== -->
      <el-tab-pane label="一级分类" name="primary">
        <el-card shadow="never">
          <div class="toolbar">
            <el-button type="primary" @click="openPrimaryDialog()">新增一级分类</el-button>
            <el-button :loading="bridgeRebuilding" @click="handleRebuildBridge">重建知识桥</el-button>
          </div>
          <el-table :data="primaries" v-loading="primaryLoading">
            <el-table-column prop="code" label="分类代码" width="140" />
            <el-table-column prop="name" label="分类名称" min-width="120" />
            <el-table-column prop="department" label="分派部门" min-width="140" />
            <el-table-column prop="sort_order" label="排序" width="80" />
            <el-table-column prop="status" label="状态" width="90">
              <template #default="{ row }">
                <el-tag v-if="row.status === 'ENABLED'" type="success" size="small">启用</el-tag>
                <el-tag v-else type="info" size="small">停用</el-tag>
              </template>
            </el-table-column>
            <el-table-column label="操作" width="280" fixed="right">
              <template #default="{ row }">
                <el-button link type="success" size="small" @click="viewSubLabels(row)">查看二级细分</el-button>
                <el-button link type="primary" size="small" @click="openPrimaryDialog(row)">编辑</el-button>
                <el-button link type="danger" size="small" @click="removePrimary(row)">删除</el-button>
              </template>
            </el-table-column>
          </el-table>
        </el-card>

        <!-- 二级细分查看弹窗 -->
        <el-dialog v-model="subLabelsVisible" :title="subLabelsPrimary.name + ' - 二级细分'" width="460px">
          <div v-if="currentSubLabels.length">
            <el-tag v-for="s in currentSubLabels" :key="s.sub_label" class="kw-tag" type="success" size="large">{{ s.sub_label }}</el-tag>
          </div>
          <div v-else class="muted">该一级分类下暂无二级细分标签</div>
          <template #footer>
            <el-button @click="subLabelsVisible = false">关闭</el-button>
            <el-button type="primary" @click="addSubLabelForPrimary">新增二级标签</el-button>
          </template>
        </el-dialog>
      </el-tab-pane>

      <!-- ==================== 关键词管理（API 7.1） ==================== -->
      <el-tab-pane label="关键词管理" name="keyword">
        <el-card shadow="never">
          <el-form inline class="filter-form">
            <el-form-item label="分类">
              <el-select v-model="kwFilters.categoryId" clearable placeholder="全部分类" style="width: 190px" @change="loadKeywords(1)">
                <el-option v-for="c in categories" :key="c.id" :label="categoryOptionLabel(c)" :value="c.id" />
              </el-select>
            </el-form-item>
            <el-form-item label="关键词">
              <el-input v-model="kwFilters.keyword" clearable placeholder="关键词" style="width: 150px" @keyup.enter="loadKeywords(1)" @clear="loadKeywords(1)" />
            </el-form-item>
            <el-form-item label="状态">
              <el-select v-model="kwFilters.status" clearable placeholder="全部状态" style="width: 120px" @change="loadKeywords(1)">
                <el-option label="启用" value="ENABLED" />
                <el-option label="停用" value="DISABLED" />
              </el-select>
            </el-form-item>
            <el-form-item>
              <el-button @click="loadKeywords(1)">查询</el-button>
              <el-button type="primary" @click="openKeywordDialog()">新增关键词</el-button>
              <el-button @click="openCategoryDialog">新增二级标签</el-button>
              <el-upload :show-file-list="false" accept=".xlsx,.xls" :http-request="(opt) => handleImport(opt, 'KEYWORD')" class="inline-upload">
                <el-button>导入Excel</el-button>
              </el-upload>
              <el-button @click="handleExport('KEYWORD')">导出Excel</el-button>
            </el-form-item>
          </el-form>

          <el-table :data="kwRows" v-loading="kwLoading">
            <el-table-column prop="id" label="ID" width="70" />
            <el-table-column prop="keyword" label="关键词" min-width="120" />
            <el-table-column label="所属分类" min-width="170">
              <template #default="{ row }">{{ categoryName(row.primary_category, row.sub_label) }}</template>
            </el-table-column>
            <el-table-column prop="weight" label="权重" width="90" />
            <el-table-column prop="status" label="状态" width="90">
              <template #default="{ row }">
                <el-tag v-if="row.status === 'ENABLED'" type="success" size="small">启用</el-tag>
                <el-tag v-else type="info" size="small">停用</el-tag>
              </template>
            </el-table-column>
            <el-table-column label="操作" width="200" fixed="right">
              <template #default="{ row }">
                <el-button link type="primary" size="small" @click="openKeywordDialog(row)">编辑</el-button>
                <el-button link :type="row.status === 'ENABLED' ? 'warning' : 'success'" size="small" @click="toggleKeyword(row)">
                  {{ row.status === 'ENABLED' ? '停用' : '启用' }}
                </el-button>
                <el-button link type="danger" size="small" @click="removeKeyword(row)">删除</el-button>
              </template>
            </el-table-column>
          </el-table>
          <el-pagination class="pagination" layout="total, sizes, prev, pager, next" :total="kwTotal"
            :page-size="kwPageSize" :page-sizes="[10, 20, 50, 100]" :current-page="kwPage" @current-change="loadKeywords" @size-change="handleKwSizeChange" />
        </el-card>
      </el-tab-pane>

      <!-- ==================== 规则配置（API 7.2） ==================== -->
      <el-tab-pane label="规则配置" name="rule">
        <el-card shadow="never">
          <div class="toolbar">
            <el-upload :show-file-list="false" accept=".xlsx,.xls" :http-request="(opt) => handleImport(opt, 'RULE')" class="inline-upload">
              <el-button size="small">导入Excel</el-button>
            </el-upload>
            <el-button size="small" @click="handleExport('RULE')">导出Excel</el-button>
          </div>
          <el-table :data="ruleRows" v-loading="ruleLoading">
            <el-table-column label="一级分类" min-width="120">
              <template #default="{ row }">{{ categoryLabel(row.primary_category) }}</template>
            </el-table-column>
            <el-table-column prop="hit_count_threshold" label="关键词命中数量阈值" min-width="160" />
            <el-table-column prop="weight_sum_threshold" label="权重求和阈值" min-width="140" />
            <el-table-column label="操作" width="120" fixed="right">
              <template #default="{ row }">
                <el-button link type="primary" size="small" @click="openRuleDialog(row)">配置</el-button>
              </template>
            </el-table-column>
          </el-table>
          <div class="tip">阈值判定规则：命中数量或权重和达到阈值即判定为对应分类，保存后经 Redis 知识桥同步至 AI 分类引擎即时生效</div>
        </el-card>
      </el-tab-pane>

      <!-- ==================== 知识库样本（API 7.3） ==================== -->
      <el-tab-pane label="知识库样本" name="sample">
        <el-card shadow="never">
          <el-form inline class="filter-form">
            <el-form-item label="分类">
              <el-select v-model="sFilters.categoryId" clearable placeholder="全部分类" style="width: 190px" @change="loadSamples(1)">
                <el-option v-for="c in categories" :key="c.id" :label="categoryOptionLabel(c)" :value="c.id" />
              </el-select>
            </el-form-item>
            <el-form-item label="来源">
              <el-select v-model="sFilters.source" clearable placeholder="全部来源" style="width: 150px" @change="loadSamples(1)">
                <el-option label="人工复核确认" value="MANUAL_REVIEW" />
                <el-option label="专家标注" value="EXPERT" />
                <el-option label="市民满意无退回" value="SATISFIED" />
                <el-option label="人工修正" value="CORRECTED" />
              </el-select>
            </el-form-item>
            <el-form-item label="质量等级">
              <el-select v-model="sFilters.qualityLevel" clearable placeholder="全部等级" style="width: 120px" @change="loadSamples(1)">
                <el-option v-for="q in ['P0', 'P1', 'P2', 'P3']" :key="q" :label="q" :value="q" />
              </el-select>
            </el-form-item>
            <el-form-item label="状态">
              <el-select v-model="sFilters.status" clearable placeholder="全部状态" style="width: 120px" @change="loadSamples(1)">
                <el-option label="启用" value="ENABLED" />
                <el-option label="停用" value="DISABLED" />
              </el-select>
            </el-form-item>
            <el-form-item>
              <el-button @click="loadSamples(1)">查询</el-button>
              <el-button type="primary" @click="openSampleDialog()">新增样本</el-button>
              <el-upload :show-file-list="false" accept=".xlsx,.xls" :http-request="(opt) => handleImport(opt, 'SAMPLE')" class="inline-upload">
                <el-button>导入Excel</el-button>
              </el-upload>
              <el-button @click="handleExport('SAMPLE')">导出Excel</el-button>
            </el-form-item>
          </el-form>

          <el-table :data="sRows" v-loading="sLoading">
            <el-table-column prop="id" label="ID" width="70" />
            <el-table-column label="所属分类" min-width="170">
              <template #default="{ row }">{{ categoryName(row.primary_category, row.sub_label) }}</template>
            </el-table-column>
            <el-table-column prop="content" label="样本内容" min-width="260" show-overflow-tooltip />
            <el-table-column prop="source" label="来源" width="130">
              <template #default="{ row }">{{ sourceLabel(row.source) }}</template>
            </el-table-column>
            <el-table-column prop="quality_level" label="质量等级" width="90" />
            <el-table-column prop="status" label="状态" width="90">
              <template #default="{ row }">
                <el-tag v-if="row.status === 'ENABLED'" type="success" size="small">启用</el-tag>
                <el-tag v-else type="info" size="small">停用</el-tag>
              </template>
            </el-table-column>
            <el-table-column label="操作" width="200" fixed="right">
              <template #default="{ row }">
                <el-button link type="primary" size="small" @click="openSampleDialog(row)">编辑</el-button>
                <el-button link :type="row.status === 'ENABLED' ? 'warning' : 'success'" size="small" @click="toggleSample(row)">
                  {{ row.status === 'ENABLED' ? '停用' : '启用' }}
                </el-button>
                <el-button link type="danger" size="small" @click="removeSample(row)">删除</el-button>
              </template>
            </el-table-column>
          </el-table>
          <el-pagination class="pagination" layout="total, sizes, prev, pager, next" :total="sTotal"
            :page-size="sPageSize" :page-sizes="[10, 20, 50, 100]" :current-page="sPage" @current-change="loadSamples" @size-change="handleSSizeChange" />
        </el-card>
      </el-tab-pane>

      <!-- ==================== 在线测试（API 7.4） ==================== -->
      <el-tab-pane label="在线测试" name="test">
        <el-card shadow="never">
          <div class="test-area">
            <el-input v-model="testText" type="textarea" :rows="5" maxlength="1000" show-word-limit
              placeholder="输入一段诉求文本，例如：小区门口乱搭乱建，存在安全隐患" />
            <el-button type="primary" :loading="testing" class="test-btn" @click="handleTest">开始测试</el-button>
          </div>

          <el-alert v-if="testResult?.ai_degraded" type="warning" :closable="false" class="test-result-alert"
            :title="testResult.fallback_message || 'AI分类服务暂时不可用'" />

          <template v-if="testResult && !testResult.ai_degraded">
            <el-descriptions :column="2" border class="test-result">
              <el-descriptions-item label="分类结果">
                <el-tag v-if="testResult.category" type="primary">{{ categoryLabel(testResult.category) }}</el-tag>
                <span v-else class="muted">未判定（建议人工复核）</span>
              </el-descriptions-item>
              <el-descriptions-item label="置信度">
                <span v-if="testResult.confidence != null" class="ai-label">AI生成·置信度{{ (testResult.confidence * 100).toFixed(0) }}%</span>
                <span v-else>-</span>
              </el-descriptions-item>
              <el-descriptions-item label="匹配得分">{{ testResult.score ?? '-' }}</el-descriptions-item>
              <el-descriptions-item label="命中关键词">
                <el-tag v-for="k in testResult.hit_keywords || []" :key="k" size="small" class="kw-tag">{{ k }}</el-tag>
                <span v-if="!(testResult.hit_keywords || []).length" class="muted">无</span>
              </el-descriptions-item>
              <el-descriptions-item label="相似样本" :span="2">
                <el-table v-if="(testResult.similar_samples || []).length" :data="testResult.similar_samples" size="small">
                  <el-table-column prop="sample_id" label="样本ID" width="100" />
                  <el-table-column prop="similarity" label="相似度" width="120" />
                </el-table>
                <span v-else class="muted">无</span>
              </el-descriptions-item>
            </el-descriptions>
            <el-alert v-if="testResult.need_review" type="warning" :closable="false" class="test-result-alert"
              title="置信度低于阈值，系统建议人工复核（need_review=true）" />
          </template>
        </el-card>
      </el-tab-pane>

      <!-- ==================== 准确率统计（API 7.7） ==================== -->
      <el-tab-pane label="准确率统计" name="accuracy">
        <el-card shadow="never">
          <el-form inline class="filter-form">
            <el-form-item label="统计周期">
              <el-select v-model="accFilters.period" style="width: 140px" @change="loadAccuracy">
                <el-option label="近7天" value="7" />
                <el-option label="近30天" value="30" />
                <el-option label="近90天" value="90" />
              </el-select>
            </el-form-item>
            <el-form-item label="分类">
              <el-select v-model="accFilters.category" clearable placeholder="全部分类" style="width: 130px" @change="loadAccuracy">
                <el-option v-for="p in primaries" :key="p.code" :label="p.name" :value="p.code" />
              </el-select>
            </el-form-item>
          </el-form>

          <el-row :gutter="16" class="accuracy-row">
            <el-col :span="12">
              <el-card shadow="never">
                <div class="stat-label">整体分类准确率</div>
                <div class="stat-value">{{ (accuracy.overall_accuracy * 100).toFixed(1) }}%</div>
              </el-card>
            </el-col>
            <el-col :span="12">
              <el-card shadow="never">
                <div class="stat-label">人工修正率</div>
                <div class="stat-value">{{ accuracy.manual_correct_rate != null ? (accuracy.manual_correct_rate * 100).toFixed(1) + '%' : '-' }}</div>
              </el-card>
            </el-col>
          </el-row>

          <el-table :data="accuracy.per_category || []" v-loading="accLoading">
            <el-table-column label="分类" min-width="120">
              <template #default="{ row }">{{ categoryLabel(row.category) }}</template>
            </el-table-column>
            <el-table-column label="分类准确率" min-width="120">
              <template #default="{ row }">{{ ((row.accuracy || 0) * 100).toFixed(1) }}%</template>
            </el-table-column>
            <el-table-column prop="cnt" label="已分类工单数" min-width="120" />
          </el-table>
          <div class="tip">准确率基于统计周期内已分类工单的平均 AI 置信度；人工修正率按人工分类修正记录占已分类工单的比例计算，按分类筛选时审计记录无分类字段、修正率不统计（显示-）</div>
        </el-card>
      </el-tab-pane>
    </el-tabs>

    <!-- 关键词编辑弹窗（API 7.1） -->
    <el-dialog v-model="keywordVisible" :title="keywordForm.id ? '编辑关键词' : '新增关键词'" width="440px">
      <el-form ref="keywordFormRef" :model="keywordForm" :rules="keywordRules" label-width="90px">
        <el-form-item label="所属分类" prop="category_id">
          <el-select v-model="keywordForm.category_id" placeholder="选择分类" style="width: 100%">
            <el-option v-for="c in categories" :key="c.id" :label="categoryOptionLabel(c)" :value="c.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="关键词" prop="keyword">
          <el-input v-model="keywordForm.keyword" placeholder="如：占道经营" maxlength="100" />
        </el-form-item>
        <el-form-item label="权重" prop="weight">
          <el-input-number v-model="keywordForm.weight" :min="0" :max="10" :step="0.1" :precision="2" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="keywordVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="handleSaveKeyword">保存</el-button>
      </template>
    </el-dialog>

    <!-- 一级分类编辑弹窗（需求 10.3(1)） -->
    <el-dialog v-model="primaryVisible" :title="primaryForm.id ? '编辑一级分类' : '新增一级分类'" width="440px">
      <el-form ref="primaryFormRef" :model="primaryForm" :rules="primaryRules" label-width="90px">
        <el-form-item label="分类代码" prop="code">
          <el-input v-model="primaryForm.code" placeholder="如：URBAN" maxlength="20" :disabled="!!primaryForm.id" />
        </el-form-item>
        <el-form-item label="分类名称" prop="name">
          <el-input v-model="primaryForm.name" placeholder="如：城市管理" maxlength="50" />
        </el-form-item>
        <el-form-item label="分派部门" prop="department">
          <el-input v-model="primaryForm.department" placeholder="如：城管部门" maxlength="50" />
        </el-form-item>
        <el-form-item label="排序">
          <el-input-number v-model="primaryForm.sort_order" :min="0" :max="100" />
        </el-form-item>
        <el-form-item label="状态">
          <el-switch v-model="primaryForm.status" active-value="ENABLED" inactive-value="DISABLED" active-text="启用" inactive-text="停用" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="primaryVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="handleSavePrimary">保存</el-button>
      </template>
    </el-dialog>

    <!-- 新增二级标签弹窗（需求 10.3(1)） -->
    <el-dialog v-model="categoryVisible" title="新增二级分类标签" width="440px">
      <el-form ref="categoryFormRef" :model="categoryForm" :rules="categoryRules" label-width="90px">
        <el-form-item label="一级分类" prop="primary_category">
          <el-select v-model="categoryForm.primary_category" style="width: 100%">
            <el-option v-for="p in primaries" :key="p.code" :label="p.name" :value="p.code" />
          </el-select>
        </el-form-item>
        <el-form-item label="二级标签" prop="sub_label">
          <el-input v-model="categoryForm.sub_label" placeholder="如：噪音扰民" maxlength="50" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="categoryVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="handleSaveCategory">保存</el-button>
      </template>
    </el-dialog>

    <!-- 规则配置弹窗（API 7.2） -->
    <el-dialog v-model="ruleVisible" title="分类规则配置" width="440px">
      <el-form ref="ruleFormRef" :model="ruleForm" :rules="ruleRules" label-width="160px">
        <el-form-item label="关键词命中数量阈值" prop="hit_count_threshold">
          <el-input-number v-model="ruleForm.hit_count_threshold" :min="1" :max="100" />
        </el-form-item>
        <el-form-item label="权重求和阈值" prop="weight_sum_threshold">
          <el-input-number v-model="ruleForm.weight_sum_threshold" :min="0" :max="100" :step="0.1" :precision="2" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="ruleVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="handleSaveRule">保存（即时生效）</el-button>
      </template>
    </el-dialog>

    <!-- 样本编辑弹窗（API 7.3） -->
    <el-dialog v-model="sampleVisible" :title="sampleForm.id ? '编辑样本' : '新增样本'" width="560px">
      <el-form ref="sampleFormRef" :model="sampleForm" :rules="sampleRules" label-width="110px">
        <el-form-item label="所属分类" prop="category_id">
          <el-select v-model="sampleForm.category_id" placeholder="选择分类" style="width: 100%">
            <el-option v-for="c in categories" :key="c.id" :label="categoryOptionLabel(c)" :value="c.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="样本内容" prop="content">
          <el-input v-model="sampleForm.content" type="textarea" :rows="4" maxlength="2000" show-word-limit
            placeholder="历史工单文本/标准诉求范例" />
        </el-form-item>
        <el-form-item label="样本来源" prop="source">
          <el-select v-model="sampleForm.source" style="width: 100%">
            <el-option label="人工复核确认（P0）" value="MANUAL_REVIEW" />
            <el-option label="专家标注（P1）" value="EXPERT" />
            <el-option label="市民满意无退回（P2）" value="SATISFIED" />
            <el-option label="人工修正（P3）" value="CORRECTED" />
          </el-select>
        </el-form-item>
        <el-form-item label="质量等级" prop="quality_level">
          <el-select v-model="sampleForm.quality_level" style="width: 100%">
            <el-option v-for="q in ['P0', 'P1', 'P2', 'P3']" :key="q" :label="qualityLabel(q)" :value="q" />
          </el-select>
        </el-form-item>
        <el-form-item v-if="sampleForm.quality_level === 'P3'" label="原AI错误类别">
          <el-select v-model="sampleForm.original_error_category" clearable placeholder="原错误分类（纠错样本标记）" style="width: 100%">
            <el-option v-for="p in primaries" :key="p.code" :label="p.name" :value="p.code" />
          </el-select>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="sampleVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="handleSaveSample">保存</el-button>
      </template>
    </el-dialog>
  </AdminLayout>
</template>

<script setup>
import { captureContentScroll, restoreContentScroll } from '../../shared/contentScroll'
// 分类规则维护：变更经后端同步 Redis 分类知识桥（cls:keywords/cls:rules/cls:labels/rag:pending），AI 引擎只读该桥
import { ref, reactive, computed, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import AdminLayout from '../components/AdminLayout.vue'
import {
  getCategories, addCategory,
  getPrimaries, addPrimary, updatePrimary, deletePrimary,
  getKeywords, addKeyword, updateKeyword, deleteKeyword,
  getRules, saveRule,
  getSamples, addSample, updateSample, deleteSample,
  testClassify, importClassify, exportClassify, getClassifyAccuracy, rebuildBridge
} from '../api/classify'
import { saveBlobResponse } from '../../shared/request.js'

// ---- 公共字典 ----
const activeTab = ref('primary')
const saving = ref(false)
const categories = ref([])
const primaries = ref([])

// 一级分类名称从 classify_primary 表动态获取，不再硬编码
const primaryMap = computed(() => Object.fromEntries(primaries.value.map(p => [p.code, p.name])))
const categoryLabel = (c) => primaryMap.value[c] || c
const categoryName = (primary, sub) => (sub ? categoryLabel(primary) + ' / ' + sub : categoryLabel(primary))
const categoryOptionLabel = (c) => (c.sub_label ? categoryLabel(c.primary_category) + ' / ' + c.sub_label : categoryLabel(c.primary_category))
const sourceLabel = (s) => ({ MANUAL_REVIEW: '人工复核确认', EXPERT: '专家标注', SATISFIED: '市民满意无退回', CORRECTED: '人工修正' }[s] || s)
const qualityLabel = (q) => ({ P0: 'P0 人工复核确认', P1: 'P1 专家标注', P2: 'P2 市民满意无退回', P3: 'P3 人工修正' }[q] || q)

const loadCategories = async () => {
  categories.value = await getCategories()
}

const loadPrimaries = async () => {
  primaries.value = await getPrimaries()
}

// ---- 一级分类管理（需求 10.3(1)） ----
const primaryLoading = ref(false)
const primaryVisible = ref(false)
const primaryFormRef = ref()
const primaryForm = reactive({ id: null, code: '', name: '', department: '', sort_order: 0, status: 'ENABLED' })
const primaryRules = {
  code: [{ required: true, message: '分类代码不能为空', trigger: 'blur' }],
  name: [{ required: true, message: '分类名称不能为空', trigger: 'blur' }],
  department: [{ required: true, message: '分派部门不能为空', trigger: 'blur' }]
}

const bridgeRebuilding = ref(false)
/** 重建分类知识桥：数据库直插/重置后 Redis 桥为空时一键恢复（classify 候选类别缺失的根因修复） */
const handleRebuildBridge = async () => {
  bridgeRebuilding.value = true
  try {
    await rebuildBridge()
    ElMessage.success('分类知识桥重建完成')
  } catch (e) {
    ElMessage.error('重建失败：' + (e.message || '未知错误'))
  } finally {
    bridgeRebuilding.value = false
  }
}

const openPrimaryDialog = (row) => {
  primaryForm.id = row?.id ?? null
  primaryForm.code = row?.code ?? ''
  primaryForm.name = row?.name ?? ''
  primaryForm.department = row?.department ?? ''
  primaryForm.sort_order = Number(row?.sort_order ?? 0)
  primaryForm.status = row?.status ?? 'ENABLED'
  primaryVisible.value = true
}

const handleSavePrimary = async () => {
  await primaryFormRef.value.validate()
  saving.value = true
  try {
    const body = {
      code: primaryForm.code,
      name: primaryForm.name,
      department: primaryForm.department,
      sort_order: primaryForm.sort_order,
      status: primaryForm.status
    }
    if (primaryForm.id) {
      await updatePrimary(primaryForm.id, body)
    } else {
      await addPrimary(body)
    }
    ElMessage.success('已保存，知识桥同步后 AI 引擎即时生效')
    primaryVisible.value = false
    loadPrimaries()
    loadCategories()
  } finally {
    saving.value = false
  }
}

const removePrimary = async (row) => {
  try {
    await ElMessageBox.confirm('确定删除一级分类「' + row.name + '」？删除前需先删除其下二级标签', '删除确认', { type: 'warning' })
  } catch { return }
  await deletePrimary(row.id)
  ElMessage.success('已删除')
  loadPrimaries()
  loadCategories()
}

// ---- 查看二级细分 ----
const subLabelsVisible = ref(false)
const subLabelsPrimary = reactive({ code: '', name: '' })
const currentSubLabels = computed(() => categories.value.filter(c => c.primary_category === subLabelsPrimary.code))

const viewSubLabels = (row) => {
  subLabelsPrimary.code = row.code
  subLabelsPrimary.name = row.name
  subLabelsVisible.value = true
}

const addSubLabelForPrimary = () => {
  const code = subLabelsPrimary.code
  subLabelsVisible.value = false
  openCategoryDialog(code)
}

// ---- 关键词管理（API 7.1） ----
const kwLoading = ref(false)
const kwRows = ref([])
const kwTotal = ref(0)
const kwPage = ref(1)
const kwPageSize = ref(10)
const kwFilters = reactive({ categoryId: '', keyword: '', status: '' })

const loadKeywords = async (p = kwPage.value) => {
  kwPage.value = p
  kwLoading.value = true
  const y = captureContentScroll()
  try {
    const data = await getKeywords({
      category_id: kwFilters.categoryId || undefined,
      keyword: kwFilters.keyword || undefined,
      status: kwFilters.status || undefined,
      page: p,
      page_size: kwPageSize.value
    })
    kwRows.value = data.list || []
    kwTotal.value = data.total || 0
  } finally {
    kwLoading.value = false
    await restoreContentScroll(y)
  }
}

const handleKwSizeChange = (size) => {
  kwPageSize.value = size
  loadKeywords(1)
}

const keywordVisible = ref(false)
const keywordFormRef = ref()
const keywordForm = reactive({ id: null, category_id: null, keyword: '', weight: 1.0 })
const keywordRules = {
  category_id: [{ required: true, message: '请选择分类', trigger: 'change' }],
  keyword: [{ required: true, message: '关键词不能为空', trigger: 'blur' }],
  weight: [{ required: true, message: '权重不能为空', trigger: 'blur' }]
}

const openKeywordDialog = (row) => {
  keywordForm.id = row?.id ?? null
  keywordForm.category_id = row?.category_id ?? null
  keywordForm.keyword = row?.keyword ?? ''
  keywordForm.weight = Number(row?.weight ?? 1.0)
  keywordVisible.value = true
}

const handleSaveKeyword = async () => {
  await keywordFormRef.value.validate()
  saving.value = true
  try {
    const body = { category_id: keywordForm.category_id, keyword: keywordForm.keyword, weight: keywordForm.weight }
    if (keywordForm.id) {
      await updateKeyword(keywordForm.id, body)
    } else {
      await addKeyword(body)
    }
    ElMessage.success('已保存，知识桥同步后 AI 引擎即时生效')
    keywordVisible.value = false
    loadKeywords()
  } finally {
    saving.value = false
  }
}

const toggleKeyword = async (row) => {
  const target = row.status === 'ENABLED' ? 'DISABLED' : 'ENABLED'
  await updateKeyword(row.id, { category_id: row.category_id, keyword: row.keyword, weight: Number(row.weight), status: target })
  ElMessage.success(target === 'ENABLED' ? '已启用' : '已停用')
  loadKeywords()
}

const removeKeyword = async (row) => {
  try {
    await ElMessageBox.confirm(`确定删除关键词「${row.keyword}」？删除后立即从分类规则中移除`, '删除确认', { type: 'warning' })
  } catch { return }
  await deleteKeyword(row.id)
  ElMessage.success('已删除')
  loadKeywords()
}

// ---- 新增二级标签（需求 10.3(1)） ----
const categoryVisible = ref(false)
const categoryFormRef = ref()
const categoryForm = reactive({ primary_category: '', sub_label: '' })
const categoryRules = {
  primary_category: [{ required: true, message: '请选择一级分类', trigger: 'change' }],
  sub_label: [{ required: true, message: '二级标签不能为空', trigger: 'blur' }]
}

const openCategoryDialog = (primary) => {
  categoryForm.primary_category = primary || primaries.value[0]?.code || ''
  categoryForm.sub_label = ''
  categoryVisible.value = true
}

const handleSaveCategory = async () => {
  await categoryFormRef.value.validate()
  saving.value = true
  try {
    await addCategory({ primary_category: categoryForm.primary_category, sub_label: categoryForm.sub_label })
    ElMessage.success('已新增二级标签')
    categoryVisible.value = false
    loadCategories()
  } finally {
    saving.value = false
  }
}

// ---- 规则配置（API 7.2） ----
const ruleLoading = ref(false)
const ruleRows = ref([])

const loadRules = async () => {
  ruleLoading.value = true
  try {
    ruleRows.value = await getRules()
  } finally {
    ruleLoading.value = false
  }
}

const ruleVisible = ref(false)
const ruleFormRef = ref()
const ruleForm = reactive({ category_id: null, hit_count_threshold: 1, weight_sum_threshold: 1.0 })
const ruleRules = {
  hit_count_threshold: [{ required: true, message: '命中数量阈值不能为空', trigger: 'blur' }],
  weight_sum_threshold: [{ required: true, message: '权重求和阈值不能为空', trigger: 'blur' }]
}

const openRuleDialog = (row) => {
  ruleForm.category_id = row.category_id
  ruleForm.hit_count_threshold = Number(row.hit_count_threshold ?? 1)
  ruleForm.weight_sum_threshold = Number(row.weight_sum_threshold ?? 1.0)
  ruleVisible.value = true
}

const handleSaveRule = async () => {
  await ruleFormRef.value.validate()
  saving.value = true
  try {
    await saveRule({
      category_id: ruleForm.category_id,
      hit_count_threshold: ruleForm.hit_count_threshold,
      weight_sum_threshold: ruleForm.weight_sum_threshold
    })
    ElMessage.success('规则已保存，即时生效')
    ruleVisible.value = false
    loadRules()
  } finally {
    saving.value = false
  }
}

// ---- 知识库样本（API 7.3） ----
const sLoading = ref(false)
const sRows = ref([])
const sTotal = ref(0)
const sPage = ref(1)
const sPageSize = ref(10)
const sFilters = reactive({ categoryId: '', source: '', qualityLevel: '', status: '' })

const loadSamples = async (p = sPage.value) => {
  sPage.value = p
  sLoading.value = true
  const y = captureContentScroll()
  try {
    const data = await getSamples({
      category_id: sFilters.categoryId || undefined,
      source: sFilters.source || undefined,
      quality_level: sFilters.qualityLevel || undefined,
      status: sFilters.status || undefined,
      page: p,
      page_size: sPageSize.value
    })
    sRows.value = data.list || []
    sTotal.value = data.total || 0
  } finally {
    sLoading.value = false
    await restoreContentScroll(y)
  }
}

const handleSSizeChange = (size) => {
  sPageSize.value = size
  loadSamples(1)
}

const sampleVisible = ref(false)
const sampleFormRef = ref()
const sampleForm = reactive({ id: null, category_id: null, content: '', source: 'MANUAL_REVIEW', quality_level: 'P0', original_error_category: '' })
const sampleRules = {
  category_id: [{ required: true, message: '请选择分类', trigger: 'change' }],
  content: [{ required: true, message: '样本内容不能为空', trigger: 'blur' }],
  source: [{ required: true, message: '请选择样本来源', trigger: 'change' }],
  quality_level: [{ required: true, message: '请选择质量等级', trigger: 'change' }]
}

const openSampleDialog = (row) => {
  sampleForm.id = row?.id ?? null
  sampleForm.category_id = row?.category_id ?? null
  sampleForm.content = row?.content ?? ''
  sampleForm.source = row?.source ?? 'MANUAL_REVIEW'
  sampleForm.quality_level = row?.quality_level ?? 'P0'
  sampleForm.original_error_category = row?.original_error_category ?? ''
  sampleVisible.value = true
}

const handleSaveSample = async () => {
  await sampleFormRef.value.validate()
  saving.value = true
  try {
    const body = {
      category_id: sampleForm.category_id,
      content: sampleForm.content,
      source: sampleForm.source,
      quality_level: sampleForm.quality_level,
      original_error_category: sampleForm.original_error_category || undefined
    }
    if (sampleForm.id) {
      await updateSample(sampleForm.id, body)
    } else {
      await addSample(body)
    }
    ElMessage.success('已保存，启用样本将自动推送向量化队列')
    sampleVisible.value = false
    loadSamples()
  } finally {
    saving.value = false
  }
}

const toggleSample = async (row) => {
  const target = row.status === 'ENABLED' ? 'DISABLED' : 'ENABLED'
  await updateSample(row.id, {
    category_id: row.category_id, content: row.content, source: row.source,
    quality_level: row.quality_level, original_error_category: row.original_error_category || undefined,
    status: target
  })
  ElMessage.success(target === 'ENABLED' ? '已启用（重新向量化）' : '已停用（移出向量库）')
  loadSamples()
}

const removeSample = async (row) => {
  try {
    await ElMessageBox.confirm(`确定删除样本 #${row.id}？删除后移出向量库且不可恢复`, '删除确认', { type: 'warning' })
  } catch { return }
  await deleteSample(row.id)
  ElMessage.success('已删除')
  loadSamples()
}

// ---- 在线测试（API 7.4） ----
const testing = ref(false)
const testText = ref('')
const testResult = ref(null)

const handleTest = async () => {
  if (!testText.value.trim()) {
    ElMessage.warning('请输入测试诉求文本')
    return
  }
  testing.value = true
  try {
    testResult.value = await testClassify({ text: testText.value.trim() })
  } finally {
    testing.value = false
  }
}

// ---- 准确率统计（API 7.7） ----
const accLoading = ref(false)
const accFilters = reactive({ period: '30', category: '' })
const accuracy = reactive({ overall_accuracy: 0, manual_correct_rate: 0, per_category: [] })

const loadAccuracy = async () => {
  accLoading.value = true
  try {
    const data = await getClassifyAccuracy({ period: accFilters.period, category: accFilters.category || undefined })
    Object.assign(accuracy, data)
  } finally {
    accLoading.value = false
  }
}

// ---- 导入导出（API 7.5/7.6） ----
const handleImport = async (opt, type) => {
  const formData = new FormData()
  formData.append('file', opt.file)
  formData.append('type', type)
  try {
    const data = await importClassify(formData)
    ElMessage.success(`导入完成：成功 ${data.imported} 条，失败 ${data.failed} 条`)
    if (type === 'KEYWORD') loadKeywords()
    if (type === 'RULE') loadRules()
    if (type === 'SAMPLE') loadSamples()
    opt.onSuccess(data)
  } catch (e) {
    opt.onError(e)
  }
}

const handleExport = async (type) => {
  // 流式下载：后端直接回写 Excel 响应流（零落盘）
  const res = await exportClassify(type)
  const typeName = { KEYWORD: '关键词', RULE: '分类规则', SAMPLE: '知识库样本' }[type] || type
  saveBlobResponse(res, `分类${typeName}导出_${new Date().toISOString().slice(0, 10).replace(/-/g, '')}.xlsx`)
}

onMounted(() => {
  loadPrimaries()
  loadCategories()
  loadKeywords()
  loadRules()
  loadSamples()
  loadAccuracy()
})
</script>

<style scoped>
.filter-form { margin-bottom: 4px; }
.pagination { margin-top: 16px; justify-content: flex-end; }
.inline-upload { display: inline-block; margin-right: 12px; }
.toolbar { margin-bottom: 12px; }
.tip { margin-top: 12px; color: #94a3b8; font-size: 12px; }
.test-area { max-width: 720px; }
.test-btn { margin-top: 12px; }
.test-result { margin-top: 16px; max-width: 720px; }
.test-result-alert { margin-top: 16px; max-width: 720px; }
.kw-tag { margin-right: 6px; }
.muted { color: #94a3b8; font-size: 13px; }
.ai-label { color: #f59e0b; font-size: 13px; }
.accuracy-row { margin-top: 12px; margin-bottom: 16px; }
.stat-label { color: #94a3b8; font-size: 13px; }
.stat-value { font-size: 26px; font-weight: 700; color: var(--el-color-primary); margin-top: 4px; }
</style>