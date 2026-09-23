-- AuditLog: platform-wide audit trail (organization/subscription changes).
-- Work item history lives in the tenant database (WorkItemAudit), not here.
CREATE TABLE `AuditLog` (
    `AuditId` BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT 'Internal ID',
    `OrganizationId` BIGINT NOT NULL COMMENT 'Which organization',
    `Action` VARCHAR(255) NOT NULL COMMENT 'Action performed',
    `EntityType` VARCHAR(100) NOT NULL COMMENT 'Organization, Subscription, etc.',
    `EntityId` BIGINT NOT NULL COMMENT 'Entity ID',
    `ChangedBy` BINARY(16) NULL COMMENT 'UserCode of the tenant user who made the change (no FK: users live in the tenant DB)',
    `OldValue` JSON NULL COMMENT 'Previous value',
    `NewValue` JSON NULL COMMENT 'New value',
    `CreatedAt` TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT 'UTC timestamp',
    INDEX `idx_audit_org` (`OrganizationId`),
    INDEX `idx_audit_date` (`CreatedAt`),
    CONSTRAINT `fk_audit_org` FOREIGN KEY (`OrganizationId`) REFERENCES `Organization` (`OrganizationId`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
