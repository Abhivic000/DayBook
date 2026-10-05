package dev.daybook.api.security;

import dev.daybook.api.tenant.application.ApiKeyRepository;
import dev.daybook.api.tenant.domain.ApiKeys;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.Optional;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Authenticates {@code Authorization: Bearer <key>} (ADR 0001).
 *
 * <ul>
 *   <li>No bearer header: passes through unauthenticated; protected endpoints then answer 401.
 *   <li>Admin key: authenticates as {@code ROLE_ADMIN}.
 *   <li>Tenant key ({@code dbk_<keyId>_<secret>}): authenticates as {@code ROLE_TENANT} with a
 *       {@link TenantPrincipal}.
 *   <li>Any other presented key: rejected with 401 immediately.
 * </ul>
 *
 * <p>Deliberately not a Spring bean: Spring Boot registers every {@code Filter} bean as a servlet
 * filter, which would run it a second time outside the security chain.
 */
class ApiKeyAuthenticationFilter extends OncePerRequestFilter {

  private static final String BEARER = "Bearer ";

  private final ApiKeyRepository apiKeys;
  private final String adminKeySha256;
  private final AuthenticationEntryPoint entryPoint;

  ApiKeyAuthenticationFilter(
      ApiKeyRepository apiKeys,
      SecurityProperties properties,
      AuthenticationEntryPoint entryPoint) {
    this.apiKeys = apiKeys;
    this.adminKeySha256 = properties.adminKeySha256().trim();
    this.entryPoint = entryPoint;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    String header = request.getHeader(HttpHeaders.AUTHORIZATION);
    if (header == null || !header.startsWith(BEARER)) {
      chain.doFilter(request, response);
      return;
    }
    Optional<Authentication> authentication =
        authenticate(header.substring(BEARER.length()).trim());
    if (authentication.isEmpty()) {
      entryPoint.commence(request, response, new BadCredentialsException("Invalid API key"));
      return;
    }
    var context = SecurityContextHolder.createEmptyContext();
    context.setAuthentication(authentication.get());
    SecurityContextHolder.setContext(context);
    try {
      chain.doFilter(request, response);
    } finally {
      SecurityContextHolder.clearContext();
    }
  }

  private Optional<Authentication> authenticate(String presented) {
    if (!adminKeySha256.isEmpty()
        && ApiKeys.hashesMatch(adminKeySha256, ApiKeys.sha256Hex(presented))) {
      return Optional.of(token("admin", "ROLE_ADMIN"));
    }
    return ApiKeys.parse(presented)
        .flatMap(
            key ->
                apiKeys
                    .findActive(key.keyId())
                    .filter(
                        stored ->
                            ApiKeys.hashesMatch(
                                stored.secretHash(), ApiKeys.sha256Hex(key.secret()))))
        .map(stored -> token(new TenantPrincipal(stored.tenantId()), "ROLE_TENANT"));
  }

  private static Authentication token(Object principal, String role) {
    return UsernamePasswordAuthenticationToken.authenticated(
        principal, null, List.of(new SimpleGrantedAuthority(role)));
  }
}
