package com.tripmap.dal.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.tripmap.dal.entity.SessionCheckpointEntity;
import org.apache.ibatis.annotations.Mapper;

/**
 * SessionCheckpointEntity Mapper。
 * 恢复查询固定模式：WHERE session_id=? ORDER BY seq DESC LIMIT 1（走 uk_session_seq）。
 */
@Mapper
public interface SessionCheckpointMapper extends BaseMapper<SessionCheckpointEntity> {
}
