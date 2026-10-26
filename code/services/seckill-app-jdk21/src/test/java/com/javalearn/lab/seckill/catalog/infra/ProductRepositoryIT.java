package com.javalearn.lab.seckill.catalog.infra;

import com.javalearn.lab.seckill.MySqlIntegrationTestSupport;
import com.javalearn.lab.seckill.catalog.domain.Product;
import com.javalearn.lab.seckill.catalog.domain.ProductRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

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
class ProductRepositoryIT extends MySqlIntegrationTestSupport {

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private ProductJpaRepository productJpaRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void flywayAppliesAllMigrations() {
        List<String> versions = jdbcTemplate.queryForList(
                "SELECT version FROM flyway_schema_history WHERE success = 1 ORDER BY installed_rank",
                String.class);

        assertThat(versions).containsExactly("1", "2", "3");
    }

    @Test
    void seedContainsExactlyTwentyProducts() {
        assertThat(productJpaRepository.count()).isEqualTo(20L);
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
}
