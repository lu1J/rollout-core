package io.github.lu1j.rolloutcore.server.service;

import io.github.lu1j.rolloutcore.domain.FlagValueType;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import tools.jackson.databind.JsonNode;
import java.util.List;

/** HTTP input shapes; immutable commands passed into the service. No entity binding. */
public final class Commands {
    private Commands() {}
    public static final String KEY = "[a-z0-9._-]{1,100}";

    public record CreateProject(@NotNull @Pattern(regexp = KEY) String projectKey,
                                @NotBlank @Size(max = 200) String name) {}
    public record CreateEnvironment(@NotNull @Pattern(regexp = KEY) String envKey,
                                    @NotBlank @Size(max = 200) String name) {}
    public record CreateVariant(@NotNull @Pattern(regexp = KEY) String variantKey,
                                @NotNull JsonNode value) {}
    public record CreateFlag(@NotNull @Pattern(regexp = KEY) String flagKey,
                             @NotBlank @Size(max = 200) String name,
                             @NotNull FlagValueType valueType,
                             @Size(max = 100) List<@NotNull @Valid CreateVariant> variants) {}
    public record CreateConfig(@NotNull @Pattern(regexp = KEY) String defaultVariantKey,
                               @NotNull Boolean enabled) {}
    public record UpdateConfig(@NotNull @Pattern(regexp = KEY) String defaultVariantKey,
                               @NotNull Boolean enabled,
                               @NotNull @PositiveOrZero Long expectedVersion) {}
    public record SwitchFlag(@NotNull @PositiveOrZero Long expectedVersion) {}
}
