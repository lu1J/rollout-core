package io.github.lu1j.rolloutcore.server.mapper;

import io.github.lu1j.rolloutcore.domain.OutboxEvent;
import org.apache.ibatis.annotations.Param;
import java.time.LocalDateTime;
import java.util.List;

public interface OutboxEventMapper {
    int insert(OutboxEvent event);
    // Must run inside the relay transaction; concurrent relays skip locked rows.
    List<OutboxEvent> findPending(@Param("limit") int limit);
    int markSent(@Param("id") long id, @Param("updatedAt") LocalDateTime updatedAt);
}
