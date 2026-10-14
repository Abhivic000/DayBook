package dev.daybook.api.support;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;

/**
 * Creates the consumer's projection tables in the API's test database by running the consumer's own
 * migration files, straight from its source folder.
 *
 * <p>The projection tables are a contract between the two services. Reading the consumer's real
 * migrations — instead of copying its SQL into the API's tests — means a consumer change that
 * breaks the API's queries fails the API's build.
 */
public final class ProjectionSchema {

  private static final List<Path> CANDIDATES =
      List.of(
          Path.of("../daybook-consumer/src/main/resources/db/migration"), // Maven: module dir
          Path.of("daybook-consumer/src/main/resources/db/migration")); // IDE: project root

  private ProjectionSchema() {}

  /**
   * Applies the consumer's migrations if they are missing. Always asks the database rather than
   * remembering a flag: a new Spring test context can come with a brand-new database container, so
   * "already migrated" is a fact about one database, not about the JVM. Flyway's migrate is a quick
   * no-op when the schema is current.
   */
  public static synchronized void ensureMigrated(DataSource dataSource) {
    Path location =
        CANDIDATES.stream()
            .filter(Files::isDirectory)
            .findFirst()
            .orElseThrow(
                () ->
                    new IllegalStateException(
                        "Consumer migrations not found; looked in " + CANDIDATES));
    Flyway.configure()
        .dataSource(dataSource)
        .schemas("projection")
        .defaultSchema("projection")
        .locations("filesystem:" + location.toAbsolutePath())
        .load()
        .migrate();
  }
}
