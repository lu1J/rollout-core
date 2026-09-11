package io.github.lu1j.rolloutcore.server.mapper;

import io.github.lu1j.rolloutcore.domain.FlagEnvironmentConfig;
import org.apache.ibatis.annotations.Param;
import java.util.List;

public interface FlagEnvironmentConfigMapper {
    int insert(FlagEnvironmentConfig value);
    FlagEnvironmentConfig find(@Param("flagId") long flagId, @Param("environmentId") long environmentId);
    int update(@Param("config") FlagEnvironmentConfig config, @Param("expectedVersion") long expectedVersion);
    int updatePolicy(@Param("config") FlagEnvironmentConfig config, @Param("expectedVersion") long expectedVersion);
}
