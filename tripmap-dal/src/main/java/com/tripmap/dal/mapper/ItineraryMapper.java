package com.tripmap.dal.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.tripmap.dal.entity.ItineraryEntity;
import org.apache.ibatis.annotations.Mapper;

/**
 * ItineraryEntity Mapper。
 * 单版本设计：sessionId 业务唯一（uk_session），重跑 = 删旧插新（应用层）。
 */
@Mapper
public interface ItineraryMapper extends BaseMapper<ItineraryEntity> {
}
