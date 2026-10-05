package dev.daybook.api.security;

import dev.daybook.api.tenant.application.ApiKeyRepository;
import dev.daybook.api.web.ProblemBody;
import dev.daybook.api.web.Problems;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;
import tools.jackson.databind.json.JsonMapper;

/**
 * Stateless API-key security. No sessions, cookies, CSRF tokens or login forms: every request
 * carries its own credential.
 */
@Configuration(proxyBeanMethods = false)
class SecurityConfig {

  @Bean
  SecurityFilterChain apiSecurity(
      HttpSecurity http, ApiKeyRepository apiKeys, SecurityProperties properties, JsonMapper json)
      throws Exception {
    AuthenticationEntryPoint unauthorized =
        (request, response, e) -> {
          response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
          write(
              response,
              json,
              Problems.of(HttpStatus.UNAUTHORIZED, "unauthorized", "Missing or invalid API key"));
        };
    AccessDeniedHandler forbidden =
        (request, response, e) ->
            write(
                response,
                json,
                Problems.of(
                    HttpStatus.FORBIDDEN,
                    "forbidden",
                    "This API key may not access this resource"));

    return http.csrf(csrf -> csrf.disable()) // no cookies, so no cross-site request forgery
        .httpBasic(basic -> basic.disable())
        .formLogin(form -> form.disable())
        .logout(logout -> logout.disable())
        .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .exceptionHandling(
            e -> e.authenticationEntryPoint(unauthorized).accessDeniedHandler(forbidden))
        .addFilterBefore(
            new ApiKeyAuthenticationFilter(apiKeys, properties, unauthorized),
            AnonymousAuthenticationFilter.class)
        .authorizeHttpRequests(
            auth ->
                auth.requestMatchers("/actuator/health/**", "/actuator/info", "/error")
                    .permitAll()
                    // Served only when springdoc is enabled (non-production profiles).
                    .requestMatchers("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html")
                    .permitAll()
                    .requestMatchers("/v1/admin/**")
                    .hasRole("ADMIN")
                    .requestMatchers("/v1/**")
                    .hasRole("TENANT")
                    .anyRequest()
                    .denyAll())
        .build();
  }

  /**
   * Replaces Spring Boot's default in-memory user with a generated password: this API has no
   * username/password users at all, only API keys.
   */
  @Bean
  UserDetailsService noUsernamePasswordUsers() {
    return username -> {
      throw new UsernameNotFoundException("Username/password authentication is not supported");
    };
  }

  private static void write(HttpServletResponse response, JsonMapper json, ProblemBody problem)
      throws IOException {
    response.setStatus(problem.status());
    response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
    response.setCharacterEncoding("UTF-8");
    response.getWriter().write(json.writeValueAsString(problem));
  }
}
