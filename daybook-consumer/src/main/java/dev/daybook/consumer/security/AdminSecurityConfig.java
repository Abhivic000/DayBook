package dev.daybook.consumer.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * The consumer's only HTTP surface besides health is admin-only (ADR 0012), authenticated with the
 * same admin key as the API (ADR 0001): {@code Authorization: Bearer <admin key>}, compared as a
 * SHA-256 hash in constant time.
 *
 * <p>This mirrors a small part of the API's security deliberately rather than sharing a library: a
 * shared module would couple the two services' releases for the sake of a few lines.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(AdminSecurityConfig.SecurityProperties.class)
class AdminSecurityConfig {

  /**
   * Security settings, bound from {@code daybook.security.*}.
   *
   * @param adminKeySha256 hex SHA-256 of the admin key; blank disables admin access (fail closed)
   */
  @ConfigurationProperties("daybook.security")
  record SecurityProperties(@DefaultValue("") String adminKeySha256) {}

  @Bean
  SecurityFilterChain consumerSecurity(HttpSecurity http, SecurityProperties properties)
      throws Exception {
    return http.csrf(csrf -> csrf.disable()) // no cookies, so no cross-site request forgery
        .httpBasic(basic -> basic.disable())
        .formLogin(form -> form.disable())
        .logout(logout -> logout.disable())
        .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .exceptionHandling(
            e ->
                e.authenticationEntryPoint(
                        (request, response, ex) -> {
                          response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
                          problem(
                              response, HttpStatus.UNAUTHORIZED, "Missing or invalid admin key");
                        })
                    .accessDeniedHandler(
                        (request, response, ex) ->
                            problem(response, HttpStatus.FORBIDDEN, "Not allowed")))
        .addFilterBefore(
            new AdminKeyFilter(properties.adminKeySha256()), AnonymousAuthenticationFilter.class)
        .authorizeHttpRequests(
            auth ->
                auth.requestMatchers("/actuator/health/**", "/actuator/info", "/error")
                    .permitAll()
                    .requestMatchers("/v1/admin/**")
                    .hasRole("ADMIN")
                    .anyRequest()
                    .denyAll())
        .build();
  }

  /** No username/password users exist; replaces Boot's generated default user. */
  @Bean
  UserDetailsService noUsernamePasswordUsers() {
    return username -> {
      throw new UsernameNotFoundException("Username/password authentication is not supported");
    };
  }

  private static void problem(HttpServletResponse response, HttpStatus status, String detail)
      throws IOException {
    String code = status == HttpStatus.UNAUTHORIZED ? "unauthorized" : "forbidden";
    response.setStatus(status.value());
    response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
    response.setCharacterEncoding("UTF-8");
    response
        .getWriter()
        .write(
            """
            {"type":"https://daybook.dev/errors/%s","title":"%s","status":%d,"detail":"%s"}"""
                .formatted(code, status.getReasonPhrase(), status.value(), detail));
  }

  /** Authenticates the admin key; any other presented key is treated as no key at all. */
  static final class AdminKeyFilter extends OncePerRequestFilter {

    private static final String BEARER = "Bearer ";
    private final byte[] adminKeySha256;

    AdminKeyFilter(String adminKeySha256Hex) {
      this.adminKeySha256 = adminKeySha256Hex.trim().getBytes(StandardCharsets.US_ASCII);
    }

    @Override
    protected void doFilterInternal(
        HttpServletRequest request, HttpServletResponse response, FilterChain chain)
        throws ServletException, IOException {
      String header = request.getHeader(HttpHeaders.AUTHORIZATION);
      if (adminKeySha256.length > 0 && header != null && header.startsWith(BEARER)) {
        byte[] presented =
            sha256Hex(header.substring(BEARER.length()).trim()).getBytes(StandardCharsets.US_ASCII);
        if (MessageDigest.isEqual(adminKeySha256, presented)) {
          var context = SecurityContextHolder.createEmptyContext();
          context.setAuthentication(
              UsernamePasswordAuthenticationToken.authenticated(
                  "admin", null, List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));
          SecurityContextHolder.setContext(context);
        }
      }
      try {
        chain.doFilter(request, response);
      } finally {
        SecurityContextHolder.clearContext();
      }
    }

    private static String sha256Hex(String value) {
      try {
        return HexFormat.of()
            .formatHex(
                MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
      } catch (NoSuchAlgorithmException e) {
        throw new IllegalStateException("SHA-256 is required on every Java platform", e);
      }
    }
  }
}
