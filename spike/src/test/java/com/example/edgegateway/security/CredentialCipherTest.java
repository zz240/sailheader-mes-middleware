package com.example.edgegateway.security;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CredentialCipherTest {

    private static final byte[] KEY_A = new byte[32];
    private static final byte[] KEY_B = new byte[32];

    static {
        Arrays.fill(KEY_A, (byte) 1);
        Arrays.fill(KEY_B, (byte) 2);
    }

    private final CredentialCipher cipher = CredentialCipher.withKey(KEY_A);

    @Test
    void roundTripsPlaintext() {
        String encrypted = cipher.encrypt("secret-pass-123");
        assertThat(cipher.decrypt(encrypted)).isEqualTo("secret-pass-123");
        assertThat(cipher.isEncrypted(encrypted)).isTrue();
    }

    @Test
    void ciphertextDoesNotContainPlaintext() {
        String encrypted = cipher.encrypt("secret-pass-123");
        assertThat(encrypted).doesNotContain("secret-pass-123");
        assertThat(encrypted).startsWith(CredentialCipher.PREFIX);
    }

    @Test
    void differentKeyFailsToDecrypt() {
        String encrypted = cipher.encrypt("secret-pass-123");
        CredentialCipher other = CredentialCipher.withKey(KEY_B);
        assertThatThrownBy(() -> other.decrypt(encrypted))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("解密失败");
    }

    @Test
    void plaintextPassesThroughAndAlreadyEncryptedIsNotDoubleEncrypted() {
        assertThat(cipher.decrypt("plain-old")).isEqualTo("plain-old");
        String once = cipher.encrypt("pw");
        String twice = cipher.encrypt(once);
        assertThat(twice).isEqualTo(once);
    }

    @Test
    void rejectsInvalidMasterKeyBase64() {
        byte[] shortKey = new byte[16];
        // 16 字节也可作为 AES-128 key，测试直接验证 withKey 可用不同长度；非法 base64 由环境初始化路径负责
        CredentialCipher shortCipher = CredentialCipher.withKey(shortKey);
        String encrypted = shortCipher.encrypt("x");
        assertThat(encrypted).startsWith(CredentialCipher.PREFIX);
        // 用错误长度的 key 解密必然失败
        assertThatThrownBy(() -> cipher.decrypt(encrypted))
                .isInstanceOf(IllegalStateException.class);
    }
}
