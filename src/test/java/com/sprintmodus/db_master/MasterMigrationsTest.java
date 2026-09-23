package com.sprintmodus.db_master;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.output.MigrateResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Applies every migration to a real, empty MySQL and checks the resulting schema. Uses MySQL
 * Connector/J, which is the driver the services will use.
 */
@Testcontainers
class MasterMigrationsTest {

	private static final String DATABASE = "master_db";
	private static final String PASSWORD = "test";
	// MySQL 8.4+ defaults to caching_sha2_password, which needs this over a non-TLS connection
	private static final String PARAMS = "?allowPublicKeyRetrieval=true";

	@Container
	static final GenericContainer<?> MARIADB = new GenericContainer<>("mysql:9.7")
			.withEnv("MYSQL_ROOT_PASSWORD", PASSWORD)
			.withEnv("MYSQL_DATABASE", DATABASE)
			.withExposedPorts(3306)
			// the entrypoint runs a temporary socket-only server first; only the final server logs port 3306
			.waitingFor(Wait.forLogMessage(".*mysqld: ready for connections.*port: 3306 .*", 1));

	private String jdbcUrl;

	@BeforeEach
	void freshDatabase() throws SQLException {
		String server = "jdbc:mysql://" + MARIADB.getHost() + ":" + MARIADB.getMappedPort(3306);
		try (Connection connection = DriverManager.getConnection(server + "/" + PARAMS, "root", PASSWORD);
				Statement statement = connection.createStatement()) {
			statement.execute("DROP DATABASE IF EXISTS " + DATABASE);
			statement.execute("CREATE DATABASE " + DATABASE + " CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci");
		}
		jdbcUrl = server + "/" + DATABASE + PARAMS;
	}

	private Flyway flyway() {
		return Flyway.configure()
				.dataSource(jdbcUrl, "root", PASSWORD)
				.locations("classpath:db/migration/master")
				.load();
	}

	@Test
	void appliesEveryMigrationToAnEmptyDatabase() {
		Flyway flyway = flyway();

		MigrateResult result = flyway.migrate();

		assertThat(result.success).isTrue();
		assertThat(result.migrationsExecuted).isEqualTo(3);
		assertThat(flyway.info().pending()).isEmpty();
		flyway.validate();
	}

	@Test
	void migratingAgainIsANoOp() {
		flyway().migrate();

		MigrateResult second = flyway().migrate();

		assertThat(second.migrationsExecuted).isZero();
	}

	@Test
	void createsExactlyTheExpectedTables() throws SQLException {
		flyway().migrate();

		assertThat(tables()).containsExactlyInAnyOrder("Organization", "OrganizationSubscription", "AuditLog");
	}

	private static final String INSERT_ORGANIZATION = "INSERT INTO Organization (TenantId, OrganizationCode, Name, Email, "
			+ "DatabaseHost, DatabaseName, DatabaseUsername, DatabasePassword) "
			+ "VALUES (UUID_TO_BIN('%s'), '%s', 'Acme', '%s', 'localhost:3306', '%s', 'u', 'p')";

	private void insertOrganization(String tenantId, String organizationCode, String email, String databaseName)
			throws SQLException {
		execute(INSERT_ORGANIZATION.formatted(tenantId, organizationCode, email, databaseName));
	}

	@Test
	void tenantIdOrganizationCodeEmailAndDatabaseNameAreEachUnique() throws SQLException {
		flyway().migrate();
		String tenantId = "11111111-1111-1111-1111-111111111111";
		insertOrganization(tenantId, "acme", "a@acme.test", "tenant_1");

		assertThatThrownBy(() -> insertOrganization(tenantId, "other", "b@acme.test", "tenant_2"))
				.hasMessageContaining("Duplicate entry");
		assertThatThrownBy(() -> insertOrganization("22222222-2222-2222-2222-222222222222", "acme", "c@acme.test", "tenant_3"))
				.hasMessageContaining("Duplicate entry");
		assertThatThrownBy(() -> insertOrganization("33333333-3333-3333-3333-333333333333", "other", "a@acme.test", "tenant_4"))
				.hasMessageContaining("Duplicate entry");
		assertThatThrownBy(() -> insertOrganization("44444444-4444-4444-4444-444444444444", "other", "d@acme.test", "tenant_1"))
				.hasMessageContaining("Duplicate entry");
	}

	@Test
	void organizationCodeIsUniqueRegardlessOfCase() throws SQLException {
		flyway().migrate();
		insertOrganization("11111111-1111-1111-1111-111111111111", "acme", "a@acme.test", "tenant_1");

		assertThatThrownBy(() -> insertOrganization("22222222-2222-2222-2222-222222222222", "ACME", "b@acme.test", "tenant_2"))
				.hasMessageContaining("Duplicate entry");
	}

	@Test
	void organizationCodeMustBeEasyToTypeAndOfAReasonableShape() throws SQLException {
		flyway().migrate();

		insertOrganization("11111111-1111-1111-1111-111111111111", "acme", "a@acme.test", "tenant_1");
		insertOrganization("22222222-2222-2222-2222-222222222222", "acme-corp-2", "b@acme.test", "tenant_2");
		insertOrganization("33333333-3333-3333-3333-333333333333", "a1b", "c@acme.test", "tenant_3");

		String[] invalid = { "ab", "-acme", "acme-", "acme corp", "acme_corp", "acme.corp" };
		for (int i = 0; i < invalid.length; i++) {
			String code = invalid[i];
			int n = i;
			assertThatThrownBy(() -> insertOrganization("99999999-9999-9999-9999-99999999999" + n, code,
					"x" + n + "@acme.test", "tenant_x" + n))
					.as("code '%s' should be rejected", code)
					.hasMessageContaining("chk_organization_code_format");
		}

		// longer than the 30-character column
		assertThatThrownBy(() -> insertOrganization("88888888-8888-8888-8888-888888888888", "a".repeat(31),
				"long@acme.test", "tenant_long"))
				.hasMessageContaining("Data too long");
	}

	@Test
	void subscriptionRequiresAnOrganizationAndThereIsOnlyOnePerOrganization() throws SQLException {
		flyway().migrate();
		String insertSubscription = "INSERT INTO OrganizationSubscription (SubscriptionCode, OrganizationId, MaxProjects, "
				+ "MaxUsers, MaxStorageMB) SELECT UUID_TO_BIN('%s'), OrganizationId, 1, 5, 100 FROM Organization";

		assertThatThrownBy(() -> execute("INSERT INTO OrganizationSubscription (SubscriptionCode, OrganizationId, MaxProjects, "
				+ "MaxUsers, MaxStorageMB) VALUES (UUID_TO_BIN('66666666-6666-6666-6666-666666666666'), 999, 1, 5, 100)"))
				.hasMessageContaining("foreign key constraint");

		insertOrganization("77777777-7777-7777-7777-777777777777", "acme", "a@acme.test", "tenant_7");
		execute(insertSubscription.formatted("88888888-8888-8888-8888-888888888888"));
		assertThatThrownBy(() -> execute(insertSubscription.formatted("99999999-9999-9999-9999-999999999999")))
				.hasMessageContaining("Duplicate entry");
	}

	@Test
	void deletingAnOrganizationCascadesToItsSubscription() throws SQLException {
		flyway().migrate();
		insertOrganization("44444444-4444-4444-4444-444444444444", "acme", "a@acme.test", "tenant_4");
		execute("INSERT INTO OrganizationSubscription (SubscriptionCode, OrganizationId, MaxProjects, MaxUsers, MaxStorageMB) "
				+ "SELECT UUID_TO_BIN('55555555-5555-5555-5555-555555555555'), OrganizationId, 1, 5, 100 FROM Organization");

		execute("DELETE FROM Organization");

		try (Connection connection = DriverManager.getConnection(jdbcUrl, "root", PASSWORD);
				Statement statement = connection.createStatement();
				ResultSet rs = statement.executeQuery("SELECT COUNT(*) FROM OrganizationSubscription")) {
			rs.next();
			assertThat(rs.getInt(1)).isZero();
		}
	}

	@Test
	void uuidColumnsAreBinary16AndDefaultToOpaqueRandomVersion4Values() throws SQLException {
		flyway().migrate();
		assertThat(columnType("Organization", "TenantId")).isEqualTo("binary(16)");
		assertThat(columnType("OrganizationSubscription", "SubscriptionCode")).isEqualTo("binary(16)");

		for (int i = 0; i < 50; i++) {
			execute("INSERT INTO Organization (OrganizationCode, Name, Email, DatabaseHost, DatabaseName, DatabaseUsername, "
					+ "DatabasePassword) VALUES ('org-" + i + "x', 'Acme', 'e" + i + "@acme.test', 'h', 'tenant_" + i + "', 'u', 'p')");
		}
		execute("INSERT INTO OrganizationSubscription (OrganizationId, MaxProjects, MaxUsers, MaxStorageMB) "
				+ "SELECT OrganizationId, 1, 5, 100 FROM Organization LIMIT 1");

		List<String> tenantIds = strings("SELECT BIN_TO_UUID(TenantId) FROM Organization");
		List<String> subscriptionCodes = strings("SELECT BIN_TO_UUID(SubscriptionCode) FROM OrganizationSubscription");
		assertThat(tenantIds).hasSize(50).doesNotHaveDuplicates();
		assertThat(subscriptionCodes).hasSize(1);
		List<String> all = new ArrayList<>(tenantIds);
		all.addAll(subscriptionCodes);
		for (String uuid : all) {
			assertThat(uuid).matches("[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}");
		}
	}

	private String columnType(String table, String column) throws SQLException {
		return strings("SELECT column_type FROM information_schema.columns WHERE table_schema = '" + DATABASE
				+ "' AND table_name = '" + table + "' AND column_name = '" + column + "'").get(0);
	}

	private List<String> strings(String sql) throws SQLException {
		List<String> values = new ArrayList<>();
		try (Connection connection = DriverManager.getConnection(jdbcUrl, "root", PASSWORD);
				Statement statement = connection.createStatement();
				ResultSet rs = statement.executeQuery(sql)) {
			while (rs.next()) {
				values.add(rs.getString(1));
			}
		}
		return values;
	}

	private List<String> tables() throws SQLException {
		List<String> names = new ArrayList<>();
		try (Connection connection = DriverManager.getConnection(jdbcUrl, "root", PASSWORD);
				Statement statement = connection.createStatement();
				ResultSet rs = statement.executeQuery(
						"SELECT table_name FROM information_schema.tables WHERE table_schema = '" + DATABASE
								+ "' AND table_name <> 'flyway_schema_history'")) {
			while (rs.next()) {
				names.add(rs.getString(1));
			}
		}
		return names;
	}

	private void execute(String sql) throws SQLException {
		try (Connection connection = DriverManager.getConnection(jdbcUrl, "root", PASSWORD);
				Statement statement = connection.createStatement()) {
			statement.execute(sql);
		}
	}
}
