package org.tjetc.backend.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.tjetc.backend.service.admin.ClassifyService;

/**
 * 启动时把数据库分类体系（一级分类/二级标签/关键词/规则）全量同步到 Redis 分类知识桥，
 * 供 Python AI 服务读取（Python 已去掉内置兜底，完全依赖 Redis）
 */
@Slf4j
@Component
public class ClassifyStartupRunner implements ApplicationRunner {

    private final ClassifyService classifyService;

    public ClassifyStartupRunner(ClassifyService classifyService) {
        this.classifyService = classifyService;
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            log.info("启动同步分类知识桥到 Redis");
            classifyService.syncAll();
        } catch (Exception e) {
            log.warn("启动同步分类知识桥失败：{}", e.getMessage());
        }
    }
}
