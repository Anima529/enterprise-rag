package com.example.rag.system.mapper;

import com.example.rag.system.domain.DatabaseInfo;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface SystemMapper {
    /** 读取数据库版本和当前数据库名称。 */
    DatabaseInfo databaseInfo();
}
