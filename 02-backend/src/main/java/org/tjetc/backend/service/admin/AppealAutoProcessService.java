package org.tjetc.backend.service.admin;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.tjetc.backend.common.BusinessException;
import org.tjetc.backend.common.LoginUser;
import org.tjetc.backend.common.ResultCode;
import org.tjetc.backend.common.UserContext;
import org.tjetc.backend.dto.ai.AiAuditResult;
import org.tjetc.backend.dto.ai.AiClassifyResult;
import org.tjetc.backend.dto.ai.AiDetectCategory;
import org.tjetc.backend.dto.ai.AiSummarizeResult;
import org.tjetc.backend.dto.ai.AiDetectResult;
import org.tjetc.backend.dto.ai.AiSplitSuggestion;
import org.tjetc.backend.entity.citizen.AppealChild;
import org.tjetc.backend.entity.citizen.AppealParent;
import org.tjetc.backend.entity.common.WorkOrder;
import org.tjetc.backend.mapper.admin.AdminWorkOrderMapper;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.tjetc.backend.mapper.common.WorkOrderAppealMapper;
import org.tjetc.backend.mapper.citizen.AppealChildMapper;
import org.tjetc.backend.mapper.citizen.AppealParentMapper;
import org.tjetc.backend.service.common.AiServiceClient;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * 诉求自动处理闭环（定时任务驱动）：
 * 阶段0：扫描 PENDING_AI 父诉求（AI标题生成中）→ AI 总结标题 → TITLE_GENERATED（未拆分，列表可见）
 * 阶段1：扫描 TITLE_GENERATED 父诉求 → AI detect 多类别检测与拆分 → 每条子诉求
 *        继承父类别 → 清洗 → AI 审核（只审一级分类；OTHER 走 AI 一级分类）→ 不一致则 AI 纠正一级
 *        → AI 二级分类（CLASSIFYING_SUB，一级已定仅选二级标签，落库 sub_category/confidence/need_review）
 *        → AI 总结子诉求标题 → 入库（READY，列表可见）→ 全部完成后父诉求置为 CHILDREN_GENERATED（已拆分）
 *        拆分与流水线各环节实时更新父诉求状态（CLEANING/AUDITING/CORRECTING/CLASSIFYING/CLASSIFYING_SUB/TITLING）并 SSE 推送
 * 阶段2：扫描 READY 子诉求（待生成工单）→ AI classify 分类 → 自动生成工单（来源=子诉求）→ 子诉求置为 ORDERED
 * 降级策略：detect 无拆分建议时整条原文作为 1 条子诉求；audit/classify/summarize 不可用时
 *         保留继承类别/截断标题/标记待人工复核，均不阻断流转
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AppealAutoProcessService {

    private final AppealParentMapper appealParentMapper;
    private final AppealChildMapper appealChildMapper;
    private final AiServiceClient aiServiceClient;
    private final OrderService orderService;
    private final StringRedisTemplate redisTemplate;
    private final AuditLogService auditLogService;
    private final SseEventService sseEventService;
    private final AdminWorkOrderMapper adminWorkOrderMapper;
    private final WorkOrderAppealMapper workOrderAppealMapper;

    /** 8 大类（classify_primary code）：AI 分类纠正结果限定范围，OTHER 为市民端“其他”枚举 */
    private static final java.util.Set<String> VALID_CATEGORIES = java.util.Set.of(
            "URBAN", "TRAFFIC", "MARKET", "ENVIRONMENT",
            "HOUSING", "LABOR", "PUBLIC", "HEALTH");

    private static final String KEY_AUTO_SPLIT = "config:auto_split_parents";
    private static final String KEY_AUTO_ORDER = "config:auto_create_orders";
    private static final String KEY_AUTO_SPLIT_OPERATOR = "config:auto_split_operator";
    private static final String KEY_AUTO_ORDER_OPERATOR = "config:auto_order_operator";
    /** 工单自动派单开关（Redis）：开启后定时扫描「待分派且已确定部门」的工单自动派单到部门 */
    private static final String KEY_AUTO_DISPATCH = "config:auto_dispatch";
    private static final String KEY_AUTO_DISPATCH_OPERATOR = "config:auto_dispatch_operator";

    /** 拆分任务注册表（内存）：taskId -> 任务进度，供前端轮询 */
    private final Map<String, SplitTask> splitTasks = new ConcurrentHashMap<>();

    /** 生成工单任务注册表（内存）：taskId -> 任务进度，供前端轮询 */
    private final Map<String, CreateOrderTask> createOrderTasks = new ConcurrentHashMap<>();

    /** 拆分任务进度（异步拆分用） */
    public static class SplitTask {
        public final String taskId;
        public final int total;
        public volatile boolean finished = false;
        public final List<Map<String, Object>> results =
                java.util.Collections.synchronizedList(new ArrayList<>());
        public SplitTask(String taskId, int total) {
            this.taskId = taskId;
            this.total = total;
        }
        public int doneCount() {
            return results.size();
        }
    }

    /** 生成工单任务进度（异步建单用） */
    public static class CreateOrderTask {
        public final String taskId;
        public final int total;
        public volatile boolean finished = false;
        public final List<Map<String, Object>> results =
                java.util.Collections.synchronizedList(new ArrayList<>());
        public CreateOrderTask(String taskId, int total) {
            this.taskId = taskId;
            this.total = total;
        }
        public int doneCount() {
            return results.size();
        }
    }

    /** 自动模式任务注册表（每类最多一个） */
    private volatile AutoTask autoSplitTask;
    private volatile AutoTask autoCreateOrderTask;
    /** 自动派单任务（工单自动分派到部门） */
    private volatile AutoTask autoDispatchTask;
    /** AI 流水线任务（待AI审核 → 审核/纠正/分类/标题 → 待生成工单） */
    private volatile AutoTask auditPipelineTask;
    /** 自动派单开关开启时间戳（仿自动拆分：开启后等满一个周期才检测） */
    private volatile long autoDispatchEnabledAt = 0;

    /** 标题单条调度器：每个父/子诉求出现后 3 秒自动生成标题（后端计时，不受开关影响） */
    /** 标题流水线单实例标志：保证持续消费循环只有一个 */
    private final java.util.concurrent.atomic.AtomicBoolean titlePipelineRunning =
            new java.util.concurrent.atomic.AtomicBoolean(false);
    /** 标题流水线循环体线程（单线程：负责“扫批→并行处理→再扫”的循环） */
    private final java.util.concurrent.ExecutorService titlePipelineRunner =
            java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "title-pipeline-runner");
                t.setDaemon(true);
                return t;
            });
    /** 标题并行处理池（共享 10 线程）：批内并行，走一个补一个 */
    private final java.util.concurrent.ExecutorService titlePipelineExecutor =
            java.util.concurrent.Executors.newFixedThreadPool(10, r -> {
                Thread t = new Thread(r, "title-pipeline");
                t.setDaemon(true);
                return t;
            });

    /** 子诉求标题流水线（子→工单 队列1 起点）：独立于父诉求标题流水线，父子互不抢占、互不阻塞 */
    private final java.util.concurrent.atomic.AtomicBoolean childTitlePipelineRunning =
            new java.util.concurrent.atomic.AtomicBoolean(false);
    /** 子诉求标题流水线循环体线程 */
    private final java.util.concurrent.ExecutorService childTitlePipelineRunner =
            java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "child-title-pipeline-runner");
                t.setDaemon(true);
                return t;
            });
    /** 子诉求标题并行处理池（独立 6 线程） */
    private final java.util.concurrent.ExecutorService childTitlePipelineExecutor =
            java.util.concurrent.Executors.newFixedThreadPool(6, r -> {
                Thread t = new Thread(r, "child-title-pipeline");
                t.setDaemon(true);
                return t;
            });

    /** 即时流水线线程池（有界 4 线程）：标题完成后的审核/分类即时触发，AI 失败快速回滚，由周期任务兜底 */
    private final java.util.concurrent.ExecutorService immediatePipelineExecutor =
            java.util.concurrent.Executors.newFixedThreadPool(10, r -> {
                Thread t = new Thread(r, "immediate-pipeline");
                t.setDaemon(true);
                return t;
            });

    private final java.util.concurrent.ScheduledExecutorService titleScheduler =
            java.util.concurrent.Executors.newScheduledThreadPool(10, r -> {
                Thread t = new Thread(r, "title-scheduler");
                t.setDaemon(true);
                return t;
            });

    /** 自动模式任务进度（按需取：无内存等待队列，totalCount 动态累加已取出数） */
    public static class AutoTask {
        public final String taskId;
        public final int concurrency;
        public volatile int totalCount = 0;
        public final java.util.Set<Long> inProgressIds = java.util.concurrent.ConcurrentHashMap.newKeySet();
        public final List<Map<String, Object>> results = java.util.Collections.synchronizedList(new ArrayList<>());
        public volatile boolean cancelled = false;
        public volatile boolean finished = false;
        public AutoTask(String taskId, int concurrency) {
            this.taskId = taskId;
            this.concurrency = concurrency;
        }
        public int doneCount() { return results.size(); }
        public int inProgressCount() { return inProgressIds.size(); }
    }

    /** 已分类待落库的子诉求（流水线分类→落库之间的载体） */
    private static class ClassifiedChild {
        final AppealChild child;
        final AiClassifyResult classify;
        ClassifiedChild(AppealChild child, AiClassifyResult classify) {
            this.child = child;
            this.classify = classify;
        }
    }

    private static final DateTimeFormatter NO_TS = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
    /** 每轮最多处理诉求数，避免单次扫描过载 */
    private static final int BATCH_SIZE = 50;
    /** 单个父诉求最多拆分子诉求条数（与项目「批量拆分/建单上限 10」规则一致，防止 AI 返回超量子诉求） */
    private static final int MAX_PARENT_SPLIT = 10;

    /** 自动模式检测周期（毫秒），可配置 */
    @Value("${app.auto.process-interval-ms:15000}")
    private long autoProcessIntervalMs;
    /** 自动拆分开关开启时间戳（用于「开启后等满一个周期才检测」） */
    private volatile long autoSplitEnabledAt = 0;
    private volatile long autoOrderEnabledAt = 0;

    /** 自动处理定时任务：到周期才驱动；开启后需等满一个周期才第一次检测 */
    @Scheduled(fixedDelayString = "${app.auto.process-interval-ms:15000}")
    public void scheduledAutoProcess() {
        try {
            long now = System.currentTimeMillis();
            // 标题阶段不受开关影响：兜底扫描（主路径为每条诉求出现后 3 秒的单条调度）
            titlePendingPipeline();          // 父诉求标题队列
            childTitlePipeline();            // 子诉求标题队列（子→工单 队列1，独立于父标题）
            if (isAutoSplitEnabled()) {
                if (autoSplitEnabledAt == 0) {
                    autoSplitEnabledAt = now; // 应用重启后开关已开：先记录时间戳，等下一轮
                } else if ((autoSplitTask == null || autoSplitTask.finished)
                        && now - autoSplitEnabledAt >= autoProcessIntervalMs) {
                    // 拆分 + AI 流水线阶段：受自动模式开关控制
                    startAutoSplit();
                    startAuditPipeline();
                }
            }
            if (isAutoOrderEnabled()) {
                if (autoOrderEnabledAt == 0) {
                    autoOrderEnabledAt = now;
                } else if ((autoCreateOrderTask == null || autoCreateOrderTask.finished)
                        && now - autoOrderEnabledAt >= autoProcessIntervalMs) {
                    startAutoCreateOrders();
                }
            }
            // 自动派单：开启后每周期扫描「待分派且已确定部门」的工单自动分派到对应部门
            if (isAutoDispatchEnabled()) {
                if (autoDispatchEnabledAt == 0) {
                    autoDispatchEnabledAt = now;
                } else if ((autoDispatchTask == null || autoDispatchTask.finished)
                        && now - autoDispatchEnabledAt >= autoProcessIntervalMs) {
                    startAutoDispatch();
                }
            }
        } catch (Exception e) {
            log.warn("诉求自动处理异常：{}", e.getMessage());
        }
    }

    public boolean isAutoSplitEnabled() {
        return "true".equals(redisTemplate.opsForValue().get(KEY_AUTO_SPLIT));
    }

    public boolean isAutoOrderEnabled() {
        return "true".equals(redisTemplate.opsForValue().get(KEY_AUTO_ORDER));
    }

    public void setAutoSplit(boolean enabled) {
        redisTemplate.opsForValue().set(KEY_AUTO_SPLIT, String.valueOf(enabled));
        // 开启时记录时间戳：定时任务需等满一个周期后才第一次检测
        autoSplitEnabledAt = enabled ? System.currentTimeMillis() : 0;
        // 记录开启者：自动拆分也归属到开启开关的管理员账号
        LoginUser user = UserContext.get();
        if (enabled && user != null && user.jobNo() != null && !user.jobNo().isBlank()) {
            redisTemplate.opsForValue().set(KEY_AUTO_SPLIT_OPERATOR, user.jobNo());
        } else if (!enabled) {
            redisTemplate.delete(KEY_AUTO_SPLIT_OPERATOR);
        }
    }

    public void setAutoOrder(boolean enabled) {
        redisTemplate.opsForValue().set(KEY_AUTO_ORDER, String.valueOf(enabled));
        autoOrderEnabledAt = enabled ? System.currentTimeMillis() : 0;
        // 记录开启者：自动建单也归属到开启开关的管理员账号
        LoginUser user = UserContext.get();
        if (enabled && user != null && user.jobNo() != null && !user.jobNo().isBlank()) {
            redisTemplate.opsForValue().set(KEY_AUTO_ORDER_OPERATOR, user.jobNo());
        } else if (!enabled) {
            redisTemplate.delete(KEY_AUTO_ORDER_OPERATOR);
        }
    }

    /** 自动拆分操作人：开启开关的管理员账号，未记录时回退 SYSTEM */
    private String autoSplitOperator() {
        String jobNo = redisTemplate.opsForValue().get(KEY_AUTO_SPLIT_OPERATOR);
        return (jobNo != null && !jobNo.isBlank()) ? "ADMIN-" + jobNo : "SYSTEM";
    }

    /** 自动建单操作人：开启开关的管理员账号，未记录时回退 SYSTEM */
    private String autoOrderOperator() {
        String jobNo = redisTemplate.opsForValue().get(KEY_AUTO_ORDER_OPERATOR);
        return (jobNo != null && !jobNo.isBlank()) ? "ADMIN-" + jobNo : "SYSTEM";
    }

    // ==================== 自动派单（工单管理：开关 + 定时扫描 + SSE） ====================

    /** 自动派单开关是否开启（Redis config:auto_dispatch == "true"） */
    public boolean isAutoDispatchEnabled() {
        return "true".equals(redisTemplate.opsForValue().get(KEY_AUTO_DISPATCH));
    }

    /**
     * 打开/关闭自动派单：记录开启者（管理员账号），开启后等满一个周期才第一次检测。
     * 与「自动拆分/自动建单」开关同构：开关只管 Redis 配置，实际扫描由定时任务驱动。
     */
    public void setAutoDispatch(boolean enabled) {
        redisTemplate.opsForValue().set(KEY_AUTO_DISPATCH, String.valueOf(enabled));
        autoDispatchEnabledAt = enabled ? System.currentTimeMillis() : 0;
        LoginUser user = UserContext.get();
        if (enabled && user != null && user.jobNo() != null && !user.jobNo().isBlank()) {
            redisTemplate.opsForValue().set(KEY_AUTO_DISPATCH_OPERATOR, user.jobNo());
        } else if (!enabled) {
            redisTemplate.delete(KEY_AUTO_DISPATCH_OPERATOR);
        }
    }

    /** 自动派单操作人：开启开关的管理员账号，未记录时回退 SYSTEM */
    private String autoDispatchOperator() {
        String jobNo = redisTemplate.opsForValue().get(KEY_AUTO_DISPATCH_OPERATOR);
        return (jobNo != null && !jobNo.isBlank()) ? "ADMIN-" + jobNo : "SYSTEM";
    }

    /** 启动自动派单：并发 4 按需取「待分派且已确定部门」工单，逐个自动分派到对应部门 */
    public String startAutoDispatch() {
        if (autoDispatchTask != null && !autoDispatchTask.finished) {
            return autoDispatchTask.taskId; // 已有任务在跑，复用
        }
        if (adminWorkOrderMapper.selectOnePendingDispatch() == null) {
            return null; // 无待分派工单，回落到定时任务
        }
        String taskId = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        AutoTask task = new AutoTask(taskId, 4);
        autoDispatchTask = task;
        ExecutorService exec = Executors.newFixedThreadPool(4);
        for (int i = 0; i < 4; i++) {
            exec.submit(() -> {
                // 首次派单延迟 3 秒（打开开关后缓冲，随后进入自动处理周期）
                try { Thread.sleep(3000); } catch (InterruptedException ignored) {}
                autoDispatchWorker(task);
            });
        }
        Thread monitor = new Thread(() -> {
            exec.shutdown();
            try { exec.awaitTermination(12, TimeUnit.HOURS); } catch (InterruptedException ignored) {}
            task.finished = true;
            log.info("自动派单 {} 完成：成功 {} / 已处理 {}", taskId,
                    task.results.stream().filter(r -> Boolean.TRUE.equals(r.get("success"))).count(),
                    task.totalCount);
        });
        monitor.setDaemon(true);
        monitor.start();
        log.info("自动派单 {} 启动：并发 4，按需取", taskId);
        return taskId;
    }

    private void autoDispatchWorker(AutoTask task) {
        int consecutiveFailures = 0;
        while (!task.cancelled) {
            WorkOrder pending;
            try {
                pending = adminWorkOrderMapper.selectOnePendingDispatch();
            } catch (Exception e) {
                // 加固：取单失败（DB 抖动）只退避重试，不终止 worker（原实现会杀死线程导致本轮任务提前结束）
                consecutiveFailures++;
                log.warn("自动派单取单失败（连续第 {} 次）：{}", consecutiveFailures, e.getMessage());
                if (consecutiveFailures >= 10) {
                    log.error("自动派单连续取单失败 10 次，worker 退出，等待下一周期自动重启");
                    break;
                }
                sleepQuiet(2000);
                continue;
            }
            consecutiveFailures = 0;
            if (pending == null) {
                break; // 无待分派工单，退出
            }
            if (task.cancelled) {
                break;
            }
            task.totalCount++;
            task.inProgressIds.add(pending.getId());
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("orderId", pending.getId());
            try {
                boolean ok = orderService.autoDispatchById(pending.getId(), autoDispatchOperator());
                r.put("success", ok);
                if (ok) {
                    // 派单成功后经 SSE 推送状态变化（工单管理页被动刷新）
                    sseEventService.emit(SseEventService.TYPE_DISPATCH, "status-change",
                            Map.of("orderId", pending.getId(), "status", "PENDING_DEPT_PROCESS"));
                }
            } catch (Exception e) {
                r.put("success", false);
                r.put("error", e.getMessage());
                log.warn("自动派单工单 {} 异常：{}", pending.getId(), e.getMessage());
            } finally {
                task.inProgressIds.remove(pending.getId());
                task.results.add(r);
            }
        }
    }

    public void cancelAutoDispatch() {
        AutoTask t = autoDispatchTask;
        if (t != null && !t.finished) {
            t.cancelled = true;
            log.info("自动派单 {} 已取消（进行中 {} 条继续）", t.taskId, t.inProgressCount());
        }
    }

    public AutoTask getAutoDispatchTask() {
        return autoDispatchTask;
    }

    /** 完整处理：先父诉求标题，再父诉求拆分，再子诉求 AI 流水线与建单；供定时任务与手动触发共用 */
    public int processAll() {
        titlePendingPipeline();
        int splitCount = processParents();
        int pipelineCount = processAuditPending();
        int orderCount = processChildren();
        if (splitCount > 0 || pipelineCount > 0 || orderCount > 0) {
            log.info("诉求自动处理完成：拆分 {} 条父诉求，AI 流水线 {} 条，生成工单 {} 张", splitCount, pipelineCount, orderCount);
        }
        return splitCount + pipelineCount + orderCount;
    }

    /** 按指定父诉求ID手动拆分（异步）：立即返回 taskId，前端轮询进度，逐个完成逐个落库 */
    public String startSplit(List<Long> parentIds) {
        // 同批上限 10 条（与前端勾选上限一致，防直接调接口绕过）
        if (parentIds != null && parentIds.size() > 10) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "单批最多拆分 10 条父诉求");
        }
        // 先批量查出所有父诉求，过滤掉非「未拆分」的（PENDING_AI 标题未生成/TITLE_GENERATED 已生成均可，claim 兜底）
        List<AppealParent> validParents = new ArrayList<>();
        if (parentIds != null) {
            for (Long id : parentIds) {
                if (id == null) continue;
                AppealParent p = appealParentMapper.selectById(id);
                if (p != null && ("TITLE_GENERATED".equals(p.getStatus()) || "PENDING_AI".equals(p.getStatus()))) {
                    validParents.add(p);
                }
            }
        }

        String taskId = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        SplitTask task = new SplitTask(taskId, validParents.size());
        splitTasks.put(taskId, task);

        if (validParents.isEmpty()) {
            task.finished = true;
            return taskId;
        }

        // 手动拆分：触发者是当前登录管理员（异步线程里 UserContext 不可用，需在主线程先取出）
        LoginUser user = UserContext.get();
        String operator = (user != null && user.jobNo() != null && !user.jobNo().isBlank())
                ? "ADMIN-" + user.jobNo() : "SYSTEM";

        // 并行线程池：上限 20，避免 AI detect 同时打太多
        ExecutorService executor = Executors.newFixedThreadPool(Math.min(validParents.size(), 10));
        List<CompletableFuture<Void>> futures = validParents.stream()
            .map(parent -> CompletableFuture.runAsync(() -> {
                Map<String, Object> r = new LinkedHashMap<>();
                r.put("parentId", parent.getId());
                r.put("appealNo", parent.getAppealNo());
                try {
                    // 原子认领：PENDING_AI → SPLITTING（拆分中），前端据此显示「拆分中」
                    if (appealParentMapper.claimForSplit(parent.getId()) == 0) {
                        r.put("success", false);
                        r.put("childCount", 0);
                        r.put("error", "已被处理");
                    } else {
                        sseEventService.emit(SseEventService.TYPE_SPLIT, "status-change",
                                Map.of("appealId", parent.getId(), "status", "SPLITTING"));
                        processParent(parent, operator);
                        r.put("success", true);
                        r.put("childCount", appealChildMapper.countChildrenByParentId(parent.getId()));
                        r.put("error", null);
                    }
                } catch (Exception e) {
                    log.warn("父诉求 {} 拆分失败：{}", parent.getAppealNo(), e.getMessage());
                    r.put("success", false);
                    r.put("childCount", 0);
                    r.put("error", e.getMessage());
                    // 回滚 SPLITTING → TITLE_GENERATED（标题已生成）
                    AppealParent rollback = new AppealParent();
                    rollback.setId(parent.getId());
                    rollback.setStatus("TITLE_GENERATED");
                    appealParentMapper.update(rollback);
                }
                // 拆好一个立即加入结果集（按完成顺序，不攒在一起）
                task.results.add(r);
                log.info("父诉求 {} 拆分完成（{} / {}）", parent.getAppealNo(), task.doneCount(), task.total);
            }, executor))
            .collect(Collectors.toList());

        // 独立守护线程等待全部完成，HTTP 请求立即返回 taskId
        Thread monitor = new Thread(() -> {
            try {
                CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                    .get(180, TimeUnit.SECONDS);
            } catch (Exception e) {
                log.warn("拆分任务 {} 等待异常：{}", taskId, e.getMessage());
            } finally {
                task.finished = true;
                executor.shutdownNow();
                log.info("拆分任务 {} 全部完成：成功 {} / 总 {} 条", taskId,
                        task.results.stream().filter(x -> Boolean.TRUE.equals(x.get("success"))).count(), task.total);
            }
        });
        monitor.setDaemon(true);
        monitor.start();
        return taskId;
    }

    /** 查询拆分任务进度（前端轮询） */
    public SplitTask getSplitTask(String taskId) {
        return splitTasks.get(taskId);
    }

    /** 阶段1：父诉求（PENDING_AI）AI 拆分生成子诉求 */
    public int processParents() {
        List<AppealParent> parents = appealParentMapper.selectPendingParents(BATCH_SIZE);
        int done = 0;
        String autoOperator = autoSplitOperator();
        for (AppealParent parent : parents) {
            try {
                processParent(parent, autoOperator);
                done++;
            } catch (Exception e) {
                log.warn("父诉求 {} 拆分失败：{}", parent.getAppealNo(), e.getMessage());
            }
        }
        return done;
    }

    /** 按指定子诉求ID生成工单（手动勾选触发，异步）：立即返回 taskId，前端轮询进度，逐个完成逐个落库 */
    public String startCreateOrders(List<Long> childIds) {
        // 同批上限 10 条（与前端勾选上限一致，防直接调接口绕过）
        if (childIds != null && childIds.size() > 10) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "单批最多生成 10 条工单");
        }
        // 手动生成工单：触发者是当前登录管理员（异步线程里 UserContext 不可用，需在主线程先取出）
        LoginUser user = UserContext.get();
        String operator = (user != null && user.jobNo() != null && !user.jobNo().isBlank())
                ? "ADMIN-" + user.jobNo() : "SYSTEM";
        // 过滤：待AI审核（先走 AI 流水线）/ 待生成工单（READY）子诉求均可生成工单，其余静默忽略
        List<AppealChild> validChildren = new ArrayList<>();
        if (childIds != null) {
            for (Long id : childIds) {
                if (id == null) continue;
                AppealChild c = appealChildMapper.selectById(id);
                if (c != null && ("AUDIT_PENDING".equals(c.getStatus())
                        || "READY".equals(c.getStatus()))) {
                    validChildren.add(c);
                }
            }
        }

        String taskId = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        CreateOrderTask task = new CreateOrderTask(taskId, validChildren.size());
        createOrderTasks.put(taskId, task);

        if (validChildren.isEmpty()) {
            task.finished = true;
            return taskId;
        }

        // 全部逻辑放入后台线程，立即返回 taskId
        Thread monitor = new Thread(() -> {
            // 阶段1：并行准备（AUDIT_PENDING 先走队列1 流水线定类）
            java.util.Map<Long, AiClassifyResult> classifyMap = new java.util.concurrent.ConcurrentHashMap<>();
            ExecutorService classifyExec = Executors.newFixedThreadPool(Math.min(validChildren.size(), 10));
            List<CompletableFuture<Void>> classifyFutures = new ArrayList<>();
            for (AppealChild child : validChildren) {
                classifyFutures.add(CompletableFuture.runAsync(() -> {
                    try {
                        // 待AI审核的子诉求：先走 AI 流水线（领取 → 审核/纠正/分类 → 总结标题 → READY）
                        if ("AUDIT_PENDING".equals(child.getStatus())) {
                            if (appealChildMapper.claimForAudit(child.getId()) == 0) {
                                return; // 已被处理
                            }
                            emitChildStatus(child, "AUDITING");
                            auditProcessChild(child, operator);
                            // 流水线后 title/category 已更新，重新加载用于建单
                            AppealChild updated = appealChildMapper.selectById(child.getId());
                            if (updated != null) {
                                child.setTitle(updated.getTitle());
                                child.setCategory(updated.getCategory());
                            }
                        }
                        // 队列1 已定一级类别，建单不再重复调 AI 分类：直接用派生分类结果
                        classifyMap.put(child.getId(), childClassify(child));
                    } catch (Exception e) {
                        log.warn("子诉求 {} 生成工单前置处理异常：{}", child.getAppealNo(), e.getMessage());
                    }
                }, classifyExec));
            }
            try {
                CompletableFuture.allOf(classifyFutures.toArray(new CompletableFuture[0]))
                        .get(120, TimeUnit.SECONDS);
            } catch (Exception e) {
                log.warn("生成工单前置处理异常：{}", e.getMessage());
            }
            classifyExec.shutdownNow();

            // 阶段2：串行逐条落库
            for (AppealChild child : validChildren) {
                Map<String, Object> r = new LinkedHashMap<>();
                r.put("childId", child.getId()); r.put("appealNo", child.getAppealNo());
                try {
                    processChild(child, classifyMap.get(child.getId()), operator);
                    r.put("success", true); r.put("error", null);
                } catch (Exception e) {
                    log.warn("子诉求 {} 生成工单失败：{}", child.getAppealNo(), e.getMessage());
                    r.put("success", false); r.put("error", e.getMessage());
                }
                task.results.add(r);
                log.info("子诉求 {} 生成工单完成（{} / {}）", child.getAppealNo(), task.doneCount(), task.total);
            }
            task.finished = true;
            log.info("生成工单任务 {} 全部完成：成功 {} / 总 {} 条", taskId,
                    task.results.stream().filter(x -> Boolean.TRUE.equals(x.get("success"))).count(), task.total);
        });
        monitor.setDaemon(true);
        monitor.start();
        return taskId;
    }

    /** 查询生成工单任务进度（前端轮询） */
    public CreateOrderTask getCreateOrderTask(String taskId) {
        return createOrderTasks.get(taskId);
    }

    // ==================== 自动模式：全量任务 + 可取消 + 进度追踪 ====================

    /** 启动自动拆分：固定 20 并发，按需从数据库取 1 个处理 1 个，处理完补 1 个，取不到即结束 */
    public String startAutoSplit() {
        if (autoSplitTask != null && !autoSplitTask.finished) {
            return autoSplitTask.taskId; // 已有任务在跑，复用
        }
        // 先探测是否有待处理诉求，无则不开任务（回到计时任务）
        if (appealParentMapper.selectPendingParents(1).isEmpty()) {
            return null;
        }
        String taskId = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        AutoTask task = new AutoTask(taskId, 10);
        autoSplitTask = task;
        ExecutorService exec = Executors.newFixedThreadPool(10);
        for (int i = 0; i < 20; i++) {
            exec.submit(() -> {
                // 首次拆分延迟 3 秒（打开开关后缓冲，随后进入自动处理周期）
                try { Thread.sleep(3000); } catch (InterruptedException ignored) {}
                autoSplitWorker(task);
            });
        }
        Thread monitor = new Thread(() -> {
            exec.shutdown();
            try { exec.awaitTermination(12, TimeUnit.HOURS); } catch (InterruptedException ignored) {}
            task.finished = true;
            log.info("自动拆分 {} 完成：成功 {} / 已处理 {}", taskId, task.results.stream().filter(r -> Boolean.TRUE.equals(r.get("success"))).count(), task.totalCount);
        });
        monitor.setDaemon(true);
        monitor.start();
        log.info("自动拆分 {} 启动：并发 20，按需取", taskId);
        return taskId;
    }

    private void autoSplitWorker(AutoTask task) {
        while (!task.cancelled) {
            // 按需取 1 个待拆分父诉求
            List<AppealParent> batch = appealParentMapper.selectPendingParents(1);
            if (batch.isEmpty()) {
                break; // 无待处理，退出（等待其他 worker 也退出后任务结束）
            }
            AppealParent parent = batch.get(0);
            // 认领前再检查取消：关闭开关后立即停止取新诉求
            if (task.cancelled) {
                break;
            }
            // 原子认领：抢占 PENDING_AI → SPLITTING，失败说明被其他 worker 抢了
            if (appealParentMapper.claimForSplit(parent.getId()) == 0) {
                continue;
            }
            // 关闭开关：停止取新诉求（已认领的继续拆完，符合“队列中的继续执行”）
            sseEventService.emit(SseEventService.TYPE_SPLIT, "status-change",
                    Map.of("appealId", parent.getId(), "status", "SPLITTING"));
            task.totalCount++;
            task.inProgressIds.add(parent.getId());
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("parentId", parent.getId());
            try {
                processParent(parent, autoSplitOperator());
                r.put("success", true);
                r.put("childCount", appealChildMapper.countChildrenByParentId(parent.getId()));
            } catch (Exception e) {
                r.put("success", false);
                r.put("error", e.getMessage());
                // 拆分失败回滚状态（标题已生成 → 未拆分），允许下轮重试
                AppealParent rollback = new AppealParent();
                rollback.setId(parent.getId());
                rollback.setStatus("TITLE_GENERATED");
                appealParentMapper.update(rollback);
            } finally {
                task.inProgressIds.remove(parent.getId());
                task.results.add(r);
            }
        }
    }

    public void cancelAutoSplit() {
        AutoTask t = autoSplitTask;
        if (t != null && !t.finished) {
            t.cancelled = true;
            log.info("自动拆分 {} 已取消（进行中 {} 条继续）", t.taskId, t.inProgressCount());
        }
    }

    public AutoTask getAutoSplitTask() {
        return autoSplitTask;
    }

    /** 启动自动建单：两级流水线——10 并行分类 + 1 串行落库，落库完一个补一个 */
    public String startAutoCreateOrders() {
        if (autoCreateOrderTask != null && !autoCreateOrderTask.finished) {
            return autoCreateOrderTask.taskId;
        }
        if (appealChildMapper.selectUnorderedChildren(1).isEmpty()) {
            return null;
        }
        String taskId = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        AutoTask task = new AutoTask(taskId, 10);
        autoCreateOrderTask = task;

        // 落库队列：分类完成的子诉求，等待串行落库
        BlockingQueue<ClassifiedChild> toPersist = new LinkedBlockingQueue<>();
        // 分类线程存活计数：归零表示分类池已取完所有可处理诉求
        AtomicInteger classifyAlive = new AtomicInteger(10);

        // 分类线程池（10 并行，按需取）
        ExecutorService classifyExec = Executors.newFixedThreadPool(10);
        for (int i = 0; i < 10; i++) {
            classifyExec.submit(() -> {
                // 首次建单延迟 3 秒（打开开关后缓冲，随后进入自动处理周期）
                try { Thread.sleep(3000); } catch (InterruptedException ignored) {}
                classifyWorker(task, toPersist, classifyAlive);
            });
        }

        // 落库线程（串行 1 个）
        Thread persistThread = new Thread(() -> {
            while (true) {
                ClassifiedChild cc = null;
                try {
                    cc = toPersist.poll(500, TimeUnit.MILLISECONDS);
                } catch (InterruptedException e) {
                    break;
                }
                if (cc == null) {
                    // 取消或分类完成，且队列已清空 → 退出（已分类的诉求会先落库完，不卡在 ORDERING）
                    if ((task.cancelled || classifyAlive.get() == 0) && toPersist.isEmpty()) {
                        break;
                    }
                    continue;
                }
                Map<String, Object> r = new LinkedHashMap<>();
                r.put("childId", cc.child.getId());
                try {
                    // 分类线程已 claim 设 ORDERING，此处直接落库，persistChild 内建单后设 ORDERED
                    persistChild(cc.child, cc.classify, autoOrderOperator());
                    r.put("success", true);
                } catch (Exception e) {
                    log.warn("子诉求 {} 生成工单失败（回滚待生成工单，下轮重试）：{}", cc.child.getAppealNo(), e.getMessage());
                    r.put("success", false);
                    r.put("error", e.getMessage());
                    AppealChild rollback = new AppealChild();
                    rollback.setId(cc.child.getId());
                    rollback.setStatus("READY");
                    appealChildMapper.update(rollback);
                } finally {
                    task.inProgressIds.remove(cc.child.getId());
                    task.results.add(r);
                }
            }
            task.finished = true;
            classifyExec.shutdownNow();
            log.info("自动建单 {} 完成：成功 {} / 已处理 {}", taskId, task.results.stream().filter(r -> Boolean.TRUE.equals(r.get("success"))).count(), task.totalCount);
        });
        persistThread.setDaemon(true);
        persistThread.start();

        log.info("自动建单 {} 启动：取单并行10 + 落库串行1（队列1已定类，建单不再分类）", taskId);
        return taskId;
    }

    /** 取单线程：按需取 1 条 READY → 原子认领（READY→ORDERING）→ 直接入落库队列（不再分类） */
    private void classifyWorker(AutoTask task, BlockingQueue<ClassifiedChild> toPersist, AtomicInteger classifyAlive) {
        try {
            while (!task.cancelled) {
                List<AppealChild> batch = appealChildMapper.selectUnorderedChildren(1);
                if (batch.isEmpty()) {
                    break;
                }
                AppealChild child = batch.get(0);
                // 认领前再检查取消：关闭开关后立即停止取新诉求
                if (task.cancelled) {
                    break;
                }
                // 原子认领：READY → ORDERING（生成中），select 不再取到它，实现并行分类
                if (appealChildMapper.claimForOrder(child.getId()) == 0) {
                    continue;
                }
                // 关闭开关后已认领的继续完成（不再回滚），仅停止取新
                sseEventService.emit(SseEventService.TYPE_ORDER, "status-change",
                        Map.of("childId", child.getId(), "status", "ORDERING"));
                task.totalCount++;
                task.inProgressIds.add(child.getId());
                // 队列1（标题→审核→READY）已定一级类别：建单不再重复调 AI 分类，直接派生分类结果入落库队列
                try {
                    toPersist.put(new ClassifiedChild(child, childClassify(child)));
                } catch (InterruptedException e) {
                    task.inProgressIds.remove(child.getId());
                    // 中断：回滚 ORDERING → READY（待生成工单）
                    AppealChild rollback = new AppealChild();
                    rollback.setId(child.getId());
                    rollback.setStatus("READY");
                    appealChildMapper.update(rollback);
                    break;
                }
            }
        } finally {
            classifyAlive.decrementAndGet();
        }
    }

    public void cancelAutoCreateOrders() {
        AutoTask t = autoCreateOrderTask;
        if (t != null && !t.finished) {
            t.cancelled = true;
            log.info("自动建单 {} 已取消（进行中 {} 条继续）", t.taskId, t.inProgressCount());
        }
    }

    public AutoTask getAutoCreateOrderTask() {
        return autoCreateOrderTask;
    }

    // ==================== 阶段2（定时任务兼容，保留给手动 trigger） ====================

    /** 阶段2（同步兼容）：子诉求（READY 待生成工单）→ 归并建单（复用队列1已定类别，不再分类） */
    public int processChildren() {
        List<AppealChild> children = appealChildMapper.selectUnorderedChildren(BATCH_SIZE);
        int done = 0;
        String autoOperator = autoOrderOperator();
        for (AppealChild child : children) {
            try {
                processChild(child, autoOperator);
                done++;
            } catch (Exception e) {
                log.warn("子诉求 {} 生成工单失败：{}", child.getAppealNo(), e.getMessage());
            }
        }
        return done;
    }

    /** 标题流水线（不受自动模式开关影响）：父/子诉求 待AI生成标题(TITLE_PENDING) → AI标题生成中(TITLE_GENERATING)
     *  → AI 总结标题落库 → 父=待拆分(TITLE_GENERATED)；子=待AI审核(AUDIT_PENDING，非OTHER) / AI分类中(CLASSIFYING，OTHER)。
     *  滑动窗口模型：10 个 worker 持续循环逐条取（处理完一条立即补一条，无批次屏障），
     *  直到队列清空全部退出 → 进入周期沉睡（15s 后再扫描）。 */
    public void titlePendingPipeline() {
        if (!titlePipelineRunning.compareAndSet(false, true)) {
            return;  // 已有实例在持续消费，避免重复启动
        }
        java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(10);
        for (int i = 0; i < 10; i++) {
            titlePipelineExecutor.execute(() -> {
                try {
                    titleWorker();
                } finally {
                    latch.countDown();
                }
            });
        }
        titlePipelineRunner.execute(() -> {
            try {
                latch.await(30, TimeUnit.MINUTES);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                titlePipelineRunning.set(false);
            }
        });
    }

    /** 单个 worker：循环逐条取（优先父，其次子），Redis 锁防并发重复，队列空则退出。
     *  健壮性加固：单轮异常（Redis/DB 抖动等）只计数退避，不再杀死线程；连续失败达阈值主动退出，
     *  交由周期扫描自愈（titlePipelineRunning 复位后 scheduledAutoProcess 自动重启流水线）。 */
    private void titleWorker() {
        // 容错：Redis 不可用时操作人暂回退 SYSTEM（原实现此处未捕获异常会直接杀死 worker 线程）
        String operator;
        try {
            operator = autoSplitOperator();
        } catch (Exception e) {
            log.warn("Redis 不可用，标题流水线操作人暂回退 SYSTEM：{}", e.getMessage());
            operator = "SYSTEM";
        }
        int idleRounds = 0;
        int consecutiveFailures = 0;
        final int maxConsecutiveFailures = 10;
        while (true) {
            boolean healthy = true;
            try {
                // 父诉求标题专用队列（子诉求标题由 childTitlePipeline 独立队列处理）
                List<AppealParent> parents = appealParentMapper.selectPendingTitleParents(1);
                if (!parents.isEmpty()) {
                    idleRounds = 0;
                    AppealParent parent = parents.get(0);
                    Boolean locked = redisTemplate.opsForValue().setIfAbsent("lock:title:p:" + parent.getId(), "1",
                            Duration.ofSeconds(60));
                    if (!Boolean.TRUE.equals(locked)) {
                        sleepQuiet(150);
                        continue;
                    }
                    try {
                        summarizeParentTitle(parent, operator);
                    } catch (Exception e) {
                        log.warn("父诉求 {} 标题总结失败：{}", parent.getAppealNo(), e.getMessage());
                    }
                    continue;
                }
                idleRounds++;
                if (idleRounds >= 3) {
                    break;
                }
                sleepQuiet(400);
            } catch (Exception e) {
                healthy = false;
                consecutiveFailures++;
                log.warn("标题流水线 worker 异常（连续第 {}/{} 次）：{}", consecutiveFailures, maxConsecutiveFailures, e.getMessage());
                if (consecutiveFailures >= maxConsecutiveFailures) {
                    log.error("标题流水线连续失败 {} 次，worker 主动退出，等待周期扫描自愈（依赖恢复后自动重启）", maxConsecutiveFailures);
                    break;
                }
                sleepQuiet(2000); // 故障退避：避免对不可用依赖高频重试
            } finally {
                if (healthy) {
                    consecutiveFailures = 0; // finally 在 continue 分支同样执行：健康轮次归零连续失败计数
                }
            }
        }
    }

    /** 子诉求标题流水线（子→工单 队列1）：滑动窗口逐条取子诉求 TITLE_PENDING → AI 生成标题 →
     *  非OTHER=AUDIT_PENDING（待AI审核）/ OTHER=CLASSIFYING（AI分类中）；独立线程池与运行标志。 */
    public void childTitlePipeline() {
        if (!childTitlePipelineRunning.compareAndSet(false, true)) {
            return; // 已有实例在持续消费，避免重复启动
        }
        java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(6);
        for (int i = 0; i < 6; i++) {
            childTitlePipelineExecutor.execute(() -> {
                try {
                    childTitleWorker();
                } finally {
                    latch.countDown();
                }
            });
        }
        childTitlePipelineRunner.execute(() -> {
            try {
                latch.await(30, TimeUnit.MINUTES);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                childTitlePipelineRunning.set(false);
            }
        });
    }

    /** 子诉求标题 worker：仅处理子诉求标题（父诉求由 titleWorker 独立处理），Redis 锁防并发重复 */
    private void childTitleWorker() {
        String operator;
        try {
            operator = autoSplitOperator();
        } catch (Exception e) {
            log.warn("Redis 不可用，子诉求标题流水线操作人暂回退 SYSTEM：{}", e.getMessage());
            operator = "SYSTEM";
        }
        int idleRounds = 0;
        int consecutiveFailures = 0;
        final int maxConsecutiveFailures = 10;
        while (true) {
            boolean healthy = true;
            try {
                List<AppealChild> children = appealChildMapper.selectTitlePendingChildren(1);
                if (!children.isEmpty()) {
                    idleRounds = 0;
                    AppealChild child = children.get(0);
                    Boolean locked = redisTemplate.opsForValue().setIfAbsent("lock:title:c:" + child.getId(), "1",
                            Duration.ofSeconds(60));
                    if (!Boolean.TRUE.equals(locked)) {
                        sleepQuiet(150);
                        continue;
                    }
                    try {
                        summarizeChildTitle(child, operator);
                    } catch (Exception e) {
                        log.warn("子诉求 {} 标题总结失败：{}", child.getAppealNo(), e.getMessage());
                    }
                    continue;
                }
                idleRounds++;
                if (idleRounds >= 3) {
                    break;
                }
                sleepQuiet(400);
            } catch (Exception e) {
                healthy = false;
                consecutiveFailures++;
                log.warn("子诉求标题流水线 worker 异常（连续第 {}/{} 次）：{}", consecutiveFailures, maxConsecutiveFailures, e.getMessage());
                if (consecutiveFailures >= maxConsecutiveFailures) {
                    log.error("子诉求标题流水线连续失败 {} 次，worker 主动退出，等待周期扫描自愈", maxConsecutiveFailures);
                    break;
                }
                sleepQuiet(2000);
            } finally {
                if (healthy) {
                    consecutiveFailures = 0;
                }
            }
        }
    }

    private void sleepQuiet(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
    }

    /** AI 总结父诉求标题（<=25字）：TITLE_GENERATING（AI标题生成中）→ 生成 → TITLE_GENERATED（待拆分）。
     *  方案A：AI 降级一次即用「内容前25字」兜底落库，永不卡死；管理员可随时手动编辑标题 */
    private void summarizeParentTitle(AppealParent parent, String operator) {
        // 实时状态：AI标题生成中
        AppealParent generating = new AppealParent();
        generating.setId(parent.getId());
        generating.setStatus("TITLE_GENERATING");
        appealParentMapper.update(generating);
        parent.setStatus("TITLE_GENERATING");
        sseEventService.emit(SseEventService.TYPE_SPLIT, "status-change",
                Map.of("appealId", parent.getId(), "status", "TITLE_GENERATING"));
        String cur = parent.getTitle();
        // title 为空 = 未生成（市民提交时不填标题），据此判断是否需要 AI 生成；已有标题不覆盖
        boolean needTitle = cur == null || cur.isBlank();
        String title = null;
        String degrade = null;
        if (needTitle) {
            Optional<AiSummarizeResult> sum;
            try {
                sum = aiServiceClient.summarize(parent.getContent());
            } catch (Exception e) {
                // AI 不可用：回滚待生成标题 + 释放调度锁，交给周期标题流水线立即重试（避免锁残留延迟兜底）
                log.warn("父诉求 {} AI 标题生成失败（回滚待重试）：{}", parent.getAppealNo(), e.getMessage());
                AppealParent rb = new AppealParent();
                rb.setId(parent.getId());
                rb.setStatus("TITLE_PENDING");
                appealParentMapper.update(rb);
                redisTemplate.delete("lock:title:p:" + parent.getId());
                return;
            }
            if (sum.isPresent() && sum.get().title() != null && !sum.get().title().isBlank()) {
                title = sum.get().title().trim();
            } else {
                // 方案A：AI 降级兜底，内容前25字，一轮收敛
                title = first25(parent.getContent());
                degrade = "AI 总结父诉求标题降级：" + parent.getAppealNo() + "（AI 不可用，暂用内容截断，可手动编辑）";
            }
        }
        AppealParent upd = new AppealParent();
        upd.setId(parent.getId());
        if (title != null) {
            upd.setTitle(title);
        }
        if (degrade != null) {
            auditLogService.recordOperation("AI", "appeal-title", degrade);
        } else if (title != null) {
            auditLogService.recordOperation("AI", "appeal-title",
                    "AI 总结父诉求标题：" + parent.getAppealNo() + " → " + title);
        }
        upd.setStatus("TITLE_GENERATED");
        appealParentMapper.update(upd);
        parent.setTitle(title != null ? title : cur);
        sseEventService.emit(SseEventService.TYPE_SPLIT, "status-change",
                Map.of("appealId", parent.getId(), "status", "TITLE_GENERATED",
                        "title", parent.getTitle() == null ? "" : parent.getTitle()));
    }

    /** AI 总结子诉求标题（<=25字）：TITLE_GENERATING（AI标题生成中）→ 生成 → 按类别分流：
     *  非 OTHER → AUDIT_PENDING（待AI审核，进入审核流水线队列）；OTHER → CLASSIFYING（AI分类中，进入分类队列）。
     *  方案A：AI 降级一次即用「内容前25字」兜底，永不卡死 */
    private void summarizeChildTitle(AppealChild child, String operator) {
        // 实时状态：AI标题生成中
        AppealChild generating = new AppealChild();
        generating.setId(child.getId());
        generating.setStatus("TITLE_GENERATING");
        appealChildMapper.update(generating);
        child.setStatus("TITLE_GENERATING");
        emitChildStatus(child, "TITLE_GENERATING");
        String content = child.getContent() == null ? "" : child.getContent();
        Optional<AiSummarizeResult> sum;
        try {
            sum = aiServiceClient.summarize(content);
        } catch (Exception e) {
            // AI 不可用：回滚待生成标题 + 释放调度锁，交给周期标题流水线立即重试（避免锁残留延迟兜底）
            log.warn("子诉求 {} AI 标题生成失败（回滚待重试）：{}", child.getAppealNo(), e.getMessage());
            AppealChild rb = new AppealChild();
            rb.setId(child.getId());
            rb.setStatus("TITLE_PENDING");
            appealChildMapper.update(rb);
            redisTemplate.delete("lock:title:c:" + child.getId());
            return;
        }
        String title = sum.map(AiSummarizeResult::title)
                .filter(t -> t != null && !t.isBlank())
                .orElse(null);
        if (title == null) {
            title = first25(content);
            auditLogService.recordOperation("AI", "appeal-title",
                    "AI 总结子诉求标题降级：" + child.getAppealNo() + "（AI 不可用，暂用内容截断，可手动编辑）");
        } else {
            auditLogService.recordOperation("AI", "appeal-title",
                    "AI 总结子诉求标题：" + child.getAppealNo() + " → " + title);
        }
        // 按类别分流下一环节：非 OTHER → 待AI审核；OTHER → AI一级分类
        String category = child.getCategory();
        String next = (category != null && "OTHER".equals(category)) ? "CLASSIFYING" : "AUDIT_PENDING";
        AppealChild upd = new AppealChild();
        upd.setId(child.getId());
        upd.setTitle(title);
        upd.setStatus(next);
        appealChildMapper.update(upd);
        child.setTitle(title);
        child.setStatus(next);
        // SSE 事件携带标题内容：前端行内同步更新（status + title）
        sseEventService.emit(SseEventService.TYPE_ORDER, "status-change",
                Map.of("childId", child.getId(), "status", next,
                        "title", title == null ? "" : title));
        // 标题完成 → 立即异步进入审核/分类（不等 30 秒周期；建单仍按队列/开关）
        // 提交到独立有界线程池：AI 失败快速回滚，30 秒周期任务兜底（不占用公共线程池）
        immediatePipelineExecutor.execute(() -> {
            try {
                if ("CLASSIFYING".equals(next)) {
                    classifyProcessChild(child, operator);
                } else {
                    // 审核：原子领取 AUDIT_PENDING → AUDITING，领取成功才处理
                    if (appealChildMapper.claimForAudit(child.getId()) == 0) return;
                    emitChildStatus(child, "AUDITING");
                    auditProcessChild(child, operator);
                }
            } catch (Exception e2) {
                log.warn("子诉求 {} 即时流水线失败（将由周期任务兜底）：{}", child.getAppealNo(), e2.getMessage());
                if ("AUDIT_PENDING".equals(next)) rollbackStatus(child, "AUDIT_PENDING");
            }
        });
    }

    /** 拆分单个父诉求（标题已在前置阶段生成）：detect → 拆分审计 + 清洗审计 → 生成子诉求 → 父诉求置为已拆分 */
    private void processParent(AppealParent parent, String operator) {
        // 兜底：手动拆分时标题可能尚未生成（3 秒调度未到/降级），用内容前25字占位
        if (parent.getTitle() == null || parent.getTitle().isBlank()) {
            AppealParent t = new AppealParent();
            t.setId(parent.getId());
            t.setTitle(first25(parent.getContent()));
            appealParentMapper.update(t);
            parent.setTitle(first25(parent.getContent()));
        }
        String text = buildText(parent);
        Optional<AiDetectResult> detect = aiServiceClient.detect(parent.getId(), text);
        // 拆分方式：LLM / NONE（Python detect 只用 LLM 拆分）
        String splitMethod = detect.map(AiDetectResult::method).orElse("NONE");

        List<AiSplitSuggestion> splits = detect
                .map(AiDetectResult::splitSuggestions)
                .filter(list -> list != null && !list.isEmpty())
                .map(list -> list.size() > MAX_PARENT_SPLIT ? list.subList(0, MAX_PARENT_SPLIT) : list)
                .orElse(null);
        int childCount = splits == null ? 1 : splits.size();

        // 1. 拆分父诉求审计（记录触发者 + LLM 拆分方式）
        String splitBasis = "{\"split_method\":\"" + (splitMethod == null ? "none" : splitMethod.toLowerCase()) + "\"}";
        String splitSummary = "父诉求 " + parent.getAppealNo() + " 拆分为 " + childCount + " 条子诉求";
        auditLogService.recordAppealSplit(operator, splitSummary, splitBasis);

        // 2. 清洗子诉求审计（记录触发者 + 敏感词/PII 脱敏明细），拿到日志ID供子诉求关联
        String cleanBasis = buildCleanDecisionBasis(splits);
        String cleanSummary = "清洗 " + childCount + " 条子诉求";
        long cleanLogId = auditLogService.recordAppealClean(operator, cleanSummary, cleanBasis);

        // 3. 生成子诉求（content = 加工后文本，clean_log_id = 清洗日志ID）
        if (splits == null || splits.isEmpty()) {
            insertChild(parent, null, cleanLogId, operator);
        } else {
            for (AiSplitSuggestion s : splits) {
                insertChild(parent, s, cleanLogId, operator);
            }
        }

        // 父诉求置为已生成子诉求（不可再次拆分，需撤回后重新拆分）
        AppealParent update = new AppealParent();
        update.setId(parent.getId());
        update.setStatus("CHILDREN_GENERATED");
        update.setChildCount(childCount);
        appealParentMapper.update(update);
        // SSE 推送：父诉求状态 → 已生成子诉求
        sseEventService.emit(SseEventService.TYPE_SPLIT, "status-change",
                Map.of("appealId", parent.getId(), "status", "CHILDREN_GENERATED"));

        log.info("父诉求 {} 拆分完成，生成子诉求 {} 条（方式 {}，清洗日志ID {}）", parent.getAppealNo(),
                childCount, splitMethod, cleanLogId);
    }

    /** 构建清洗细则 JSON：每条子诉求的敏感词/PII 脱敏明细 */
    private String buildCleanDecisionBasis(List<AiSplitSuggestion> splits) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"children\":[");
        List<AiSplitSuggestion> list = splits == null ? List.of() : splits;
        int i = 0;
        for (AiSplitSuggestion s : list) {
            if (i > 0) {
                sb.append(",");
            }
            sb.append("{\"event_type\":\"").append(s.eventType() == null ? "" : s.eventType()).append("\"");
            sb.append(",\"clean_log\":").append(s.cleanLog() == null ? "null" : s.cleanLog());
            sb.append("}");
            i++;
        }
        sb.append("]}");
        return sb.toString();
    }

    /** 生成单条子诉求（继承父诉求行政区划、联系信息与类别；s 为空表示不拆分的兜底子诉求）。
     *  以「待AI审核」（AUDIT_PENDING）入库即列表可见，进入流水线任务队列；
     *  后续 AI 审核/类别纠正/分类/标题由 AI 流水线任务按周期处理并实时更新子诉求状态。 */
    private void insertChild(AppealParent parent, AiSplitSuggestion s, long cleanLogId, String operator) {
        // content 存加工后文本（clean_content）；为空则回退原文
        String cleanContent = s != null ? s.cleanContent() : null;
        String content = (cleanContent != null && !cleanContent.isBlank())
                ? cleanContent
                : (s != null && s.text() != null && !s.text().isBlank() ? s.text() : parent.getContent());
        AppealChild child = new AppealChild();
        child.setAppealNo(nextAppealNo());
        child.setParentId(parent.getId());
        child.setTitle(null);                           // 标题为空 = 未生成，流水线 AI 总结后覆盖
        child.setContent(content);
        // 继承父类别（流水线中审核/纠正）；历史遗留类别（PROPERTY/LIVELIHOOD 等）收敛为 OTHER 交由 AI 分类纠正
        String inheritedCategory = parent.getCategory();
        if (inheritedCategory != null && !"OTHER".equals(inheritedCategory)
                && !VALID_CATEGORIES.contains(inheritedCategory)) {
            inheritedCategory = "OTHER";
        }
        child.setCategory(inheritedCategory);
        child.setCleanLogId(cleanLogId);
        child.setStatus("TITLE_PENDING");               // 待AI生成标题：列表可见，标题流水线队列
        child.setCitizenName(parent.getCitizenName());
        child.setCitizenPhone(parent.getCitizenPhone());
        child.setProvince(parent.getProvince());
        child.setCity(parent.getCity());
        child.setDistrict(parent.getDistrict());
        child.setDistrictCode(parent.getDistrictCode());
        child.setStreet(parent.getStreet());
        child.setStreetCode(parent.getStreetCode());
        child.setCommunity(parent.getCommunity());
        child.setAddressDetail(parent.getAddressDetail());
        child.setRegion(parent.getRegion());
        child.setBroadFlag(parent.getBroadFlag());
        appealChildMapper.insert(child);
        // 出现后 3 秒自动生成标题（后端计时，单条）
        scheduleChildTitle(child.getId());
    }

    // ==================== AI 审核流水线（待AI审核 → 审核一级 → [纠正] → AI二级分类 → 待生成工单；OTHER 走一级分类） ====================

    /** 单条子诉求 AI 审核（只审一级分类）：
     *  AI审核中 → [一致/降级] AI二级分类 → 待生成工单；
     *  [不一致] AI类别纠正中（纠正一级）→ AI二级分类 → 待生成工单；
     *  [OTHER] 转 AI一级分类（CLASSIFYING）→ AI二级分类 → 待生成工单。
     *  每环节实时更新子诉求状态 + SSE 推送，审计留痕；AI 降级跳过对应环节不卡队列 */
    private void auditProcessChild(AppealChild child, String operator) {
        String content = child.getContent() == null ? "" : child.getContent();
        String category = child.getCategory();          // 继承自父诉求（标题流水线已生成标题）
        StringBuilder auditNote = new StringBuilder();
        // 类别收敛：一级分类只允许 8 大类 + OTHER；历史遗留类别（PROPERTY/LIVELIHOOD 等）
        // 一律按 OTHER 走 AI 一级分类，避免带非法类别流转到建单
        if (category != null && !category.isBlank() && !"OTHER".equals(category)
                && !VALID_CATEGORIES.contains(category)) {
            auditNote.append("历史遗留类别 ").append(category).append(" 收敛为 OTHER，AI 一级分类");
            category = "OTHER";
        }
        if (category != null && !category.isBlank() && !"OTHER".equals(category)) {
            // 状态已为 AUDITING（AI审核中，领取时原子更新）
            Optional<AiAuditResult> audit = aiServiceClient.auditCategory(content, category);
            if (audit.isPresent() && !audit.get().consistent()) {
                // 审核不一致 → AI 类别纠正中（纠正一级；纠正结果只取一级，二级由 CLASSIFYING_SUB 阶段独立判定）
                child.setStatus("CORRECTING");
                appealChildMapper.update(child);
                emitChildStatus(child, "CORRECTING");
                auditNote.append("AI审核不一致（").append(audit.get().reason()).append("）");
                Optional<AiClassifyResult> fix = aiServiceClient.classify(null, content);
                if (fix.isPresent() && fix.get().primaryCategory() != null
                        && VALID_CATEGORIES.contains(fix.get().primaryCategory())) {
                    category = fix.get().primaryCategory();
                    auditNote.append("→AI纠正一级分类为 ").append(category);
                    // 纠正出结果即时推送新类别（不等 READY）
                    child.setCategory(category);
                    sseEventService.emit(SseEventService.TYPE_ORDER, "status-change",
                            Map.of("childId", child.getId(), "status", "CORRECTING",
                                    "category", category));
                } else {
                    auditNote.append("，分类纠正未命中8大类，保留 ").append(category);
                }
            } else if (audit.isPresent()) {
                auditNote.append("AI审核一致（").append(audit.get().reason()).append("）");
            } else {
                auditNote.append("AI审核降级，保留继承类别 ").append(category);
            }
        } else if ("OTHER".equals(category)) {
            // OTHER → AI 一级分类（classifyProcessChild 内含一级分类 + 二级分类两阶段直至 READY）
            child.setCategory(category);
            classifyProcessChild(child, operator);
            return;
        } else {
            auditNote.append("无类别，跳过审核");
        }
        child.setCategory(category);

        // 一级已定（审核一致/纠正后/降级保留）→ AI 二级分类阶段 → READY
        subClassifyAndReady(child, auditNote.toString(), operator);
    }

    /**
     * AI 二级分类阶段（CLASSIFYING_SUB）：一级分类已确定，仅在该一级类的二级标签清单中选 sub_category，
     * 结果（二级/置信度/复核标记）落库 appeal_child；完成后置 READY（待生成工单）。
     * 降级契约：AI 不可用/无结果时二级归为"其他"、need_review=1（标记人工复核），不阻断流转（建单以一级为准）。
     */
    private void subClassifyAndReady(AppealChild child, String auditNote, String operator) {
        String content = child.getContent() == null ? "" : child.getContent();
        String category = child.getCategory();
        // 进入「AI二级分类」实时状态
        child.setStatus("CLASSIFYING_SUB");
        appealChildMapper.update(child);
        emitChildStatus(child, "CLASSIFYING_SUB");
        String subNote = "";
        try {
            Optional<org.tjetc.backend.dto.ai.AiSubClassifyResult> sub = aiServiceClient
                    .classifySecondary(content, category == null ? "OTHER" : category);
            if (sub.isPresent() && trimToNull(sub.get().subCategory()) != null) {
                child.setSubCategory(trimToNull(sub.get().subCategory()));
                child.setConfidence(sub.get().confidence() == null ? null
                        : java.math.BigDecimal.valueOf(sub.get().confidence()));
                child.setNeedReview(Boolean.TRUE.equals(sub.get().needReview()) ? 1 : 0);
                subNote = "二级分类=" + child.getSubCategory();
            } else {
                // 降级：无结果归为"其他" + 标记人工复核（建单以一级为准）
                child.setSubCategory("其他");
                child.setNeedReview(1);
                subNote = "AI二级分类降级（无结果，归为其他并标记人工复核）";
                log.warn("子诉求 {} AI 二级分类降级（无结果），继续流转", child.getAppealNo());
            }
        } catch (Exception e) {
            // AI 不可用/读超时：二级归为"其他"不阻断流转（建单用一级类别即可），待人工或后续校正
            child.setSubCategory("其他");
            child.setNeedReview(1);
            subNote = "AI二级分类失败（" + e.getMessage() + "，归为其他并标记人工复核）";
            log.warn("子诉求 {} AI 二级分类失败，继续流转：{}", child.getAppealNo(), e.getMessage());
        }

        // 二级分类完成：待生成工单（标题已在前置流水线生成）
        child.setStatus("READY");
        appealChildMapper.update(child);
        // SSE 事件携带类别/二级/标题：前端行内同步更新
        sseEventService.emit(SseEventService.TYPE_ORDER, "status-change",
                Map.of("childId", child.getId(), "status", "READY",
                        "category", category == null ? "" : category,
                        "sub_category", child.getSubCategory() == null ? "" : child.getSubCategory(),
                        "title", child.getTitle() == null ? "" : child.getTitle()));

        auditLogService.recordOperation("AI", "appeal-audit",
                "子诉求 " + child.getAppealNo() + "："
                        + (auditNote != null && !auditNote.isBlank() ? auditNote + "，" : "")
                        + "一级分类=" + (category == null ? "无" : category)
                        + (subNote.isBlank() ? "" : "，" + subNote)
                        + "，待生成工单");
    }

    /** 子诉求状态 SSE 推送（order 流，{childId, status}） */
    private void emitChildStatus(AppealChild child, String status) {
        sseEventService.emit(SseEventService.TYPE_ORDER, "status-change",
                Map.of("childId", child.getId(), "status", status));
    }

    /** 启动 AI 审核/分类流水线任务（自动模式周期驱动）：5 并行处理「待AI审核」与「AI分类中」子诉求直至待生成工单；无待处理返回 null */
    public String startAuditPipeline() {
        if (auditPipelineTask != null && !auditPipelineTask.finished) {
            return auditPipelineTask.taskId;
        }
        if (appealChildMapper.selectAuditPending(1).isEmpty()
                && appealChildMapper.selectClassifyingChildren(1).isEmpty()) {
            return null;
        }
        String taskId = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        AutoTask task = new AutoTask(taskId, 10);
        auditPipelineTask = task;
        AtomicInteger alive = new AtomicInteger(10);
        ExecutorService exec = Executors.newFixedThreadPool(10);
        for (int i = 0; i < 5; i++) {
            exec.submit(() -> {
                // 首次启动延迟 3 秒（与自动建单一致，打开开关后缓冲）
                try { Thread.sleep(3000); } catch (InterruptedException ignored) {}
                auditPipelineWorker(task, alive);
            });
        }
        Thread monitor = new Thread(() -> {
            while (alive.get() > 0) {
                try { Thread.sleep(500); } catch (InterruptedException ignored) { break; }
            }
            task.finished = true;
            exec.shutdownNow();
            log.info("AI 流水线 {} 结束：成功 {} / 处理 {}", taskId,
                    task.results.stream().filter(r -> Boolean.TRUE.equals(r.get("success"))).count(), task.totalCount);
        });
        monitor.setDaemon(true);
        monitor.start();
        return taskId;
    }



    /** 流水线 worker：按需取 1 条待处理子诉求（待AI审核→审核；AI一级分类→分类；
     *  AI二级分类→孤儿恢复）→ 处理至待生成工单 */
    private void auditPipelineWorker(AutoTask task, AtomicInteger alive) {
        try {
            while (!task.cancelled) {
                // 优先取「待AI审核」，其次「AI一级分类」（OTHER），最后「AI二级分类」（进程中断孤儿恢复）
                List<AppealChild> batch = appealChildMapper.selectAuditPending(1);
                int branch = 0; // 0=审核 1=一级分类 2=二级分类孤儿恢复
                if (batch.isEmpty()) {
                    batch = appealChildMapper.selectClassifyingChildren(1);
                    branch = 1;
                }
                if (batch.isEmpty()) {
                    batch = appealChildMapper.selectClassifyingSubChildren(1);
                    branch = 2;
                }
                if (batch.isEmpty()) {
                    break;
                }
                AppealChild child = batch.get(0);
                if (task.cancelled) {
                    break;
                }
                Map<String, Object> r = new LinkedHashMap<>();
                r.put("childId", child.getId());
                task.totalCount++;
                task.inProgressIds.add(child.getId());
                try {
                    if (branch == 0) {
                        if (appealChildMapper.claimForAudit(child.getId()) == 0) {
                            continue; // 已被其它 worker 领取
                        }
                        emitChildStatus(child, "AUDITING");
                        auditProcessChild(child, autoSplitOperator());
                    } else if (branch == 1) {
                        // OTHER 一级分类：Redis 锁防 5 worker 并发重复处理同一条
                        Boolean locked = redisTemplate.opsForValue().setIfAbsent("lock:classify:" + child.getId(), "1",
                                Duration.ofSeconds(120));
                        if (!Boolean.TRUE.equals(locked)) {
                            continue;
                        }
                        classifyProcessChild(child, autoSplitOperator());
                    } else {
                        // CLASSIFYING_SUB 孤儿恢复（进程在二级分类中途中断）：锁防并发，重走二级直至 READY
                        Boolean locked = redisTemplate.opsForValue().setIfAbsent("lock:subclassify:" + child.getId(), "1",
                                Duration.ofSeconds(120));
                        if (!Boolean.TRUE.equals(locked)) {
                            continue;
                        }
                        subClassifyAndReady(child, "二级分类中断恢复（重试）", autoSplitOperator());
                    }
                    r.put("success", true);
                } catch (Exception e) {
                    r.put("success", false);
                    r.put("error", e.getMessage());
                    // 按分支回滚：审核→待AI审核；一级分类→AI一级分类；二级分类→AI二级分类（各队列下轮重试）
                    rollbackStatus(child, switch (branch) {
                        case 1 -> "CLASSIFYING";
                        case 2 -> "CLASSIFYING_SUB";
                        default -> "AUDIT_PENDING";
                    });
                } finally {
                    task.inProgressIds.remove(child.getId());
                    task.results.add(r);
                }
            }
        } finally {
            alive.decrementAndGet();
        }
    }

    /** 流水线失败回滚：置回指定队列状态（下轮重试） */
    private void rollbackStatus(AppealChild child, String status) {
        AppealChild rb = new AppealChild();
        rb.setId(child.getId());
        rb.setStatus(status);
        appealChildMapper.update(rb);
    }

    /** 父诉求出现后 1 秒自动生成标题（后端计时，单条，不受开关影响；Redis 锁防并发重复） */
    public void scheduleParentTitle(Long parentId) {
        titleScheduler.schedule(() -> {
            try {
                AppealParent parent = appealParentMapper.selectById(parentId);
                if (parent == null || (parent.getTitle() != null && !parent.getTitle().isBlank())) {
                    return; // 已删除或已有标题（其它路径已处理）
                }
                Boolean locked = redisTemplate.opsForValue().setIfAbsent("lock:title:p:" + parentId, "1",
                        Duration.ofSeconds(60));
                if (!Boolean.TRUE.equals(locked)) {
                    return;
                }
                summarizeParentTitle(parent, "SYSTEM");
            } catch (Exception e) {
                log.warn("父诉求 {} 定时标题生成失败（定时周期兜底重试）：{}", parentId, e.getMessage());
            }
        }, 1, TimeUnit.SECONDS);
    }

    /** 子诉求出现后 1 秒自动生成标题（后端计时，单条，不受开关影响；Redis 锁防并发重复） */
    public void scheduleChildTitle(Long childId) {
        titleScheduler.schedule(() -> {
            try {
                AppealChild child = appealChildMapper.selectById(childId);
                if (child == null || (child.getTitle() != null && !child.getTitle().isBlank())) {
                    return; // 已删除或已有标题
                }
                Boolean locked = redisTemplate.opsForValue().setIfAbsent("lock:title:c:" + childId, "1",
                        Duration.ofSeconds(60));
                if (!Boolean.TRUE.equals(locked)) {
                    return;
                }
                summarizeChildTitle(child, "SYSTEM");
            } catch (Exception e) {
                log.warn("子诉求 {} 定时标题生成失败（定时周期兜底重试）：{}", childId, e.getMessage());
            }
        }, 1, TimeUnit.SECONDS);
    }

    /** OTHER 子诉求 AI 一级分类：AI一级分类中（CLASSIFYING）→ classify（结果限定8大类，未命中保留 OTHER，
     *  只取一级；二级由 CLASSIFYING_SUB 阶段独立判定）→ AI二级分类 → 待生成工单 */
    private void classifyProcessChild(AppealChild child, String operator) {
        // Redis 锁：防即时链与周期流水线并发重复处理同一条
        Boolean locked = redisTemplate.opsForValue().setIfAbsent("lock:classify:" + child.getId(), "1",
                Duration.ofSeconds(120));
        if (!Boolean.TRUE.equals(locked)) {
            return;
        }
        String content = child.getContent() == null ? "" : child.getContent();
        String category = child.getCategory() == null ? "OTHER" : child.getCategory();
        // 进入「AI一级分类」实时状态（调用方可能从 AUDITING/CLASSIFYING 等状态转入）
        child.setStatus("CLASSIFYING");
        appealChildMapper.update(child);
        emitChildStatus(child, "CLASSIFYING");
        Optional<AiClassifyResult> fix = aiServiceClient.classify(null, content);
        String note;
        if (fix.isPresent() && fix.get().primaryCategory() != null
                && VALID_CATEGORIES.contains(fix.get().primaryCategory())) {
            category = fix.get().primaryCategory();
            // 一级分类出结果即时推送新类别（不等二级完成）
            child.setCategory(category);
            sseEventService.emit(SseEventService.TYPE_ORDER, "status-change",
                    Map.of("childId", child.getId(), "status", "CLASSIFYING",
                            "category", category));
            note = "OTHER→AI一级分类为 " + category;
        } else {
            note = "OTHER→AI一级分类未命中8大类，保留 OTHER";
        }
        child.setCategory(category);
        // 一级已定 → AI 二级分类阶段 → READY
        subClassifyAndReady(child, note, operator);
    }

    /** 阶段1.5（同步兼容）：待AI审核子诉求 → AI 流水线 → 待生成工单；供 processAll/手动触发 */
    public int processAuditPending() {
        List<AppealChild> children = appealChildMapper.selectAuditPending(BATCH_SIZE);
        int done = 0;
        String operator = autoSplitOperator();
        for (AppealChild child : children) {
            try {
                if (appealChildMapper.claimForAudit(child.getId()) == 0) {
                    continue;
                }
                emitChildStatus(child, "AUDITING");
                auditProcessChild(child, operator);
                done++;
            } catch (Exception e) {
                log.warn("子诉求 {} AI 流水线失败：{}", child.getAppealNo(), e.getMessage());
                rollbackStatus(child, "AUDIT_PENDING");
            }
        }
        // OTHER 一级分类队列：AI一级分类 → AI二级分类 → 待生成工单
        List<AppealChild> classifying = appealChildMapper.selectClassifyingChildren(BATCH_SIZE);
        for (AppealChild child : classifying) {
            try {
                classifyProcessChild(child, operator);
                done++;
            } catch (Exception e) {
                log.warn("子诉求 {} AI 一级分类失败：{}", child.getAppealNo(), e.getMessage());
                rollbackStatus(child, "CLASSIFYING");
            }
        }
        // AI二级分类队列（进程中断孤儿恢复）：重走二级直至待生成工单
        List<AppealChild> subclassifying = appealChildMapper.selectClassifyingSubChildren(BATCH_SIZE);
        for (AppealChild child : subclassifying) {
            try {
                Boolean locked = redisTemplate.opsForValue().setIfAbsent("lock:subclassify:" + child.getId(), "1",
                        Duration.ofSeconds(120));
                if (!Boolean.TRUE.equals(locked)) {
                    continue;
                }
                subClassifyAndReady(child, "二级分类中断恢复（重试）", operator);
                done++;
            } catch (Exception e) {
                log.warn("子诉求 {} AI 二级分类恢复失败：{}", child.getAppealNo(), e.getMessage());
                rollbackStatus(child, "CLASSIFYING_SUB");
            }
        }
        return done;
    }

    /** 子诉求 → 去重归并建单（队列1 已定类，供定时任务等直接调用） */
    private void processChild(AppealChild child, String operator) {
        processChild(child, childClassify(child), operator);
    }

    /** 读取子诉求已落库的完整分类结果（队列1 已写入一级+二级+置信度+复核标记）。
     *  建单阶段（队列2）不再调 AI 分类，直接据此归并(dedup)与落库。 */
    private AiClassifyResult childClassify(AppealChild child) {
        String cat = child.getCategory();
        if (cat == null || cat.isBlank()) {
            cat = "OTHER"; // 防御兜底（正常由 insertChild/auditProcessChild 收敛为 8 大类/OTHER）
        }
        boolean nr = child.getNeedReview() != null && child.getNeedReview() == 1;
        return new AiClassifyResult(cat, trimToNull(child.getSubCategory()),
                child.getConfidence() == null ? null : child.getConfidence().doubleValue(),
                null, null, nr);
    }

    /** 子诉求 → 去重归并建单（复用已算好的分类结果，跳过 AI classify） */
    private void processChild(AppealChild child, AiClassifyResult classify, String operator) {
        // 防御：分类结果缺失（AI 降级/解析失败）时不允许建出无类别工单——回滚重走分类
        if (classify == null || classify.primaryCategory() == null || classify.primaryCategory().isBlank()) {
            log.warn("子诉求 {} 分类结果缺失（回滚待生成工单，等待 AI 分类重试）", child.getAppealNo());
            AppealChild rb = new AppealChild();
            rb.setId(child.getId());
            rb.setStatus("READY");
            appealChildMapper.update(rb);
            return;
        }
        if (appealChildMapper.claimForOrder(child.getId()) == 0) {
            log.info("子诉求 {} 已被处理或状态变更，跳过", child.getAppealNo());
            return;
        }
        sseEventService.emit(SseEventService.TYPE_ORDER, "status-change",
                Map.of("childId", child.getId(), "status", "ORDERING"));
        try {
            persistChild(child, classify, operator);
            log.info("子诉求 {} 已处理", child.getAppealNo());
        } catch (Exception e) {
            AppealChild rollback = new AppealChild();
            rollback.setId(child.getId());
            rollback.setStatus("READY");
            appealChildMapper.update(rollback);
            throw e;
        }
    }

    /** 落库（不含原子认领）：findMergeTarget → 归并/新建，完成后子诉求状态置为 ORDERED */
    private void persistChild(AppealChild child, AiClassifyResult classify, String operator) {
        WorkOrder target = orderService.findMergeTarget(child, classify);
        if (target != null) {
            orderService.mergeIntoOrder(target.getId(), child, operator);
        } else {
            orderService.createFromAppeal(child, classify, operator);
        }
        // 建单完成：状态 ORDERING → ORDERED（已生成工单）
        AppealChild done = new AppealChild();
        done.setId(child.getId());
        done.setStatus("ORDERED");
        appealChildMapper.update(done);
        // SSE 推送：子诉求状态 → 已生成工单
        sseEventService.emit(SseEventService.TYPE_ORDER, "status-change",
                Map.of("childId", child.getId(), "status", "ORDERED"));
    }

    /** 撤回父诉求拆分：删除所有子诉求，父诉求回退为 TITLE_GENERATED（未拆分，标题已生成可重拆）。
     *  已关联工单的子诉求先解绑（复用管理端 demergeChildAppeal，避免 work_order_appeal 孤儿引用） */
    @Transactional
    public void withdrawSplit(Long parentId) {
        AppealParent parent = appealParentMapper.selectById(parentId);
        if (parent == null || !"CHILDREN_GENERATED".equals(parent.getStatus())) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "仅「已拆分」状态的父诉求可撤回");
        }
        // 删除所有子诉求：已建单的子诉求先解绑工单关联（降关注度/等级），防孤儿
        List<AppealChild> children = appealChildMapper.selectChildrenByParentId(parentId);
        for (AppealChild child : children) {
            Long orderId = workOrderAppealMapper.selectOrderIdByAppealId(child.getId());
            if (orderId != null) {
                orderService.demergeChildAppeal(child.getId(), orderId, "AI-split-withdraw");
            }
            appealChildMapper.deleteById(child.getId());
        }
        // 父诉求回退为「未拆分」（标题已生成，可直接重新拆分）
        AppealParent update = new AppealParent();
        update.setId(parentId);
        update.setStatus("TITLE_GENERATED");
        update.setChildCount(0);
        appealParentMapper.update(update);
        log.info("父诉求 {} 撤回拆分，删除子诉求 {} 条", parent.getAppealNo(), children.size());
    }

    private String buildText(AppealParent a) {
        return (a.getTitle() == null ? "" : a.getTitle()) + "\n" + (a.getContent() == null ? "" : a.getContent());
    }

    /** 兜底标题：内容去空白后取前 25 字（AI 降级时使用，可手动编辑） */
    private String first25(String content) {
        String t = content == null ? "" : content.strip();
        if (t.isEmpty()) {
            return "未命名诉求";
        }
        return t.length() > 25 ? t.substring(0, 25) : t;
    }

    /** 类别列表逗号拼接去重 */
    private String joinCategories(List<AiDetectCategory> categories) {
        if (categories == null || categories.isEmpty()) {
            return null;
        }
        LinkedHashSet<String> set = new LinkedHashSet<>();
        for (AiDetectCategory c : categories) {
            String cat = trimToNull(c.category());
            if (cat != null) {
                set.add(cat);
            }
        }
        return set.isEmpty() ? null : String.join(",", set);
    }

    private String trimToNull(String s) {
        return s == null ? null : (s.isBlank() ? null : s.trim());
    }

    /** 子诉求编号：AS + 时间戳 + 序号（子表内唯一）；加锁防止并发生成重复编号 */
    private synchronized String nextAppealNo() {
        String prefix = "AS" + LocalDateTime.now().format(NO_TS);
        long seq = appealChildMapper.countByPrefix(prefix) + 1;
        return prefix + String.format("%03d", seq);
    }
}