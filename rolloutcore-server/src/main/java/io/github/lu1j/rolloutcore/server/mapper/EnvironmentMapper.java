package io.github.lu1j.rolloutcore.server.mapper;

import io.github.lu1j.rolloutcore.domain.Environment;
import org.apache.ibatis.annotations.Param;
import java.util.List;

public interface EnvironmentMapper {
    int insert(Environment value);
    Environment findByKey(@Param("projectId") long projectId, @Param("envKey") String envKey);
    List<Environment> listByProject(@Param("projectId") long projectId);
}
