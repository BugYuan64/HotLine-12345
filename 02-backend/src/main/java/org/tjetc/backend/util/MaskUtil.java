package org.tjetc.backend.util;

import cn.hutool.core.util.DesensitizedUtil;
import cn.hutool.core.util.StrUtil;

/**
 * 数据脱敏工具（需求 19 数据脱敏与安全管控 / API 14.2 数据格式约定）：
 * - 手机号中间 4 位脱敏：138****5678
 * - 姓名隐藏中间字：张*三（两字姓名隐藏姓：*三）
 */
public final class MaskUtil {

    private MaskUtil() {
    }

    public static String maskPhone(String phone) {
        if (StrUtil.isBlank(phone)) {
            return phone;
        }
        return DesensitizedUtil.mobilePhone(phone);
    }

    public static String maskName(String name) {
        if (StrUtil.isBlank(name)) {
            return name;
        }
        return DesensitizedUtil.chineseName(name);
    }
}
