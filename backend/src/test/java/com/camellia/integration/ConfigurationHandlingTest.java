package com.camellia.integration;

import com.camellia.config.CamelliaProperties;
import com.camellia.config.DataSourceConfig;
import com.camellia.integration.gemini.GeminiException;
import com.camellia.integration.gemini.GeminiGateway;
import com.camellia.integration.gemini.GoogleGenAiGateway;
import com.camellia.integration.snowflake.DatabaseMode;
import com.camellia.integration.snowflake.SnowflakeConfigurationException;
import com.camellia.integration.snowflake.SnowflakeConnectionSettings;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Safe handling of missing Snowflake and Gemini configuration. */
class ConfigurationHandlingTest {

    @Test
    void autoModeWithoutSnowflakeUsesLabeledLocalFallback() {
        CamelliaProperties p = new CamelliaProperties();
        assertThat(new DataSourceConfig().databaseMode(p)).isEqualTo(DatabaseMode.LOCAL_FALLBACK);
        assertThat(DatabaseMode.LOCAL_FALLBACK.label()).contains("NOT Snowflake");
    }

    @Test
    void explicitSnowflakeModeWithMissingConfigFailsWithDiagnostic() {
        CamelliaProperties p = new CamelliaProperties();
        p.getDatabase().setMode("snowflake");
        assertThatThrownBy(() -> new DataSourceConfig().databaseMode(p))
                .isInstanceOf(SnowflakeConfigurationException.class)
                .hasMessageContaining("SNOWFLAKE_ACCOUNT")
                .hasMessageContaining("SNOWFLAKE_PRIVATE_KEY_FILE")
                .hasMessageContaining("CAMELLIA_DB_MODE=local");
    }

    @Test
    void autoModeWithPartialSnowflakeConfigFailsInsteadOfSilentlyFallingBack() {
        CamelliaProperties p = new CamelliaProperties();
        p.getSnowflake().setAccount("myorg-myacct");
        assertThatThrownBy(() -> new DataSourceConfig().databaseMode(p))
                .isInstanceOf(SnowflakeConfigurationException.class)
                .hasMessageContaining("SNOWFLAKE_USER");
    }

    @Test
    void snowflakeSettingsNormalizeAccountAndUseKeyPairAuth() {
        CamelliaProperties.Snowflake s = new CamelliaProperties.Snowflake();
        s.setAccount("https://myorg-myacct.snowflakecomputing.com/");
        s.setUser("svc");
        s.setPrivateKeyFile("C:\\keys\\rsa_key.p8");
        s.setWarehouse("WH");
        SnowflakeConnectionSettings settings = new SnowflakeConnectionSettings(s);
        assertThat(settings.jdbcUrl()).isEqualTo("jdbc:snowflake://myorg-myacct.snowflakecomputing.com/");
        var props = settings.connectionProperties(true);
        assertThat(props.getProperty("authenticator")).isEqualTo("SNOWFLAKE_JWT");
        assertThat(props.getProperty("private_key_file")).isEqualTo("C:/keys/rsa_key.p8");
        assertThat(props.getProperty("db")).isEqualTo("CAMELLIA_DEMO");
        assertThat(props.getProperty("JDBC_QUERY_RESULT_FORMAT")).isEqualTo("JSON");
        assertThat(settings.problems()).anyMatch(m -> m.contains("does not exist"));
    }

    @Test
    void privateKeyPathExpandsHomeDirectory() {
        CamelliaProperties.Snowflake s = new CamelliaProperties.Snowflake();
        s.setPrivateKeyFile("~/.snowflake/camellia_rsa_key.p8");
        assertThat(new SnowflakeConnectionSettings(s).privateKeyPath())
                .isEqualTo(System.getProperty("user.home") + "/.snowflake/camellia_rsa_key.p8");
    }

    @Test
    void geminiWithoutKeyReportsNotConfiguredAndNeverCallsTheApi() {
        GoogleGenAiGateway g = new GoogleGenAiGateway(new CamelliaProperties());
        assertThat(g.isConfigured()).isFalse();
        assertThatThrownBy(() -> g.generateJson(new GeminiGateway.Request("t", "s", List.of(GeminiGateway.Turn.user("hi")), Map.of())))
                .isInstanceOf(GeminiException.class)
                .satisfies(e -> {
                    assertThat(((GeminiException) e).status()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
                    assertThat(((GeminiException) e).code()).isEqualTo("GEMINI_NOT_CONFIGURED");
                });
        assertThat(g.stats().calls()).isZero();
    }
}
