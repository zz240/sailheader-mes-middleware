package com.example.edgegateway.service;

import com.example.edgegateway.mapper.SiteMapper;
import com.example.edgegateway.model.Site;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import java.util.NoSuchElementException;

/**
 * 站点 API Key：生成时返回一次明文，库里只存 SHA-256 哈希（撤销加密后仍不落明文）。
 * 外部触发端点用 X-API-Key 头携带明文 key，哈希后与库中比对（常量时间比较）。
 */
@Service
public class ApiKeyService {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final SiteMapper siteMapper;

    public ApiKeyService(SiteMapper siteMapper) {
        this.siteMapper = siteMapper;
    }

    /** 生成新 API Key（返回明文仅此一次），站点不存在抛 404。 */
    public String generate(Long siteId) {
        Site site = siteMapper.selectById(siteId);
        if (site == null) {
            throw new NoSuchElementException("站点不存在: " + siteId);
        }
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        String raw = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        site.setApiKey(hash(raw));
        siteMapper.updateById(site);
        return raw;
    }

    /** 校验候选 key 是否匹配站点存储的哈希。 */
    public boolean matches(Long siteId, String candidate) {
        if (siteId == null || candidate == null || candidate.isBlank()) {
            return false;
        }
        Site site = siteMapper.selectById(siteId);
        if (site == null || site.getApiKey() == null) {
            return false;
        }
        byte[] expected = site.getApiKey().getBytes(StandardCharsets.UTF_8);
        byte[] actual = hash(candidate).getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(expected, actual);
    }

    private String hash(String raw) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(raw.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }
}
