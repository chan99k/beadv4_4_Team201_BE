package app.giftify.settlement.config;

import java.io.IOException;
import java.util.Optional;

import org.jspecify.annotations.NonNull;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.filter.OncePerRequestFilter;

import app.giftify.security.common.MemberAuthenticationToken;
import app.giftify.security.common.MemberPrincipal;
import app.giftify.shared.domain.vo.MemberInfo;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
public class SettlementMemberPrincipalFilter extends OncePerRequestFilter {

    private final RestClient restClient;

    public SettlementMemberPrincipalFilter(
            RestClient.Builder builder,
            @Value("${app.service.api-server.url:http://localhost:8080}") String apiServerUrl
    ) {
        this.restClient = builder.baseUrl(apiServerUrl).build();
    }

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request,
                                    @NonNull HttpServletResponse response,
                                    @NonNull FilterChain filterChain) throws ServletException, IOException {
        try {
            enrichSecurityContext();
        } catch (Exception e) {
            log.warn("[SettlementMemberPrincipalFilter] SecurityContext enrichment failed", e);
        }
        filterChain.doFilter(request, response);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return path.startsWith("/api/internal/") || path.equals("/favicon.ico");
    }

    private void enrichSecurityContext() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof Jwt jwt)) {
            return;
        }

        String authSub = jwt.getSubject();
        MemberInfo memberInfo = findMemberByAuthSub(authSub)
            .orElseGet(() -> MemberInfo.forUnregistered(authSub));

        MemberPrincipal principal = MemberPrincipal.from(memberInfo);
        Authentication newAuth = new MemberAuthenticationToken(principal);

        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(newAuth);
        SecurityContextHolder.setContext(context);
    }

    private Optional<MemberInfo> findMemberByAuthSub(String authSub) {
        try {
            ResponseEntity<MemberInfo> response = restClient.get()
                .uri("/api/internal/members/by-auth-sub?authSub={authSub}", authSub)
                .retrieve()
                .toEntity(MemberInfo.class);
            if (response.getStatusCode().is2xxSuccessful()) {
                return Optional.ofNullable(response.getBody());
            }
            return Optional.empty();
        } catch (Exception e) {
            log.debug("[SettlementMemberPrincipalFilter] Failed to fetch member: {}", authSub);
            return Optional.empty();
        }
    }
}
