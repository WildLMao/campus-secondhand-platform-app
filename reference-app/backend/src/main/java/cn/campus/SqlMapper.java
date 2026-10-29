package cn.campus;

import java.util.*;
import org.apache.ibatis.annotations.*;

// sql 参数仅允许由服务端固定语句提供；用户数据通过 #{p.xxx} 绑定。
@Mapper
public interface SqlMapper {
    @Select("${sql}")
    List<Map<String, Object>> query(@Param("sql") String sql, @Param("p") Map<String, Object> params);

    @Update("${sql}")
    int execute(@Param("sql") String sql, @Param("p") Map<String, Object> params);
}
