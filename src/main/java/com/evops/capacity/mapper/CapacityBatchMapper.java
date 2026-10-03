package com.evops.capacity.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.evops.capacity.entity.CapacityBatch;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface CapacityBatchMapper extends BaseMapper<CapacityBatch> {

    /** 批次写操作入口行锁：同批次的入队/退位/递补/超时处理串行化，保证序号与空位不重不漏。 */
    @Select("SELECT * FROM t_capacity_batch WHERE batch_id = #{batchId} FOR UPDATE")
    CapacityBatch selectByBatchForUpdate(@Param("batchId") Long batchId);
}
