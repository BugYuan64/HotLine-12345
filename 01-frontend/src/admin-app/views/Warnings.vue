<template>
  <!-- 预警管理（需求 2.3 预警管理页 / API 8.1~8.4）：预警规则配置 + 预警记录查询 -->
  <AdminLayout title="预警与预判">
    <el-tabs v-model="activeTab">
      <el-tab-pane label="预警规则" name="rule">
        <el-card shadow="never">
          <div class="toolbar">
            <el-button type="primary" @click="openRuleDialog()">新增预警规则</el-button>
            <el-button @click="handleScan" :loading="scanLoading">立即扫描</el-button>
          </div>

          <el-table :data="ruleRows" v-loading="ruleLoading" height="420">
            <el-table-column prop="rule_name" label="规则名称" min-width="180" show-overflow-tooltip />
            <el-table-column label="时间维度" width="90">
              <template #default="{ row }">{{ dimensionLabel(row.time_dimension) }}</template>
            </el-table-column>
            <el-table-column label="分类" width="150">
              <template #default="{ row }">
                {{ row.category ? categoryLabel(row.category) + (row.sub_category ? '-' + row.sub_category : '') : '全部' }}
              </template>
            </el-table-column>
            <el-table-column label="预警等级" width="100">
              <template #default="{ row }">
                <el-tag :type="levelTagType(row.warning_level)" size="small">{{ levelLabel(row.warning_level) }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column prop="count_threshold" label="数量阈值" width="90" />
            <el-table-column label="状态" width="90">
              <template #default="{ row }">
                <el-tag :type="ruleStatusTagType(row.status)" size="small">{{ ruleStatusLabel(row.status) }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column label="推送对象" min-width="220">
              <template #default="{ row }">
                <div class="push-limit-row">
                  <span class="push-limit-label">辖区</span>
                  <template v-if="(row.push_counties || []).length">
                    <el-tag v-for="c in row.push_counties" :key="c" size="small" type="success" class="target-tag">{{ countyNameOf(c) }}</el-tag>
                  </template>
                  <el-tag v-else size="small" type="info" effect="plain" class="target-tag">全部区县</el-tag>
                </div>
                <div class="push-limit-row">
                  <span class="push-limit-label">类别</span>
                  <template v-if="(row.push_categories || []).length">
                    <el-tag v-for="d in row.push_categories" :key="d" size="small" type="warning" class="target-tag">{{ primaryMap[d] || d }}</el-tag>
                  </template>
                  <el-tag v-else size="small" type="info" effect="plain" class="target-tag">全部类别</el-tag>
                </div>
              </template>
            </el-table-column>
            <el-table-column label="操作" width="220" fixed="right">
              <template #default="{ row }">
                <el-button link type="primary" size="small" @click="openRuleDialog(row)">编辑</el-button>
                <el-button link :type="row.status === 'ENABLED' ? 'warning' : 'success'" size="small" @click="toggleRule(row)">
                  {{ row.status === 'ENABLED' ? '停用' : '启用' }}
                </el-button>
                <el-button link type="danger" size="small" @click="removeRule(row)">删除</el-button>
              </template>
            </el-table-column>
          </el-table>

          <!-- 诉求趋势对比折线图（独立筛选，不与诉求预判共用筛选框；最多同时 5 条线） -->
          <div class="trend-section">
            <div class="section-title">诉求趋势对比</div>
            <el-form inline class="trend-filters">
              <el-form-item label="一级分类">
                <el-select v-model="trendForm.category" clearable placeholder="全部分类" style="width: 150px" @change="onTrendCategoryChange">
                  <el-option v-for="p in primaries" :key="p.code" :label="p.name" :value="p.code" />
                </el-select>
              </el-form-item>
              <el-form-item label="二级分类">
                <el-select v-model="trendForm.sub" :disabled="!trendForm.category" clearable placeholder="全部（一级总括）" style="width: 170px">
                  <el-option v-for="s in trendSubOptions" :key="s" :label="s" :value="s" />
                </el-select>
              </el-form-item>
              <el-form-item label="区县">
                <el-select v-model="trendForm.region" clearable placeholder="全部区县" style="width: 130px">
                  <el-option v-for="r in regionOptions" :key="r" :label="r" :value="r" />
                </el-select>
              </el-form-item>
              <el-form-item label="时间">
                <el-select v-model="trendForm.days" style="width: 100px">
                  <el-option label="近7天" :value="7" />
                  <el-option label="近15天" :value="15" />
                  <el-option label="近30天" :value="30" />
                </el-select>
              </el-form-item>
              <el-form-item>
                <el-button type="primary" :loading="trendAdding" @click="addTrendLine">
                  <el-icon class="trend-btn-icon"><Plus /></el-icon>添加折线
                </el-button>
              </el-form-item>
            </el-form>
            <!-- 已添加的折线（彩色圆点标识，可关闭移除） -->
            <div class="trend-lines" v-if="trendLines.length">
              <el-tag v-for="(l, i) in trendLines" :key="l.key" closable effect="plain" class="trend-line-tag"
                @close="removeTrendLine(i)">
                <span class="trend-dot" :style="{ background: lineColor(i) }"></span>{{ l.name }}
              </el-tag>
            </div>
            <div v-if="!trendLines.length" class="trend-empty">
              选择一级分类、二级分类、区县与时间后点击「添加折线」：不选分类为全部分类总括，选具体一级展示该类细分趋势（最多 5 条）
            </div>
            <div v-show="trendLines.length" ref="trendChartRef" class="trend-chart"></div>
          </div>

          <!-- 诉求预判（融合进预警管理，位于预警规则下方，固定展示不被规则列表挤掉） -->
          <div class="prediction-section">
            <div class="section-title">诉求预判</div>

            <el-form inline class="prediction-filters">
              <el-form-item label="区县">
                <el-select v-model="predFilters.region" clearable placeholder="全部区县" style="width: 160px" @change="loadPrediction">
                  <el-option v-for="r in regionOptions" :key="r" :label="r" :value="r" />
                </el-select>
              </el-form-item>
              <el-form-item label="一级分类">
                <el-select v-model="predFilters.category" clearable placeholder="全部分类" style="width: 160px" @change="onPredCategoryChange">
                  <el-option v-for="p in primaries" :key="p.code" :label="p.name" :value="p.code" />
                </el-select>
              </el-form-item>
              <el-form-item label="二级分类">
                <el-select v-model="predFilters.subCategory" clearable placeholder="全部二级分类" style="width: 170px" :disabled="!predFilters.category" @change="loadPrediction">
                  <el-option v-for="s in predSecondaryOptions" :key="s" :label="s" :value="s" />
                </el-select>
              </el-form-item>
              <el-form-item label="周期">
                <el-select v-model="predFilters.period" clearable placeholder="全部周期" style="width: 120px" @change="loadPrediction">
                  <el-option label="1天" value="DAY" />
                  <el-option label="3天" value="3_DAY" />
                  <el-option label="7天" value="7_DAY" />
                </el-select>
              </el-form-item>
              <el-form-item>
                <el-button type="primary" @click="loadPrediction">查询</el-button>
              </el-form-item>
              <el-form-item>
                <el-button type="warning" plain :loading="predRunning" @click="handleRunPrediction">重新预判</el-button>
              </el-form-item>
              <el-form-item class="pred-config-btn">
                <el-button @click="openConfigDialog">预判配置</el-button>
              </el-form-item>
            </el-form>

            <!-- 预判结果：不可预判时空态提示 -->
            <el-empty v-if="predUnavailable" description="暂无足够历史数据，无法预测" :image-size="80" />
            <div v-else class="prediction-results">
              <div class="pred-block">
                <div class="pred-block-title">一级分类热点</div>
                <div ref="primaryChartRef" class="pred-chart"></div>
                <el-table :data="primaryHotspots" v-loading="predLoading">
                  <el-table-column label="一级分类" min-width="140">
                    <template #default="{ row }">{{ categoryLabel(row.category) }}</template>
                  </el-table-column>
                  <el-table-column prop="predicted_count" label="预测量" width="100" />
                  <el-table-column label="置信度" width="100">
                    <template #default="{ row }">{{ fmtConfidence(row.confidence) }}</template>
                  </el-table-column>
                  <el-table-column label="操作" width="130" fixed="right">
                    <template #default="{ row }">
                      <el-button link type="primary" size="small" @click="generateRule(row)">生成预警规则</el-button>
                    </template>
                  </el-table-column>
                </el-table>
              </div>
              <div class="pred-block">
                <div class="pred-block-title">二级分类热点</div>
                <div ref="secondaryChartRef" class="pred-chart"></div>
                <el-table :data="secondaryHotspots" v-loading="predLoading">
                  <el-table-column label="一级分类" min-width="140">
                    <template #default="{ row }">{{ categoryLabel(row.category) }}</template>
                  </el-table-column>
                  <el-table-column prop="sub_category" label="二级分类" min-width="130" />
                  <el-table-column prop="predicted_count" label="预测量" width="100" />
                  <el-table-column label="置信度" width="100">
                    <template #default="{ row }">{{ fmtConfidence(row.confidence) }}</template>
                  </el-table-column>
                  <el-table-column label="操作" width="130" fixed="right">
                    <template #default="{ row }">
                      <el-button link type="primary" size="small" @click="generateRule(row)">生成预警规则</el-button>
                    </template>
                  </el-table-column>
                </el-table>
              </div>
            </div>
          </div>

        </el-card>
      </el-tab-pane>

      <el-tab-pane name="record">
        <template #label>
          <span>预警记录 <el-badge :value="warnRecordUnread" :hidden="warnRecordUnread === 0" :max="99" /></span>
        </template>
        <el-card shadow="never">
          <el-form inline class="filter-form">
            <el-form-item label="预警等级">
              <el-select v-model="wFilters.warningLevel" clearable placeholder="全部等级" style="width: 120px" @change="loadRecords(1)">
                <el-option label="一般" value="GENERAL" />
                <el-option label="较重" value="SEVERE" />
                <el-option label="紧急" value="URGENT" />
              </el-select>
            </el-form-item>
            <el-form-item label="审核状态">
              <el-select v-model="wFilters.status" clearable placeholder="全部状态" style="width: 110px" @change="loadRecords(1)">
                <el-option label="待审核" value="PENDING" />
                <el-option label="已发送" value="SENT" />
                <el-option label="已驳回" value="REJECTED" />
              </el-select>
            </el-form-item>
            <el-form-item label="分类">
              <el-select v-model="wFilters.category" clearable placeholder="全部分类" style="width: 120px" @change="loadRecords(1)">
                <el-option v-for="p in primaries" :key="p.code" :label="p.name" :value="p.code" />
              </el-select>
            </el-form-item>
            <el-form-item label="辖区">
              <el-input v-model="wFilters.region" clearable placeholder="全部辖区" style="width: 140px" @keyup.enter="loadRecords(1)" @clear="loadRecords(1)" />
            </el-form-item>
            <el-form-item label="时间范围">
              <el-date-picker v-model="wFilters.timeRange" type="datetimerange" range-separator="至"
                start-placeholder="开始" end-placeholder="结束" format="YYYY-MM-DD HH:mm:ss"
                value-format="YYYY-MM-DD HH:mm:ss" style="width: 340px" @change="loadRecords(1)" />
            </el-form-item>
            <el-form-item>
              <el-button type="primary" @click="loadRecords(1)">查询</el-button>
            </el-form-item>
          </el-form>

          <el-table :data="wRows" v-loading="wLoading" @expand-change="onExpand" :row-style="recordRowStyle">
            <el-table-column type="expand">
              <template #default="{ row }">
                <div class="record-detail">
                  <div class="detail-block">
                    <div class="detail-label">AI 预警总结</div>
                    <div class="detail-text">{{ row.ai_summary || '-' }}</div>
                  </div>
                  <div class="detail-block">
                    <div class="detail-label">AI 应对措施建议</div>
                    <div class="detail-text">{{ row.measure || '-' }}</div>
                  </div>
                  <div class="detail-block">
                    <div class="detail-label">参考优秀处置案例</div>
                    <div class="detail-text">{{ row.ref_cases || '-' }}</div>
                  </div>
                  <div class="ai-label">AI生成内容·仅供参考</div>
                </div>
              </template>
            </el-table-column>
            <el-table-column prop="id" label="ID" width="70" />
            <el-table-column prop="warning_no" label="预警编号" width="150" show-overflow-tooltip>
              <template #default="{ row }">
                <NewDot :visible="row.is_new" />
                <span>{{ row.warning_no }}</span>
              </template>
            </el-table-column>
            <el-table-column label="规则" width="160" show-overflow-tooltip>
              <template #default="{ row }">{{ row.rule_name || row.rule_id }}</template>
            </el-table-column>
            <el-table-column label="预警等级" width="100">
              <template #default="{ row }">
                <el-tag :type="levelTagType(row.warning_level)" size="small">{{ levelLabel(row.warning_level) }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column label="审核状态" width="90">
              <template #default="{ row }">
                <el-tag :type="recordStatusTagType(row.status)" size="small">{{ recordStatusLabel(row.status) }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column label="分类" width="150">
              <template #default="{ row }">{{ row.category ? categoryLabel(row.category) + (row.sub_category ? '-' + row.sub_category : '') : '全部' }}</template>
            </el-table-column>
            <el-table-column prop="region" label="辖区" width="120" show-overflow-tooltip>
              <template #default="{ row }">{{ row.county_name || row.region || '-' }}</template>
            </el-table-column>
            <el-table-column label="推送对象" min-width="180">
              <template #default="{ row }">
                <template v-if="deptEntries(row).length">
                  <el-tooltip :disabled="deptEntries(row).length <= 4" placement="top" :show-after="300">
                    <template #content>
                      <div v-for="t in deptEntries(row)" :key="t">{{ userTypeLabel(t) }}</div>
                    </template>
                    <span>
                      <el-tag v-for="t in deptEntries(row).slice(0, 4)" :key="t" size="small" class="target-tag">{{ userTypeLabel(t) }}</el-tag>
                      <el-tag v-if="deptEntries(row).length > 4" size="small" type="info" effect="plain">+{{ deptEntries(row).length - 4 }}</el-tag>
                    </span>
                  </el-tooltip>
                </template>
                <span v-else>—</span>
              </template>
            </el-table-column>
            <el-table-column prop="related_attention" label="累计关注度" width="100" />
            <el-table-column prop="trigger_time" label="预警时间" width="170" />
            <el-table-column label="响应状态" width="100">
              <template #default="{ row }">
                <el-tag :type="row.respond_status === 'RESPONDED' ? 'success' : 'warning'" size="small">
                  {{ row.respond_status === 'RESPONDED' ? '已响应' : '未响应' }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column prop="respond_time" label="响应时间" width="170">
              <template #default="{ row }">{{ row.respond_time || '-' }}</template>
            </el-table-column>
            <el-table-column label="操作" width="300" fixed="right">
              <template #default="{ row }">
                <template v-if="row.status === 'PENDING'">
                  <el-button link type="primary" size="small" @click="openContentDialog(row)">编辑</el-button>
                  <el-button link type="success" size="small" @click="handleSend(row)">发送</el-button>
                  <el-button link type="danger" size="small" @click="handleReject(row)">驳回</el-button>
                </template>
                <el-button link type="warning" size="small" @click="handleGenerateRule(row)">生成规则</el-button>
                <el-button v-if="row.status === 'SENT'" link type="primary" size="small" :disabled="row.respond_status === 'RESPONDED'" @click="handleRespond(row)">标记已处理</el-button>
                <el-button v-if="row.status === 'SENT'" link type="info" size="small" @click="openDispatchProgress(row)">分发进度</el-button>
              </template>
            </el-table-column>
          </el-table>
          <el-pagination class="pagination" layout="total, prev, pager, next" :total="wTotal"
            :page-size="wPageSize" :current-page="wPage" @current-change="loadRecords" />
        </el-card>
      </el-tab-pane>
      <el-tab-pane label="处置案例库" name="case">
        <el-card shadow="never">
          <div class="toolbar">
            <el-button type="primary" @click="openCaseDialog()">新增处置案例</el-button>
            <el-upload :show-file-list="false" :http-request="handleCaseImport" accept=".xlsx,.xls">
              <el-button :loading="caseImporting">Excel 批量导入</el-button>
            </el-upload>
          </div>

          <el-form inline class="filter-form">
            <el-form-item label="关键词">
              <el-input v-model="cFilters.keyword" clearable placeholder="标题/描述" style="width: 160px" @keyup.enter="loadCases(1)" @clear="loadCases(1)" />
            </el-form-item>
            <el-form-item label="分类">
              <el-select v-model="cFilters.category" clearable placeholder="全部分类" style="width: 120px" @change="loadCases(1)">
                <el-option v-for="p in primaries" :key="p.code" :label="p.name" :value="p.code" />
              </el-select>
            </el-form-item>
            <el-form-item label="辖区">
              <el-input v-model="cFilters.region" clearable placeholder="全部辖区" style="width: 140px" @keyup.enter="loadCases(1)" @clear="loadCases(1)" />
            </el-form-item>
            <el-form-item label="状态">
              <el-select v-model="cFilters.status" clearable placeholder="全部状态" style="width: 110px" @change="loadCases(1)">
                <el-option label="启用" value="ENABLED" />
                <el-option label="停用" value="DISABLED" />
              </el-select>
            </el-form-item>
            <el-form-item>
              <el-button type="primary" @click="loadCases(1)">查询</el-button>
            </el-form-item>
          </el-form>

          <el-table :data="cRows" v-loading="cLoading">
            <el-table-column prop="id" label="ID" width="70" />
            <el-table-column prop="case_no" label="案例编号" width="160" show-overflow-tooltip />
            <el-table-column prop="title" label="问题标题" min-width="160" show-overflow-tooltip />
            <el-table-column label="分类" width="150">
              <template #default="{ row }">{{ row.category ? categoryLabel(row.category) + (row.sub_category ? '-' + row.sub_category : '') : '-' }}</template>
            </el-table-column>
            <el-table-column prop="region" label="辖区" width="120" show-overflow-tooltip />
            <el-table-column prop="measure_plan" label="处置方案" min-width="200" show-overflow-tooltip />
            <el-table-column label="状态" width="90">
              <template #default="{ row }">
                <el-tag :type="row.status === 'ENABLED' ? 'success' : 'info'" size="small">
                  {{ row.status === 'ENABLED' ? '启用' : '停用' }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column label="操作" width="140" fixed="right">
              <template #default="{ row }">
                <el-button link type="primary" size="small" @click="openCaseDialog(row)">编辑</el-button>
                <el-button link type="danger" size="small" @click="removeCase(row)">删除</el-button>
              </template>
            </el-table-column>
          </el-table>
          <el-pagination class="pagination" layout="total, prev, pager, next" :total="cTotal"
            :page-size="cPageSize" :current-page="cPage" @current-change="loadCases" />
        </el-card>
      </el-tab-pane>

      <el-tab-pane name="sensitive">
        <template #label>
          <span>敏感事件 <el-badge :value="sensitiveUnread" :hidden="sensitiveUnread === 0" :max="99" /></span>
        </template>
        <el-card shadow="never">
          <el-form inline class="filter-form">
            <el-form-item label="风险等级">
              <el-select v-model="senFilters.risk_level" clearable placeholder="全部等级" style="width: 120px" @change="loadSensitive(1)">
                <el-option label="一般" value="GENERAL" />
                <el-option label="较重" value="SEVERE" />
                <el-option label="紧急" value="URGENT" />
              </el-select>
            </el-form-item>
            <el-form-item label="处理状态">
              <el-select v-model="senFilters.handle_status" clearable placeholder="全部状态" style="width: 120px" @change="loadSensitive(1)">
                <el-option label="未处理" value="UNHANDLED" />
                <el-option label="已处理" value="HANDLED" />
              </el-select>
            </el-form-item>
            <el-form-item>
              <el-button type="primary" @click="loadSensitive(1)">查询</el-button>
            </el-form-item>
          </el-form>

          <el-table :data="sensitives" v-loading="senLoading">
            <!-- 未读"新"小红点列：统一置于行最左侧 -->
            <el-table-column width="28" align="center">
              <template #default="{ row }">
                <NewDot :visible="row.is_new" />
              </template>
            </el-table-column>
            <el-table-column prop="id" label="ID" width="70" />
            <!-- 诉求来源：剥离模式显示 SE 编号，旧旁路数据回退显示来源诉求ID -->
            <el-table-column label="诉求编号" width="180">
              <template #default="{ row }">{{ row.appeal_no || (row.appeal_id ? '诉求#' + row.appeal_id : '—') }}</template>
            </el-table-column>
            <el-table-column prop="content" label="诉求内容" min-width="220" show-overflow-tooltip />
            <el-table-column prop="citizen_phone" label="市民手机" width="120">
              <template #default="{ row }">{{ row.citizen_phone || '—' }}</template>
            </el-table-column>
            <el-table-column label="地区" width="150" show-overflow-tooltip>
              <template #default="{ row }">{{ [row.district, row.street].filter(Boolean).join(' ') || '—' }}</template>
            </el-table-column>
            <el-table-column prop="risk_type" label="风险类型" width="150" show-overflow-tooltip />
            <el-table-column label="风险等级" width="90">
              <template #default="{ row }">{{ levelLabel(row.risk_level) }}</template>
            </el-table-column>
            <el-table-column label="触发时间" width="170">
              <template #default="{ row }">{{ formatTime(row.trigger_time) }}</template>
            </el-table-column>
            <el-table-column label="处理状态" width="90">
              <template #default="{ row }">{{ handleStatusLabel(row.handle_status) }}</template>
            </el-table-column>
            <el-table-column prop="advice" label="处置建议" min-width="180" show-overflow-tooltip />
            <el-table-column prop="push_targets" label="推送对象" width="100" show-overflow-tooltip />
            <el-table-column label="操作" width="160" fixed="right">
              <template #default="{ row }">
                <el-button link type="primary" size="small" @click="openSensitiveDetail(row)">查看</el-button>
                <el-button v-if="row.handle_status !== 'HANDLED'" type="primary" link @click="handleSensitive(row)">标记已处理</el-button>
                <span v-else>-</span>
              </template>
            </el-table-column>
          </el-table>
          <el-pagination class="pagination" layout="total, prev, pager, next" :total="senTotal"
            :page-size="senPageSize" :current-page="senPage" @current-change="loadSensitive" />
        </el-card>
      </el-tab-pane>
    </el-tabs>

    <!-- 预警规则编辑弹窗（API 8.1） -->
    <el-dialog v-model="ruleVisible" :title="ruleForm.id ? '编辑预警规则' : '新增预警规则'" width="520px">
      <el-form ref="ruleFormRef" :model="ruleForm" :rules="ruleRules" label-width="110px">
        <el-form-item label="规则名称" prop="rule_name">
          <el-input v-model="ruleForm.rule_name" placeholder="如：单日民生类诉求超20条预警" maxlength="100" />
        </el-form-item>
        <el-form-item label="时间维度" prop="time_dimension">
          <el-select v-model="ruleForm.time_dimension" style="width: 100%">
            <el-option label="单日" value="DAY" />
            <el-option label="3日" value="3_DAY" />
            <el-option label="7日" value="7_DAY" />
          </el-select>
        </el-form-item>
        <el-form-item label="一级分类">
          <el-select v-model="ruleForm.category" clearable placeholder="全部分类" style="width: 100%" @change="ruleForm.sub_category = ''">
            <el-option v-for="p in primaries" :key="p.code" :label="p.name" :value="p.code" />
          </el-select>
        </el-form-item>
        <el-form-item label="二级分类">
          <el-autocomplete v-model="ruleForm.sub_category" :fetch-suggestions="fetchSubCategories"
            clearable placeholder="可空；点选已有或直接输入二级分类（如：占道经营）" style="width: 100%" />
        </el-form-item>
        <el-form-item label="辖区">
          <el-input v-model="ruleForm.region" clearable placeholder="全部辖区（如：XX区）" />
        </el-form-item>
        <el-form-item label="累计关注度阈值" prop="count_threshold">
          <el-input-number v-model="ruleForm.count_threshold" :min="1" :max="999999" />
          <span class="threshold-tip">窗口内命中工单的关注度累计达到该值即预警</span>
        </el-form-item>
        <el-form-item label="预警等级" prop="warning_level">
          <el-select v-model="ruleForm.warning_level" style="width: 100%">
            <el-option label="一般" value="GENERAL" />
            <el-option label="较重" value="SEVERE" />
            <el-option label="紧急（多渠道强提醒）" value="URGENT" />
          </el-select>
        </el-form-item>
        <el-form-item label="辖区限制">
          <el-select v-model="ruleForm.push_counties" multiple clearable collapse-tags placeholder="不选 = 全部区县" style="width: 100%">
            <el-option v-for="c in counties" :key="c.region_code" :label="c.region_name" :value="c.region_code" />
          </el-select>
        </el-form-item>
        <el-form-item label="部门类别限制">
          <el-select v-model="ruleForm.push_categories" multiple clearable collapse-tags placeholder="不选 = 全部部门类别" style="width: 100%">
            <el-option v-for="p in primaries" :key="p.code" :label="p.name" :value="p.code" />
          </el-select>
          <span class="threshold-tip">推送部门 = 选定辖区 × 选定类别（都不选=全部56部门）；管理端始终收到提醒</span>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="ruleVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="handleSaveRule">保存</el-button>
      </template>
    </el-dialog>

    <!-- 优秀处置案例编辑弹窗（API 10.12，预警建议 RAG 检索来源） -->
    <el-dialog v-model="caseVisible" :title="caseForm.id ? '编辑处置案例' : '新增处置案例'" width="620px">
      <el-form ref="caseFormRef" :model="caseForm" :rules="caseRules" label-width="110px">
        <el-form-item label="案例编号">
          <el-input v-model="caseForm.case_no" :disabled="!!caseForm.id" placeholder="留空自动生成（WC+日期+序号）" maxlength="50" />
        </el-form-item>
        <el-form-item label="问题标题" prop="title">
          <el-input v-model="caseForm.title" maxlength="200" placeholder="如：某小区违规占用消防通道集中整治" />
        </el-form-item>
        <el-form-item label="一级分类">
          <el-select v-model="caseForm.category" clearable placeholder="请选择分类" style="width: 100%" @change="caseForm.sub_category = ''">
            <el-option v-for="p in primaries" :key="p.code" :label="p.name" :value="p.code" />
          </el-select>
        </el-form-item>
        <el-form-item label="二级分类">
          <el-input v-model="caseForm.sub_category" clearable placeholder="二级分类标签（可空）" maxlength="50" />
        </el-form-item>
        <el-form-item label="所属辖区">
          <el-input v-model="caseForm.region" clearable placeholder="辖区（可空，如：XX区）" maxlength="100" />
        </el-form-item>
        <el-form-item label="诉求描述" prop="content">
          <el-input v-model="caseForm.content" type="textarea" :rows="3" placeholder="用于检索匹配的问题侧描述" />
        </el-form-item>
        <el-form-item label="处置方案" prop="measure_plan">
          <el-input v-model="caseForm.measure_plan" type="textarea" :rows="3" placeholder="优秀处置做法" />
        </el-form-item>
        <el-form-item label="处置效果">
          <el-input v-model="caseForm.measure_result" type="textarea" :rows="2" placeholder="处置效果/结果（可空）" />
        </el-form-item>
        <el-form-item label="状态">
          <el-select v-model="caseForm.status" style="width: 100%">
            <el-option label="启用" value="ENABLED" />
            <el-option label="停用" value="DISABLED" />
          </el-select>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="caseVisible = false">取消</el-button>
        <el-button type="primary" :loading="caseSaving" @click="handleSaveCase">保存</el-button>
      </template>
    </el-dialog>

    <!-- 预警审核内容编辑弹窗（方案B：审核中修订 AI 总结与应对措施） -->
    <el-dialog v-model="contentVisible" title="编辑预警内容" width="620px">
      <el-form ref="contentFormRef" :model="contentForm" label-width="110px">
        <el-form-item label="AI 预警总结">
          <el-input v-model="contentForm.ai_summary" type="textarea" :rows="5" maxlength="2000" show-word-limit placeholder="可修订 AI 生成的预警总结" />
        </el-form-item>
        <el-form-item label="应对措施">
          <el-input v-model="contentForm.measure" type="textarea" :rows="6" maxlength="4000" show-word-limit placeholder="可修订 AI 生成的应对措施建议" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="contentVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="handleSaveContent">保存</el-button>
      </template>
    </el-dialog>

    <!-- 预警分发进度（只读）：已分发街道办及各自响应状态 -->
    <el-dialog v-model="dispatchProgressVisible" title="预警分发进度" width="720px">
      <el-table :data="dispatchProgressRows" v-loading="dispatchProgressLoading" size="small">
        <el-table-column prop="county_name" label="区县" width="130">
          <template #default="{ row }">{{ row.county_name || '-' }}</template>
        </el-table-column>
        <el-table-column prop="street_office" label="街道/乡镇" min-width="140" />
        <el-table-column label="响应状态" width="100">
          <template #default="{ row }">
            <el-tag :type="row.respond_status === 'RESPONDED' ? 'success' : 'warning'" size="small">
              {{ row.respond_status === 'RESPONDED' ? '已响应' : '未响应' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="dispatch_time" label="分发时间" width="170">
          <template #default="{ row }">{{ row.dispatch_time || '-' }}</template>
        </el-table-column>
        <el-table-column prop="respond_time" label="响应时间" width="170">
          <template #default="{ row }">{{ row.respond_time || '-' }}</template>
        </el-table-column>
        <el-table-column prop="respond_user_name" label="响应人" width="110">
          <template #default="{ row }">{{ row.respond_user_name || '-' }}</template>
        </el-table-column>
      </el-table>
      <div v-if="!dispatchProgressLoading && !dispatchProgressRows.length" class="muted" style="text-align:center;margin-top:10px;">尚未分发到街道办</div>
    </el-dialog>

    <!-- 预判参数配置弹窗（API 8.8） -->
    <el-dialog v-model="configVisible" title="修改预判配置" width="440px">
      <el-form ref="configFormRef" :model="configForm" label-width="110px">
        <el-form-item label="预测周期">
          <el-select v-model="configForm.predict_period" style="width: 100%">
            <el-option label="1天" value="DAY" />
            <el-option label="3天" value="3DAY" />
            <el-option label="7天" value="7DAY" />
          </el-select>
        </el-form-item>
        <el-form-item label="置信度阈值">
          <el-input-number v-model="configForm.confidence_threshold" :min="0" :max="1" :step="0.05" :precision="2" />
        </el-form-item>
        <el-form-item label="热点数量">
          <el-input-number v-model="configForm.cluster_count" :min="3" :max="20" />
        </el-form-item>
        <el-form-item label="历史数据范围">
          <el-select v-model="configForm.history_range" style="width: 100%">
            <el-option label="30天" value="30" />
            <el-option label="90天" value="90" />
            <el-option label="180天" value="180" />
            <el-option label="365天" value="365" />
          </el-select>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="configVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="handleSaveConfig">保存</el-button>
      </template>
    </el-dialog>

    <!-- 敏感事件详情（查看对话框）：剥离诉求全字段快照 + 附件（数据来自列表行，无需二次请求） -->
    <el-dialog v-model="sensitiveDetailVisible" title="敏感事件详情" width="760px">
      <el-descriptions v-if="currentSensitiveDetail" :column="2" border size="small">
        <el-descriptions-item label="诉求编号">
          {{ currentSensitiveDetail.appeal_no || (currentSensitiveDetail.appeal_id ? '诉求#' + currentSensitiveDetail.appeal_id : '—') }}
        </el-descriptions-item>
        <el-descriptions-item label="诉求类别">
          {{ currentSensitiveDetail.category ? categoryLabel(currentSensitiveDetail.category) : '—' }}
        </el-descriptions-item>
        <el-descriptions-item label="风险类型">{{ currentSensitiveDetail.risk_type || '—' }}</el-descriptions-item>
        <el-descriptions-item label="风险等级">
          <el-tag :type="levelTagType(currentSensitiveDetail.risk_level)" size="small">{{ levelLabel(currentSensitiveDetail.risk_level) }}</el-tag>
        </el-descriptions-item>
        <el-descriptions-item label="触发时间">{{ formatTime(currentSensitiveDetail.trigger_time) }}</el-descriptions-item>
        <el-descriptions-item label="处理状态">{{ handleStatusLabel(currentSensitiveDetail.handle_status) }}</el-descriptions-item>
        <el-descriptions-item label="推送对象">{{ currentSensitiveDetail.push_targets || '—' }}</el-descriptions-item>
        <el-descriptions-item label="宽泛诉求">{{ currentSensitiveDetail.broad_flag === 1 ? '是（全市范围）' : '否' }}</el-descriptions-item>
        <el-descriptions-item label="市民姓名">{{ currentSensitiveDetail.citizen_name || '—' }}</el-descriptions-item>
        <el-descriptions-item label="市民手机">{{ currentSensitiveDetail.citizen_phone || '—' }}</el-descriptions-item>
        <el-descriptions-item label="省 / 市">
          {{ [currentSensitiveDetail.province, currentSensitiveDetail.city].filter(Boolean).join(' / ') || '—' }}
        </el-descriptions-item>
        <el-descriptions-item label="区县 / 街道">
          {{ [currentSensitiveDetail.district, currentSensitiveDetail.street].filter(Boolean).join(' / ') || '—' }}
        </el-descriptions-item>
        <el-descriptions-item label="社区">{{ currentSensitiveDetail.community || '—' }}</el-descriptions-item>
        <el-descriptions-item label="详细地址">{{ currentSensitiveDetail.address_detail || '—' }}</el-descriptions-item>
        <el-descriptions-item label="完整地址" :span="2">{{ currentSensitiveDetail.region || '—' }}</el-descriptions-item>
        <el-descriptions-item label="诉求内容" :span="2">
          <div class="sensitive-content-text">{{ currentSensitiveDetail.content || '—' }}</div>
        </el-descriptions-item>
        <el-descriptions-item label="AI 处置建议" :span="2">
          <div class="sensitive-content-text">{{ currentSensitiveDetail.advice || '—' }}</div>
        </el-descriptions-item>
        <el-descriptions-item label="附件" :span="2">
          <template v-if="(currentSensitiveDetail.attachments || []).length">
            <el-link v-for="(f, i) in currentSensitiveDetail.attachments" :key="i"
              type="primary" :href="contentUrlOfRaw(f.file_url)" target="_blank" style="margin-right: 12px">
              {{ f.file_name }}（{{ fmtFileSize(f.file_size) }}）
            </el-link>
          </template>
          <span v-else>无</span>
        </el-descriptions-item>
      </el-descriptions>
    </el-dialog>
  </AdminLayout>
</template>

<script setup>
// 预警配置管理：规则停用后不再触发；命中阈值触发后自动调 AI 生成总结与应对措施并站内推送（全程审计留痕）
import { ref, reactive, computed, onMounted, onBeforeUnmount, nextTick, watch } from 'vue'
import { formatTime } from '../../shared/format.js'
import NewDot from '../../shared/NewDot.vue'
import { contentUrlOfRaw } from '../../shared/AttachmentGrid.vue'
import { useRoute } from 'vue-router'
import * as echarts from 'echarts'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Plus } from '@element-plus/icons-vue'
import AdminLayout from '../components/AdminLayout.vue'
import { getUnreadSummary, markModuleRead } from '../api/unread'
import { getPrimaries, getCategories } from '../api/classify'
import { getAdminDailyTrend } from '../api/stats'
import { getCounties, getWarningRules, addWarningRule, updateWarningRule, deleteWarningRule, getWarnings, getSubCategories, scanWarnings, respondWarning, sendWarning, rejectWarning, updateWarningContent, generateRuleFromRecord, getWarningCases, addWarningCase, updateWarningCase, deleteWarningCase, importWarningCases, getSensitiveEvents, handleSensitiveEvent, getPredictionQuery, getPredictionConfig, updatePredictionConfig, runPrediction, createRuleFromPrediction, getWarningDispatches } from '../api/warning'
import { warningRecordStatusLabel as recordStatusLabel, warningRecordStatusColor as recordStatusTagType } from '../../shared/dict'

// ---- 公共字典 ----
const activeTab = ref('rule')
// 支持从待办卡片跳转携带 tab（如 /warnings?tab=sensitive）
const route = useRoute()
const saving = ref(false)

// 预警预判 tab 级未读红点：预警记录 / 敏感事件 各自独立显示，菜单角标 = 两者之和
const tabUnread = ref({})
const warnRecordUnread = computed(() => tabUnread.value.warning_record || 0)
const sensitiveUnread = computed(() => tabUnread.value.sensitive_event || 0)

const loadTabUnread = async () => {
  try { tabUnread.value = await getUnreadSummary() } catch { /* 静默失败 */ }
}

// 点进「预警记录」/「敏感事件」：先加载该 tab 列表（行携带 is_new，与已读游标比较），
// 渲染"新"角标后再上报已读并清本地角标与菜单角标；切换即刷新，保证第二次进入无角标
watch(activeTab, (tab) => {
  if (tab === 'record') {
    loadRecords().finally(() => {
      markModuleRead('warning_record').finally(() => {
        tabUnread.value.warning_record = 0
        window.dispatchEvent(new Event('unread:refresh'))
      })
    })
  } else if (tab === 'sensitive') {
    loadSensitive().finally(() => {
      markModuleRead('sensitive_event').finally(() => {
        tabUnread.value.sensitive_event = 0
        window.dispatchEvent(new Event('unread:refresh'))
      })
    })
  }
})
// 一级分类 code -> 名称（动态来自 classify_primary，8 大一级分类）
const primaries = ref([])
const primaryMap = reactive({})
// 一级分类 code -> 二级标签列表（动态来自 classify_category）
const secondaryMap = reactive({})

const loadClassifyDict = async () => {
  try {
    const list = await getPrimaries()
    primaries.value = (list || []).filter((p) => p.code)
    Object.keys(primaryMap).forEach((k) => delete primaryMap[k])
    primaries.value.forEach((p) => { primaryMap[p.code] = p.name })
  } catch { /* 忽略，下拉为空仅影响展示 */ }
  try {
    const cats = await getCategories()
    Object.keys(secondaryMap).forEach((k) => delete secondaryMap[k])
    ;(cats || []).forEach((c) => {
      if (c.primary_category && c.sub_label) {
        if (!secondaryMap[c.primary_category]) secondaryMap[c.primary_category] = []
        secondaryMap[c.primary_category].push(c.sub_label)
      }
    })
  } catch { /* 忽略 */ }
}

const categoryLabel = (c) => primaryMap[c] || c
const dimensionLabel = (d) => ({ DAY: '单日', '3_DAY': '3日', '7_DAY': '7日' }[d] || d)
const levelLabel = (l) => ({ GENERAL: '一般', SEVERE: '较重', URGENT: '紧急' }[l] || l)
const levelTagType = (l) => ({ GENERAL: 'info', SEVERE: 'warning', URGENT: 'danger' }[l] || 'info')
const ruleStatusLabel = (s) => ({ ENABLED: '启用', DISABLED: '停用', DRAFT: '草稿' }[s] || s)
const ruleStatusTagType = (s) => ({ ENABLED: 'success', DISABLED: 'info', DRAFT: 'warning' }[s] || 'info')
const periodLabel = (p) => ({ DAY: '1天', '3DAY': '3天', '7DAY': '7天', '3_DAY': '3天', '7_DAY': '7天' }[p] || p)
/** 区县code → 区县名（推送限制展示用） */
const countyNameOf = (code) => counties.value.find((c) => c.region_code === code)?.region_name || code
/** 预警记录推送对象：department 逗号串拆数组（空=未配置部门，仅管理端可见） */
const deptEntries = (row) => (row.department ? row.department.split(',').map((s) => s.trim()).filter(Boolean) : [])
/** 推送对象展示名：兼容纯分类code（全市）与「区县:分类」复合键（预警记录 department 解析用） */
const userTypeLabel = (t) => {
  if (t === 'STREET' || t === 'ADMIN') return { STREET: '街道办', ADMIN: '管理员' }[t]
  if (t && t.includes(':')) {
    const [cc, dept] = t.split(':')
    return countyNameOf(cc) + (primaryMap[dept] || dept)
  }
  return primaryMap[t] || t
}

// ---- 预警规则（API 8.1） ----
const ruleLoading = ref(false)
const ruleRows = ref([])

const loadRules = async () => {
  ruleLoading.value = true
  try {
    ruleRows.value = await getWarningRules()
  } finally {
    ruleLoading.value = false
  }
}

// ---- 手动立即扫描（A5）：触发规则扫描后刷新预警记录列表 ----
const scanLoading = ref(false)
const handleScan = async () => {
  scanLoading.value = true
  try {
    const res = await scanWarnings()
    ElMessage.success(`扫描完成，触发预警 ${res?.triggered ?? 0} 条，请到「预警记录」查看`)
    loadRecords()
  } finally {
    scanLoading.value = false
  }
}

const ruleVisible = ref(false)
const ruleFormRef = ref()
const ruleForm = reactive({ id: null, rule_name: '', time_dimension: 'DAY', category: '', sub_category: '', region: '', count_threshold: 20, warning_level: 'SEVERE', push_counties: [], push_categories: [] })
const ruleRules = {
  rule_name: [{ required: true, message: '规则名称不能为空', trigger: 'blur' }],
  time_dimension: [{ required: true, message: '请选择时间维度', trigger: 'change' }],
  count_threshold: [{ required: true, message: '累计关注度阈值不能为空', trigger: 'blur' }],
  warning_level: [{ required: true, message: '请选择预警等级', trigger: 'change' }]
}

const openRuleDialog = (row) => {
  ruleForm.id = row?.id ?? null
  ruleForm.rule_name = row?.rule_name ?? ''
  ruleForm.time_dimension = row?.time_dimension ?? 'DAY'
  ruleForm.category = row?.category ?? ''
  ruleForm.sub_category = row?.sub_category ?? ''
  ruleForm.region = row?.region ?? ''
  ruleForm.count_threshold = Number(row?.count_threshold ?? 20)
  ruleForm.warning_level = row?.warning_level ?? 'SEVERE'
  ruleForm.push_counties = row?.push_counties ? [...row.push_counties] : []
  ruleForm.push_categories = row?.push_categories ? [...row.push_categories] : []
  ruleVisible.value = true
}

/** 二级分类建议：按当前一级分类拉取已用于工单的二级分类 */
const fetchSubCategories = async (query, cb) => {
  const list = await getSubCategories(ruleForm.category || undefined).catch(() => [])
  const suggestions = (list || [])
    .filter((s) => !query || s.includes(query))
    .map((s) => ({ value: s }))
  cb(suggestions)
}

const handleSaveRule = async () => {
  await ruleFormRef.value.validate()
  saving.value = true
  try {
    const body = {
      rule_name: ruleForm.rule_name,
      time_dimension: ruleForm.time_dimension,
      category: ruleForm.category || undefined,
      sub_category: ruleForm.sub_category || undefined,
      region: ruleForm.region || undefined,
      count_threshold: ruleForm.count_threshold,
      warning_level: ruleForm.warning_level,
      push_counties: ruleForm.push_counties.length ? ruleForm.push_counties : undefined,
      push_categories: ruleForm.push_categories.length ? ruleForm.push_categories : undefined
    }
    if (ruleForm.id) {
      await updateWarningRule(ruleForm.id, body)
    } else {
      await addWarningRule(body)
    }
    ElMessage.success('规则已保存')
    ruleVisible.value = false
    loadRules()
  } finally {
    saving.value = false
  }
}

const toggleRule = async (row) => {
  const target = row.status === 'ENABLED' ? 'DISABLED' : 'ENABLED'
  await updateWarningRule(row.id, {
    rule_name: row.rule_name, time_dimension: row.time_dimension, category: row.category || undefined,
    sub_category: row.sub_category || undefined, region: row.region || undefined,
    count_threshold: Number(row.count_threshold), warning_level: row.warning_level,
    push_counties: row.push_counties || undefined, push_categories: row.push_categories || undefined, status: target
  })
  ElMessage.success(target === 'ENABLED' ? '已启用' : '已停用（不再触发）')
  loadRules()
}

const removeRule = async (row) => {
  try {
    await ElMessageBox.confirm(`确定删除预警规则「${row.rule_name}」？`, '删除确认', { type: 'warning' })
  } catch { return }
  await deleteWarningRule(row.id)
  ElMessage.success('已删除')
  loadRules()
}

// ---- 预警记录（API 8.2 + 方案B审核流程） ----
const wLoading = ref(false)
const wRows = ref([])
const wTotal = ref(0)
const wPage = ref(1)
const wPageSize = 20
const wFilters = reactive({ warningLevel: '', category: '', region: '', status: '', timeRange: null })

// 紧急待审核记录置顶提醒（红色底纹，配合后端 PENDING+URGENT 优先排序）
const recordRowStyle = ({ row }) =>
  (row.status === 'PENDING' && row.warning_level === 'URGENT') ? { background: '#fef0f0' } : {}

const loadRecords = async (p = wPage.value) => {
  wPage.value = p
  wLoading.value = true
  const scroller = document.querySelector('main.content')
  const y = scroller ? scroller.scrollTop : null
  try {
    const data = await getWarnings({
      warning_level: wFilters.warningLevel || undefined,
      category: wFilters.category || undefined,
      region: wFilters.region || undefined,
      status: wFilters.status || undefined,
      start_time: wFilters.timeRange?.[0] || undefined,
      end_time: wFilters.timeRange?.[1] || undefined,
      page: p,
      page_size: wPageSize
    })
    wRows.value = data.list || []
    wTotal.value = data.total || 0
  } finally {
    wLoading.value = false
    await nextTick()
    // 双重回写：nextTick 后再于绘制帧补一次，对抗 el-table 内部后置重排导致的滚动重置
    if (scroller && y != null) scroller.scrollTop = y
    requestAnimationFrame(() => { if (scroller) scroller.scrollTop = y })
  }
}

// ---- 审核发送（方案B）：待审核 → 已发送，推送站内消息 ----
const handleSend = async (row) => {
  try {
    await ElMessageBox.confirm(`确认发送预警「${row.warning_no}」并推送至目标部门？`, '发送确认', { type: 'warning' })
  } catch { return }
  await sendWarning(row.id)
  ElMessage.success('预警已发送')
  loadRecords()
}

// ---- 审核驳回（方案B）：待审核 → 已驳回，保留备查 ----
const handleReject = async (row) => {
  try {
    await ElMessageBox.confirm(`确认驳回预警「${row.warning_no}」？驳回后保留备查。`, '驳回确认', { type: 'warning' })
  } catch { return }
  await rejectWarning(row.id)
  ElMessage.success('预警已驳回')
  loadRecords()
}

// ---- 审核中编辑 AI 总结与应对措施（方案B） ----
const contentVisible = ref(false)
const contentFormRef = ref()
const contentForm = reactive({ id: null, ai_summary: '', measure: '' })

const openContentDialog = (row) => {
  contentForm.id = row.id
  contentForm.ai_summary = row.ai_summary || ''
  contentForm.measure = row.measure || ''
  contentVisible.value = true
}

const handleSaveContent = async () => {
  saving.value = true
  try {
    await updateWarningContent(contentForm.id, {
      ai_summary: contentForm.ai_summary || undefined,
      measure: contentForm.measure || undefined
    })
    ElMessage.success('预警内容已更新')
    contentVisible.value = false
    loadRecords()
  } finally {
    saving.value = false
  }
}

// ---- 从预警记录生成预警规则草稿（方案B：驳回记录备查 + 二次利用） ----
const handleGenerateRule = async (row) => {
  try {
    await ElMessageBox.confirm(
      '将基于该预警记录生成一条「草稿」预警规则，需在预警规则列表中人工确认启用后才会参与扫描。是否继续？',
      '生成预警规则', { type: 'info' }
    )
  } catch { return }
  await generateRuleFromRecord(row.id)
  ElMessage.success('已生成草稿规则，请在预警规则列表确认启用')
  loadRules()
}

// ---- 标记预警已响应（需求 11(4)）：关闭预警响应闭环 ----
const handleRespond = async (row) => {
  await respondWarning(row.id)
  ElMessage.success('已标记为已响应')
  loadRecords()
}

// ---- 预警分发进度（只读）：查看已分发街道办及各自响应状态 ----
const dispatchProgressVisible = ref(false)
const dispatchProgressLoading = ref(false)
const dispatchProgressRows = ref([])

const openDispatchProgress = async (row) => {
  dispatchProgressVisible.value = true
  dispatchProgressLoading.value = true
  try {
    dispatchProgressRows.value = await getWarningDispatches(row.id)
  } finally {
    dispatchProgressLoading.value = false
  }
}

const onExpand = () => {} // 展开行查看详情

// ---- 优秀处置案例库（API 10.12，预警建议 RAG 检索来源） ----
const cLoading = ref(false)
const cRows = ref([])
const cTotal = ref(0)
const cPage = ref(1)
const cPageSize = 20
const cFilters = reactive({ keyword: '', category: '', region: '', status: '' })

const loadCases = async (p = cPage.value) => {
  cPage.value = p
  cLoading.value = true
  const scroller = document.querySelector('main.content')
  const y = scroller ? scroller.scrollTop : null
  try {
    const data = await getWarningCases({
      keyword: cFilters.keyword || undefined,
      category: cFilters.category || undefined,
      region: cFilters.region || undefined,
      status: cFilters.status || undefined,
      page: p,
      page_size: cPageSize
    })
    cRows.value = data.list || []
    cTotal.value = data.total || 0
  } finally {
    cLoading.value = false
    await nextTick()
    // 双重回写：nextTick 后再于绘制帧补一次，对抗 el-table 内部后置重排导致的滚动重置
    if (scroller && y != null) scroller.scrollTop = y
    requestAnimationFrame(() => { if (scroller) scroller.scrollTop = y })
  }
}

const caseVisible = ref(false)
const caseSaving = ref(false)
const caseImporting = ref(false)
const caseFormRef = ref()
const caseForm = reactive({ id: null, case_no: '', title: '', category: '', sub_category: '', region: '', content: '', measure_plan: '', measure_result: '', status: 'ENABLED' })
const caseRules = {
  title: [{ required: true, message: '问题标题不能为空', trigger: 'blur' }],
  content: [{ required: true, message: '诉求描述不能为空', trigger: 'blur' }],
  measure_plan: [{ required: true, message: '处置方案不能为空', trigger: 'blur' }]
}

const openCaseDialog = (row) => {
  caseForm.id = row?.id ?? null
  caseForm.case_no = row?.case_no ?? ''
  caseForm.title = row?.title ?? ''
  caseForm.category = row?.category ?? ''
  caseForm.sub_category = row?.sub_category ?? ''
  caseForm.region = row?.region ?? ''
  caseForm.content = row?.content ?? ''
  caseForm.measure_plan = row?.measure_plan ?? ''
  caseForm.measure_result = row?.measure_result ?? ''
  caseForm.status = row?.status ?? 'ENABLED'
  caseVisible.value = true
}

const handleSaveCase = async () => {
  await caseFormRef.value.validate()
  caseSaving.value = true
  try {
    const body = {
      case_no: caseForm.case_no || undefined,
      title: caseForm.title,
      category: caseForm.category || undefined,
      sub_category: caseForm.sub_category || undefined,
      region: caseForm.region || undefined,
      content: caseForm.content,
      measure_plan: caseForm.measure_plan,
      measure_result: caseForm.measure_result || undefined,
      status: caseForm.status
    }
    if (caseForm.id) {
      await updateWarningCase(caseForm.id, body)
    } else {
      await addWarningCase(body)
    }
    ElMessage.success('处置案例已保存')
    caseVisible.value = false
    loadCases()
  } finally {
    caseSaving.value = false
  }
}

const removeCase = async (row) => {
  try {
    await ElMessageBox.confirm(`确定删除处置案例「${row.title}」？`, '删除确认', { type: 'warning' })
  } catch { return }
  await deleteWarningCase(row.id)
  ElMessage.success('已删除')
  loadCases()
}

/** Excel 批量导入处置案例（API 10.12） */
const handleCaseImport = async (option) => {
  caseImporting.value = true
  try {
    const fd = new FormData()
    fd.append('file', option.file)
    const res = await importWarningCases(fd)
    ElMessage.success(`导入完成：成功 ${res?.imported ?? 0} 条，失败 ${res?.failed ?? 0} 条`)
    loadCases()
  } finally {
    caseImporting.value = false
  }
}

// ---- 敏感事件（P0-3 / 需求 9(9)） ----
const handleStatusLabel = (v) => ({ UNHANDLED: '未处理', HANDLED: '已处理' }[v] || v)

const senLoading = ref(false)
const sensitives = ref([])
const senTotal = ref(0)
const senPage = ref(1)
const senPageSize = 20
const senFilters = reactive({ risk_level: '', handle_status: '' })

const loadSensitive = async (p = senPage.value) => {
  senPage.value = p
  senLoading.value = true
  const scroller = document.querySelector('main.content')
  const y = scroller ? scroller.scrollTop : null
  try {
    const data = await getSensitiveEvents({
      risk_level: senFilters.risk_level || undefined,
      handle_status: senFilters.handle_status || undefined,
      page: p,
      page_size: senPageSize
    })
    sensitives.value = data.list || []
    senTotal.value = data.total || 0
  } finally {
    senLoading.value = false
    await nextTick()
    // 双重回写：nextTick 后再于绘制帧补一次，对抗 el-table 内部后置重排导致的滚动重置
    if (scroller && y != null) scroller.scrollTop = y
    requestAnimationFrame(() => { if (scroller) scroller.scrollTop = y })
  }
}

const handleSensitive = async (row) => {
  await handleSensitiveEvent(row.id)
  ElMessage.success('已标记处理')
  loadSensitive()
}

// ---- 敏感事件详情（查看对话框）：全字段快照 + 附件 ----
const sensitiveDetailVisible = ref(false)
const currentSensitiveDetail = ref(null)

const openSensitiveDetail = (row) => {
  currentSensitiveDetail.value = row
  sensitiveDetailVisible.value = true
}

/** 附件大小人性化展示（B/KB/MB） */
const fmtFileSize = (n) => {
  if (n == null) return '-'
  if (n < 1024) return n + ' B'
  if (n < 1024 * 1024) return (n / 1024).toFixed(1) + ' KB'
  return (n / 1024 / 1024).toFixed(1) + ' MB'
}

// ---- 诉求预判（融合进预警管理，需求 9 / API 8.3/8.8） ----
const regionOptions = ref([])
const counties = ref([])   // [{ region_code, region_name }]，56部门复合推送对象用
const loadCounties = async () => {
  try {
    const list = (await getCounties()) || []
    regionOptions.value = list.map((c) => c.region_name)
    counties.value = list.map((c) => ({ region_code: c.region_code, region_name: c.region_name }))
  } catch {
    regionOptions.value = []
    counties.value = []
  }
}
const predLoading = ref(false)
const predRunning = ref(false)
const predUnavailable = ref(false)
const primaryHotspots = ref([])
const secondaryHotspots = ref([])
const predFilters = reactive({ region: '', category: '', subCategory: '', period: '' })
// 预判图表实例与 DOM 引用（独立横向条形图，预测量越大颜色越红）
const primaryChartRef = ref()
const secondaryChartRef = ref()
let primaryChart = null
let secondaryChart = null
// 诉求趋势对比折线图实例与 DOM 引用（独立筛选，最多 5 条线，效果参考部门端首页）
const trendChartRef = ref()
let trendChart = null

const predSecondaryOptions = computed(() => {
  const sel = predFilters.category
  return sel ? (secondaryMap[sel] || []) : []
})

const fmtConfidence = (c) => (c != null ? (Number(c) * 100).toFixed(1) + '%' : '-')

/** 二级分类热点按一级分类分组排序，便于按大类查看其下级细分 */
const sortSecondary = (list) => (list || []).slice().sort((a, b) => {
  if (a.category !== b.category) return String(a.category).localeCompare(String(b.category))
  return (b.predicted_count || 0) - (a.predicted_count || 0)
})

/** 横向条形图：预测量越大颜色越红（插值由浅橙到深红） */
const barColor = (value, max) => {
  if (!max || max <= 0) return '#f56c6c'
  const ratio = value / max
  const r = Math.round(245 - ratio * 130)
  const g = Math.round(108 - ratio * 60)
  return `rgb(${r}, ${g}, 100)`
}

const buildBarOption = (title, rows, nameOf, valueOf) => {
  const source = (rows || []).map((r) => ({ name: nameOf(r), value: valueOf(r) }))
  const valid = source.filter((s) => s.name != null && s.name !== '')
  const max = Math.max(0, ...valid.map((s) => s.value || 0))
  // 横向条形图：y 轴为分类（自下而上），x 轴为预测量
  return {
    title: { text: title, left: 0, textStyle: { fontSize: 13, fontWeight: 600 } },
    tooltip: { trigger: 'axis', axisPointer: { type: 'shadow' } },
    grid: { left: 8, right: 40, top: 36, bottom: 8, containLabel: true },
    xAxis: { type: 'value', minInterval: 1 },
    yAxis: {
      type: 'category',
      data: valid.map((s) => s.name),
      axisLabel: { width: 90, overflow: 'truncate' }
    },
    series: [{
      type: 'bar',
      barMaxWidth: 22,
      itemStyle: { borderRadius: [0, 4, 4, 0] },
      label: { show: true, position: 'right', color: '#303133' },
      data: valid.map((s) => ({
        value: s.value,
        itemStyle: { color: barColor(s.value, max) }
      }))
    }]
  }
}

// ---- 诉求趋势对比折线图（独立筛选，不与诉求预判共用筛选框；最多 5 条线） ----
const MAX_TREND_LINES = 5
const TREND_COLORS = ['#165DFF', '#00B42A', '#FF7D00', '#722ED1', '#F53F3F']
const trendForm = reactive({ category: '', sub: '', region: '', days: 15 })
const trendLines = ref([])   // 已添加折线 { key, name, category, sub, region, days, dates, values }
const trendAdding = ref(false)
// 二级分类选项：依赖所选一级分类（全部分类时不可选二级）
const trendSubOptions = computed(() => (trendForm.category && secondaryMap[trendForm.category]) || [])

const lineColor = (i) => TREND_COLORS[i % TREND_COLORS.length]

/** 切换一级分类时清空二级分类 */
const onTrendCategoryChange = () => {
  trendForm.sub = ''
}

/** 折线命名：一级分类名（空 = 全部分类总括）+ 二级 + 区县 + 时间窗 */
const trendLineName = (category, sub, region, days) => {
  const primary = category ? (primaryMap[category] || category) : '全部分类'
  return `${primary}${sub ? '-' + sub : ''}${region ? '@' + region : ''}·近${days}天`
}

/** 添加一条折线：调 /admin/trends/daily 拉取每日诉求量序列后渲染（最多 5 条） */
const addTrendLine = async () => {
  if (trendLines.value.length >= MAX_TREND_LINES) { ElMessage.warning(`最多同时展示 ${MAX_TREND_LINES} 条折线`); return }
  const key = `${trendForm.category}|${trendForm.sub}|${trendForm.region}|${trendForm.days}`
  if (trendLines.value.some((l) => l.key === key)) { ElMessage.warning('该分类与时间的折线已存在'); return }
  trendAdding.value = true
  try {
    const data = await getAdminDailyTrend({
      days: trendForm.days,
      category: trendForm.category || undefined,
      subCategory: trendForm.sub || undefined,
      region: trendForm.region || undefined
    })
    trendLines.value.push({
      key,
      name: trendLineName(trendForm.category, trendForm.sub, trendForm.region, trendForm.days),
      category: trendForm.category || '',
      sub: trendForm.sub || '',
      region: trendForm.region || '',
      days: trendForm.days,
      dates: data?.dates || [],
      values: data?.values || []
    })
    await nextTick()
    renderTrendChart()
  } catch { /* 接口异常由请求拦截器统一提示 */ } finally { trendAdding.value = false }
}

/** 移除一条折线并重绘 */
const removeTrendLine = (i) => {
  trendLines.value.splice(i, 1)
  renderTrendChart()
}

/** 时间窗口变更：已存在折线保持各自分类/区县不变，按新天数重新拉取并重绘 */
watch(() => trendForm.days, async (days) => {
  if (!trendLines.value.length) return
  await Promise.all(trendLines.value.map(async (l) => {
    try {
      const data = await getAdminDailyTrend({
        days,
        category: l.category || undefined,
        subCategory: l.sub || undefined,
        region: l.region || undefined
      })
      l.key = `${l.category}|${l.sub}|${l.region}|${days}`
      l.name = trendLineName(l.category, l.sub, l.region, days)
      l.days = days
      l.dates = data?.dates || []
      l.values = data?.values || []
    } catch { /* 单条失败保留原数据 */ }
  }))
  // 时间统一后可能出现重复折线（原仅天数不同的两条），去重保留首条
  const seen = new Set()
  trendLines.value = trendLines.value.filter((l) => {
    if (seen.has(l.key)) return false
    seen.add(l.key)
    return true
  })
  renderTrendChart()
})

/** 渲染对比折线：x 轴取最长时间窗（各线窗口不同时窗口外断开为 null） */
const renderTrendChart = () => {
  if (!trendChartRef.value || !trendLines.value.length) return
  // 若实例绑定的 DOM 已被重建，则销毁重建，避免画在失效节点上
  if (trendChart && trendChart.getDom() !== trendChartRef.value) {
    trendChart.dispose()
    trendChart = null
  }
  if (!trendChart) trendChart = echarts.init(trendChartRef.value)
  const maxDays = Math.max(...trendLines.value.map((l) => l.days))
  const base = trendLines.value.find((l) => l.days === maxDays)
  const xAxis = base ? base.dates : []
  const series = trendLines.value.map((l, idx) => ({
    name: l.name,
    type: 'line',
    smooth: true,
    showSymbol: false,
    itemStyle: { color: lineColor(idx) },
    lineStyle: { width: 2 },
    data: xAxis.map((label) => {
      const i = l.dates.indexOf(label)
      return i >= 0 ? l.values[i] : null
    })
  }))
  trendChart.setOption({
    tooltip: { trigger: 'axis' },
    legend: { top: 0, type: 'scroll' },
    grid: { left: 40, right: 20, top: 36, bottom: 28 },
    xAxis: { type: 'category', boundaryGap: false, data: xAxis },
    yAxis: { type: 'value', minInterval: 1, name: '诉求量' },
    series
  }, true)
}

const renderPredCharts = () => {
  const primaryRows = primaryHotspots.value || []
  const secondaryRows = secondaryHotspots.value || []
  // 若实例绑定的 DOM 已被 v-if/v-else 重建，则销毁重建，避免画在失效节点上
  if (primaryChart && primaryChart.getDom() !== primaryChartRef.value) {
    primaryChart.dispose()
    primaryChart = null
  }
  if (secondaryChart && secondaryChart.getDom() !== secondaryChartRef.value) {
    secondaryChart.dispose()
    secondaryChart = null
  }
  if (primaryChartRef.value) {
    if (!primaryChart) primaryChart = echarts.init(primaryChartRef.value)
    primaryChart.setOption(buildBarOption(
      '', primaryRows,
      (r) => categoryLabel(r.category),
      (r) => r.predicted_count
    ), true)
  }
  if (secondaryChartRef.value) {
    if (!secondaryChart) secondaryChart = echarts.init(secondaryChartRef.value)
    secondaryChart.setOption(buildBarOption(
      '', secondaryRows,
      (r) => r.sub_category || categoryLabel(r.category),
      (r) => r.predicted_count
    ), true)
  }
}

/** 窗口尺寸变化时自适应图表宽度 */
const handleResize = () => {
  if (primaryChart) primaryChart.resize()
  if (secondaryChart) secondaryChart.resize()
  if (trendChart) trendChart.resize()
}

const loadPrediction = async () => {
  predLoading.value = true
  try {
    const data = await getPredictionQuery({
      region: predFilters.region || undefined,
      category: predFilters.category || undefined,
      sub_category: predFilters.subCategory || undefined,
      period: predFilters.period || undefined
    })
    primaryHotspots.value = data.primary_hotspots || []
    secondaryHotspots.value = sortSecondary(data.secondary_hotspots)
    predUnavailable.value = !!data.unpredictable
    await nextTick()
    renderPredCharts()
  } finally {
    predLoading.value = false
  }
}

/** 手动重新预判（无需重启后端，成功后刷新看板） */
const handleRunPrediction = async () => {
  predRunning.value = true
  try {
    const res = await runPrediction()
    const degraded = !!res?.degraded
    if (degraded) {
      ElMessage.warning('AI 服务不可用，本次未重新预判（保留上一周期数据）')
    } else {
      ElMessage.success(`重新预判完成，共 ${res?.hotspots ?? 0} 个热点`)
    }
    await loadPrediction()
  } finally {
    predRunning.value = false
  }
}

/** 切换一级分类时重置二级分类并重查 */
const onPredCategoryChange = () => {
  predFilters.subCategory = ''
  loadPrediction()
}

/** 预判热点一键生成草稿规则（DRAFT，人工确认后启用） */
const generateRule = async (row) => {
  try {
    await ElMessageBox.confirm(
      '将基于该预判热点生成一条「草稿」预警规则，需在预警规则列表中人工确认启用后才会参与扫描。是否继续？',
      '生成预警规则', { type: 'info' }
    )
  } catch { return }
  await createRuleFromPrediction({
    category: row.category || undefined,
    sub_category: row.sub_category || undefined,
    region: predFilters.region || undefined,
    predicted_count: row.predicted_count,
    period: predFilters.period || undefined,
    confidence: row.confidence
  })
  ElMessage.success('已生成草稿规则，请在预警规则列表确认启用')
  loadRules()
}

// ---- 预判参数配置（API 8.8） ----
const config = reactive({ predict_period: '7DAY', confidence_threshold: 0.7, cluster_count: 10, history_range: '90' })
const configVisible = ref(false)
const configFormRef = ref()
const configForm = reactive({ predict_period: '7DAY', confidence_threshold: 0.7, cluster_count: 10, history_range: '90' })

const loadConfig = async () => {
  try {
    const data = await getPredictionConfig()
    if (data && Object.keys(data).length) Object.assign(config, data)
  } catch { /* 忽略 */ }
}

const openConfigDialog = () => {
  configForm.predict_period = config.predict_period
  configForm.confidence_threshold = config.confidence_threshold
  configForm.cluster_count = config.cluster_count
  configForm.history_range = config.history_range
  configVisible.value = true
}

const handleSaveConfig = async () => {
  saving.value = true
  try {
    await updatePredictionConfig({
      predict_period: configForm.predict_period,
      confidence_threshold: configForm.confidence_threshold,
      cluster_count: configForm.cluster_count,
      history_range: configForm.history_range
    })
    Object.assign(config, configForm)
    ElMessage.success('配置已保存')
    configVisible.value = false
  } finally {
    saving.value = false
  }
}

onMounted(async () => {
  if (route.query.tab) {
    activeTab.value = String(route.query.tab)
  }
  loadTabUnread()
  await loadClassifyDict()
  await loadCounties()
  loadRules()
  loadPrediction()
  loadConfig()
  loadRecords()
  loadCases()
  loadSensitive()
  window.addEventListener('resize', handleResize)
})

onBeforeUnmount(() => {
  window.removeEventListener('resize', handleResize)
  if (primaryChart) { primaryChart.dispose(); primaryChart = null }
  if (secondaryChart) { secondaryChart.dispose(); secondaryChart = null }
  if (trendChart) { trendChart.dispose(); trendChart = null }
})
</script>

<style scoped>
.toolbar { margin-bottom: 12px; display: flex; align-items: center; gap: 12px; }
.filter-form { margin-bottom: 4px; }
.pagination { margin-top: 16px; justify-content: flex-end; }
.target-tag { margin-right: 6px; }
.push-limit-row { display: flex; align-items: center; flex-wrap: wrap; gap: 2px 0; line-height: 22px; }
.push-limit-label { flex-shrink: 0; margin-right: 6px; font-size: 12px; color: #909399; }
.record-detail { padding: 8px 16px; }
.detail-block { margin-bottom: 8px; }
.detail-label { color: var(--el-text-color-primary); font-weight: 600; font-size: 13px; margin-bottom: 4px; }
.detail-text { color: var(--el-text-color-regular); font-size: 13px; white-space: pre-line; }
.ai-label { color: #f59e0b; font-size: 13px; }
.threshold-tip { margin-left: 10px; font-size: 12px; color: #909399; white-space: nowrap; }
.prediction-section { margin-top: 20px; padding-top: 16px; border-top: 1px solid #ebeef5; }
/* 敏感事件详情：长文本内容区（保留换行、限高滚动） */
.sensitive-content-text { white-space: pre-line; color: var(--el-text-color-regular); font-size: 13px; max-height: 160px; overflow-y: auto; line-height: 1.6; }
.section-title { font-size: 15px; font-weight: 600; color: var(--el-text-color-primary); margin-bottom: 12px; }
.prediction-filters { margin-bottom: 4px; display: flex; flex-wrap: wrap; align-items: flex-end; }
.pred-config-btn { margin-left: auto; }
.prediction-results { display: flex; flex-direction: column; gap: 16px; }
.pred-block-title { font-size: 13px; font-weight: 600; color: var(--el-text-color-primary); margin: 4px 0 8px; }
.pred-chart { width: 100%; height: 220px; margin-bottom: 8px; }
/* 诉求趋势对比折线图（独立筛选，最多5条线） */
.trend-section { margin-top: 20px; padding-top: 16px; border-top: 1px solid #ebeef5; }
.trend-filters { margin-bottom: 8px; }
.trend-btn-icon { margin-right: 4px; }
.trend-lines { display: flex; flex-wrap: wrap; margin-bottom: 8px; }
.trend-line-tag { margin: 0 8px 8px 0; }
.trend-dot { display: inline-block; width: 8px; height: 8px; border-radius: 50%; margin-right: 6px; }
.trend-empty { color: var(--el-text-color-secondary); font-size: 13px; padding: 8px 0 4px; }
.trend-chart { width: 100%; height: 320px; }
/* tab 未读角标：贴 tab 右上角、更小巧精致，与侧边栏菜单角标区分为更紧凑的警示样式 */
:deep(.el-tabs__item .el-badge__content) {
  top: 6px;
  right: -6px;
  transform: translateX(28%) translateY(-45%);
  height: 16px;
  min-width: 16px;
  line-height: 16px;
  padding: 0 5px;
  font-size: 11px;
  border-radius: 8px;
  background: #e5484d;
  border: none;
  box-shadow: 0 1px 4px rgba(229, 72, 77, 0.35);
  font-weight: 500;
  font-feature-settings: 'tnum';
}
</style>