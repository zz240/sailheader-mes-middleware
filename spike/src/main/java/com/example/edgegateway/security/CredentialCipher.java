package com.example.edgegateway.security;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;

/**
 * 凭证加密：AES-GCM（256 位，随机 12 字节 IV，128 位 tag）。
 *
 * <p>master key 从环境变量 EDGE_MASTER_KEY 读取（Base64 编码的 32 字节）；
 * 未设置时生成临时 key 并告警（重启后失效，需持久化到环境变量）。
 * 密文格式：enc:AESGCM:base64(iv + ciphertext含tag)。</p>
 */
public final class CredentialCipher {

    public static final String PREFIX = "enc:AESGCM:";
    private static final int IV_LENGTH = 12;
    private static final int TAG_BITS = 128;

    private static final CredentialCipher DEFAULT = createFromEnvironment();

    private final SecretKey key;

    private CredentialCipher(SecretKey key) {
        this.key = key;
    }

    /** 默认实例：从 EDGE_MASTER_KEY 初始化，未设置则生成临时 key。 */
    public static CredentialCipher defaultCipher() {
        return DEFAULT;
    }

    /** 测试/迁移用：用显式 key 构造实例（便于错误 key 场景）。 */
    public static CredentialCipher withKey(byte[] keyBytes) {
        return new CredentialCipher(new SecretKeySpec(keyBytes, "AES"));
    }

    public String encrypt(String plain) {
        if (plain == null || plain.startsWith(PREFIX)) {
            return plain; // 已加密或空值不做二次加密
        }
        try {
            byte[] iv = new byte[IV_LENGTH];
            new SecureRandom().nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            byte[] ciphertext = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            ByteBuffer buffer = ByteBuffer.allocate(iv.length + ciphertext.length);
            buffer.put(iv).put(ciphertext);
            return PREFIX + Base64.getEncoder().encodeToString(buffer.array());
        } catch (Exception e) {
            throw new IllegalStateException("凭证加密失败", e);
        }
    }

    public String decrypt(String value) {
        if (value == null || !value.startsWith(PREFIX)) {
            return value; // 明文/旧数据直接透传
        }
        try {
            byte[] all = Base64.getDecoder().decode(value.substring(PREFIX.length()));
            byte[] iv = Arrays.copyOfRange(all, 0, IV_LENGTH);
            byte[] ciphertext = Arrays.copyOfRange(all, IV_LENGTH, all.length);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            return new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException(
                    "凭证解密失败（EDGE_MASTER_KEY 不匹配或数据损坏）: " + e.getMessage(), e);
        }
    }

    public boolean isEncrypted(String value) {
        return value != null && value.startsWith(PREFIX);
    }

    private static CredentialCipher createFromEnvironment() {
        String envKey = System.getenv("EDGE_MASTER_KEY");
        if (envKey != null && !envKey.isBlank()) {
            return new CredentialCipher(decodeKey(envKey));
        }
        SecretKey generated = generateKey();
        System.err.println("WARN: 未设置环境变量 EDGE_MASTER_KEY，已生成临时 master key（重启后失效）。"
                + "请持久化到环境变量后再加密生产凭证，临时 key: "
                + Base64.getEncoder().encodeToString(generated.getEncoded()));
        return new CredentialCipher(generated);
    }

    private static SecretKey decodeKey(String base64) {
        try {
            byte[] bytes = Base64.getDecoder().decode(base64.trim());
            return new SecretKeySpec(bytes, "AES");
        } catch (Exception e) {
            throw new IllegalStateException("EDGE_MASTER_KEY 不是合法的 Base64 密钥", e);
        }
    }

    private static SecretKey generateKey() {
        try {
            KeyGenerator generator = KeyGenerator.getInstance("AES");
            generator.init(256);
            return generator.generateKey();
        } catch (Exception e) {
            throw new IllegalStateException("生成 master key 失败", e);
        }
    }
}
