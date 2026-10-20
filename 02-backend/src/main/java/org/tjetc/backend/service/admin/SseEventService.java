package org.tjetc.backend.service.admin;

import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * SSE 实时推送服务（诉求自动处理状态变化 → 前端被动刷新）
 * 三条流：split（父诉求拆分）、order（子诉求生成工单）、dispatch（工单自动派单）。
 * 连接按「用户 → 连接」映射存储：同一用户重复订阅同一流时先关闭旧连接（不累积）。
 * 心跳：每 10 秒向所有连接发送注释帧，断开的连接（页面刷新/关闭）立即清理。
 */
@Slf4j
@Service
public class SseEventService {

    public static final String TYPE_SPLIT = "split";
    public static final String TYPE_ORDER = "order";
    /** 工单自动派单流（工单管理页自动派单状态变化推送） */
    public static final String TYPE_DISPATCH = "dispatch";

    private final Map<Long, SseEmitter> splitUsers = new ConcurrentHashMap<>();
    private final Map<Long, SseEmitter> orderUsers = new ConcurrentHashMap<>();
    private final Map<Long, SseEmitter> dispatchUsers = new ConcurrentHashMap<>();

    /** 订阅某个流，返回 SseEmitter 交给 Controller（30 分钟无事件自动超时兜底）。
     *  按用户去重：同一用户重复订阅同一流时，先关闭旧连接（避免连接数随页面重进/重连无限累积） */
    public SseEmitter subscribe(String type, Long userId) {
        SseEmitter emitter = new SseEmitter(30L * 60 * 1000);
        Map<Long, SseEmitter> map = mapOf(type);
        Long key = (userId != null) ? userId : -System.nanoTime();  // 无 userId 时用临时负键（仍可心跳/清理）
        SseEmitter old = map.put(key, emitter);
        if (old != null) {
            try { old.complete(); } catch (Exception ignored) { }
            log.info("SSE 替换 {} 流用户 {} 的旧连接（去重）", type, userId);
        }
        final Long keyFinal = key;
        emitter.onCompletion(() -> map.remove(keyFinal, emitter));
        emitter.onTimeout(() -> map.remove(keyFinal, emitter));
        emitter.onError(e -> map.remove(keyFinal, emitter));
        log.info("SSE 订阅 {} 流，当前连接数 {}", type, map.size());
        return emitter;
    }

    /** 心跳：向所有连接发送注释帧（:hb），断开的连接立即清理 */
    @Scheduled(fixedDelay = 10000)
    public void heartbeat() {
        sendComment(splitUsers.values());
        sendComment(orderUsers.values());
        sendComment(dispatchUsers.values());
    }

    private void sendComment(Collection<SseEmitter> emitters) {
        for (SseEmitter emitter : emitters) {
            try {
                emitter.send(SseEmitter.event().comment("hb"));
            } catch (Exception e) {
                emitters.remove(emitter);
            }
        }
    }

    /** 广播事件到某个流的所有连接 */
    public void emit(String type, String eventName, Object data) {
        for (SseEmitter emitter : mapOf(type).values()) {
            try {
                emitter.send(SseEmitter.event().name(eventName).data(data));
            } catch (Exception e) {
                mapOf(type).values().remove(emitter);
            }
        }
    }

    private Map<Long, SseEmitter> mapOf(String type) {
        if (TYPE_SPLIT.equals(type)) return splitUsers;
        if (TYPE_DISPATCH.equals(type)) return dispatchUsers;
        return orderUsers;
    }
}
