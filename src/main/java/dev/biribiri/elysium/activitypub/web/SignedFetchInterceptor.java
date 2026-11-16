package dev.biribiri.elysium.activitypub.web;

import dev.biribiri.elysium.activitypub.http.FederationProperties;
import dev.biribiri.elysium.activitypub.signature.SignatureVerificationException.Failure;
import dev.biribiri.elysium.activitypub.web.FetchSigners.FetchSignature;
import dev.biribiri.elysium.ratelimit.ClientAddresses;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Checks the signature on fetches of {@link SignedFetch} documents before their handler runs, so that a refusal doesn't
 * depend on whether the document exists.
 *
 * <p>Every such answer varies by {@code Signature}, whether or not signatures are required, because a handler can read one
 * anyway. When they're required, a signed answer can't be cached for anyone else, since a blocked server mustn't be served
 * from a cache that a permitted one filled.
 */
@Configuration(proxyBeanMethods = false)
class SignedFetchInterceptor implements HandlerInterceptor, WebMvcConfigurer {

    private final FetchSigners signers;
    private final AddressFetchBudgets budgets;
    private final ClientAddresses clients;
    private final boolean required;

    SignedFetchInterceptor(
            FetchSigners signers,
            AddressFetchBudgets budgets,
            ClientAddresses clients,
            FederationProperties federation) {
        this.signers = signers;
        this.budgets = budgets;
        this.clients = clients;
        this.required = federation.authorizedFetch();
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(this);
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!(handler instanceof HandlerMethod method) || !method.hasMethodAnnotation(SignedFetch.class)) {
            return true;
        }
        response.addHeader(HttpHeaders.VARY, FetchSigners.SIGNATURE);
        if (!required) {
            return true;
        }
        switch (signers.check(request)) {
            case FetchSignature.Signed _ -> {
                response.setHeader(HttpHeaders.CACHE_CONTROL, CacheControl.noStore().cachePrivate().getHeaderValue());
                return true;
            }
            case FetchSignature.Unsigned _ -> {
                if (method.getMethodAnnotation(SignedFetch.class).allowUnsigned()) {
                    return true;
                }
                response.setStatus(HttpStatus.UNAUTHORIZED.value());
                return false;
            }
            case FetchSignature.Refused(var failure) -> {
                response.setStatus(failure.status());
                if (failure == Failure.FETCH_BUDGET_SPENT) {
                    response.setHeader(HttpHeaders.RETRY_AFTER, budgets.retryAfter(clients.keyFor(request)));
                }
                return false;
            }
        }
    }
}
