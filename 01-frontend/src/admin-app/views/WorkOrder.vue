<template>
  <!-- 管理端工单管理（需求 2.3 / API 6.2~6.9）：工单查询、审核确认、等级升级、手动录入、编辑、删除、批量导入、分类修正 -->
  <AdminLayout title="工单管理">
    <el-card shadow="never">
      <el-form inline class="filter-form">
        <el-form-item label="工单编号">
          <el-input v-model="filters.orderNo" clearable placeholder="工单编号" style="width: 160px" @keyup.enter="load(1)" @clear="load(1)" />
        </el-form-item>
        <el-form-item label="分类">
          <el-select v-model="filters.category" clearable placeholder="全部分类" style="width: 120px" @change="load(1)">
            <el-option v-for="p in primaries" :key="p.code" :label="p.name" :value="p.code" />
          </el-select>
        </el-form-item>
        <el-form-item label="等级">
          <el-select v-model="filters.level" clearable placeholder="全部等级" style="width: 110px" @change="load(1)">
            <el-option label="一级" value="一级" />
            <el-option label="二级" value="二级" />
            <el-option label="三级" value="三级" />
          </el-select>
        </el-form-item>
        <el-form-item label="辖区">
          <el-select v-model="filters.region" clearable filterable placeholder="全部辖区" style="width: 150px" @change="load(1)">
            <el-option v-for="c in counties" :key="c.region_code" :label="c.region_name" :value="c.region_name" />
          </el-select>
        </el-form-item>
        <el-form-item label="状态">
          <el-select v-model="filters.status" clearable placeholder="全部状态" style="width: 140px" @change="load(1)">
            <el-option v-for="(label, key) in statusLabels" :key="key" :label="label" :value="key" />
          </el-select>
        </el-form-item>
        <el-form-item label="关键词">
          <el-input v-model="filters.keyword" clearable placeholder="标题/内容关键词" style="width: 160px" @keyup.enter="load(1)" @clear="load(1)" />
        </el-form-item>
        <el-form-item>
          <el-button type="primary" @click="load(1)">查询</el-button>
          <el-button @click="resetFilters">重置</el-button>
        </el-form-item>
      </el-form>

      <div class="toolbar">
        <el-button type="primary" @click="openCreate">手动录入</el-button>
        <el-button @click="openImport">批量导入</el-button>
        <el-button type="success" @click="handleExport" :disabled="!exportSelection.length">批量导出</el-button>
        <!-- 自动派单开关：开启后每 30 秒自动把「待分派且已确定部门」的工单派给对应部门（模板同父诉求/子诉求自动拆分/建单） -->
        <el-switch
          v-model="autoDispatch"
          @change="onAutoDispatchChange"
          active-text="自动派单"
          inactive-text="手动派单"
          inline-prompt
          :active-action-icon="ArrowRight"
          :inactive-action-icon="ArrowLeft"
          style="margin-left: 20px; --el-switch-on-color: #c0c4cc; --el-switch-off-color: #dcdfe6"
        />
        <el-progress
          type="dashboard"
          :percentage="dispatchRatePercent"
          :width="56"
          :color="'#c0c4cc'"
          style="margin-left: 12px; vertical-align: middle"
        >
          <template #default="{ percentage }">
            <span class="dashboard-value">{{ percentage }}%</span>
          </template>
        </el-progress>

        <span v-if="pendingCount > 0" class="auto-dispatch-tip">待分派 {{ pendingCount }} 条<template v-if="autoDispatch">（每 30 秒自动派单）</template></span>
        <span class="muted">录入/导入后工单自动执行智能分类，状态为「待确认」，需审核确认后进入分派流程</span>
      </div>

      <OrderTable
        :rows="rows" :total="total" :page="page" :page-size="pageSize" :loading="loading"
        :status-labels="statusLabels" :status-tag-type="statusType" :category-label="categoryLabel"
        :show-deadline="false" :action-width="380" :selectable="true"
        @page-change="load" @size-change="handleSizeChange"
        @selection-change="onExportSelectionChange"
        @expand-change="(row) => { loadTimeline(row); newMarker.markRowSeen(row) }" @row-act="newMarker.markRowSeen"
      >
        <template #expand="{ row }">
          <div class="flow-expand" v-loading="expandLoading[row.id]">
            <div class="flow-title">流转记录（当前步骤已高亮）</div>
            <el-timeline v-if="timelineMap[row.id] && timelineMap[row.id].length">
              <el-timeline-item v-for="(t, i) in timelineMap[row.id]" :key="i"
                :type="i === timelineMap[row.id].length - 1 ? 'primary' : undefined"
                :hollow="i !== timelineMap[row.id].length - 1"
                :timestamp="t.time" placement="top">
                <div class="flow-node" :class="{ current: i === timelineMap[row.id].length - 1 }">
                  【{{ t.node }}】{{ t.role }}：{{ t.content }}
                </div>
              </el-timeline-item>
            </el-timeline>
            <el-empty v-else-if="!expandLoading[row.id]" description="暂无流转记录" :image-size="50" />
          </div>
        </template>
        <template #extra-columns>
          <el-table-column prop="sub_category" label="二级分类" width="90" show-overflow-tooltip />
          <el-table-column prop="attention_count" label="关注度" width="70" align="center">
            <template #default="{ row }">
              <el-tag v-if="row.attention_count > 1" type="warning" size="small">{{ row.attention_count }}</el-tag>
              <span v-else>{{ row.attention_count || 1 }}</span>
            </template>
          </el-table-column>
          <el-table-column label="置信度" width="72">
            <template #default="{ row }">
              <span v-if="row.confidence != null">{{ (row.confidence * 100).toFixed(1) }}%</span>
              <span v-else>-</span>
            </template>
          </el-table-column>
          <el-table-column prop="department" label="分派部门" width="90" show-overflow-tooltip>
            <template #default="{ row }">{{ row.department || '-' }}</template>
          </el-table-column>
          <el-table-column prop="region" label="辖区" width="90" show-overflow-tooltip />
          <el-table-column prop="street_office" label="承办街道办" width="110" show-overflow-tooltip>
            <template #default="{ row }">{{ row.street_office || row.street_name || '-' }}</template>
          </el-table-column>
        </template>
        <template #action="{ row }">
          <el-button link type="info" size="small" @click="openDetail(row)">查看</el-button>
          <!-- 分派后按钮不消失：变已分派不可点 -->
          <el-button v-if="row.status === 'PENDING_DISPATCH'" link type="success" size="small" @click="openDispatch(row)">分派</el-button>
          <el-button v-else-if="row.department || row.status === 'PENDING_REASSIGN'" link type="success" size="small" disabled>已分派</el-button>
          <el-button v-if="row.status === 'PENDING_UPDATE'" link type="warning" size="small" @click="openUpgrade(row)">升级</el-button>
          <!-- 终审确认（新流程）：部门已审核/已办结/已评价可点，弹窗内选 确认归档 / 退回 -->
          <el-button v-if="['DEPT_AUDITED', 'CLOSED', 'EVALUATED'].includes(row.status)" link type="success" size="small" @click="openFinalConfirm(row)">终审确认</el-button>
          <el-button v-if="row.status === 'DEPT_REJECTED'" link type="success" size="small" @click="handleArchive(row)">归档</el-button>
          <!-- 部门驳回后回到管理端（部门已驳回/待重新分派）统一走「重新分类」重新分派 -->
          <el-button v-if="['DEPT_REJECTED', 'PENDING_REASSIGN'].includes(row.status)" link type="warning" size="small" @click="openReclassify(row)">重新分类</el-button>
          <!-- 删除：仅「待分派」工单可删除；分派后（含驳回/归档）一律不可删除 -->
          <el-button v-if="row.status === 'PENDING_DISPATCH'" link type="danger" size="small" @click="handleDelete(row)">删除</el-button>
        </template>
      </OrderTable>
    </el-card>

    <!-- 手动录入（API 6.5） -->
    <el-dialog v-model="createVisible" title="手动录入工单" width="560px">
      <el-form label-width="90px">
        <el-form-item label="工单标题" required>
          <el-input v-model="createForm.title" maxlength="200" show-word-limit placeholder="工单标题（必填）" />
        </el-form-item>
        <el-form-item label="工单内容" required>
          <el-input v-model="createForm.content" type="textarea" :rows="4" placeholder="工单内容（必填，录入后自动智能分类）" />
        </el-form-item>
        <el-form-item label="所属区县">
          <el-select v-model="createForm.district" clearable placeholder="请选择区县（选填）" style="width: 100%" @change="onCreateDistrictChange">
            <el-option v-for="d in counties" :key="d.region_code" :label="d.region_name" :value="d.region_name" />
          </el-select>
        </el-form-item>
        <el-form-item label="街道/乡镇">
          <el-select v-model="createForm.street" clearable placeholder="请选择街道/乡镇（选填）" style="width: 100%" :disabled="!createForm.district">
            <el-option v-for="s in createStreets" :key="s.region_code" :label="s.region_name" :value="s.region_name" />
          </el-select>
        </el-form-item>
        <el-form-item label="详细地址">
          <el-input v-model="createForm.addressDetail" maxlength="100" clearable placeholder="详细地址（选填）" />
        </el-form-item>
        <el-form-item label="工单等级">
          <el-select v-model="createForm.level" placeholder="默认三级" style="width: 140px">
            <el-option label="一级" value="一级" />
            <el-option label="二级" value="二级" />
            <el-option label="三级" value="三级" />
          </el-select>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="createVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="handleCreate">提交</el-button>
      </template>
    </el-dialog>

    <!-- 编辑工单内容（查看弹窗内触发） -->
    <el-dialog v-model="contentEditVisible" title="编辑工单内容" width="560px">
      <el-form label-width="90px">
        <el-form-item label="诉求标题">
          <el-input v-model="contentEditForm.title" maxlength="200" show-word-limit placeholder="诉求标题" />
        </el-form-item>
        <el-form-item label="诉求内容">
          <el-input v-model="contentEditForm.content" type="textarea" :rows="5" placeholder="诉求内容" />
        </el-form-item>
      </el-form>
      <div class="muted">修改记录将写入工单流转日志，保留修改前后数据</div>
      <template #footer>
        <el-button @click="contentEditVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="saveContentEdit">保存</el-button>
      </template>
    </el-dialog>

    <!-- 等级升级（API 6.4） -->
    <el-dialog v-model="upgradeVisible" title="工单等级升级确认" width="440px">
      <el-form label-width="90px">
        <el-form-item label="目标等级" required>
          <el-select v-model="upgradeForm.newLevel" placeholder="请选择目标等级" style="width: 160px">
            <el-option label="一级" value="一级" />
            <el-option label="二级" value="二级" />
            <el-option label="三级" value="三级" />
          </el-select>
        </el-form-item>
      </el-form>
      <div class="muted">确认后等级与优先级同步更新，工单状态变为「已确认」</div>
      <template #footer>
        <el-button @click="upgradeVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="handleUpgrade">确认升级</el-button>
      </template>
    </el-dialog>

    <!-- 编辑工单分类（查看弹窗内触发，一级/二级联动） -->
    <el-dialog v-model="categoryEditVisible" title="编辑工单分类" width="560px">
      <el-form label-width="90px">
        <el-form-item label="一级分类" required>
          <el-select v-model="categoryEditForm.category" placeholder="请选择一级分类" style="width: 100%" @change="onCategoryChange">
            <el-option v-for="p in primaries" :key="p.code" :label="p.name" :value="p.code" />
          </el-select>
        </el-form-item>
        <el-form-item label="二级分类">
          <el-select v-model="categoryEditForm.sub_category" clearable placeholder="请选择二级分类" style="width: 100%">
            <el-option v-for="c in subCategories" :key="c.id" :label="c.sub_label" :value="c.sub_label" />
            <el-option key="__other__" label="其他" value="其他" />
          </el-select>
        </el-form-item>
        <el-form-item v-if="categoryEditForm.category && categoryEditForm.category !== 'OTHER'" label="分派部门">
          <span>{{ deptOfCategory(categoryEditForm.category) || '-' }}</span>
        </el-form-item>
      </el-form>
      <div v-if="categoryEditForm.category === 'OTHER'" class="muted">一级分类为「其他」：保存后需在详情中手动选择分派部门，工单方可分派</div>
      <div v-else-if="categoryEditForm.sub_category === '其他'" class="muted">二级分类为「其他」：保存后承办街道办置空，需在详情中手动选择承办街道办</div>
      <div v-else class="muted">分类变更会同步更新分派部门</div>
      <template #footer>
        <el-button @click="categoryEditVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="saveCategoryEdit">保存</el-button>
      </template>
    </el-dialog>

    <!-- 批量导入（API 6.8） -->
    <el-dialog v-model="importVisible" title="工单 Excel 批量导入" width="520px">
      <el-upload drag :auto-upload="false" :limit="1" accept=".xlsx,.xls" :on-change="onImportFileChange" :on-remove="onImportFileRemove">
        <div class="el-upload__text">将文件拖到此处，或<em>点击上传</em></div>
        <template #tip>
          <div class="el-upload__tip muted">模板列：诉求标题 | 诉求内容 | 所属辖区 | 工单等级（等级选填）</div>
        </template>
      </el-upload>
      <template #footer>
        <el-button @click="importVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" :disabled="!importFile" @click="handleImport">开始导入</el-button>
      </template>
    </el-dialog>

    <!-- 终审确认（新流程）：确认归档 或 退回 -->
    <el-dialog v-model="fcVisible" title="终审确认" width="520px">
      <el-form label-width="90px">
        <el-form-item label="处理方式">
          <el-radio-group v-model="fcAction">
            <el-radio value="archive">确认归档（工单转入终审归档页）</el-radio>
            <el-radio value="reject">退回（重新流转）</el-radio>
          </el-radio-group>
        </el-form-item>
        <template v-if="fcAction === 'reject'">
          <el-form-item label="退回目标">
            <el-radio-group v-model="fcTarget">
              <el-radio value="dept">退回部门（待分派，部门重新分派街道办）</el-radio>
              <el-radio value="street">直接退回街道办（待街道办处理）</el-radio>
            </el-radio-group>
          </el-form-item>
          <el-form-item label="退回原因" required>
            <el-input v-model="fcReason" type="textarea" :rows="3" maxlength="500" show-word-limit placeholder="请填写退回原因" />
          </el-form-item>
        </template>
        <el-form-item v-else label="归档说明">
          <span class="muted">归档后工单转入终审归档页，只读不可修改</span>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="fcVisible = false">取消</el-button>
        <el-button type="primary" @click="handleFinalConfirm">确认</el-button>
      </template>
    </el-dialog>

    <!-- 手动分派/转派（API 6.10，56部门口径：先选区县，目标部门=区县+分类部门拼接名） -->
    <el-dialog v-model="dispatchVisible" title="分派至部门" width="520px">
      <el-form label-width="100px">
        <el-form-item label="所属区县" required>
          <el-select v-model="dispatchForm.countyCode" style="width: 100%" placeholder="请选择所属区县">
            <el-option v-for="c in counties" :key="c.region_code" :label="c.region_name" :value="c.region_code" />
          </el-select>
        </el-form-item>
        <el-form-item label="目标部门" required>
          <el-input :model-value="dispatchDeptDisplay" disabled placeholder="暂无分派部门" />
        </el-form-item>
        <el-form-item v-if="!dispatchDeptDisplay" label="提示">
          <span class="muted">该工单尚未确定分派部门（一级分类为「其他」时无自动映射），请先在详情中选择分派部门</span>
        </el-form-item>
        <el-form-item label="分派说明">
          <el-input v-model="dispatchForm.remark" type="textarea" :rows="3" maxlength="500" show-word-limit placeholder="分派说明（选填）" />
        </el-form-item>
      </el-form>
      <div class="muted">分派后工单进入「待部门端处理」，仅所选区县的该分类部门可见。分派部门由工单一级分类决定，如需调整请先在详情中编辑类别。</div>
      <template #footer>
        <el-button @click="dispatchVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" :disabled="!dispatchDeptDisplay" @click="handleDispatch">确认分派</el-button>
      </template>
    </el-dialog>

    <!-- 重新分类（部门已驳回工单）：选区县+类别带出部门，确认后一步重新分派（56部门口径） -->
    <el-dialog v-model="reclassifyVisible" title="重新分类" width="560px">
      <el-form label-width="100px">
        <el-form-item label="所属区县">
          <el-select v-model="reclassifyForm.countyCode" clearable placeholder="历史工单可选，不选则沿用原区县" style="width: 100%">
            <el-option v-for="c in counties" :key="c.region_code" :label="c.region_name" :value="c.region_code" />
          </el-select>
        </el-form-item>
        <el-form-item label="类别" required>
          <el-select v-model="reclassifyForm.category" placeholder="请选择一级分类" style="width: 100%" @change="onReclassifyCategoryChange">
            <el-option v-for="p in primaries" :key="p.code" :label="p.name" :value="p.code" />
          </el-select>
        </el-form-item>
        <el-form-item label="二级标签">
          <el-select v-model="reclassifyForm.sub_category" clearable placeholder="请选择二级分类" style="width: 100%" :disabled="!reclassifyForm.category">
            <el-option v-for="c in reclassifySubs" :key="c.id" :label="c.sub_label" :value="c.sub_label" />
          </el-select>
        </el-form-item>
        <el-form-item label="部门" required>
          <el-input :model-value="reclassifyDeptDisplay" disabled placeholder="选择类别后自动带出" />
        </el-form-item>
      </el-form>
      <div class="muted">确认后保存新分类并把工单退回「待分派」，再点击列表「分派」按钮完成重新分派</div>
      <template #footer>
        <el-button @click="reclassifyVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="handleReclassify">保存分类</el-button>
      </template>
    </el-dialog>

    <!-- 工单详情查看弹窗 -->
    <OrderDetailDialog
      v-model="detailVisible"
      :detail="detail"
      :status-labels="statusLabels" :status-tag-type="statusType" :category-label="categoryLabel"
      width="1200px"
      @open="loadDetail"
    >
      <template #content="{ detail: d }">
        <div class="detail-edit-row">
          <span class="detail-text">{{ d.title || '-' }}</span>
          <el-button link type="primary" :icon="Edit" @click="openContentEdit" />
        </div>
        <div v-if="d.content" class="detail-content">{{ d.content }}</div>
      </template>
      <template #category="{ detail: d }">
        <div class="detail-edit-row">
          <span class="detail-text">{{ categoryLabel(d.category) }}<span v-if="d.sub_category"> / {{ d.sub_category }}</span></span>
          <el-button link type="primary" :icon="Edit" @click="openCategoryEdit" />
        </div>
      </template>
      <template #department="{ detail: d }">
        <div class="detail-select-col">
          <el-select
            v-model="detail.department"
            filterable clearable
            placeholder="请选择分派部门"
            style="width: 100%"
            :disabled="d.is_archived === 1"
            @change="saveDeptChange"
          >
            <el-option-group v-for="g in deptOptions" :key="g.county" :label="g.county">
              <el-option v-for="o in g.options" :key="o.value" :label="o.label" :value="o.value" />
            </el-option-group>
          </el-select>
          <div v-if="d.category === 'OTHER'" class="muted other-tip">一级分类为「其他」：请手动选择分派部门后，工单方可分派（选中后自动派单模式可接管）</div>
        </div>
      </template>
      <template #street-office="{ detail: d }">
        <div class="detail-select-col">
          <el-select
            v-model="detail.street_office"
            filterable clearable
            placeholder="请选择承办街道办"
            style="width: 100%"
            :disabled="d.is_archived === 1"
            @change="saveStreetChange"
          >
            <el-option v-for="s in streetOptions" :key="s.region_code" :label="s.region_name" :value="s.region_name" />
          </el-select>
          <div v-if="d.sub_category === '其他'" class="muted other-tip">二级分类为「其他」：请手动选择承办街道办后方可分派</div>
        </div>
      </template>
      <template #extra>
        <el-descriptions-item label="工单生成时间">{{ formatTime(detail.create_time) }}</el-descriptions-item>
        <el-descriptions-item label="工单等级">{{ detail.level || '-' }}</el-descriptions-item>
        <el-descriptions-item label="关注数">{{ detail.attention_count ?? 0 }}</el-descriptions-item>
        <el-descriptions-item label="办结时间">{{ formatTime(detail.close_time) || '-' }}</el-descriptions-item>
        <el-descriptions-item v-if="detail.is_archived === 1" label="归档时间">{{ formatTime(detail.archive_time) || '-' }}</el-descriptions-item>
        <el-descriptions-item v-if="detail.is_archived === 1" label="完成状态">
          <el-tag v-if="detail.is_completed === 1" type="success" size="small">已完成</el-tag>
          <el-tag v-else type="warning" size="small">未完成</el-tag>
        </el-descriptions-item>
      </template>
      <template #after>
        <div class="appeal-list-title">关联子诉求（{{ detail.appeals ? detail.appeals.length : 0 }} 条）</div>
        <el-table :data="detail.appeals || []" border size="small">
          <el-table-column prop="appeal_no" label="诉求编号" width="180" />
          <el-table-column prop="content" label="诉求内容" min-width="220" show-overflow-tooltip />
          <el-table-column prop="location" label="地点" min-width="160" show-overflow-tooltip />
          <el-table-column prop="citizen_name" label="市民名称" width="100" />
          <el-table-column prop="citizen_phone" label="联系电话" width="130" />
          <el-table-column label="提交时间" width="160">
            <template #default="{ row }">{{ formatTime(row.submit_time) }}</template>
          </el-table-column>
        </el-table>
        <!-- 诉求附件（市民提交时上传；图片缩略图/文档图标） -->
        <div class="appeal-list-title" style="margin-top:12px">诉求附件（{{ (detail.appeal_attachments || []).length }} 个）</div>
        <AttachmentGrid v-if="(detail.appeal_attachments || []).length" :files="detail.appeal_attachments" />
        <div v-else class="muted">无</div>
      </template>
      <template #footer-actions>
        <el-button type="primary" @click="detailVisible = false">关闭</el-button>
      </template>
    </OrderDetailDialog>
  </AdminLayout>
</template>

<script setup>
// 管理端工单管理：多条件查询 + 确认/升级/录入/编辑/删除/导入/分类修正/手动分派/手动归档（API 6.2~6.10、11.3.6）
import { ref, reactive, computed, onMounted, onUnmounted } from 'vue'
import { formatTime } from '../../shared/format.js'
import { useRoute } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Edit, ArrowRight, ArrowLeft } from '@element-plus/icons-vue'
import AdminLayout from '../components/AdminLayout.vue'
import OrderTable from '../../shared/OrderTable.vue'
import OrderDetailDialog from '../../shared/OrderDetailDialog.vue'
import AttachmentGrid from '../../shared/AttachmentGrid.vue'
import { markModuleRead, getLastViewed } from '../api/unread'
import { useNewMarker } from '../../shared/useNewMarker'
import { saveBlobResponse } from '../../shared/request.js'
import {
  getOrders, upgradeOrder, createOrder, updateOrder,
  deleteOrder, importOrders, exportOrders, dispatchOrder, archiveOrder, getOrderDetail,
  getCounties, getRegionChildren,
  getOrderTimeline, finalConfirm, reclassifyOrder,
  getAutoDispatchConfig, setAutoDispatchConfig, startAutoDispatch,
  getAutoDispatchStatus, cancelAutoDispatch
} from '../api/order'
import { getPrimaries, getCategories } from '../api/classify'
import { ORDER_STATUS, orderStatusLabel, orderStatusColor } from '../../shared/dict'

// 管理端统一使用 ADMIN 角色文案（保留各端既有差异）
const ROLE = 'ADMIN'
const statusLabels = Object.fromEntries(Object.keys(ORDER_STATUS).map((k) => [k, orderStatusLabel(k, ROLE)]))
const statusType = (s) => orderStatusColor(s, ROLE)

const primaries = ref([])
const primaryMap = computed(() => Object.fromEntries(primaries.value.map(p => [p.code, p.name])))
const categoryLabel = (c) => primaryMap.value[c] || c || '-'
const loadPrimaries = async () => {
  primaries.value = await getPrimaries()
}
const loadCategoriesData = async () => {
  try { categories.value = await getCategories() } catch { /* ignore */ }
}

// ---- 列表查询（API 6.2） ----
const loading = ref(false)
const rows = ref([])
const total = ref(0)
const page = ref(1)
const pageSize = ref(20)
const filters = reactive({ orderNo: '', category: '', level: '', region: '', status: '', keyword: '' })
// 支持从待办卡片跳转携带状态筛选（如 /orders?status=RETURNED）
const route = useRoute()

// ---- 行展开流转时间线（懒加载，最后一条=当前进行步骤并高亮） ----
const timelineMap = reactive({})
const expandLoading = reactive({})
const loadTimeline = async (row) => {
  if (timelineMap[row.id] || expandLoading[row.id]) return
  expandLoading[row.id] = true
  try {
    const data = await getOrderTimeline(row.id)
    timelineMap[row.id] = data.timeline || []
  } catch { timelineMap[row.id] = [] } finally { expandLoading[row.id] = false }
}

// ---- 终审确认（新流程）：已办结/已评价 → 确认归档 或 退回 ----
const fcVisible = ref(false)
const fcRow = ref(null)
const fcAction = ref('archive')
const fcReason = ref('')
const fcTarget = ref('dept')
const openFinalConfirm = (row) => {
  fcRow.value = row
  fcAction.value = 'archive'
  fcReason.value = ''
  fcTarget.value = 'dept'
  fcVisible.value = true
}
const handleFinalConfirm = async () => {
  if (fcAction.value === 'reject' && !fcReason.value.trim()) {
    ElMessage.warning('退回原因不能为空'); return
  }
  const target = fcRow.value
  if (!target) return
  await finalConfirm(target.id, {
    action: fcAction.value,
    reject_reason: fcAction.value === 'reject' ? fcReason.value.trim() : undefined,
    reject_target: fcAction.value === 'reject' ? fcTarget.value : undefined
  })
  ElMessage.success(fcAction.value === 'archive' ? '已终审确认归档，工单转入终审归档页' : '已退回，工单按目标重新流转')
  fcVisible.value = false
  fcRow.value = null
  load()
}

// ---- 部门已驳回工单「重新分类」→ 弹窗保存新分类退回待分派，再由「分派」按钮完成分派（56部门口径） ----
const reclassifyVisible = ref(false)
const reclassifyTarget = ref(null)
const reclassifyForm = reactive({ category: '', sub_category: '', department: '', countyCode: '' })
const reclassifySubs = computed(() => categories.value.filter(c => c.primary_category === reclassifyForm.category))

/** 重新分类弹窗：区县名（供「区县+部门」拼接展示） */
const reclassifyCountyName = computed(() =>
  counties.value.find((c) => c.region_code === reclassifyForm.countyCode)?.region_name || '')
const reclassifyDeptDisplay = computed(() => {
  if (!reclassifyForm.department) return ''
  return reclassifyCountyName.value ? reclassifyCountyName.value + reclassifyForm.department : reclassifyForm.department
})

const openReclassify = async (row) => {
  reclassifyTarget.value = row
  reclassifyForm.category = row.category || ''
  reclassifyForm.sub_category = row.sub_category || ''
  reclassifyForm.department = (primaries.value.find(p => p.code === row.category) || {}).department || row.department || ''
  reclassifyForm.countyCode = countyCodeOf(row.county_name) ||
    (row.region && countyCodeOf((row.region + '').split(/[\s/]/)[0])) || ''
  // 记录「重新分类」操作日志（后端写审计 + 流转时间线）
  try { await reclassifyOrder(row.id) } catch { /* 留痕失败不阻塞弹窗 */ }
  reclassifyVisible.value = true
}

const onReclassifyCategoryChange = () => {
  reclassifyForm.sub_category = ''
  const hit = primaries.value.find(p => p.code === reclassifyForm.category)
  reclassifyForm.department = (hit && hit.department) || ''
}

const handleReclassify = async () => {
  if (!reclassifyForm.countyCode) { ElMessage.warning('请选择所属区县'); return }
  if (!reclassifyForm.category) { ElMessage.warning('请选择类别'); return }
  if (!reclassifyForm.department) { ElMessage.warning('该类别未配置分派部门'); return }
  saving.value = true
  try {
    // 仅保存新分类并把工单退回「待分派」，不分派；分派由列表「分派」按钮完成
    await reclassifyOrder(reclassifyTarget.value.id, {
      category: reclassifyForm.category,
      sub_category: reclassifyForm.sub_category || undefined,
      county_code: reclassifyForm.countyCode
    })
    ElMessage.success('重新分类已保存，工单回到待分派，请点击「分派」完成分派')
    reclassifyVisible.value = false
    load()
  } catch (e) {
    ElMessage.error((e && e.message) || '重新分类保存失败')
  } finally { saving.value = false }
}

// 工单“新”角标（快照判新）：进入页面取游标快照，翻页/查询/局部刷新不影响未操作行的角标
const newMarker = useNewMarker('work_order', {
  getLastViewed: (m) => getLastViewed(m),
  markRead: (m) => markModuleRead(m)
}, ['create_time'])

const load = async (p = page.value) => {
  page.value = p
  loading.value = true
  try {
    const data = await getOrders({
      order_no: filters.orderNo || undefined,
      category: filters.category || undefined,
      level: filters.level || undefined,
      region: filters.region || undefined,
      status: filters.status || undefined,
      keyword: filters.keyword || undefined,
      archived: 0,
      page: p,
      page_size: pageSize.value
    })
    rows.value = data.list || []
    total.value = data.total || 0
    listPendingCount.value = data.list_pending_count ?? 0
    newMarker.decorate(rows.value)      // 快照判新（覆盖后端 decorate）
    newMarker.reportRead()              // 推进游标并刷新侧边栏
  } finally {
    loading.value = false
  }
}

const handleSizeChange = (size) => {
  pageSize.value = size
  load(1)
}

const resetFilters = () => {
  filters.orderNo = ''
  filters.category = ''
  filters.level = ''
  filters.region = ''
  filters.status = ''
  filters.keyword = ''
  load(1)
}

// ---- 手动录入（API 6.5） ----
const saving = ref(false)
const createVisible = ref(false)
const createForm = reactive({ title: '', content: '', district: '', street: '', addressDetail: '', level: '' })
const counties = ref([])      // [{ region_code, region_name }]
const createStreets = ref([]) // [{ region_code, region_name }]

/** 区县下拉数据（sys_region，复用公众端区划接口） */
const loadCreateCounties = async () => {
  try {
    counties.value = await getCounties()
  } catch {
    counties.value = []
  }
}

/** 区县切换：清空街道并加载对应街道/镇/乡 */
const onCreateDistrictChange = async (districtName) => {
  createForm.street = ''
  createStreets.value = []
  if (!districtName) return
  const found = counties.value.find((c) => c.region_name === districtName)
  if (!found) return
  try {
    createStreets.value = await getRegionChildren(found.region_code)
  } catch {
    createStreets.value = []
  }
}

const openCreate = () => {
  createForm.title = ''
  createForm.content = ''
  createForm.district = ''
  createForm.street = ''
  createForm.addressDetail = ''
  createForm.level = ''
  createStreets.value = []
  createVisible.value = true
}

const handleCreate = async () => {
  if (!createForm.title.trim() || !createForm.content.trim()) {
    ElMessage.warning('工单标题、工单内容为必填项')
    return
  }
  saving.value = true
  try {
    const data = await createOrder({
      title: createForm.title.trim(),
      content: createForm.content.trim(),
      district: createForm.district || undefined,
      street: createForm.street || undefined,
      address_detail: createForm.addressDetail.trim() || undefined,
      level: createForm.level || undefined
    })
    ElMessage.success(`工单 ${data.order_no} 已录入，分类：${categoryLabel(data.category)}`)
    createVisible.value = false
    load(1)
  } catch (e) {
    // 捕获超时/接口失败：AI 分类慢时明确提示，避免 Uncaught (in promise)
    ElMessage.error((e && e.message) || '工单创建失败，请稍后重试')
  } finally {
    saving.value = false
  }
}

// ---- 编辑工单内容（查看弹窗内触发） ----
const contentEditVisible = ref(false)
const contentEditOrderId = ref(null)
const contentEditForm = reactive({ title: '', content: '' })

const openContentEdit = () => {
  if (!detail.id) return
  contentEditOrderId.value = detail.id
  contentEditForm.title = detail.title || ''
  contentEditForm.content = detail.content || ''
  contentEditVisible.value = true
}

const saveContentEdit = async () => {
  saving.value = true
  try {
    await updateOrder(contentEditOrderId.value, { title: contentEditForm.title, content: contentEditForm.content })
    ElMessage.success('工单内容已更新')
    contentEditVisible.value = false
    loadDetail()
    load()
  } finally {
    saving.value = false
  }
}

// ---- 等级升级（API 6.4） ----
const upgradeVisible = ref(false)
const upgradeOrderRef = ref(null)
const upgradeForm = reactive({ newLevel: '' })

const openUpgrade = (row) => {
  upgradeOrderRef.value = row
  upgradeForm.newLevel = ''
  upgradeVisible.value = true
}

const handleUpgrade = async () => {
  if (!upgradeForm.newLevel) {
    ElMessage.warning('请选择目标等级')
    return
  }
  saving.value = true
  try {
    await upgradeOrder(upgradeOrderRef.value.id, { new_level: upgradeForm.newLevel })
    ElMessage.success(`工单已升级为「${upgradeForm.newLevel}」`)
    upgradeVisible.value = false
    load()
  } finally {
    saving.value = false
  }
}

// ---- 编辑工单分类（查看弹窗内触发，一级/二级联动） ----
const categoryEditVisible = ref(false)
const categoryEditOrderId = ref(null)
const categoryEditForm = reactive({ category: '', sub_category: '' })
const categories = ref([])

const subCategories = computed(() => categories.value.filter(c => c.primary_category === categoryEditForm.category))

/** 一级分类 → 分派部门（与后端 updateOrder 分类联动一致，取自 classify_primary） */
const deptOfCategory = (code) => primaries.value.find(p => p.code === code)?.department || ''

const openCategoryEdit = () => {
  if (!detail.id) return
  categoryEditOrderId.value = detail.id
  categoryEditForm.category = detail.category || ''
  categoryEditForm.sub_category = detail.sub_category || ''
  categoryEditVisible.value = true
}

const onCategoryChange = () => {
  // 改变一级分类后清空二级分类，等待重新选择
  categoryEditForm.sub_category = ''
}

const saveCategoryEdit = async () => {
  if (!categoryEditForm.category) {
    ElMessage.warning('请选择一级分类')
    return
  }
  saving.value = true
  try {
    await updateOrder(categoryEditOrderId.value, {
      category: categoryEditForm.category,
      sub_category: categoryEditForm.sub_category || ''
    })
    ElMessage.success('工单分类已更新')
    categoryEditVisible.value = false
    loadDetail()
    load()
  } finally {
    saving.value = false
  }
}

// ---- 删除（API 6.7） ----
const handleDelete = async (row) => {
  try {
    await ElMessageBox.confirm(`确认删除工单 ${row.order_no}？将进行逻辑删除，历史可追溯`, '删除确认', { type: 'warning' })
  } catch { return }
  await deleteOrder(row.id)
  ElMessage.success('已删除')
  load()
}

// ---- 批量导入（API 6.8） ----
const importVisible = ref(false)
const importFile = ref(null)

const openImport = () => {
  importFile.value = null
  importVisible.value = true
}

const onImportFileChange = (file) => {
  importFile.value = file.raw || null
}

const onImportFileRemove = () => {
  importFile.value = null
}

const handleImport = async () => {
  if (!importFile.value) {
    ElMessage.warning('请先选择 Excel 文件')
    return
  }
  const formData = new FormData()
  formData.append('file', importFile.value)
  saving.value = true
  try {
    const data = await importOrders(formData)
    const errors = data.errors || []
    ElMessage.success(`导入完成：成功 ${data.success_count} 条，失败 ${data.fail_count} 条`)
    if (errors.length) {
      const detail = errors.slice(0, 5).map((e) => `第${e.row}行：${e.reason}`).join('；')
      ElMessage.warning(`失败明细（部分）：${detail}`)
    }
    importVisible.value = false
    importFile.value = null
    load(1)
  } finally {
    saving.value = false
  }
}

// ---- 批量导出（API 6.8 对称）：勾选工单后导出为 Excel，前 4 列对齐导入模板可回导，附加工单状态列 ----
const exportSelection = ref([])
const onExportSelectionChange = (sel) => { exportSelection.value = sel || [] }

const handleExport = async () => {
  const ids = exportSelection.value.map((r) => r.id)
  if (!ids.length) {
    ElMessage.warning('请先勾选要导出的工单')
    return
  }
  saving.value = true
  try {
    // 流式下载：后端直接回写 Excel 响应流（零落盘），下载走登录态鉴权
    const res = await exportOrders(ids)
    saveBlobResponse(res, `工单批量导出_${new Date().toISOString().slice(0, 10).replace(/-/g, '')}.xlsx`)
    ElMessage.success(`导出完成：共 ${ids.length} 条`)
  } finally {
    saving.value = false
  }
}

// ---- 手动分派/转派（API 6.10，56部门口径） ----
const dispatchVisible = ref(false)
const dispatchTarget = ref(null)
const dispatchForm = reactive({ countyCode: '', department: '', remark: '' })

/** 目标部门展示名 = 所选区县名 + 分类对应部门名（56口径拼接，与后端 deptDisplay 一致）；
 *  一级分类为「其他」（OTHER）时无分类映射，回退用详情弹窗手动选定的部门（须属于所选区县） */
const dispatchCountyName = computed(() =>
  counties.value.find((c) => c.region_code === dispatchForm.countyCode)?.region_name || '')
const dispatchDeptDisplay = computed(() => {
  if (!dispatchForm.countyCode) return ''
  const dept = deptOfCategory(dispatchTarget.value?.category)
  if (dept) return dispatchCountyName.value + dept
  const manual = dispatchTarget.value?.department || ''
  return manual.startsWith(dispatchCountyName.value) ? manual : ''
})

/** 区县名 → 区县编码（打开弹窗默认选中工单已有区县） */
const countyCodeOf = (name) =>
  (name && counties.value.find((c) => c.region_name === name) || {}).region_code || ''

const openDispatch = (row) => {
  dispatchTarget.value = row
  dispatchForm.countyCode = countyCodeOf(row.county_name) ||
    (row.region && countyCodeOf(row.region.split(/[\s/]/)[0])) || ''
  dispatchForm.department = row.department || ''
  dispatchForm.remark = ''
  dispatchVisible.value = true
}

const handleDispatch = async () => {
  if (!dispatchForm.countyCode) {
    ElMessage.warning('请选择所属区县')
    return
  }
  if (!dispatchDeptDisplay.value) {
    ElMessage.warning('该工单尚未确定分派部门，请先在详情中编辑类别')
    return
  }
  saving.value = true
  try {
    await dispatchOrder(dispatchTarget.value.id, {
      department: dispatchDeptDisplay.value,
      county_code: dispatchForm.countyCode,
      remark: dispatchForm.remark || undefined
    })
    ElMessage.success('分派成功')
    dispatchVisible.value = false
    load()
  } catch (e) {
    ElMessage.error((e && e.message) || '分派失败，请稍后重试')
  } finally {
    saving.value = false
  }
}

// ---- 手动归档（API 11.3.6） ----
const handleArchive = async (row) => {
  try {
    await ElMessageBox.confirm('确认归档工单 ' + row.order_no + '？归档后不可再修改', '归档确认', { type: 'info' })
  } catch { return }
  saving.value = true
  try {
    await archiveOrder(row.id)
    ElMessage.success('归档成功')
    load()
  } finally {
    saving.value = false
  }
}

// ---- 工单详情查看 ----
const detailVisible = ref(false)
const detailLoading = ref(false)
const detailCurrentId = ref(null)
/** 操作列「查看」：打开工单详情弹窗 */
const openDetail = async (row) => {
  detailCurrentId.value = row.id
  detailVisible.value = true
  await loadDetail()
}
const detail = reactive({ order_no: '', status: '', title: '', content: '', category: '', sub_category: '', department: '', street_office: '', create_time: '', appeals: [] })
const loadDetail = async () => {
  if (!detailCurrentId.value) return
  detailLoading.value = true
  Object.assign(detail, { order_no: '', status: '', title: '', content: '', category: '', sub_category: '', department: '', street_office: '', create_time: '', appeals: [] })
  try {
    const d = await getOrderDetail(detailCurrentId.value)
    Object.assign(detail, d)
    loadStreetOptions()
  } catch (e) {
    ElMessage.error('加载工单详情失败：' + (e.message || '未知'))
  } finally {
    detailLoading.value = false
  }
}

// ---- 详情弹窗「部门/街道办」下拉（管理端手动修改分派部门与承办街道办） ----
/** 部门下拉选项：7 区县 × 8 分类部门（56 部门口径，label=value=「区县+部门名」与后端 deptDisplay 一致） */
const deptOptions = computed(() => counties.value.map((c) => ({
  county: c.region_name,
  options: primaries.value
    .filter((p) => p.department)
    .map((p) => {
      const label = c.region_name + p.department
      return { label, value: label }
    })
})))

/** 街道办下拉选项：按工单区县加载下属街道/镇/乡（区县缺失时为空，待选部门确定区县后出现） */
const streetOptions = ref([])
const loadStreetOptions = async () => {
  streetOptions.value = []
  const countyCode = detail.county_code || countyCodeOf(detail.county_name) ||
    (detail.region ? countyCodeOf((detail.region + '').split(/[\s/]/)[0]) : '')
  if (!countyCode) return
  try {
    streetOptions.value = await getRegionChildren(countyCode)
  } catch {
    streetOptions.value = []
  }
}

/** 详情弹窗修改分派部门：后端解析「区县+部门名」同步更新区县与一级分类 */
const saveDeptChange = async (val) => {
  saving.value = true
  try {
    await updateOrder(detail.id, { department: val || '' })
    ElMessage.success(val ? `分派部门已更新为「${val}」` : '分派部门已清空，工单待人工指定部门')
    await loadDetail()
    load()
  } catch (e) {
    ElMessage.error((e && e.message) || '分派部门更新失败')
    await loadDetail()
  } finally {
    saving.value = false
  }
}

/** 详情弹窗修改承办街道办 */
const saveStreetChange = async (val) => {
  saving.value = true
  try {
    await updateOrder(detail.id, { street_office: val || '' })
    ElMessage.success(val ? `承办街道办已更新为「${val}」` : '承办街道办已清空')
    await loadDetail()
    load()
  } catch (e) {
    ElMessage.error((e && e.message) || '承办街道办更新失败')
    await loadDetail()
  } finally {
    saving.value = false
  }
}

onMounted(async () => {
  if (route.query.status) {
    filters.status = String(route.query.status)
  }
  await newMarker.init()   // 先取游标快照，再加载列表（判新基准稳定）
  load()
  initAutoDispatch()
  subscribeDispatchStream()
  loadPrimaries()
  loadCategoriesData()
  loadCreateCounties()
})

// ---- 自动派单：开关 + 30 秒周期轮询 + SSE 状态推送（模板同父诉求/子诉求自动拆分/建单） ----
const autoDispatch = ref(false)          // 开关当前状态（自动派单 on/off）
const autoDispatchActive = ref(false)    // 是否有自动派单任务在运行
const autoDispatchTotal = ref(0)
const autoDispatchDone = ref(0)
const totalOrders = ref(0)               // 未删除工单总数（自动派单状态展示用）
/** 当前筛选列表中的待分派工单数（工单页分派进度分子，随筛选/分页由列表接口返回） */
const listPendingCount = ref(0)
// 环形面板（常驻）：分派进度 =（列表总数 - 列表中待分派数）/ 列表总数。
// 分母 = 当前筛选条件下的列表总数 total（不再是数据库全表数量），列表为空时按 100% 展示
const dispatchRatePercent = computed(() => {
  const listTotal = total.value
  if (listTotal <= 0) return 100
  const pendingInList = Math.min(listPendingCount.value, listTotal)
  return Math.min(100, Math.round((listTotal - pendingInList) / listTotal * 100))
})
const pendingCount = ref(0)              // 待分配工单数
let autoDispatchPollTimer = null
let dispatchEs = null

/** 页面加载：读取开关配置与待分配数；若开关已开且任务进行中，继续轮询 */
const initAutoDispatch = async () => {
  try {
    const cfg = await getAutoDispatchConfig()
    autoDispatch.value = !!cfg.autoDispatch
    pendingCount.value = cfg.pendingCount || 0
    totalOrders.value = cfg.totalOrders || 0
    if (cfg.enabled) {
      const st = await getAutoDispatchStatus()
      totalOrders.value = st.totalOrders || totalOrders.value
      if (st.active) {
        autoDispatchActive.value = true
        pollAutoDispatch()
      }
    }
  } catch { /* 静默失败，不阻断页面 */ }
}

/** 开关切换：开启→立即启动一轮 + 轮询；关闭→取消任务 */
const onAutoDispatchChange = async (val) => {
  try { await setAutoDispatchConfig(val) } catch { /* ignore */ }
  if (val) {
    autoDispatchActive.value = true
    try { await startAutoDispatch() } catch { /* ignore */ }
    pollAutoDispatch()
  } else {
    try { await cancelAutoDispatch() } catch { /* ignore */ }
    autoDispatchActive.value = false
    load()
  }
}

/** 每 1 秒轮询自动派单进度；30 秒定时任务负责启动，这里只跟进度与收尾 */
const pollAutoDispatch = async () => {
  while (autoDispatchActive.value) {
    await new Promise((r) => setTimeout(r, 1000))
    if (!autoDispatchActive.value) return
    let st = null
    try { st = await getAutoDispatchStatus() } catch { continue }
    if (!st) { autoDispatchActive.value = false; load(); return }
    pendingCount.value = st.pendingCount || 0
    totalOrders.value = st.totalOrders || totalOrders.value
    autoDispatchTotal.value = st.total || autoDispatchTotal.value
    autoDispatchDone.value = st.done || 0
    if (st.finished || st.cancelled) {
      autoDispatchActive.value = false
      await load()
      return
    }
    if (!st.active) { autoDispatchActive.value = false; await load(); return }
  }
}

/** SSE 订阅：后端自动派单成功时推送 status-change → 更新对应行状态或兜底刷新 */
const subscribeDispatchStream = () => {
  const token = localStorage.getItem('admin_token') || ''
  dispatchEs = new EventSource('/api/v1/admin/orders/auto-dispatch/stream?token=' + token)
  let errored = false
  dispatchEs.addEventListener('status-change', (e) => {
    errored = false
    try {
      const data = JSON.parse(e.data) // { orderId, status }
      const row = rows.value.find((r) => r.id === data.orderId)
      if (row) {
        const wasPending = row.status === 'PENDING_DISPATCH'
        row.status = data.status
        // 当前页工单被自动派单：同步本页待分派数，保证分派进度环形面板实时准确
        if (wasPending) listPendingCount.value = Math.max(0, listPendingCount.value - 1)
      } else {
        load() // 不在当前页，整页兜底刷新
      }
      pendingCount.value = Math.max(0, pendingCount.value - 1)
    } catch { /* ignore */ }
  })
  dispatchEs.onerror = () => {
    if (dispatchEs.readyState === EventSource.CLOSED) {
      if (!errored) {
        errored = true
        ElMessage.warning('自动派单实时连接已断开（登录已失效），请重新登录后刷新页面')
      }
      return
    }
    if (!errored) {
      errored = true
      ElMessage.warning('自动派单实时连接中断，正在自动重连...')
    }
  }
}

onUnmounted(() => {
  if (autoDispatchPollTimer) clearInterval(autoDispatchPollTimer)
  if (dispatchEs) dispatchEs.close()
  newMarker.reportRead() // 离开页面推进游标：本页小点全部覆灭，徽章清零
})
</script>

<style scoped>
.filter-form { margin-bottom: 4px; }
.toolbar { margin-bottom: 12px; display: flex; align-items: center; gap: 12px; }
.pagination { margin-top: 16px; justify-content: flex-end; }
.muted { color: #94a3b8; font-size: 13px; }
.auto-dispatch-tip { color: #4b5563; font-size: 13px; margin-left: 10px; }
.dashboard-value { font-size: 12px; font-weight: 600; color: #909399; }
.flow-expand { padding: 4px 12px; }
.flow-title { font-weight: 600; margin-bottom: 8px; color: var(--el-text-color-primary); }
.flow-node { font-size: 13px; color: var(--el-text-color-regular); }
.flow-node.current { color: var(--el-color-primary); font-weight: 600; }
.appeal-list-title { margin: 16px 0 8px; font-size: 14px; font-weight: 600; color: #1f2937; }
.detail-edit-row { display: flex; align-items: center; gap: 8px; }
.detail-text { flex: 1; word-break: break-all; }
.detail-content { margin-top: 8px; font-size: 13px; color: var(--el-text-color-regular); white-space: pre-wrap; word-break: break-all; }
.detail-select-col { width: 100%; }
.other-tip { font-size: 12px; line-height: 1.4; margin-top: 2px; }
</style>