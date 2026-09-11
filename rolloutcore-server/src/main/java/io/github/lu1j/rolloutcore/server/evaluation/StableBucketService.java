package io.github.lu1j.rolloutcore.server.evaluation;

import io.github.lu1j.rolloutcore.server.service.BusinessException;
import io.github.lu1j.rolloutcore.server.service.InputRules;
import org.springframework.stereotype.Component;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

@Component
public class StableBucketService {
    /** Permanent v1 contract: UTF-8, NUL separators, SHA-256, first 4 bytes big-endian unsigned, mod 10000. */
    public int bucket(String projectKey, String environmentKey, String flagKey, String userId) {
        InputRules.key(projectKey);
        InputRules.key(environmentKey);
        InputRules.key(flagKey);
        if (userId == null || userId.isBlank() || userId.length() > 256 || userId.indexOf('\0') >= 0) {
            throw BusinessException.validation("userId must contain 1-256 characters and no NUL");
        }
        String input = projectKey + '\0' + environmentKey + '\0' + flagKey + '\0' + userId;
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(input.getBytes(StandardCharsets.UTF_8));
            return (int) (Integer.toUnsignedLong(ByteBuffer.wrap(digest).getInt()) % 10000);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("JDK SHA-256 unavailable", impossible);
        }
    }
}
