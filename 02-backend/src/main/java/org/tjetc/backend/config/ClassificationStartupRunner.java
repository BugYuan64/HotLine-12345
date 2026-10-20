package org.tjetc.backend.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.tjetc.backend.service.admin.ClassifyService;

/**
 * 启动时全量重建分类知识桥（cls:keywords/cls:rules/cls:labels/cls:primary）：
 * 数据库直插/重置后 Redis 桥可能为空，导致 classify 无类别候选（全部退化为 OTHER）。
 * Order 早于预测任务，保证预判/拆分/建单使用的类别候选完整。
 */
@Slf4j
@Component
@Order(1)
@RequiredArgsConstructor
public class ClassificationStartupRunner implements ApplicationRunner {

    private final ClassifyService classifyService;

    @Override
    public void run(ApplicationArguments args) {
        try {
            classifyService.rebuildBridges();
        } catch (Exception e) {
            log.warn("启动重建分类知识桥失败：{}", e.getMessage());
        }
        try {
            classifyService.seedRagVectorsIfMissing();
        } catch (Exception e) {
            log.warn("启动播种 RAG 向量库失败：{}", e.getMessage());
        }
    }
}
