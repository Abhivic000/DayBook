package dev.daybook.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

/** The application starts against a real database and reports itself ready. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class ApplicationStartupIT {

  @Autowired MockMvc mockMvc;

  @Test
  void readinessProbeReportsUp() throws Exception {
    mockMvc
        .perform(get("/actuator/health/readiness"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("UP"));
  }

  /** FR-11.5, ADR 0014: readiness checks the database but never Kafka. */
  @Test
  void readinessDependsOnTheDatabaseButNotOnKafka() throws Exception {
    mockMvc
        .perform(get("/actuator/health/readiness"))
        .andExpect(jsonPath("$.components.db.status").value("UP"))
        .andExpect(jsonPath("$.components.kafka").doesNotExist());
  }

  /** Liveness must not depend on the database: restarting the app cannot fix a database outage. */
  @Test
  void livenessDoesNotDependOnTheDatabase() throws Exception {
    mockMvc
        .perform(get("/actuator/health/liveness"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.components.db").doesNotExist());
  }
}
