package com.tripmap.dal.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.tripmap.dal.entity.PoiEntity;
import org.apache.ibatis.annotations.Mapper;

/**
 * PoiEntity Mapper。
 * 口径见《tripmap数据库设计说明书》：无物理外键，引用完整性由应用层校验。
 */
@Mapper
public interface PoiMapper extends BaseMapper<PoiEntity> {
}
