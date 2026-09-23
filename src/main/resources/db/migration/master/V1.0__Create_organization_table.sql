-- Organization: one row per tenant. Points at that tenant's own database.
-- The master DB holds tenant-level data only (organizations, subscriptions, platform audit).
-- It has NO user accounts: every user lives in, and authenticates against, their tenant's database.
-- UUID columns are BINARY(16). The default builds a random (version 4) UUID from ONE RANDOM_BYTES call by masking
-- and then setting the version (4) and variant (10) bits. MySQL's own UUID() is version 1 (time + MAC based), which
-- is not opaque, and column defaults cannot call stored functions. Read with BIN_TO_UUID(), write with UUID_TO_BIN().
CREATE TABLE `Organization` (
    `OrganizationId` BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT 'Internal ID',
    `TenantId` BINARY(16) NOT NULL DEFAULT ((RANDOM_BYTES(16) & X'FFFFFFFFFFFF0FFF3FFFFFFFFFFFFFFF') | X'00000000000040008000000000000000') COMMENT 'UUID v4 - Exposed to client (the tenantId in the JWT); the tenant DB is named tenant_{TenantId without dashes}',
    `OrganizationCode` VARCHAR(30) NOT NULL COMMENT 'Short, easy-to-remember code chosen by the owner; users type it at login (e.g. acme). Unique, case-insensitive; the app stores it lowercase',
    `Name` VARCHAR(255) NOT NULL COMMENT 'Organization name',
    `Email` VARCHAR(255) NOT NULL COMMENT 'Organization email',
    `DatabaseHost` VARCHAR(255) NOT NULL COMMENT 'Host of tenant database (e.g., localhost:3306)',
    `DatabaseName` VARCHAR(255) NOT NULL COMMENT 'Tenant database name: tenant_{TenantId without dashes}',
    `DatabaseUsername` VARCHAR(255) NOT NULL COMMENT 'Database user (encrypted in prod)',
    `DatabasePassword` VARCHAR(255) NOT NULL COMMENT 'Database password (encrypted in prod)',
    `IsActive` BOOLEAN NOT NULL DEFAULT TRUE COMMENT 'Soft delete flag',
    `CreatedAt` TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT 'UTC timestamp',
    `UpdatedAt` TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT 'UTC timestamp',
    UNIQUE KEY `uk_organization_tenant_id` (`TenantId`),
    UNIQUE KEY `uk_organization_code` (`OrganizationCode`),
    UNIQUE KEY `uk_organization_email` (`Email`),
    UNIQUE KEY `uk_organization_database_name` (`DatabaseName`),
    -- Shape only (3-30 chars, letters/digits/hyphens, no leading or trailing hyphen). Lowercasing, reserved words and
    -- availability are checked by the application.
    CONSTRAINT `chk_organization_code_format` CHECK (`OrganizationCode` REGEXP '^[a-z0-9][a-z0-9-]{1,28}[a-z0-9]$')
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
