package io.github.lu1j.rolloutcore.server.mapper;

import io.github.lu1j.rolloutcore.domain.FlagVariant;
import org.apache.ibatis.annotations.Param;
import java.util.List;

public interface FlagVariantMapper {
    int insert(FlagVariant value);
    FlagVariant findByKey(@Param("flagId") long flagId, @Param("variantKey") String variantKey);
    List<FlagVariant> listByFlag(@Param("flagId") long flagId);
}
