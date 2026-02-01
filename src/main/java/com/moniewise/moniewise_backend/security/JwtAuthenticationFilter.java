package com.moniewise.moniewise_backend.security;

import com.moniewise.moniewise_backend.entity.User; // Import your User entity
import com.moniewise.moniewise_backend.service.UserService;
import io.jsonwebtoken.ExpiredJwtException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;

@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(JwtAuthenticationFilter.class);
    private final JwtUtil jwtUtil;
    private final UserService userService;

    public JwtAuthenticationFilter(JwtUtil jwtUtil, UserService userService) {
        this.jwtUtil = jwtUtil;
        this.userService = userService;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) throws ServletException {
        String path = request.getServletPath();
        // Skip the filter for these specific paths
        return path.startsWith("/auth/google");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {

        String header = request.getHeader("Authorization");
        String token = null;
        String email = null;

        if (header != null && header.startsWith("Bearer ")) {
            token = header.substring(7);
            try {
                email = jwtUtil.extractEmail(token);
            } catch (ExpiredJwtException e) {
                log.warn("JWT expired for request: {}", request.getRequestURI());
            } catch (Exception e) {
                log.warn("Invalid JWT: {}", e.getMessage());
            }
        }

        if (email != null && SecurityContextHolder.getContext().getAuthentication() == null) {
            // 1. Load standard UserDetails (for Spring Security)
            UserDetails userDetails = userService.loadUserByUsername(email);

            // 2. Load your custom User entity (to check the Session ID)
            User user = userService.findByEmail(email);

            // 3. Extract Session ID from the incoming Token
            String tokenSessionId = jwtUtil.extractSessionId(token);

            // 4. THE CRITICAL CHECK: Does Token ID match Database ID?
            // If user.getCurrentSessionId() is null, it means no valid session exists.
            boolean isSessionValid = tokenSessionId != null &&
                    tokenSessionId.equals(user.getCurrentSessionId());

            if (jwtUtil.validateToken(token, userDetails) && isSessionValid) {
                UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(
                        userDetails, null, userDetails.getAuthorities());
                auth.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
                SecurityContextHolder.getContext().setAuthentication(auth);
                log.debug("Set authentication for: {}", email);
            } else if (!isSessionValid) {
                log.warn("Session Mismatch: User {} tried to use an old token/device.", email);
            }
        }

        chain.doFilter(request, response);
    }
}