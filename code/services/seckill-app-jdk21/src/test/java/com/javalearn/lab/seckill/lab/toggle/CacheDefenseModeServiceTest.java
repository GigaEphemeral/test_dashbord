package com.javalearn.lab.seckill.lab.toggle;

import com.javalearn.lab.common.BizException;
import com.javalearn.lab.common.CommonErrorCode;
import com.javalearn.lab.seckill.lab.config.LabCacheProperties;
import com.javalearn.lab.seckill.lab.config.TestLabCacheProperties;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CacheDefenseModeServiceTest {

    @Test
    void seedsTheConfiguredMode() {
        LabCacheProperties properties = TestLabCacheProperties.defaults();
        properties.getPenetration().setMode("null-cache");

        assertThat(new CacheDefenseModeService(properties).current())
                .isSameAs(CacheDefenseMode.NULL_CACHE);
    }

    @Test
    void rejectsAnUnseedableModeAtStartup() {
        LabCacheProperties properties = TestLabCacheProperties.defaults();
        properties.getPenetration().setMode("maybe");

        assertThatThrownBy(() -> new CacheDefenseModeService(properties))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("app.lab.cache.penetration.mode")
                .hasMessageContaining("maybe");
    }

    @Test
    void switchesBetweenTheThreeModesAtRuntime() {
        CacheDefenseModeService service =
                new CacheDefenseModeService(TestLabCacheProperties.defaults());
        assertThat(service.current()).isSameAs(CacheDefenseMode.OFF);

        service.update("bloom");
        assertThat(service.current()).isSameAs(CacheDefenseMode.BLOOM);

        service.update("null-cache");
        assertThat(service.current()).isSameAs(CacheDefenseMode.NULL_CACHE);

        service.update("off");
        assertThat(service.current()).isSameAs(CacheDefenseMode.OFF);
    }

    @Test
    void unknownModeIsRejectedAndLeavesTheCurrentModeUntouched() {
        CacheDefenseModeService service =
                new CacheDefenseModeService(TestLabCacheProperties.defaults());

        assertThatThrownBy(() -> service.update("Null-Cache"))
                .isInstanceOfSatisfying(BizException.class, exception -> {
                    assertThat(exception.getErrorCode()).isSameAs(CommonErrorCode.INVALID_ARGUMENT);
                    assertThat(exception.getMessage()).isEqualTo("Unknown mode: Null-Cache");
                });
        assertThat(service.current()).isSameAs(CacheDefenseMode.OFF);
    }

    @Test
    void missingModeIsRejected() {
        CacheDefenseModeService service =
                new CacheDefenseModeService(TestLabCacheProperties.defaults());

        assertThatThrownBy(() -> service.update(null))
                .isInstanceOf(BizException.class);
        assertThatThrownBy(() -> service.update("  "))
                .isInstanceOf(BizException.class)
                .hasMessage("Mode is required");
        assertThatCode(() -> service.update("bloom")).doesNotThrowAnyException();
    }

    @Test
    void modeKeysAreTheWireContract() {
        assertThat(CacheDefenseMode.fromKey("off")).isSameAs(CacheDefenseMode.OFF);
        assertThat(CacheDefenseMode.fromKey("null-cache")).isSameAs(CacheDefenseMode.NULL_CACHE);
        assertThat(CacheDefenseMode.fromKey("bloom")).isSameAs(CacheDefenseMode.BLOOM);
        assertThat(CacheDefenseMode.fromKey("mutex")).isSameAs(CacheDefenseMode.MUTEX);
        assertThat(CacheDefenseMode.fromKey("logical-expire")).isSameAs(CacheDefenseMode.LOGICAL_EXPIRE);
        assertThat(CacheDefenseMode.fromKey("ttl-jitter")).isSameAs(CacheDefenseMode.TTL_JITTER);
        assertThat(CacheDefenseMode.fromKey("l1-cache")).isSameAs(CacheDefenseMode.L1_CACHE);
        assertThat(CacheDefenseMode.fromKey("db-fallback")).isSameAs(CacheDefenseMode.DB_FALLBACK);
        assertThat(CacheDefenseMode.fromKey("")).isNull();
        assertThat(CacheDefenseMode.fromKey(null)).isNull();
        assertThat(CacheDefenseMode.values())
                .extracting(CacheDefenseMode::key)
                .containsExactly("off", "null-cache", "bloom", "mutex", "logical-expire",
                        "ttl-jitter", "l1-cache", "db-fallback");
    }

    @Test
    void theAvalancheModesAreAdditiveAndSeedable() {
        LabCacheProperties properties = TestLabCacheProperties.defaults();
        properties.getPenetration().setMode("db-fallback");
        CacheDefenseModeService seeded = new CacheDefenseModeService(properties);

        assertThat(seeded.current()).isSameAs(CacheDefenseMode.DB_FALLBACK);

        seeded.update("ttl-jitter");
        assertThat(seeded.current()).isSameAs(CacheDefenseMode.TTL_JITTER);
        seeded.update("l1-cache");
        assertThat(seeded.current()).isSameAs(CacheDefenseMode.L1_CACHE);
        seeded.update("off");
        assertThat(seeded.current()).isSameAs(CacheDefenseMode.OFF);
    }

    @Test
    void anAvalancheModeWithTheWrongCasingIsStillRejected() {
        CacheDefenseModeService service =
                new CacheDefenseModeService(TestLabCacheProperties.defaults());

        assertThatThrownBy(() -> service.update("TTL-Jitter"))
                .isInstanceOf(BizException.class)
                .hasMessage("Unknown mode: TTL-Jitter");
        assertThat(service.current()).isSameAs(CacheDefenseMode.OFF);
    }
}
