-- OrganizationSubscription: plan and limits. Limits are copied into the JWT claims at login.
-- UUID columns are BINARY(16). The default builds a random (version 4) UUID from ONE RANDOM_BYTES call by masking
-- and then setting the version (4) and variant (10) bits. MySQL's own UUID() is version 1 (time + MAC based), which
-- is not opaque, and column defaults cannot call stored functions. Read with BIN_TO_UUID(), write with UUID_TO_BIN().
CREATE TABLE `OrganizationSubscription` (
    `SubscriptionId` BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT 'Internal ID',
    `SubscriptionCode` BINARY(16) NOT NULL DEFAULT ((RANDOM_BYTES(16) & X'FFFFFFFFFFFF0FFF3FFFFFFFFFFFFFFF') | X'00000000000040008000000000000000') COMMENT 'UUID v4',
    `OrganizationId` BIGINT NOT NULL COMMENT 'Organization (one-to-one)',
    `PlanType` ENUM('FREE', 'PRO', 'ENTERPRISE') NOT NULL DEFAULT 'FREE' COMMENT 'Subscription tier',
    `MaxProjects` INT NOT NULL COMMENT 'Free:1, Pro:10, Enterprise:999',
    `MaxUsers` INT NOT NULL COMMENT 'Free:5, Pro:50, Enterprise:999',
    `MaxStorageMB` INT NOT NULL COMMENT 'Free:100, Pro:5000, Enterprise:100000',
    `ExpiresAt` TIMESTAMP NULL COMMENT 'Expiration date (NULL = perpetual)',
    `IsActive` BOOLEAN NOT NULL DEFAULT TRUE COMMENT 'Soft delete flag',
    `CreatedAt` TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT 'UTC timestamp',
    `UpdatedAt` TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT 'UTC timestamp',
    UNIQUE KEY `uk_subscription_code` (`SubscriptionCode`),
    UNIQUE KEY `uk_subscription_org` (`OrganizationId`),
    INDEX `idx_subscription_plan` (`PlanType`),
    CONSTRAINT `fk_subscription_org` FOREIGN KEY (`OrganizationId`) REFERENCES `Organization` (`OrganizationId`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
