package io.github.lu1j.rolloutcore.server.mapper;

import io.github.lu1j.rolloutcore.domain.FeatureFlag;
import org.apache.ibatis.annotations.Param;
import java.util.List;

public interface FeatureFlagMapper {
    int insert(FeatureFlag value);
    FeatureFlag findByKey(@Param("projectId") long projectId, @Param("flagKey") String flagKey);
}
