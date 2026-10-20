package org.tjetc.backend.entity.common;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 市民端诉求提交记录 citizen_appeal（市民端独立落库，四端数据解耦）
 * 市民提交诉求时除写入管理端诉求域（appeal_parent）外，同步在市民端落一条独立记录；
 * 市民端"我的诉求"等展示可只读本表，与管理端诉求域解耦。
 * parent_id 为流转至管理端后的 appeal_parent.id（同一事务内写入，必然存在）。
 */
@Data
@NoArgsConstructor
public class CitizenAppeal {

    private Long id;
    /** 诉求编号（与 appeal_parent.appeal_no 一致，全局唯一） */
    private String appealNo;
    /** 管理端诉求主表ID（appeal_parent.id） */
    private Long parentId;
    /** 提交市民（citizen_account.id，短信登录账号） */
    private Long citizenUserId;
    /** 市民姓名（可空，市民提交时不强制） */
    private String citizenName;
    /** 市民手机号（登录账号，必填） */
    private String citizenPhone;
    /** 诉求标题（AI 标题生成后可回填；提交时为空） */
    private String title;
    /** 诉求内容 */
    private String content;
    private String province;
    private String city;
    private String district;
    private String street;
    private String community;
    private String addressDetail;
    /** 是否宽泛诉求：1 宽泛（区县级）/ 0 具体 */
    private Integer broadFlag;
    /** 提交时间 */
    private LocalDateTime submitTime;
}
