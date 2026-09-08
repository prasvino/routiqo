package com.routiqo.core.security;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
@Configuration
public class SecurityConfiguration {
 @Bean InMemoryUserDetailsManager noDevelopmentUsers() { return new InMemoryUserDetailsManager(); }
 @Bean SecurityFilterChain security(HttpSecurity http) throws Exception {
   return http.sessionManagement(s->s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
     .authorizeHttpRequests(auth->auth.requestMatchers(HttpMethod.GET,"/api/v1/health","/api/v1/destinations").permitAll().anyRequest().denyAll())
     .exceptionHandling(errors->errors.authenticationEntryPoint((request,response,error)->response.sendError(401)))
     .build();
 }
}

