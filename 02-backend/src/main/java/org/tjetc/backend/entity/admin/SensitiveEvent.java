package org.tjetc.backend.entity.admin;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 敏感事件记录表 sensitive_event（需求 表8 / API 10.7）
 */
@Data
@NoArgsConstructor
public class SensitiveEvent {

    private Long id;
    /** 来源诉求ID（旧版旁路模式）；剥离模式下为 NULL */
    private Long appealId;
    /** 敏感事件编号（SE+时间戳+序号；剥离模式生成） */
    private String appealNo;
    /** 风险类型：极端情绪/群体事件苗头/公共安全 */
    private String riskType;
    /** 风险等级：GENERAL/SEVERE/URGENT */
    private String riskLevel;
    private LocalDateTime triggerTime;
    /** 处理状态：UNHANDLED/HANDLED */
    private String handleStatus;
    private String pushTargets;
    /** AI 处置建议 */
    private String advice;
    /** 诉求标题（市民提交时为空） */
    private String title;
    /** 诉求内容（原始全文快照） */
    private String content;
    /** 诉求类别（8大类code或OTHER，市民提交自带） */
    private String category;
    /** 市民姓名（提诉人） */
    private String citizenName;
    /** 市民手机号（提诉人） */
    private String citizenPhone;
    private String province;
    private String city;
    private String district;
    private String street;
    private String community;
    private String addressDetail;
    /** 完整地址（district+street+address_detail拼接） */
    private String region;
    /** 是否宽泛诉求：1 宽泛 / 0 具体 */
    private Integer broadFlag;
    /** 附件文件ID列表（逗号分隔，sys_file.id；剥离模式下附件不绑定诉求，靠此列关联） */
    private String attachmentIds;
}
