//package com.moniewise.moniewise_backend.config;
//
//import com.moniewise.moniewise_backend.entity.User;
//import com.moniewise.moniewise_backend.security.JwtAuthenticationFilter;
//import com.moniewise.moniewise_backend.security.JwtUtil;
//import com.moniewise.moniewise_backend.service.UserService;
//import org.springframework.context.annotation.Bean;
//import org.springframework.context.annotation.Configuration;
//import org.springframework.context.annotation.Lazy;
//import org.springframework.http.HttpStatus;
//import org.springframework.security.authentication.AuthenticationManager;
//import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
//import org.springframework.security.config.annotation.web.builders.HttpSecurity;
//import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
//import org.springframework.security.config.http.SessionCreationPolicy;
//import org.springframework.security.core.userdetails.UserDetails;
//import org.springframework.security.core.userdetails.UserDetailsService;
//import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
//import org.springframework.security.crypto.password.PasswordEncoder;
//import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserService;
//import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
//import org.springframework.security.web.SecurityFilterChain;
//import org.springframework.security.web.authentication.HttpStatusEntryPoint;
//import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
//
//import java.util.Set;
//
//@Configuration
//@EnableWebSecurity
//public class SecurityConfig {
//
//    private final JwtAuthenticationFilter jwtAuthenticationFilter;
//    private final JwtUtil jwtUtil;
//    private final UserDetailsService userDetailsService;
//    private final UserService userService;
//
//    public SecurityConfig(
//            @Lazy  JwtAuthenticationFilter jwtAuthenticationFilter,
//            JwtUtil jwtUtil,
//            UserDetailsService userDetailsService,
//            UserService userService
//    ) {
//        this.jwtAuthenticationFilter = jwtAuthenticationFilter;
//        this.jwtUtil = jwtUtil;
//        this.userDetailsService = userDetailsService;
//        this.userService = userService;
//    }
//
//    @Bean
//    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
//        http
//                .csrf().disable()
//                .sessionManagement().sessionCreationPolicy(SessionCreationPolicy.STATELESS)
//                .and()
//                .exceptionHandling()
//                .authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)) // Add this
//                .and()
//                .authorizeRequests()
//                .antMatchers("/auth/signup", "/auth/login", "/auth/oauth2/**").permitAll() // Only login and OAuth open
//                .antMatchers("/auth/logout", "/auth/refresh").authenticated()  // Require JWT for logout
//                .antMatchers("/tnc/**").authenticated()  // Require JWT for tnc
//                .antMatchers("/users/**").authenticated()  // Require JWT for tnc
//                .antMatchers("/budgets/**").authenticated()
//                .antMatchers("/wallets/**").authenticated()  // Require JWT for tnc
//                .antMatchers("/webhooks/**").authenticated()  // Require JWT for tnc
//                .anyRequest().authenticated()
//                .and()
//                .oauth2Login()
//                .userInfoEndpoint()
//                .oidcUserService(oidcUserService())
//                .and()
//                .successHandler((request, response, authentication) -> {
//                    DefaultOidcUser oidcUser = (DefaultOidcUser) authentication.getPrincipal();
//                    String email = oidcUser.getEmail();
//                    User user = userService.findOrCreateOAuthUser(email); // Ensure user exists
//                    UserDetails userDetails = userService.loadUserByUsername(email); // Get UserDetails
//                    String token = jwtUtil.generateToken(userDetails); // Pass UserDetails
//                    response.setContentType("application/json");
//                    response.getWriter().write("{\"token\":\"" + token + "\"}");
//                })
//                .and()
//                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);
//
//        return http.build();
//    }
//
//    @Bean
//    public OidcUserService oidcUserService() {
//        OidcUserService oidcUserService = new OidcUserService();
//        oidcUserService.setAccessibleScopes(Set.of("email", "profile"));
//        return oidcUserService;
//    }
//
//    @Bean
//    public AuthenticationManager authenticationManager(AuthenticationConfiguration authConfig) throws Exception {
//        return authConfig.getAuthenticationManager();
//    }
//}


package com.moniewise.moniewise_backend.config;

import com.moniewise.moniewise_backend.entity.User;
import com.moniewise.moniewise_backend.security.JwtAuthenticationFilter;
import com.moniewise.moniewise_backend.security.JwtUtil;
import com.moniewise.moniewise_backend.service.UserService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserService;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.Arrays;
import java.util.Set;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtAuthenticationFilter;
    private final JwtUtil jwtUtil;
    private final UserDetailsService userDetailsService;
    private final UserService userService;

    public SecurityConfig(
            @Lazy JwtAuthenticationFilter jwtAuthenticationFilter,
            JwtUtil jwtUtil,
            UserDetailsService userDetailsService,
            UserService userService
    ) {
        this.jwtAuthenticationFilter = jwtAuthenticationFilter;
        this.jwtUtil = jwtUtil;
        this.userDetailsService = userDetailsService;
        this.userService = userService;
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .cors().and() // Enable CORS
                .csrf().disable()
                .sessionManagement().sessionCreationPolicy(SessionCreationPolicy.STATELESS)
                .and()
                .exceptionHandling()
                .authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED))
                .and()
                .authorizeRequests()
                .antMatchers(
                        "/auth/signup",
                        "/auth/login",
                        "/auth/oauth2/**",
                        "/tnc/**",
                        "/users/otp/generate",  // Add this
                        "/users/otp/verify"     // Add this
                ).permitAll()
                .antMatchers("/auth/logout", "/auth/refresh").authenticated()
//                .antMatchers("/tnc/**").authenticated()
                .antMatchers("/users/**").authenticated()
//                .antMatchers("/users/otp/generate", "/users/otp/verify").authenticated()
                .antMatchers("/budgets/**").authenticated()
                .antMatchers("/wallets/**").authenticated()
                .antMatchers(HttpMethod.PATCH, "/users/tnc").authenticated() // Explicitly secure TNC
                .antMatchers("/webhooks/paystack").permitAll() // Open for Paystack
                .anyRequest().authenticated()
                .and()
                .oauth2Login()
                .userInfoEndpoint()
                .oidcUserService(oidcUserService())
                .and()
                .successHandler((request, response, authentication) -> {
                    DefaultOidcUser oidcUser = (DefaultOidcUser) authentication.getPrincipal();
                    String email = oidcUser.getEmail();
                    User user = userService.findOrCreateOAuthUser(email);
                    UserDetails userDetails = userService.loadUserByUsername(email);
                    String token = jwtUtil.generateToken(userDetails);
                    response.setContentType("application/json");
                    response.getWriter().write("{\"token\":\"" + token + "\"}");
                })
                .and()
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(Arrays.asList("http://localhost:3000", "https://your-flutter-app.com")); // Adjust for Flutter
        configuration.setAllowedMethods(Arrays.asList("GET", "POST", "PUT", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(Arrays.asList("*"));
        configuration.setAllowCredentials(true);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }

    @Bean
    public OidcUserService oidcUserService() {
        OidcUserService oidcUserService = new OidcUserService();
        oidcUserService.setAccessibleScopes(Set.of("email", "profile"));
        return oidcUserService;
    }

    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration authConfig) throws Exception {
        return authConfig.getAuthenticationManager();
    }

//    @Bean
//    public PasswordEncoder passwordEncoder() {
//        return new BCryptPasswordEncoder();
//    }
}