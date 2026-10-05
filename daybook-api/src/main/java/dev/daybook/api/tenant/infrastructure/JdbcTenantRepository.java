package dev.daybook.api.tenant.infrastructure;

import dev.daybook.api.tenant.application.TenantRepository;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class JdbcTenantRepository implements TenantRepository {

  private final JdbcClient jdbc;

  JdbcTenantRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public void insert(UUID id, String name) {
    jdbc.sql("INSERT INTO tenants (id, name) VALUES (:id, :name)")
        .param("id", id)
        .param("name", name)
        .update();
  }

  @Override
  public boolean exists(UUID id) {
    return jdbc.sql("SELECT EXISTS (SELECT 1 FROM tenants WHERE id = :id)")
        .param("id", id)
        .query(Boolean.class)
        .single();
  }
}
