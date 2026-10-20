package org.tjetc.backend.common;

import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 分页数据结构（API 接口文档 2.3）：
 * { list, total, page, page_size, total_pages }
 */
public record PageData<T>(List<T> list, long total, int page, int page_size, int total_pages) {

    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("list", list);
        map.put("total", total);
        map.put("page", page);
        map.put("page_size", page_size);
        map.put("total_pages", total_pages);
        return map;
    }

    public static <T> PageData<T> of(List<T> list, long total, int page, int pageSize) {
        int totalPages = pageSize == 0 ? 0 : (int) Math.ceil((double) total / pageSize);
        return new PageData<>(list, total, page, pageSize, totalPages);
    }
}
