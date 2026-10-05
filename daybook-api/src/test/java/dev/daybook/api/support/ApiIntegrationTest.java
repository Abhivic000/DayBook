package dev.daybook.api.support;

import dev.daybook.api.TestcontainersConfiguration;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;

/**
 * Full-stack HTTP test against real Postgres. Every API test uses this one annotation so they share
 * a single Spring context (and container) instead of each starting their own.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@SpringBootTest(
    properties =
        "daybook.security.admin-key-sha256="
            + "944650a7cd0f9e14d5c4fb15edbffb7fa45fb9ed36a4fa9be3d7e5476ae51bd9")
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, ApiClient.class})
public @interface ApiIntegrationTest {

  /** Plaintext of the admin key whose SHA-256 is configured above. */
  String ADMIN_KEY = "test-admin-key";
}
