package org.tjetc.backend.mapper.staff;

import org.apache.ibatis.annotations.Mapper;
import org.tjetc.backend.entity.staff.OrderDisposeAttachment;

import java.util.List;

/**
 * 处置凭证附件 Mapper（API 11.1.2：凭证/现场照片，最多5个，单个≤10MB）
 */
@Mapper
public interface OrderDisposeAttachmentMapper {

    int insert(OrderDisposeAttachment attachment);

    List<OrderDisposeAttachment> selectByOrderId(Long orderId);

    int countByOrderId(Long orderId);
}