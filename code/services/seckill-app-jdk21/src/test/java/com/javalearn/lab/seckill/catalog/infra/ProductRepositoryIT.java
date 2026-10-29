package com.javalearn.lab.seckill.catalog.infra;

import com.javalearn.lab.seckill.MySqlRabbitIntegrationTestSupport;
import com.javalearn.lab.seckill.catalog.domain.Product;
import com.javalearn.lab.seckill.catalog.domain.ProductRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ScriptUtils;

import javax.sql.DataSource;
import java.sql.Connection;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Repository adapter + Flyway migration test against a real MySQL 8.4.4.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "observability.env=test",
                "observability.instance=test-1"
        })
class ProductRepositoryIT extends MySqlRabbitIntegrationTestSupport {

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private ProductJpaRepository productJpaRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private DataSource dataSource;

    @Test
    void flywayAppliesAllMigrations() {
        List<String> versions = jdbcTemplate.queryForList(
                "SELECT version FROM flyway_schema_history WHERE success = 1 ORDER BY installed_rank",
                String.class);

        assertThat(versions).containsExactly("1", "2", "3", "4");
    }

    @Test
    void seedContainsExactlyTenThousandProducts() {
        assertThat(productJpaRepository.count()).isEqualTo(10_000L);
    }

    @Test
    void builtInInventoryFollowsTheDeterministicFormula() {
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM inventory_stock", Long.class)).isEqualTo(10_000L);
        // Product 1/20 are exercised by order ITs sharing this container, so the
        // formula is asserted on untouched generated ids.
        assertThat(available(21L)).isEqualTo(276);
        assertThat(available(10_000L)).isEqualTo(262);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM inventory_stock"
                        + " WHERE product_id BETWEEN 21 AND 10000"
                        + " AND (available <> MOD(product_id * 37, 501) OR reserved <> 0)",
                Long.class)).isZero();
    }

    @Test
    void rerunningTheBaselineMigrationIsANoOp() throws Exception {
        try (Connection connection = dataSource.getConnection()) {
            ScriptUtils.executeSqlScript(connection,
                    new ClassPathResource("db/migration/V4__catalog_baseline_10000.sql"));
        }

        assertThat(productJpaRepository.count()).isEqualTo(10_000L);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM inventory_stock", Long.class)).isEqualTo(10_000L);
        assertThat(available(1L)).isEqualTo(37);
    }

    @Test
    void findsSeededProductById() {
        Optional<Product> found = productRepository.findById(1L);

        assertThat(found).isPresent();
        Product product = found.get();
        assertThat(product.getId()).isEqualTo(1L);
        assertThat(product.getName()).isNotBlank();
        assertThat(product.getPrice()).isNotNull().isPositive();
        assertThat(product.getStatus()).isNotBlank();
    }

    @Test
    void missingProductReturnsEmpty() {
        assertThat(productRepository.findById(999999L)).isEmpty();
    }

    private int available(long productId) {
        return jdbcTemplate.queryForObject(
                "SELECT available FROM inventory_stock WHERE product_id = ?",
                Integer.class, productId);
    }
}
