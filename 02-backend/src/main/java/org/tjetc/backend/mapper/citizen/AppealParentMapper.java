package org.tjetc.backend.mapper.citizen;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.tjetc.backend.entity.citizen.AppealParent;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 父诉求 Mapper（appeal_parent）：市民一次提交的原始诉求，1对多关联子诉求
 */
@Mapper
public interface AppealParentMapper {

    AppealParent selectByNo(@Param("appealNo") String appealNo);

    AppealParent selectById(@Param("id") Long id);

    /** 诉求编号当日序号（AP+时间戳+序号，父表内唯一），用于生成诉求编号 */
    long countByPrefix(@Param("prefix") String prefix);

    /** 父诉求总量统计（全局数据统计 API 8.4 诉求总数口径：按父诉求创建时间） */
    long countByStart(@Param("start") LocalDateTime start);

    /** 管理端父诉求多条件分页查询（API 6.1）：appealNo/status/keyword */
    List<AppealParent> selectPage(@Param("appealNo") String appealNo,
                                  @Param("status") String status,
                                  @Param("keyword") String keyword,
                                  @Param("offset") int offset,
                                  @Param("size") int size);

    long countPage(@Param("appealNo") String appealNo,
                   @Param("status") String status,
                   @Param("keyword") String keyword);

    /** 待拆分的父诉求（status=TITLE_GENERATED，标题已生成未拆分），供定时任务扫描 */
    List<AppealParent> selectPendingParents(@Param("limit") int limit);

    /** 标题阶段扫描：AI 标题生成中（PENDING_AI）的父诉求 */
    List<AppealParent> selectPendingTitleParents(@Param("limit") int limit);

    /** 原子认领父诉求拆分：仅 PENDING_AI → CHILDREN_GENERATED，返回受影响行数（0 表示已被处理） */
    int claimForSplit(@Param("id") Long id);

    int insert(AppealParent appeal);

    /** 管理端编辑诉求：更新诉求可编辑字段 */
    int update(AppealParent appeal);

    /** 管理端删除诉求（物理删除） */
    int deleteById(@Param("id") Long id);

    /** 公众端：按市民手机号分页查询诉求（首页办理进度 / 查看全部诉求列表）；appealNo 可空，为空查全部 */
    List<AppealParent> selectPageByPhone(@Param("phone") String phone,
                                         @Param("appealNo") String appealNo,
                                         @Param("offset") int offset,
                                         @Param("size") int size);

    /** 公众端：统计某手机号诉求总数；appealNo 可空，为空统计全部 */
    long countPageByPhone(@Param("phone") String phone,
                          @Param("appealNo") String appealNo);
}