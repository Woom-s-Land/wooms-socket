package com.ee06.mychat.global.jwt.filter;

import com.ee06.mychat.global.exception.ErrorCode;
import com.ee06.mychat.global.jwt.JWTUtil;
import com.ee06.mychat.global.jwt.exception.CustomAuthenticationException;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.MalformedJwtException;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Arrays;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

@RequiredArgsConstructor
public class JWTFilter {
    private final JWTUtil jwtUtil;

    private boolean isNotValidate(String token){
        return !jwtUtil.isExpired(token) && !jwtUtil.validateToken(token);
    }

}
