package com.example.metaautoreply.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;

/**
 * HTTP Basic on the admin surface, and nothing else.
 *
 * <p>Getting the exemptions right matters more than the protection: Meta cannot authenticate,
 * so a webhook behind Basic auth receives a 401, retries, and is eventually disabled — the
 * service would go quiet with no obvious cause.
 */
@Configuration
public class SecurityConfig {

	@Bean
	public PasswordEncoder passwordEncoder() {
		return PasswordEncoderFactories.createDelegatingPasswordEncoder();
	}

	@Bean
	public UserDetailsService adminUser(AdminProperties props, PasswordEncoder passwordEncoder) {
		return new InMemoryUserDetailsManager(User.withUsername(props.user())
				.password(passwordEncoder.encode(props.password()))
				.roles("ADMIN")
				.build());
	}

	@Bean
	public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
		return http
				.authorizeHttpRequests(auth -> auth
						// Meta calls these and cannot present credentials. Both must stay open.
						.requestMatchers("/webhook/**").permitAll()
						.requestMatchers(HttpMethod.GET, "/actuator/health", "/actuator/health/**").permitAll()
						// The debug endpoints return raw payloads — commenter ids, usernames,
						// message text. The `debug` profile already gates them, but while a
						// tunnel is up the whole app is publicly reachable, so require a login
						// as well.
						.requestMatchers("/api/**", "/debug/**", "/admin.html").authenticated()
						.anyRequest().denyAll())
				.httpBasic(Customizer.withDefaults())
				// CSRF protection assumes a browser session and a token; this is a stateless
				// API called with curl and Basic auth, and /webhook must accept unauthenticated
				// POSTs from Meta. Disabled deliberately, not by omission.
				.csrf(csrf -> csrf.disable())
				.sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
				.build();
	}
}
