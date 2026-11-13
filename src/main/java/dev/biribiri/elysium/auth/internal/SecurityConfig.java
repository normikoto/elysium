package dev.biribiri.elysium.auth.internal;

import dev.biribiri.elysium.actor.ActorService;
import dev.biribiri.elysium.actor.InstanceProperties;
import jakarta.servlet.DispatcherType;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.annotation.web.configurers.oauth2.server.authorization.OAuth2AuthorizationServerConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.server.authorization.authentication.ClientSecretAuthenticationProvider;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2AuthorizationConsentAuthenticationProvider;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2AuthorizationConsentAuthenticationToken;
import org.springframework.security.oauth2.server.resource.introspection.OpaqueTokenIntrospector;
import org.springframework.security.web.DefaultRedirectStrategy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.Http403ForbiddenEntryPoint;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.security.web.authentication.LoginUrlAuthenticationEntryPoint;
import org.springframework.security.web.authentication.SavedRequestAwareAuthenticationSuccessHandler;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.context.SecurityContextHolderFilter;
import org.springframework.security.web.savedrequest.HttpSessionRequestCache;
import org.springframework.security.web.util.matcher.OrRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.springframework.security.config.Customizer.withDefaults;
import static org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher.pathPattern;

@Configuration(proxyBeanMethods = false)
@EnableMethodSecurity
class SecurityConfig {

    static final String AUTHORIZE_PATH = "/oauth/authorize";
    static final String TOKEN_PATH = "/oauth/token";
    static final String REVOKE_PATH = "/oauth/revoke";
    static final String CONSENT_PATH = "/oauth/consent";
    static final String AUTHORIZED_APPS_PATH = "/oauth/authorized_applications";
    static final String REVOKE_AUTHORIZED_APP_PATH = AUTHORIZED_APPS_PATH + "/{id}/revoke";
    static final String LOGIN_PATH = "/login";
    static final String SIGNUP_PATH = "/signup";
    static final String SIGNUP_VERIFY_PATH = SIGNUP_PATH + "/verify";
    static final String DELETE_ACCOUNT_PATH = "/settings/delete-account";
    static final String SETTINGS_PATH = "/settings";
    static final String TWO_FACTOR_LOGIN_PATH = LOGIN_PATH + "/two-factor";
    static final String TWO_FACTOR_SETTINGS_PATH = SETTINGS_PATH + "/two-factor";
    static final String TWO_FACTOR_SETUP_PATH = TWO_FACTOR_SETTINGS_PATH + "/setup";
    static final String TWO_FACTOR_CONFIRM_PATH = TWO_FACTOR_SETTINGS_PATH + "/confirm";
    static final String TWO_FACTOR_DISABLE_PATH = TWO_FACTOR_SETTINGS_PATH + "/disable";
    static final String RECOVERY_CODES_PATH = TWO_FACTOR_SETTINGS_PATH + "/recovery-codes";

    static final String METADATA_PATH = "/.well-known/oauth-authorization-server";
    static final String APP_REGISTRATION_PATH = "/api/v1/apps";

    /**
     * Pages for admins, which all go under this path so that they're covered by one rule.
     */
    private static final String ADMIN_PATHS = "/admin/**";

    /**
     * The job UI's own API, which it calls from the browser. See {@link #sameOriginJobApiRequests}.
     */
    private static final String JOB_API_PATHS = "/admin/db-scheduler-api/**";

    private static final String[] PUBLIC_PATHS = {
            "/", "/.well-known/webfinger", "/.well-known/nodeinfo", "/nodeinfo/2.0", "/nodeinfo/2.1", "/users/*",
            "/users/*/followers", "/users/*/following", "/tags/*", "/users/*/outbox", "/users/*/statuses/*",
            "/users/*/statuses/*/likes", "/users/*/statuses/*/shares", "/users/*/quote_authorizations/*", "/actor",
            "/actor/outbox", "/actuator/health", "/actuator/health/**", "/images/*", "/media/*", "/api-docs/**",
            "/api-docs.yaml"
    };

    /**
     * API endpoints that can be read without a token. A token is still checked if the request includes one. An account is
     * matched by an ID's characters, so that paths such as {@code verify_credentials} still need a token.
     */
    private static final String[] PUBLIC_API_PATHS = {
            "/api/v1/instance", "/api/v2/instance", "/api/v1/statuses/*", "/api/v1/statuses/*/context",
            "/api/v1/statuses/*/history", "/api/v1/polls/*",
            "/api/v1/accounts/*/statuses",
            "/api/v1/timelines/public", "/api/v1/timelines/tag/*", "/api/v1/tags/*",
            "/api/v1/accounts/{id:[0-9a-fA-F-]+}", "/api/v1/accounts/{id:[0-9a-fA-F-]+}/followers",
            "/api/v1/accounts/{id:[0-9a-fA-F-]+}/following", "/api/v1/accounts/lookup", "/api/v2/search"
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
     * Checks passwords for the login form. It's declared so that accounts with two-factor login stop after their password, with
     * {@link TwoFactorRequiredException}. Being the only provider bean, it replaces the one Spring Boot would make, so there's no
     * other provider that could log someone in with only a password.
     */
    @Bean
    DaoAuthenticationProvider passwordLogins(
            LocalAccounts accounts, PasswordEncoder passwordEncoder, TwoFactorAccounts twoFactor) {
        var provider = new DaoAuthenticationProvider(accounts);
        provider.setPasswordEncoder(passwordEncoder);
        // Replaces the default check, which is only that the password hasn't expired, and passwords here never do.
        provider.setPostAuthenticationChecks(user -> {
            // A logged in user's name is their actor ID: see LocalAccounts.loadUserByUsername.
            var actorId = UUID.fromString(user.getUsername());
            if (twoFactor.isEnabled(actorId)) {
                throw new TwoFactorRequiredException(actorId);
            }
        });
        return provider;
    }

    /**
     * Handles the OAuth endpoints. This names them explicitly rather than using the configurer's own matcher, because
     * that would also expose the token introspection endpoint. That endpoint can't be turned off and doesn't check
     * which client owns a token, so here it falls through to the default chain and is denied.
     */
    @Bean
    @Order(1)
    SecurityFilterChain authorizationServerChain(
            HttpSecurity http,
            InstanceProperties instance,
            OAuthResponses responses,
            AdminAccess adminAccess,
            ActorService actors,
            LocalAccounts accounts) {
        return http
                .securityMatcher(authorizationServerRequests())
                .addFilterAfter(new ActiveAccountFilter(actors, accounts), SecurityContextHolderFilter.class)
                .cors(cors -> cors.configurationSource(clientCors()))
                .oauth2AuthorizationServer(server -> configure(server, instance, responses, adminAccess))
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
    SecurityFilterChain apiChain(HttpSecurity http, OpaqueTokenIntrospector introspector, OAuthResponses responses) {
        return http
                .securityMatcher("/api/**")
                .cors(cors -> cors.configurationSource(clientCors()))
                // There's no session to protect, and app registration is called with no credentials at all.
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(requests -> requests
                        .dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
                        .requestMatchers(HttpMethod.POST, APP_REGISTRATION_PATH).permitAll()
                        .requestMatchers(HttpMethod.GET, PUBLIC_API_PATHS).permitAll()
                        .requestMatchers(HttpMethod.HEAD, PUBLIC_API_PATHS).permitAll()
                        .anyRequest().authenticated())
                .oauth2ResourceServer(server -> server
                        .opaqueToken(token -> token.introspector(introspector))
                        .authenticationEntryPoint((_, response, _) -> responses.invalidToken(response))
                        .accessDeniedHandler((_, response, _) -> responses.insufficientScope(response)))
                .build();
    }

    /**
     * Handles deliveries from other servers, which the {@code activitypub} module checks the signatures of. They're never
     * logged in, so there's no session to create, or for CSRF to protect.
     */
    @Bean
    @Order(3)
    SecurityFilterChain inboxChain(HttpSecurity http) {
        return http
                .securityMatcher(new OrRequestMatcher(
                        pathPattern(HttpMethod.POST, "/inbox"),
                        pathPattern(HttpMethod.POST, "/actor/inbox"),
                        pathPattern(HttpMethod.POST, "/users/*/inbox")))
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(requests -> requests.anyRequest().permitAll())
                .build();
    }

    /**
     * Handles everything else: public documents, and the HTML login, sign-up and consent pages. Anything not listed
     * here is denied. Allowing any authenticated user instead would let anyone who signs up read internal endpoints.
     */
    @Bean
    @Order(4)
    SecurityFilterChain defaultChain(
            HttpSecurity http,
            AuthRateLimits limits,
            AdminAccess adminAccess,
            InstanceProperties instance,
            ActorService actors,
            LocalAccounts accounts,
            TwoFactorAccounts twoFactor) {
        var pagesNeedingLogin = new OrRequestMatcher(
                adminPageRequests(),
                pathPattern(HttpMethod.GET, DELETE_ACCOUNT_PATH),
                pathPattern(HttpMethod.GET, SETTINGS_PATH),
                pathPattern(HttpMethod.GET, TWO_FACTOR_SETTINGS_PATH),
                pathPattern(HttpMethod.GET, AUTHORIZED_APPS_PATH));

        // Reads the request saved by the authorization server chain, but never replaces it, so a request denied here
        // can't lose a pending authorization. It only saves a page if nothing is waiting, so that logging in can return to
        // that page.
        var requestCache = new HttpSessionRequestCache();
        requestCache.setRequestMatcher(
                request -> pagesNeedingLogin.matches(request) && requestCache.getRequest(request, null) == null);

        var redirects = new DefaultRedirectStrategy();

        // Setting a success handler replaces Spring's default one, which is also what it would give this request cache.
        var savedRequests = new SavedRequestAwareAuthenticationSuccessHandler();
        savedRequests.setRequestCache(requestCache);
        // Both for the login form, and for a code after it.
        AuthenticationSuccessHandler loginSuccess = (request, response, authentication) -> {
            limits.loginSucceeded(request);
            LoginGenerationFilter.loggedIn(request);
            savedRequests.onAuthenticationSuccess(request, response, authentication);
        };

        return http
                .addFilterAfter(new ActiveAccountFilter(actors, accounts), SecurityContextHolderFilter.class)
                // Runs after the CSRF filter, so requests without a valid token don't use up anyone's attempts.
                .addFilterBefore(new LoginRateLimitFilter(limits), UsernamePasswordAuthenticationFilter.class)
                // After the rate limit, as filters at the same position run in the order they're added.
                .addFilterBefore(new LoginGenerationFilter(accounts), UsernamePasswordAuthenticationFilter.class)
                // Applies Spring MVC's CORS settings, including to preflight requests: @CrossOrigin on controllers, and
                // the mappings in WebMvcConfigurer classes.
                .cors(withDefaults())
                .csrf(csrf -> csrf.ignoringRequestMatchers(sameOriginJobApiRequests(instance)))
                .authorizeHttpRequests(requests -> requests
                        // Lets Spring's error handling keep its original status instead of turning it into a 403.
                        .dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
                        .requestMatchers(HttpMethod.GET, PUBLIC_PATHS).permitAll()
                        .requestMatchers(HttpMethod.HEAD, PUBLIC_PATHS).permitAll()
                        .requestMatchers(HttpMethod.GET, SIGNUP_PATH, SIGNUP_VERIFY_PATH).permitAll()
                        .requestMatchers(HttpMethod.POST, SIGNUP_PATH, SIGNUP_VERIFY_PATH).permitAll()
                        // Submitting it is handled by TwoFactorLoginFilter, before this.
                        .requestMatchers(HttpMethod.GET, TWO_FACTOR_LOGIN_PATH).permitAll()
                        .requestMatchers(HttpMethod.GET, CONSENT_PATH).authenticated()
                        .requestMatchers(HttpMethod.GET, DELETE_ACCOUNT_PATH).authenticated()
                        .requestMatchers(HttpMethod.POST, DELETE_ACCOUNT_PATH).authenticated()
                        .requestMatchers(HttpMethod.GET, SETTINGS_PATH).authenticated()
                        .requestMatchers(HttpMethod.POST, SETTINGS_PATH).authenticated()
                        .requestMatchers(HttpMethod.GET, TWO_FACTOR_SETTINGS_PATH).authenticated()
                        .requestMatchers(HttpMethod.POST, TWO_FACTOR_SETUP_PATH, TWO_FACTOR_CONFIRM_PATH,
                                TWO_FACTOR_DISABLE_PATH, RECOVERY_CODES_PATH).authenticated()
                        .requestMatchers(HttpMethod.GET, AUTHORIZED_APPS_PATH).authenticated()
                        .requestMatchers(HttpMethod.POST, REVOKE_AUTHORIZED_APP_PATH).authenticated()
                        // Not hasRole: see AdminAccess.
                        .requestMatchers(ADMIN_PATHS).access(adminAccess)
                        .anyRequest().denyAll())
                .requestCache(cache -> cache.requestCache(requestCache))
                .with(new TwoFactorLoginFilter.Configurer(actors, accounts, twoFactor, limits, loginSuccess), withDefaults())
                .formLogin(login -> login
                        .loginPage(LOGIN_PATH)
                        .successHandler(loginSuccess)
                        .failureHandler((request, response, failure) -> {
                            if (failure instanceof TwoFactorRequiredException required) {
                                // The password was right. Whether the code is counts separately.
                                limits.loginNotCounted(request);
                                // The account exists, so its generation was read.
                                TwoFactorChallenge.start(request, required.actorId(),
                                        LoginGenerationFilter.generationBeingLoggedIn(request).orElseThrow());
                                redirects.sendRedirect(request, response, TWO_FACTOR_LOGIN_PATH);
                                return;
                            }
                            // Unknown usernames are reported as bad credentials too.
                            if (failure instanceof BadCredentialsException) {
                                limits.loginFailed(request);
                            } else {
                                limits.loginNotCounted(request);
                            }
                            // Not one of Spring's failure handlers, which keep the exception in the session. It has the
                            // submitted password in it, and sessions are stored in the database.
                            redirects.sendRedirect(request, response, LOGIN_PATH
                                    + (failure instanceof PasswordHashingBusyException ? "?busy" : "?error"));
                        })
                        .permitAll())
                .logout(withDefaults())
                // Sessions expire, so an admin who reloads a page needs a way back in. Other requests get
                // a bare 403. The job UI's API calls are among them, because they couldn't use a login page as a response.
                .exceptionHandling(exceptions -> exceptions
                        .defaultAuthenticationEntryPointFor(new LoginUrlAuthenticationEntryPoint(LOGIN_PATH), pagesNeedingLogin)
                        .defaultAuthenticationEntryPointFor(new Http403ForbiddenEntryPoint(), _ -> true))
                .build();
    }

    private static void configure(
            OAuth2AuthorizationServerConfigurer server,
            InstanceProperties instance,
            OAuthResponses responses,
            AdminAccess adminAccess) {
        server.authorizationEndpoint(endpoint -> endpoint
                        .authorizationRequestConverter(DefaultScopeConverters.authorizationRequest())
                        .authorizationRequestConverter(new ConsentScopeConverter(adminAccess))
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
     * Pages that a person opens, rather than the files they load and the API calls they make. The {@code Accept} header
     * tells these apart because a browser asks for HTML when opening a page, and not otherwise.
     */
    private static RequestMatcher adminPageRequests() {
        // The API and the files that pages load aren't pages to send someone back to.
        var notPages = new OrRequestMatcher(
                pathPattern(JOB_API_PATHS),
                pathPattern("/admin/db-scheduler/assets/**"),
                pathPattern("/admin/db-scheduler/js/**"));
        var admin = pathPattern(HttpMethod.GET, ADMIN_PATHS);
        return request -> {
            var accept = request.getHeader(HttpHeaders.ACCEPT);
            return admin.matches(request)
                    && !notPages.matches(request)
                    && accept != null
                    && accept.contains(MediaType.TEXT_HTML_VALUE);
        };
    }

    /**
     * Requests from the job UI, which can't send a CSRF token, since it's a prebuilt page that doesn't know about them.
     * Browsers always add {@code Origin} to these POST requests, and pages can't change it, so a page on another site can't
     * pass this. A request without the header isn't let through, and still needs a token. This uses the configured URL
     * and not the request's own, because a proxy or a client can change what the request says.
     */
    private static RequestMatcher sameOriginJobApiRequests(InstanceProperties instance) {
        var api = pathPattern(HttpMethod.POST, JOB_API_PATHS);
        return request -> api.matches(request) && instance.origin().equals(request.getHeader(HttpHeaders.ORIGIN));
    }

    /**
     * Allows browser-based clients to call the API from any origin, as Mastodon does. They authenticate with tokens
     * rather than cookies, so this doesn't expose logged-in sessions. The authorization endpoint isn't included.
     *
     * <p>This must not be a bean: Spring Security would then use a bean named {@code corsConfigurationSource} for every
     * chain, in place of Spring MVC's CORS settings: {@code @CrossOrigin} on the discovery controller, and the mapping
     * for actor and status URLs in the {@code activitypub} module.
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
