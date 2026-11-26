package io.github.komokendd.dsm.brokerage.common;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.web.filter.OncePerRequestFilter;

/** Authenticates the operator separately from the one permitted service caller. */
public final class DemoApiSecurity extends OncePerRequestFilter {
    private final String application;
    private final byte[] operator;
    private final byte[] caller;
    private final byte[] readOnlyCaller;

    public DemoApiSecurity(String application, String operatorToken, String callerToken) {
        this(application, operatorToken, callerToken, null);
    }

    public DemoApiSecurity(String application, String operatorToken, String callerToken, String readOnlyToken) {
        this.application = application;
        this.operator = digest(operatorToken);
        this.caller = callerToken == null || callerToken.isEmpty() ? null : digest(callerToken);
        this.readOnlyCaller = readOnlyToken == null || readOnlyToken.isEmpty() ? null : digest(readOnlyToken);
        if ((caller != null && MessageDigest.isEqual(operator, caller))
                || (readOnlyCaller != null && MessageDigest.isEqual(operator, readOnlyCaller))
                || (caller != null && readOnlyCaller != null && MessageDigest.isEqual(caller, readOnlyCaller))) {
            throw new IllegalArgumentException("Operator and service tokens must be different");
        }
    }

    private static byte[] digest(String value) {
        if (value == null || !value.matches("[a-f0-9]{64}")) {
            throw new IllegalArgumentException("API verifiers must be SHA-256 digests of independent random tokens");
        }
        return java.util.HexFormat.of().parseHex(value);
    }

    static byte[] hash(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.US_ASCII));
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String path = request.getServletPath();
        if (path.isEmpty()) path = request.getRequestURI();
        if ("GET".equals(request.getMethod()) && (path.equals("/actuator/health")
                || path.equals("/actuator/health/readiness") || path.equals("/actuator/health/liveness"))) {
            chain.doFilter(request, response);
            return;
        }
        String authorization = request.getHeader("Authorization");
        byte[] presented = authorization != null && authorization.startsWith("Bearer ") && authorization.length() <= 135
                ? hash(authorization.substring(7)) : new byte[0];
        if (MessageDigest.isEqual(operator, presented)
                || (caller != null && MessageDigest.isEqual(caller, presented) && allowedCaller(request.getMethod(), path))
                || (readOnlyCaller != null && MessageDigest.isEqual(readOnlyCaller, presented)
                && "GET".equals(request.getMethod()) && allowedReadOnlyCaller(path))) {
            chain.doFilter(request, response);
            return;
        }
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setHeader("WWW-Authenticate", "Bearer");
        response.setContentType("application/json");
        response.getWriter().write("{\"code\":\"UNAUTHORIZED\"}");
    }

    private boolean allowedReadOnlyCaller(String path) {
        return switch (application) {
            case "buying-power-service" -> path.matches("/internal/buying-power/accounts/A[0-9]{5}");
            case "order-state-service" -> path.matches("/internal/orders/[A-Za-z0-9-]+");
            default -> false;
        };
    }

    private boolean allowedCaller(String method, String path) {
        return switch (application) {
            case "order-service" -> ("POST".equals(method) && path.equals("/orders"))
                    || ("GET".equals(method) && path.matches("/orders/[A-Za-z0-9-]+"));
            case "buying-power-service" -> ("POST".equals(method)
                    && (path.equals("/internal/buying-power/reservations")
                    || path.matches("/internal/buying-power/reservations/[A-Za-z0-9-]+/release")))
                    || ("GET".equals(method) && path.matches("/internal/buying-power/reservations/[A-Za-z0-9-]+"));
            case "exchange-gateway-simulator" -> ("GET".equals(method) && path.equals("/internal/market/quotes"))
                    || ("POST".equals(method) && (path.equals("/internal/demo/opening-auction")
                    || path.equals("/internal/demo/sell-off")));
            case "push-gateway-simulator" -> "POST".equals(method) && path.equals("/push");
            default -> false;
        };
    }
}
