package com.example.edgegateway.web;

import com.example.edgegateway.service.CredentialMigrationService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** 管理端点：存量数据一次性迁移（凭证加密）。 */
@RestController
@RequestMapping("/api/admin")
public class AdminController {

    private final CredentialMigrationService migrationService;

    public AdminController(CredentialMigrationService migrationService) {
        this.migrationService = migrationService;
    }

    @PostMapping("/migrate-credentials")
    public Map<String, Object> migrateCredentials() {
        List<String> migrated = migrationService.migrateAll();
        return Map.of("migrated", migrated.size(), "items", migrated);
    }
}
