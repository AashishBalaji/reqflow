package com.reqflow.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.HeadersConfigurer;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Reads (GET) are open so the API stays easy to explore and the AI triage
 * agent can always list pending requisitions. Every write (POST, the only
 * mutating verb this API exposes) requires HTTP Basic auth, including the
 * approve/reject endpoints -- those are exactly the actions that should be
 * attributable to a specific caller, and decidedBy already assumes the
 * caller identified itself. One in-memory user from config, BCrypt-hashed
 * at startup -- intentionally minimal (no self-service accounts, no
 * roles beyond one) since this is scoped to "the mutating surface isn't
 * wide open," not a full identity system.
 */
@Configuration
public class SecurityConfig {

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
                .csrf(csrf -> csrf.disable()) // stateless JSON API, no browser session/cookie to forge
                .authorizeHttpRequests(auth -> auth
                        // Plain path matcher rather than PathRequest.toH2Console() --
                        // that helper resolves H2ConsoleAutoConfiguration's bean lazily
                        // per-request and throws NoSuchBeanDefinitionException in any
                        // context where the H2 console isn't auto-configured (e.g. test
                        // profiles that don't set spring.h2.console.enabled).
                        .requestMatchers("/h2-console/**").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/**").permitAll()
                        .anyRequest().authenticated())
                .httpBasic(basic -> {})
                .headers(headers -> headers.frameOptions(HeadersConfigurer.FrameOptionsConfig::disable)); // H2 console uses frames

        return http.build();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public UserDetailsService userDetailsService(ApiCredentialsProperties credentials, PasswordEncoder encoder) {
        return new InMemoryUserDetailsManager(
                User.builder()
                        .username(credentials.getUsername())
                        .password(encoder.encode(credentials.getPassword()))
                        .roles("REVIEWER")
                        .build());
    }
}
