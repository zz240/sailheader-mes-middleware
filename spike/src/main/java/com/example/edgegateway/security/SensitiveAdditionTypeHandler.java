package com.example.edgegateway.security;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;

import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 敏感字段 JSON 类型处理器：数据源 addition 写入时对敏感键值加密，
 * 读取时透明解密。旧明文数据（不以 enc:AESGCM: 开头）直接透传，兼容迁移。
 */
public class SensitiveAdditionTypeHandler extends BaseTypeHandler<Map<String, Object>> {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    /** 敏感键：值在入库前加密、出库时解密。 */
    private static final Set<String> SENSITIVE_KEYS = Set.of(
            "password", "token", "bearerToken", "apiKey", "secret");

    private final CredentialCipher cipher;

    public SensitiveAdditionTypeHandler() {
        this(CredentialCipher.defaultCipher());
    }

    SensitiveAdditionTypeHandler(CredentialCipher cipher) {
        this.cipher = cipher;
    }

    @Override
    public void setNonNullParameter(
            PreparedStatement ps, int i, Map<String, Object> parameter, JdbcType jdbcType) throws SQLException {
        try {
            ps.setString(i, MAPPER.writeValueAsString(encryptMap(parameter)));
        } catch (Exception e) {
            throw new SQLException("序列化数据源配置失败", e);
        }
    }

    @Override
    public Map<String, Object> getNullableResult(ResultSet rs, String columnName) throws SQLException {
        return parse(rs.getString(columnName));
    }

    @Override
    public Map<String, Object> getNullableResult(ResultSet rs, int columnIndex) throws SQLException {
        return parse(rs.getString(columnIndex));
    }

    @Override
    public Map<String, Object> getNullableResult(CallableStatement cs, int columnIndex) throws SQLException {
        return parse(cs.getString(columnIndex));
    }

    private Map<String, Object> parse(String json) throws SQLException {
        if (json == null) {
            return null;
        }
        try {
            return decryptMap(MAPPER.readValue(json, MAP_TYPE));
        } catch (Exception e) {
            throw new SQLException("解析数据源配置失败", e);
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> encryptMap(Map<String, Object> source) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : source.entrySet()) {
            Object value = entry.getValue();
            if (SENSITIVE_KEYS.contains(entry.getKey()) && value instanceof String s) {
                out.put(entry.getKey(), cipher.encrypt(s));
            } else if (value instanceof Map<?, ?> nested) {
                out.put(entry.getKey(), encryptMap((Map<String, Object>) nested));
            } else if (value instanceof List<?> list) {
                out.put(entry.getKey(), encryptList(list));
            } else {
                out.put(entry.getKey(), value);
            }
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private List<Object> encryptList(List<?> source) {
        return source.stream().map(item -> {
            if (item instanceof Map<?, ?> map) {
                return (Object) encryptMap((Map<String, Object>) map);
            }
            if (item instanceof List<?> list) {
                return (Object) encryptList(list);
            }
            return item;
        }).toList();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> decryptMap(Map<String, Object> source) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : source.entrySet()) {
            Object value = entry.getValue();
            if (SENSITIVE_KEYS.contains(entry.getKey()) && value instanceof String s) {
                out.put(entry.getKey(), cipher.decrypt(s));
            } else if (value instanceof Map<?, ?> nested) {
                out.put(entry.getKey(), decryptMap((Map<String, Object>) nested));
            } else if (value instanceof List<?> list) {
                out.put(entry.getKey(), decryptList(list));
            } else {
                out.put(entry.getKey(), value);
            }
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private List<Object> decryptList(List<?> source) {
        return source.stream().map(item -> {
            if (item instanceof Map<?, ?> map) {
                return (Object) decryptMap((Map<String, Object>) map);
            }
            if (item instanceof List<?> list) {
                return (Object) decryptList(list);
            }
            return item;
        }).toList();
    }
}
