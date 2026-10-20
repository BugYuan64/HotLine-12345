package org.tjetc.backend.mapper.common;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.tjetc.backend.entity.common.SysRegion;

import java.util.List;

@Mapper
public interface SysRegionMapper {

    /** 查询全部区县（level=COUNTY 且启用），下拉用 */
    List<SysRegion> selectCounties();

    /** 查询某区县下的街道/镇/乡（parent_code 命中且 level 属于乡镇级） */
    List<SysRegion> selectChildrenByParent(String parentCode);

    /** 按区划编码精确查询（注册时校验区县/街道合法性用） */
    SysRegion selectByCode(String regionCode);

    /** 按中文名 + 层级反查区划编码（中文名在层级内唯一时才可靠） */
    String selectCodeByNameAndLevel(@Param("name") String name, @Param("level") String level);

    /** 按父级编码 + 中文名反查乡镇级区划编码（避免跨区县重名） */
    String selectChildCodeByParentAndName(@Param("parentCode") String parentCode, @Param("name") String name);
}