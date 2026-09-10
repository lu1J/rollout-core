package io.github.lu1j.rolloutcore.server.mapper;

import io.github.lu1j.rolloutcore.domain.Project;
import org.apache.ibatis.annotations.Param;
import java.util.List;

public interface ProjectMapper {
    int insert(Project value);
    Project findByKey(@Param("projectKey") String projectKey);
}
