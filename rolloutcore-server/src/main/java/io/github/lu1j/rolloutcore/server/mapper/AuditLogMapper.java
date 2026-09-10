package io.github.lu1j.rolloutcore.server.mapper;

import io.github.lu1j.rolloutcore.domain.AuditLog;
import org.apache.ibatis.annotations.Param;
import java.util.List;

public interface AuditLogMapper {
    int insert(AuditLog value);
    List<AuditLog> list(@Param("projectId") long projectId, @Param("limit") int limit, @Param("offset") int offset);
}
