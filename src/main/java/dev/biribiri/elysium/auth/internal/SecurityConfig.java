package dev.biribiri.elysium.auth.internal;

import dev.biribiri.elysium.actor.InstanceProperties;
import jakarta.servlet.DispatcherType;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.oauth2.server.authorization.OAuth2AuthorizationServerConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.server.authorization.authentication.ClientSecretAuthenticationProvider;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2AuthorizationConsentAuthenticationProvider;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2AuthorizationConsentAuthenticationToken;
import org.springframework.security.oauth2.server.resource.introspection.OpaqueTokenIntrospector;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.ExceptionMappingAuthenticationFailureHandler;
import org.springframework.security.web.authentication.Http403ForbiddenEntryPoint;
import org.springframework.security.web.authentication.LoginUrlAuthenticationEntryPoint;
import org.springframework.security.web.savedrequest.HttpSessionRequestCache;
import org.springframework.security.web.util.matcher.OrRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.springframework.security.config.Customizer.withDefaults;
import static org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher.pathPattern;

@Configuration(proxyBeanMethods = false)
@EnableMethodSecurity
class SecurityConfig {

    static final String AUTHORIZE_PATH = "/oauth/authorize";
    static final String TOKEN_PATH = "/oauth/token";
    static final String REVOKE_PATH = "/oauth/revoke";
    static final String CONSENT_PATH = "/oauth/consent";
    static final String LOGIN_PATH = "/login";
    static final String SIGNUP_PATH = "/signup";

    private static final String METADATA_PATH = "/.well-known/oauth-authorization-server";
    private static final String APP_REGISTRATION_PATH = "/api/v1/apps";

    private static final String[] PUBLIC_PATHS = {
            "/", "/.well-known/webfinger", "/.well-known/nodeinfo", "/nodeinfo/2.0", "/nodeinfo/2.1", "/users/*",
            "/users/*/statuses/*", "/actuator/health", "/actuator/health/**", "/images/*", "/api-docs/**",
            "/api-docs.yaml"
    };

    /**
     * API endpoints that can be read without a token. A token is still checked if the request includes one.
     */
    private static final String[] PUBLIC_API_PATHS = {
            "/api/v1/instance", "/api/v1/statuses/*", "/api/v1/accounts/*/statuses", "/api/v1/timelines/public"
    };

    /**
     * Metadata Spring always publishes, but which describes things this server doesn't offer.
     */
    private static final Set<String> UNSUPPORTED_METADATA = Set.of(
            "jwks_uri",
            "introspection_endpoint",
            "introspection_endpoint_auth_methods_supported",
            "tls_client_certificate_bound_access_tokens",
            "dpop_signing_alg_values_supported");

    /**
     * Handles the OAuth endpoints. This names them explicitly rather than using the configurer's own matcher, because
     * that would also expose the token introspection endpoint. That endpoint can't be turned off and doesn't check
     * which client owns a token, so here it falls through to the default chain and is denied.
     */
    @Bean
    @Order(1)
    SecurityFilterChain authorizationServerChain(
            HttpSecurity http, InstanceProperties instance, OAuthResponses responses) throws Exception {
        return http
                .securityMatcher(authorizationServerRequests())
                .cors(cors -> cors.configurationSource(clientCors()))
                .oauth2AuthorizationServer(server -> configure(server, instance, responses))
                .authorizeHttpRequests(requests -> requests.anyRequest().authenticated())
                .exceptionHandling(exceptions -> exceptions.authenticationEntryPoint(
                        new ClientOrLoginEntryPoint(new LoginUrlAuthenticationEntryPoint(LOGIN_PATH), responses)))
                .build();
    }

    /**
     * Handles the client API, which authenticates every request with a bearer token and keeps no session.
     */
    @Bean
    @Order(2)
    SecurityFilterChain apiChain(HttpSecurity http, OpaqueTokenIntrospector introspector, OAuthResponses responses)
            throws Exception {
        return http
                .securityMatcher("/api/**")
                .cors(cors -> cors.configurationSource(clientCors()))
                // There's no session to protect, and app registration is called with no credentials at all.
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(requests -> requests
                        .dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
                        .requestMatchers(HttpMethod.POST, APP_REGISTRATION_PATH).permitAll()
                        .requestMatchers(HttpMethod.GET, PUBLIC_API_PATHS).permitAll()
                        .requestMatchers(HttpMethod.HEAD, PUBLIC_API_PATHS).permitAll()
                        .anyRequest().authenticated())
                .oauth2ResourceServer(server -> server
                        .opaqueToken(token -> token.introspector(introspector))
                        .authenticationEntryPoint((request, response, e) -> responses.invalidToken(response))
                        .accessDeniedHandler((request, response, e) -> responses.insufficientScope(response)))
                .build();
    }

    /**
     * Handles everything else: public documents, and the HTML login, sign-up and consent pages. Anything not listed
     * here is denied. Allowing any authenticated user instead would let anyone who signs up read internal endpoints.
     */
    @Bean
    @Order(3)
    SecurityFilterChain defaultChain(HttpSecurity http) throws Exception {
        // Reads the request saved by the authorization server chain, but never replaces it, so a request denied here
        // can't lose a pending authorization.
        var requestCache = new HttpSessionRequestCache();
        requestCache.setRequestMatcher(request -> false);

        var loginFailure = new ExceptionMappingAuthenticationFailureHandler();
        loginFailure.setDefaultFailureUrl(LOGIN_PATH + "?error");
        loginFailure.setExceptionMappings(Map.of(PasswordHashingBusyException.class.getName(), LOGIN_PATH + "?busy"));

        return http
                // Applies Spring MVC's CORS settings, including to preflight requests: @CrossOrigin on controllers, and
                // the mappings in WebMvcConfigurer classes.
                .cors(withDefaults())
                .authorizeHttpRequests(requests -> requests
                        // Lets Spring's error handling keep its original status instead of turning it into a 403.
                        .dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
                        .requestMatchers(HttpMethod.GET, PUBLIC_PATHS).permitAll()
                        .requestMatchers(HttpMethod.HEAD, PUBLIC_PATHS).permitAll()
                        .requestMatchers(HttpMethod.GET, SIGNUP_PATH).permitAll()
                        .requestMatchers(HttpMethod.POST, SIGNUP_PATH).permitAll()
                        .requestMatchers(HttpMethod.GET, CONSENT_PATH).authenticated()
                        .anyRequest().denyAll())
                .requestCache(cache -> cache.requestCache(requestCache))
                .formLogin(login -> login.loginPage(LOGIN_PATH).failureHandler(loginFailure).permitAll())
                .logout(withDefaults())
                .exceptionHandling(exceptions -> exceptions.authenticationEntryPoint(new Http403ForbiddenEntryPoint()))
                .build();
    }

    private static void configure(
            OAuth2AuthorizationServerConfigurer server, InstanceProperties instance, OAuthResponses responses) {
        server.authorizationEndpoint(endpoint -> endpoint
                        .authorizationRequestConverter(DefaultScopeConverters.authorizationRequest())
                        .consentPage(CONSENT_PATH)
                        .authorizationResponseHandler(responses::authorizationSuccess)
                        .errorResponseHandler(responses::authorizationFailure)
                        .authenticationProviders(providers -> providers.stream()
                                .filter(OAuth2AuthorizationConsentAuthenticationProvider.class::isInstance)
                                .map(OAuth2AuthorizationConsentAuthenticationProvider.class::cast)
                                .forEach(SecurityConfig::treatEmptyConsentAsDenial)))
                .tokenEndpoint(endpoint -> endpoint
                        .accessTokenRequestConverter(DefaultScopeConverters.clientCredentialsRequest())
                        .accessTokenResponseHandler(responses::accessToken))
                .tokenRevocationEndpoint(endpoint -> endpoint
                        .revocationResponseHandler(responses::revocationSuccess)
                        .errorResponseHandler(responses::revocationFailure))
                .clientAuthentication(authentication -> authentication
                        .authenticationProviders(providers -> providers.stream()
                                .filter(ClientSecretAuthenticationProvider.class::isInstance)
                                .map(ClientSecretAuthenticationProvider.class::cast)
                                .forEach(provider -> provider.setPasswordEncoder(ClientSecretEncoder.INSTANCE)))
                        .errorResponseHandler(responses::clientAuthenticationFailure))
                .authorizationServerMetadataEndpoint(endpoint -> endpoint.authorizationServerMetadataCustomizer(
                        metadata -> metadata
                                .claims(claims -> UNSUPPORTED_METADATA.forEach(claims::remove))
                                .claim("grant_types_supported", List.of(
                                        AuthorizationGrantType.AUTHORIZATION_CODE.getValue(),
                                        AuthorizationGrantType.CLIENT_CREDENTIALS.getValue()))
                                .claim("token_endpoint_auth_methods_supported", clientAuthenticationMethods())
                                .claim("revocation_endpoint_auth_methods_supported", clientAuthenticationMethods())
                                .claim("response_modes_supported", List.of("query"))
                                .claim("scopes_supported", List.copyOf(OAuthScopes.supported()))
                                .claim("app_registration_endpoint",
                                        instance.resolve(APP_REGISTRATION_PATH).toString())));
    }

    /**
     * Makes the Deny button work for a user who approved this client before. Spring only treats a consent as denied if
     * it ends up with no scopes, but it merges in the previously approved ones first, so without this it would issue a
     * code. This also removes the earlier approval, so the client has to ask again next time.
     */
    private static void treatEmptyConsentAsDenial(OAuth2AuthorizationConsentAuthenticationProvider provider) {
        provider.setAuthorizationConsentCustomizer(context -> {
            OAuth2AuthorizationConsentAuthenticationToken consent = context.getAuthentication();
            if (consent.getScopes().isEmpty()) {
                context.getAuthorizationConsent().authorities(Set::clear);
            }
        });
    }

    private static List<String> clientAuthenticationMethods() {
        return List.of(
                ClientAuthenticationMethod.CLIENT_SECRET_BASIC.getValue(),
                ClientAuthenticationMethod.CLIENT_SECRET_POST.getValue());
    }

    /**
     * Preflight requests are included for the endpoints browser-based clients call, because the endpoints themselves
     * only match POST or GET.
     */
    private static RequestMatcher authorizationServerRequests() {
        return new OrRequestMatcher(
                pathPattern(HttpMethod.GET, AUTHORIZE_PATH),
                pathPattern(HttpMethod.POST, AUTHORIZE_PATH),
                pathPattern(HttpMethod.POST, TOKEN_PATH),
                pathPattern(HttpMethod.OPTIONS, TOKEN_PATH),
                pathPattern(HttpMethod.POST, REVOKE_PATH),
                pathPattern(HttpMethod.OPTIONS, REVOKE_PATH),
                pathPattern(HttpMethod.GET, METADATA_PATH),
                pathPattern(HttpMethod.OPTIONS, METADATA_PATH));
    }

    /**
     * Allows browser-based clients to call the API from any origin, as Mastodon does. They authenticate with tokens
     * rather than cookies, so this doesn't expose logged-in sessions. The authorization endpoint isn't included.
     *
     * <p>This must not be a bean: Spring Security would then use a bean named {@code corsConfigurationSource} for every
     * chain, in place of Spring MVC's CORS settings: {@code @CrossOrigin} on the discovery controller, and the mapping
     * for actor URLs in the {@code activitypub} module.
     */
    private static CorsConfigurationSource clientCors() {
        var configuration = new CorsConfiguration();
        configuration.addAllowedOrigin(CorsConfiguration.ALL);
        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        // Idempotency-Key is ignored, but clients that send it would otherwise fail the preflight request.
        configuration.setAllowedHeaders(List.of("Authorization", "Content-Type", "Idempotency-Key"));
        configuration.setExposedHeaders(List.of("Link"));

        var source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", configuration);
        source.registerCorsConfiguration(TOKEN_PATH, configuration);
        source.registerCorsConfiguration(REVOKE_PATH, configuration);
        source.registerCorsConfiguration(METADATA_PATH, configuration);
        return source;
    }
}
