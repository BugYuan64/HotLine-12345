"""智能客服知识库（需求：RAG 问答，Redis Stack 向量库 + BM25 关键词混合检索）

- 每个端一个独立知识库（scope 隔离）：
    citizen 市民端 → 索引 chat_citizen_idx（前缀 chat:citizen:v:）
    staff   部门端 → 索引 chat_staff_idx（前缀 chat:staff:v:）
    street  街道办端 → 索引 chat_street_idx（前缀 chat:street:v:）
- schema：vector(HNSW COSINE) + title/content/keywords(TEXT，BM25 打分) + category(TAG)
- 混合检索：向量 KNN（语义召回）∪ 关键词 BM25（精确词命中），RRF（倒数排名融合）合并
- 中文分词：jieba cut_for_search 后空格连接（RediSearch 默认按空格分词）
- 首次启动：索引为空时自动灌入对应端的内置 FAQ，保证开箱可用
"""
import struct
import uuid

import jieba
from loguru import logger

from app.config import (
    CHAT_SIMILARITY_THRESHOLD, CHAT_TOP_K, EMBEDDING_DIM,
)
from app.core.ai.llm_client import LlmUnavailable, embed


def tokenize(text: str) -> str:
    """中文分词：jieba 搜索引擎模式，空格连接（供 RediSearch TEXT/BM25 使用）"""
    return " ".join(t.strip() for t in jieba.cut_for_search(text) if t.strip())


def extract_keywords(text: str, limit: int = 12) -> list:
    """抽取长度>=2 的前 limit 个词作为关键词（入库留痕 / 关键词检索兜底）"""
    return [t for t in tokenize(text).split() if len(t) >= 2][:limit]


def _doc(title: str, category: str, content: str) -> dict:
    """构造一条知识文档"""
    return {"title": title, "category": category, "content": content}


# ------------------------------------------------------------------ 各端独立内置默认知识库
# 市民端：12345 政务热线公开问答 + 本平台使用指南
DEFAULT_KB_CITIZEN = [
    _doc("12345热线受理范围", "受理范围",
         "12345政务服务便民热线受理：市民在生产生活中的非紧急类求助、政务信息咨询、公共服务咨询、"
         "对政府部门及工作人员履职尽责的批评意见与投诉举报、城市管理和社会治理类问题反映。"
         "本平台按8大一级分类受理：城市管理（占道经营、乱搭乱建、市容环卫、噪音扰民）、交通管理（拥堵、道路设施、停车秩序）、"
         "市场监管（食品安全、价格收费、消费维权）、环境保护（大气/水/噪音污染、违法排污）、住房保障（物业纠纷、房屋质量、保障房）、"
         "劳动保障（欠薪、劳动合同、社保、工伤）、公共服务（停水停电、供暖供气、市政设施）、医疗卫生（医疗服务投诉）。"
         "已进入诉讼、仲裁、行政复议程序的事项，以及110/119/120等紧急类警情不在受理范围内，请直接拨打对应应急电话。"),
    _doc("12345热线服务时间", "服务时间",
         "12345政务服务热线提供7×24小时全天候人工服务，法定节假日正常受理。"
         "您也可以随时通过本平台的智能助手咨询常见问题，或在线提交诉求，工作人员会在工作时间及时受理分派。"),
    _doc("诉求办理进度查询方式", "办理进度",
         "查询办理进度有三种方式：1.登录后在「我的诉求」页面查看本人全部诉求及处理状态；"
         "2.「办理进度」页面输入诉求编号查询（已登录系统按手机号自动核验身份，未登录需输入诉求预留手机号核验）；"
         "3.直接问智能助手，例如说「帮我查一下我的诉求办理进度」，可查到从AI拆分、部门签收、办理到办结的完整流转时间线。"
         "每一步状态变更都会通过站内消息推送通知您。"),
    _doc("在线提交诉求的流程", "诉求提交",
         "在线提交诉求流程：1.登录后进入「提交诉求」页面；2.选择问题分类卡片（城市管理/交通管理/市场监管/环境保护/"
         "住房保障/劳动保障/公共服务/医疗卫生/其他诉求）；3.填写详细描述，写明时间、地点、具体情况及您的诉求；"
         "4.选择所在区县、街道/乡镇并填写详细地址；5.可上传照片等佐证材料；6.提交后系统AI自动拆分、分类并分派至责任部门。"
         "填写不清楚时可用智能助手的「诉求描述智能补全」，AI帮您补全标题描述并提示缺失要素。"),
    _doc("诉求提交后AI如何处理", "AI处理",
         "提交诉求后系统AI自动处理：1.一条诉求包含多个问题时，AI自动拆分为多条子诉求，每条子诉求独立生成工单、"
         "独立办理、独立评价；2.AI按分类体系自动识别一级分类与二级标签并给出置信度，低置信度工单转人工复核，"
         "保证分派准确；3.同源重复诉求自动归并，关注度累加，聚集问题会被优先处置并触发预警。"
         "整体流转：AI拆分分类 → 管理端确认分派责任部门 → 部门签收办理（需要时可下派街道办）→ 处置完成 → 终审办结 → 评价。"),
    _doc("怎么写诉求处理得更快", "填写技巧",
         "让诉求被快速受理的填写要点：1.写清问题发生的时间（如：近一周、每天晚上8点后）；"
         "2.写准地点，区县/街道/详细地址选填到位（如：xx区xx街道xx小区x号楼下）；"
         "3.具体描述问题现象与影响（如：路灯不亮，晚上出行不安全）；4.明确表达您的诉求（如：希望尽快修复）；"
         "5.尽量上传现场照片佐证。一条诉求写多个问题也没关系，AI会自动拆分分别办理。"
         "不确定怎么写时，可在智能助手中说「帮我补全一下诉求描述」，AI根据您输入的部分内容生成补全建议。"),
    _doc("诉求编号与工单编号规则", "编号规则",
         "平台编号规则：父诉求编号AP开头（如AP20260908000001，一条提交对应一个父诉求）；"
         "子诉求编号AS开头（AI拆分出的每个独立问题）；工单编号WO开头（如WO20260908017，每条子诉求对应一张工单）。"
         "查询进度、评价、咨询智能助手时使用这些编号可精确定位；忘记编号可在「我的诉求」或直接问智能助手「我提交过哪些诉求」获取。"),
    _doc("诉求处置结果的评价规则", "评价规则",
         "每条子诉求处置完成后7天内可单独评价（拆分出的各子诉求独立评价互不影响），"
         "评价等级：非常满意/满意/基本满意/不满意/非常不满意。评价提交后不可修改；"
         "超过7天未评价，系统将自动进入部门审核流程。对处置结果不满意或非常不满意时，"
         "可提交「申请重新处理」并说明理由（500字以内），申请提交后由管理员审批，"
         "审批通过工单回退重办，审批结果通过站内消息通知您。"),
    _doc("智能助手能帮我做什么", "智能助手",
         "市民端智能助手小政三大能力：1.查我的诉求列表——直接问「我提交过哪些诉求」，可按编号精确查或分页浏览；"
         "2.查办理进度与流转时间线——报诉求编号（AP/AS开头）或直接说「帮我查一下诉求的办理进度」，"
         "可看到AI拆分→部门签收→办理→办结各节点时间；3.诉求描述智能补全——提交诉求前说「帮我补全一下诉求描述」，"
         "AI补全标题/描述并提示缺失要素（仅生成建议，不会替您提交）。此外可咨询热线受理范围、办理流程、评价规则等知识。"),
    _doc("敏感诉求的处理说明", "敏感诉求",
         "提交的诉求内容命中敏感词时，系统将自动剥离进入敏感事件管理流程，页面提示「已受理」，"
         "该诉求不会生成父诉求、不会拆分、不会生成工单。敏感事件由管理端按流程单独处理。"
         "如认为处理有误或需进一步反映，可拨打12345人工热线咨询。"),
    _doc("紧急情况求助渠道", "应急渠道",
         "紧急情况请直接拨打应急电话：报警110、火警119、急救120、交通事故122。政务热线12345仅受理非紧急类事项，"
         "如遇燃气泄漏、危及人身安全等紧急情况请立即撤离现场并拨打对应应急电话。"),
]

# 部门端：工单处置 / 办理规范 / 时效要求 内参知识
DEFAULT_KB_STAFF = [
    _doc("部门工单签收要求", "工单规范",
         "部门收到分派的工单后应及时签收，签收时限为工作日2小时内。签收后须在规定的办理时限内完成处置并提交办理结果，"
         "逾期未办结将被系统标记为超时工单并影响部门绩效考核。工单到达部门的时间以系统记录为准，"
         "可在工单列表按「待签收」状态筛选查看。"),
    _doc("工单办理时限要求", "工单规范",
         "工单办理时限：一般事项5个工作日，复杂事项10个工作日，经审批可申请延期。"
         "一级工单（高关注度）要求优先处置。涉及多部门协同的工单由主办部门牵头办理并汇总处置结果。"),
    _doc("部门智能助手能帮你做什么", "智能助手",
         "部门端智能助手四大能力：1.查本部门工单列表——可按工单号精确查，也可按关键词/状态/街道办/时间范围过滤，"
         "如说「帮我查一下最近的工单」「查一下状态是待签收的工单」；2.查工单详情——报工单号（WO开头）或工单ID，"
         "如「查一下工单WO20260908017的详情」；3.查工单流转时间线——如「这张工单流转到哪一步了」，"
         "可看分派→签收→下派街道→处置各节点时间；4.查部门工作台汇总——如「看看我们部门的工作台汇总」，"
         "获取待签收/办理中/超时等统计。此外可咨询签收时限、办理规范、退回延期等制度类问题（数据仅限本部门权限内）。"),
    _doc("部门工作台汇总说明", "工作台",
         "部门工作台汇总展示本部门工单核心统计：待签收数、办理中数、已办结数、超时工单数及本月工单趋势。"
         "数据按本部门归属分类实时统计，是安排当日工作的第一入口。不方便打开页面时可直接问智能助手"
         "「看看我们部门的工作台汇总」获取。"),
    _doc("工单等级与处置优先级", "工单规范",
         "工单按关注度（归并的同源子诉求数量）定级：关注度1~2为三级、3~4为二级、5及以上为一级工单。"
         "等级越高代表同类投诉越集中，处置时限越短且要求优先处置；同源子诉求归并时关注度累加并自动触发等级升级。"
         "一级工单通常伴随预警事件，应结合预警页面的应对建议协同处置。"),
    _doc("部门下派街道办流程", "工单规范",
         "工单需要属地力量现场处置时，部门可下派至辖区街道办：工单状态变为「部门分派街道办」（PENDING_DEPT_ASSIGN）→"
         "「街道办处置中」（PENDING_STREET_PROCESS）→ 街道办提交处置结果（STREET_DONE）→ 回部门审核。"
         "部门审核通过后工单转管理端终审；审核不通过可退回街道办重办。街道办退回部门的次数上限为2次，"
         "第3次将强制升级管理员处置。"),
    _doc("低置信度工单人工复核", "工单规范",
         "AI自动分类时对每张工单给出置信度，低于阈值的工单带有「待人工复核」标记。"
         "部门处置前应先核对这类工单的分类是否正确（分类有误会跨部门流转延误办理），"
         "确认分类有误时可联系管理端在分类规则维护中调整关键词与规则，避免同类误分派再次发生。"),
    _doc("工单答复与市民评价", "办理规范",
         "部门办理完毕后应填写办理结果与答复内容，系统自动通知市民。市民在处置完成后7天内按子诉求单独评价"
         "（非常满意/满意/基本满意/不满意/非常不满意），超时未评价系统自动进入部门审核；"
         "市民评价不满意且申请重新处理的，由管理员审批后工单回退重办，请留意站内消息提醒。"),
    _doc("工单退回与延期流程", "办理规范",
         "工单不能满足办理条件或超出本部门职责时，可在规定时限内提交退回申请（附退回理由），由管理员复核后重新分派。"
         "需要延长办理时限的，提交延期申请（附原因与预计办结时间），经管理员审批通过后生效。"
         "退回与延期均有次数上限，频繁退回会影响部门考核，请谨慎使用。"),
    _doc("部门处置结果填写规范", "处置规范",
         "提交处置结果时应包含：1.处置措施与过程；2.最终结果说明；3.处置相关佐证材料（可选）。"
         "答复内容须真实、完整、有针对性，禁止敷衍应付。涉及赔偿/补偿的须说明金额与依据。"
         "处置结果将同步展示给市民并作为满意度评价的依据。"),
    _doc("预警工单应急处置", "应急联动",
         "涉及投诉集中的局部预警事件，承办部门应优先处置并同步上报统筹部门。"
         "同一区域/同一问题聚集举报时，部门须主动排查共性问题并在48小时内反馈处置进展。"
         "可查询历史处置案例辅助制定应对措施（管理端预警详情提供AI处置建议）。"),
]

# 街道办端：辖区网格 / 属地责任 / 街道事项 内参知识
DEFAULT_KB_STREET = [
    _doc("街道办属地责任范围", "属地责任",
         "街道办负责本辖区范围内涉及物业服务、市容环境、社区治理、市政设施等属地管理事项的工单处置。"
         "超出街道职权范围的事项应说明情况后申请退回，由上级部门重新分派。"
         "工单由责任部门下派至街道办，街道办只需处置本辖区权限内的工单。"),
    _doc("街道智能助手能帮你做什么", "智能助手",
         "街道办端智能助手四大能力：1.查本街道工单列表——可按工单号精确查，也可按关键词/状态/时间范围过滤，"
         "如说「查一下分到我们街道的工单」；2.查工单详情——报工单号（WO开头）或工单ID；"
         "3.查工单流转时间线——如「这张工单处置到什么环节了」，可看部门下派→签收→处置各节点时间；"
         "4.查分发到本街道的预警——如「有哪些预警分发到我们街道办」，可按预警等级/二级分类/时间筛选。"
         "此外可咨询属地责任、签收要求、办理时限、网格联动等制度类问题（数据仅限本街道权限内）。"),
    _doc("街道工单签收要求", "工单规范",
         "街道办接收工单后应在工作日内及时签收并核实属地归属。不属于本辖区范围的工单须退回并注明正确属地，"
         "避免工单跨区域流转延误办理。工单到达街道办的时间以系统记录为准，未读红点按到达时间统计。"),
    _doc("街道办理时限与办结", "办理规范",
         "街道办办理时限与部门端一致：一般事项5个工作日、复杂事项10个工作日，可申请延期。"
         "处置完成后在系统提交处置结果（含处置标签与佐证材料），工单转回责任部门审核；"
         "部门审核通过后转管理端终审，终审通过后工单办结并对市民开放评价。"),
    _doc("街道办退回部门规则", "工单规范",
         "工单超出街道办职权（如需执法权、需市级部门统筹）时，可退回责任部门并注明理由。"
         "街道办退回部门的次数上限为2次，第3次系统将强制升级管理员处置，请确保退回理由充分明确。"
         "退回前建议先与部门沟通确认，避免无效流转。"),
    _doc("预警分发响应要求", "预警联动",
         "管理端审核通过的预警事件可分发至辖区相关街道办。街道办收到预警后应在「预警」页面查看预警等级、"
         "分类、辖区范围、AI总结与应对措施建议，联动社区网格员对辖区同类问题优先排查处置，"
         "并将处置进展通过预警联动机制反馈。可随时问智能助手「有哪些预警分发到我们街道办」获取最新列表。"),
    _doc("街道工作台与工单列表", "工作台",
         "街道办工作台展示本街道待签收、处置中、已办结、超时工单等核心统计，是每日开工第一入口。"
         "工单列表支持按状态、时间范围筛选；需要快速检索时可直接问智能助手"
         "「查一下分到我们街道的工单」，支持工单号、关键词、状态等条件过滤。"),
    _doc("社区网格联动处置", "社区治理",
         "涉及小区物业、邻里纠纷、社区公共设施等事项，街道办应联动社区网格员现场核实处置。"
         "网格员发现问题应通过街道办统一登记上报，形成闭环管理。"),
    _doc("街道市政设施上报", "市政设施",
         "辖区道路破损、路灯不亮、井盖缺失等市政设施问题，街道办应先协助设置警示并即时上报，"
         "由城市管理部门安排修复，街道办负责跟进并反馈进展。"),
    _doc("街道应急事件响应", "应急联动",
         "辖区发生燃气泄漏、内涝、危房等应急事件时，街道办应立即组织现场处置并拨打对应应急电话，"
         "同时通过预警模块同步上报上级部门，不得延误。"),
]

# 管理端：平台管理与运营配置 内参知识（专注管理员高频问题）
DEFAULT_KB_ADMIN = [
    _doc("账号审核操作流程", "账号审核",
         "部门/街道办通过注册入口新注册的账号，审核状态默认为待审核（PENDING）、账号状态为未启用（DISABLED），"
         "不会自动出现在待处理账号列表中，需系统管理员在「账号审核」页面人工审核。审核操作：1.进入「账号审核」列表查看待审核账号；"
         "2.点击「通过」，账号置为 APPROVED+ENABLED，可正常登录；3.点击「驳回」并填写驳回原因，注册人可看到原因后重新申请。"
         "支持勾选多条记录批量审核。种子管理员账号 admin001 不经过审核流程，直接为已审核状态。"),
    _doc("部门/街道办注册工号规则", "账号审核",
         "部门端注册工号由后端自动分配：部门代号+顺序序号（如 urban001、traffic001），"
         "由 generateJobNo 接口按该部门最大序号+1 生成，归属类型对应 8 大分派部门之一；"
         "街道办注册工号按所选地区代号+顺序序号（如 tf001）。注册后需管理员审核通过才能登录。"),
    _doc("用户账号的停用与启用", "用户管理",
         "管理端可在「用户账号管理」中查看全部内部账号（含部门与街道办人员），对异常或离职账号执行停用/启用。"
         "停用后该账号无法登录系统但保留历史数据；启用后恢复正常。工号/手机号全局唯一，不可重复注册。"),
    _doc("父诉求与子诉求关系", "诉求管理",
         "市民每提交一条诉求记录为一条父诉求，系统 AI 会把一条父诉求按独立事件拆分为多条子诉求。"
         "子诉求是生成工单的最小单位：一条子诉求对应一张工单（同源重复子诉求会自动归并）。"
         "父诉求状态：PENDING_AI 待拆分 → 拆分中 SPLITTING → 已生成子诉求 CHILDREN_GENERATED。"),
    _doc("AI 自动拆分与自动建单开关", "诉求管理",
         "诉求自动处理由两个开关控制（Redis 配置，管理端可一键开启关闭）：1.config:auto_split_parents 父诉求自动拆分；"
         "2.config:auto_create_orders 子诉求自动生成工单。两个开关同时开启后，系统按 30 秒周期扫描待处理诉求："
         "先 PENDING_AI 父诉求 → AI 拆分生成子诉求，再 PROCESSING 子诉求 → AI 分类自动建单。"
         "关闭自动模式后，可在诉求管理页面对父诉求/子诉求手动勾选触发拆分或建单。"),
    _doc("撤回父诉求拆分", "诉求管理",
         "父诉求已生成子诉求（CHILDREN_GENERATED）后，如需调整可执行「撤回拆分」：系统删除该父诉求下的全部子诉求，"
         "父诉求回退为 PENDING_AI 状态，可重新拆分。注意：子诉求已生成工单的情况下，撤回前需先处理关联工单。"),
    _doc("工单流转状态机", "工单管理",
         "工单全生命周期状态：PENDING_DISPATCH 待确认分派 → PENDING_DEPT_PROCESS 部门处理中 → PENDING_DEPT_ASSIGN 部门分派街道办 "
         "→ PENDING_STREET_PROCESS 街道办处置中 → STREET_DONE 街道办办结 → PENDING_FINAL_AUDIT 终审 → CLOSED 办结 → "
         "ARCHIVED 归档 / EVALUATED 已评价。各环节均留痕至工单流转日志，禁止绕过状态机直接改状态。"),
    _doc("工单分派与退办规则", "工单管理",
         "工单由管理员确认后按 AI 分类结果分派至对应责任部门（8 大部门之一）；部门可再下派至辖区街道办。"
         "签收时限：部门 2 个工作小时内；办理时限：一般事项 5 个工作日、复杂 10 个工作日，可申请延期（上限 2 次）。"
         "部门退回街道办上限 2 次，第 3 次强制升级管理员处置；街道办退回部门同理。超期工单系统自动标记并督办。"),
    _doc("工单等级规则", "工单管理",
         "工单按关注度（归并的同源子诉求数量）定级：关注度 1~2 为三级、3~4 为二级、5 及以上为一级工单。"
         "等级越高处理时限越短（一级 2 个工作日），并要求优先处置。同源子诉求归并时关注度累加并触发等级升级。"),
    _doc("终审与归档操作", "工单管理",
         "工单办结后进入终审：管理员审核承办材料与处置结果，终审通过后工单 CLOSED 办结并对市民开放评价；"
         "终审驳回可退回部门或街道办重新办理。办结满 7 天且已评价的工单自动归档（或管理员手动归档），"
         "归档后工单只读不可再修改。"),
    _doc("预判功能与参数配置", "诉求预判",
         "预判模块按历史工单统计桥数据预测未来各类别诉求数量：管理端进入「预警与预判」可配置预测周期（DAY/3_DAY/7_DAY）、"
         "置信度阈值、聚类数与历史窗口（30/90/180/365 天），支持手动点击「立即预判」触发。"
         "预判依赖 Python AI 服务与 Redis 统计桥，AI 服务不可用时降级为不产出结果（不报错）。"
         "预判结果含热点（分类/辖区/预测数/置信度）与未来趋势曲线，一级分类对应 8 大部门。"),
    _doc("预警规则维护要点", "预警管理",
         "预警规则定义「时间维度窗口内，满足分类/辖区的工单累计关注度达到阈值」即自动触发预警。"
         "维护要点：1.时间维度可选 DAY/3_DAY/7_DAY（窗口越长越保守）；2.分类选 8 大一级分类之一（如 URBAN/LABOR）；"
         "3.阈值过低易误报、过高可能漏报，建议按集中投诉量调试；4.规则可设为 ENABLED/DISABLED/DRAFT（草稿不参与扫描）；"
         "5.也可从预判结果一键生成规则草稿，人工确认后启用。触发后预警进入管理端待审核（PENDING），审核可发送/驳回。"),
    _doc("预警扫描机制", "预警管理",
         "预警扫描由定时任务驱动（默认每日 01:30，预判之后执行），也可管理端手动触发「立即扫描」。"
         "每次扫描遍历全部启用规则：按规则的时间窗口统计匹配工单的累计关注度（attention_count 求和），"
         "达到阈值且当日未触发过该规则，则生成一条待审核预警记录（PENDING），含 AI 总结与应对措施建议。"
         "预警支持分派/推送功能，可向责任人推送站内消息。"),
    _doc("分类体系维护", "分类管理",
         "系统一级分类固定为 8 大部门分类（URBAN 城市管理/TRAFFIC 交通/MARKET 市场监管/ENVIRONMENT 环保/"
         "HOUSING 住建/LABOR 劳动保障/PUBLIC 公共服务/HEALTH 医疗卫生），与部门账号归属类型一一对应。"
         "管理端可维护二级分类标签、AI 分类关键词与规则（命中权重），关键词库与规则库会同步至 Python AI 服务用于工单自动分类。"
         "修改分类体系后工单分类将按新规则重新评估。"),
    _doc("系统审计与日志", "系统审计",
         "管理端「系统审计」集中展示操作审计日志（登录日志、诉求拆分/清洗审计、AI 生成审计、预警操作、工单流转等），"
         "可按操作人/类型/时间筛选。审计日志为追加式留痕，不可删除不可篡改，用于责任追溯与安全合规检查。"),
    _doc("客服知识库文档管理", "智能客服",
         "管理员端智能客服使用独立知识库（chat_admin_idx，与市民端/部门端/街道办端完全隔离），"
         "回答平台管理、工单处置、预判预警配置等管理员高频问题。管理端聊天页面提供知识库文档上传入口，"
         "新文档入库后全量参与检索；LLM 不可用时客服自动降级为引用知识库原文回答，知识未命中时提示功能开发中。"),
    _doc("预判无产出时的排查要点", "诉求预判",
         "预判执行无结果时按以下顺序排查：1.确认后端日志是否提示「AI 服务不可用」（Python 8000 服务是否运行）；"
         "2.确认工单表存在近期（预判历史窗口内）且分类/辖区字段非空的工单用于统计；"
         "3.确认 Redis 统计桥 stats:daily:* 与 predict:texts 是否生成；4.预判置信度阈值不宜过高，"
         "历史数据稀疏时建议阈值降到 0.3 以下再试；5.历史数据过于平摊（每天每类都很少）时预测数取整可能为 0，"
         "建议工单时间相对集中以便 AI 学到规律。"),
    _doc("预警不触发的常见排查", "预警管理",
         "预警扫描后无预警记录时按以下顺序排查：1.确认启用规则存在（ENABLED 状态）且分类未使用已废弃的分类值；"
         "2.确认时间维度窗口内匹配分类+辖区的工单累计关注度达到阈值（可临时调低阈值验证）；"
         "3.确认工单的辖区（region）字段不为空且与规则辖区前缀一致；4.同一规则当日已触发过则不会重复触发（每日限一次）。"),
    _doc("管理端智能助手能帮你做什么", "智能助手",
         "管理端智能助手能力分两类。【数据查询】1.全平台工单列表（工单号/分类/等级/辖区/状态/关键词/归档多条件过滤）；"
         "2.工单全链路信息（督办/流转/处理全要素）；3.父诉求列表与子诉求明细；4.审计日志（系统/部门/街道办三视图，"
         "按类型/操作人/模块/时间过滤）；5.预警记录与预警规则；6.预判热点/未来趋势/预判准确率；"
         "7.内部账号列表与待审核账号；8.全局待办汇总（待审核账号/待分派/待终审数量）；9.自动处理开关状态。【快捷操作】"
         "10.知识库文档入库（说「把一篇知识写入知识库」并提供标题与正文）；11.手动触发预警扫描；12.手动触发诉求预判。"
         "数据类问题助手会实时调用工具查询真实数据，不会编造。"),
    _doc("市民评价与重开审批", "工单管理",
         "工单办结后市民可按子诉求在处置完成7天内评价（非常满意/满意/基本满意/不满意/非常不满意），"
         "超时未评价系统自动进入部门审核。市民评价不满意/非常不满意时可提交「申请重新处理」（500字以内理由），"
         "重开申请状态：PENDING 待审批 → APPROVED 通过（工单回退重办）/ REJECTED 驳回，"
         "审批入口在管理端工单/评价相关页面，审批结果通过站内消息通知市民。"),
    _doc("部门与街道办审计日志", "系统审计",
         "管理端「系统审计」支持三视图：平台系统日志、部门审计、街道办审计。部门审计记录部门端登录、"
         "工单签收、处置提交、退回延期等操作；街道办审计记录街道办端登录、工单处置等操作。"
         "可按日志类型、操作人、模块、部门、区县、诉求关键词、时间范围筛选，用于责任追溯与考核依据。"),
]


# 各端默认知识库映射
DEFAULT_KB_BY_SCOPE = {
    "citizen": DEFAULT_KB_CITIZEN,
    "staff": DEFAULT_KB_STAFF,
    "street": DEFAULT_KB_STREET,
    "admin": DEFAULT_KB_ADMIN,
}

# scope 白名单
SCOPES = ("citizen", "staff", "street", "admin")


class ChatKnowledgeBase:
    """智能客服知识库（按端隔离）：向量 + BM25 混合检索（RRF 融合）"""

    def __init__(self, redis_vector, redis_text, scope: str = "citizen"):
        if scope not in SCOPES:
            raise ValueError(f"未知知识库 scope: {scope}（可选 {SCOPES}）")
        self.scope = scope
        self.index = f"chat_{scope}_idx"   # 独立索引：chat_citizen_idx / chat_staff_idx / chat_street_idx
        self.prefix = f"chat:{scope}:v:"   # 独立 key 前缀：chat:citizen:v: / chat:staff:v: / chat:street:v:
        self.rv = redis_vector      # 二进制客户端（向量字节）
        self.rt = redis_text        # 文本客户端（FT.SEARCH BM25 / 计数）
        self._index_ready = False

    # ------------------------------------------------------------------ 索引
    def ensure_index(self):
        """确保当前 scope 的向量索引存在（不存在则创建）"""
        try:
            self.rt.execute_command("FT.INFO", self.index)
            self._index_ready = True
            logger.info("客服知识库索引 {} 已存在（scope={}）", self.index, self.scope)
            return
        except Exception:
            pass
        try:
            self.rt.execute_command(
                "FT.CREATE", self.index,
                "ON", "HASH", "PREFIX", "1", self.prefix,
                "SCHEMA",
                "vector", "VECTOR", "HNSW", "6",
                "TYPE", "FLOAT32", "DIM", EMBEDDING_DIM, "DISTANCE_METRIC", "COSINE",
                "title", "TEXT",
                "content", "TEXT",
                "keywords", "TEXT",
                "category", "TAG",
            )
            self._index_ready = True
            logger.info("客服知识库索引 {} 创建成功（scope={}, dim={}）", self.index, self.scope, EMBEDDING_DIM)
        except Exception as e:
            logger.warning("客服知识库索引创建失败（scope={}），RAG 问答降级：{}", self.scope, e)

    # ------------------------------------------------------------------ 入库
    def add_doc(self, title: str, content: str, category: str = "") -> dict:
        """知识文档入库：分词 → Embedding → 写入当前 scope 向量索引；返回 {doc_id, keywords}"""
        if not self._index_ready:
            raise RuntimeError(f"向量索引不可用（scope={self.scope}）")
        keywords = extract_keywords(title + "。" + content)
        vectors = embed([title + "\n" + content])
        if not vectors:
            raise RuntimeError("向量化失败")
        doc_id = uuid.uuid4().hex[:16]
        self.rv.hset(f"{self.prefix}{doc_id}", mapping={
            "vector": struct.pack(f"{len(vectors[0])}f", *vectors[0]),
            "title": title,
            "content": content,
            "keywords": " ".join(keywords),
            "category": category or "",
        })
        logger.info("知识文档入库 scope={} doc_id={} title={}", self.scope, doc_id, title)
        return {"doc_id": doc_id, "keywords": keywords}

    def doc_count(self) -> int:
        """索引内文档总数（用于首次启动自动灌库判断）

        必须使用二进制客户端 self.rv 执行：FT.SEARCH 可能返回含中文等非 ASCII 的原始内容，
        文本客户端（decode_responses=True）会在解码阶段抛 UnicodeDecodeError，导致计数恒为 0、
        每次启动都重复灌库。兼容新版 redis-py 的 dict 返回（键可能为 bytes/str）。
        """
        try:
            rows = self.rv.execute_command(
                "FT.SEARCH", self.index, "*", "LIMIT", "0", "1", "DIALECT", "2")
            if isinstance(rows, dict):
                total = rows.get(b"total_results", rows.get("total_results", 0))
                return int(total)
            return int(rows[0])
        except Exception:
            return 0

    def seed_default_if_empty(self):
        """索引为空时自动灌入当前 scope 的内置 FAQ（无 Embedding 能力时跳过，配置密钥后重启生效）"""
        kb = DEFAULT_KB_BY_SCOPE.get(self.scope, [])
        try:
            if self.doc_count() > 0:
                logger.info("客服知识库已有文档（scope={}），跳过内置知识灌库", self.scope)
                return
            for item in kb:
                try:
                    self.add_doc(item["title"], item["content"], item["category"])
                except LlmUnavailable:
                    logger.warning("Embedding 不可用，内置知识灌库中止（scope={}，配置 DASHSCOPE_API_KEY 后重启自动重试）", self.scope)
                    return
            logger.info("内置客服知识灌库完成 scope={} 共 {} 条", self.scope, len(kb))
        except Exception as e:
            logger.warning("内置知识灌库异常（scope={}，忽略）：{}", self.scope, e)

    # ------------------------------------------------------------------ 混合检索
    def _knn(self, vector: list, k: int) -> list:
        """向量 KNN 语义召回 → [{id, title, content, category}]（rank 从 1 开始）"""
        blob = struct.pack(f"{len(vector)}f", *vector)
        rows = self.rv.execute_command(
            "FT.SEARCH", self.index,
            f"*=>[KNN {k} @vector $v AS score]",
            "PARAMS", "2", "v", blob,
            "RETURN", "4", "title", "content", "category", "score",
            "SORTBY", "score", "ASC",
            "DIALECT", "2",
        )
        out = []
        for i in range(1, len(rows), 2):
            fields = rows[i + 1] if i + 1 < len(rows) else []
            doc = {}
            for j in range(0, len(fields) - 1, 2):
                key = fields[j].decode() if isinstance(fields[j], bytes) else str(fields[j])
                val = fields[j + 1].decode() if isinstance(fields[j + 1], bytes) else fields[j + 1]
                doc[key] = val
            try:
                similarity = 1.0 - float(doc.get("score", 1))
            except (TypeError, ValueError):
                similarity = 0.0
            if similarity < CHAT_SIMILARITY_THRESHOLD:
                continue
            out.append({
                "id": rows[i].decode() if isinstance(rows[i], bytes) else str(rows[i]),
                "title": doc.get("title", ""),
                "content": doc.get("content", ""),
                "category": doc.get("category", ""),
                "similarity": round(similarity, 4),
            })
        return out

    def _bm25(self, question: str, k: int) -> list:
        """关键词 BM25 精确召回（jieba 分词 OR 查询，RediSearch TEXT 字段默认 BM25 排序）"""
        terms = [t for t in tokenize(question).split() if len(t) >= 2][:12]
        if not terms:
            return []
        query = " | ".join(terms)
        rows = self.rt.execute_command(
            "FT.SEARCH", self.index, query,
            "RETURN", "3", "title", "content", "category",
            "LIMIT", "0", str(k),
            "DIALECT", "2",
        )
        out = []
        for i in range(1, len(rows), 2):
            fields = rows[i + 1] if i + 1 < len(rows) else []
            doc = {}
            for j in range(0, len(fields) - 1, 2):
                key = fields[j].decode() if isinstance(fields[j], bytes) else str(fields[j])
                val = fields[j + 1].decode() if isinstance(fields[j + 1], bytes) else fields[j + 1]
                doc[key] = val
            out.append({
                "id": rows[i].decode() if isinstance(rows[i], bytes) else str(rows[i]),
                "title": doc.get("title", ""),
                "content": doc.get("content", ""),
                "category": doc.get("category", ""),
                "similarity": None,
            })
        return out

    def search(self, question: str, k: int = None) -> list:
        """混合检索：向量 KNN ∪ BM25 关键词，RRF（Reciprocal Rank Fusion, k=60）融合排序"""
        k = k or CHAT_TOP_K
        vec_docs, kw_docs = [], []
        try:
            vec_docs = self._knn(embed([question])[0], k)
        except LlmUnavailable:
            raise  # Embedding 不可用：交给上层降级
        except Exception as e:
            logger.warning("客服向量检索失败（scope={}，降级仅关键词）：{}", self.scope, e)
        try:
            kw_docs = self._bm25(question, k)
        except Exception as e:
            logger.warning("客服关键词检索失败（scope={}）：{}", self.scope, e)

        # RRF 融合：score = Σ 1/(60+rank)；同文档去重合并
        rrf: dict = {}
        for rank, doc in enumerate(vec_docs, start=1):
            rrf.setdefault(doc["id"], {"doc": doc, "score": 0.0})
            rrf[doc["id"]]["score"] += 1.0 / (60 + rank)
        for rank, doc in enumerate(kw_docs, start=1):
            entry = rrf.setdefault(doc["id"], {"doc": doc, "score": 0.0})
            entry["score"] += 1.0 / (60 + rank)
            if entry["doc"].get("similarity") is None:
                entry["doc"]["similarity"] = doc.get("similarity")

        merged = sorted(rrf.values(), key=lambda x: x["score"], reverse=True)
        return [item["doc"] for item in merged[:k]]