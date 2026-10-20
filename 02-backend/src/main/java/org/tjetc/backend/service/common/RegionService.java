package org.tjetc.backend.service.common;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.tjetc.backend.entity.common.SysRegion;
import org.tjetc.backend.mapper.common.SysRegionMapper;

import java.util.List;

/**
 * 区划字典服务：为前端「区县 → 街道/镇/乡」两级联动下拉提供数据。
 * 街道办注册前需先选区县，再按其下级乡镇级区划选择所属街道/镇/乡。
 */
@Service
@RequiredArgsConstructor
public class RegionService {

    private final SysRegionMapper regionMapper;

    /** 全部区县（level=COUNTY） */
    public List<SysRegion> listCounties() {
        return regionMapper.selectCounties();
    }

    /** 某区县下的街道/镇/乡 */
    public List<SysRegion> listChildren(String parentCode) {
        return regionMapper.selectChildrenByParent(parentCode);
    }

    /** 按编码精确查询（注册校验） */
    public SysRegion getByCode(String regionCode) {
        return regionMapper.selectByCode(regionCode);
    }

    /** 按区县中文名反查区县编码；查不到返回 null */
    public String resolveCountyCode(String countyName) {
        if (countyName == null || countyName.isBlank()) {
            return null;
        }
        String trimmed = countyName.trim();
        String code = regionMapper.selectCodeByNameAndLevel(trimmed, "COUNTY");
        if (code != null) {
            return code;
        }
        // 前缀兜底：历史数据/街道级辖区（如「海港区新城区」）按区县名前缀命中，避免分发候选退化为全市
        for (SysRegion county : listCounties()) {
            if (trimmed.startsWith(county.getRegionName())) {
                return county.getRegionCode();
            }
        }
        return null;
    }

    /** 按区县编码 + 街道/镇/乡中文名反查乡镇级编码；查不到返回 null */
    public String resolveStreetCode(String countyCode, String streetName) {
        if (countyCode == null || countyCode.isBlank() || streetName == null || streetName.isBlank()) {
            return null;
        }
        return regionMapper.selectChildCodeByParentAndName(countyCode, streetName.trim());
    }
}