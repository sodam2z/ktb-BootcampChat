package com.ktb.chatapp.config;

import com.ktb.chatapp.security.CustomBearerTokenResolver;
import com.ktb.chatapp.security.SessionAwareJwtAuthenticationConverter;
import com.ktb.chatapp.service.RateLimitService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.cors.CorsConfigurationSource;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = SecurityConfigTest.ProbeController.class)
@Import(SecurityConfig.class)
class SecurityConfigTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private CorsConfigurationSource corsConfigurationSource;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    @MockitoBean
    private CustomBearerTokenResolver bearerTokenResolver;

    @MockitoBean
    private SessionAwareJwtAuthenticationConverter jwtAuthenticationConverter;

    /** RateLimitInterceptor가 웹 슬라이스에 함께 올라오므로 그 의존성도 채워야 한다. */
    @MockitoBean
    private RateLimitService rateLimitService;

    /**
     * 업로드된 이미지는 브라우저가 {@code <img src>}로 직접 요청하므로 인증 헤더를 실을 수 없다.
     * 404는 파일이 없다는 뜻이고, 401이면 필터 체인이 요청을 막았다는 뜻이다.
     */
    @Test
    void uploadedFilesAreReachableWithoutAuthHeaders() throws Exception {
        mockMvc.perform(get("/api/files/profiles/sample.png"))
                .andExpect(status().isNotFound());
    }

    @Test
    void otherApiEndpointsStillRequireAuthentication() throws Exception {
        mockMvc.perform(get("/api/probe"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void corsConfigurationIsReusedAcrossRequests() {
        MockHttpServletRequest firstRequest = new MockHttpServletRequest("GET", "/api/probe");
        MockHttpServletRequest secondRequest = new MockHttpServletRequest("GET", "/api/probe");

        assertSame(
                corsConfigurationSource.getCorsConfiguration(firstRequest),
                corsConfigurationSource.getCorsConfiguration(secondRequest));
    }

    @RestController
    static class ProbeController {

        @GetMapping("/api/probe")
        String probe() {
            return "ok";
        }
    }
}
