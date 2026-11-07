package dev.biribiri.elysium.auth.internal;

import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import org.springdoc.core.customizers.OperationCustomizer;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;

import java.util.regex.Pattern;

/**
 * Documents the scopes each API endpoint needs, from the {@link PreAuthorize} expression that enforces them, so that the
 * two can't disagree. Each scope the expression names is a separate requirement, since an endpoint that accepts several
 * accepts any one of them. {@code isAnonymous()} adds an empty requirement, which makes the token optional. An expression
 * that combines its conditions any other way, with {@code and} or {@code not}, isn't documented, so that the endpoint has
 * to declare its requirements itself rather than be described wrongly.
 *
 * <p>An endpoint that needs a token but no particular scope has no expression, so it declares this with an explicit
 * {@code @SecurityRequirement}, which this leaves alone.
 */
@Component
class ScopeRequirements implements OperationCustomizer {

    private static final Pattern SCOPE = Pattern.compile("'SCOPE_([^']+)'");
    private static final Pattern NOT_ALTERNATIVES = Pattern.compile("\\b(and|not)\\b|!|&&");

    @Override
    public Operation customize(Operation operation, HandlerMethod handlerMethod) {
        if (operation.getSecurity() != null && !operation.getSecurity().isEmpty()) {
            return operation;
        }
        var preAuthorize = AnnotatedElementUtils.findMergedAnnotation(handlerMethod.getMethod(), PreAuthorize.class);
        if (preAuthorize == null) {
            preAuthorize = AnnotatedElementUtils.findMergedAnnotation(handlerMethod.getBeanType(), PreAuthorize.class);
        }
        if (preAuthorize == null) {
            return operation;
        }

        var expression = preAuthorize.value();
        if (NOT_ALTERNATIVES.matcher(expression).find()) {
            return operation;
        }
        var scopes = SCOPE.matcher(expression);
        while (scopes.find()) {
            operation.addSecurityItem(new SecurityRequirement().addList(OAuthApiDocs.SCHEME, scopes.group(1)));
        }
        if (expression.contains("isAnonymous()")) {
            operation.addSecurityItem(new SecurityRequirement());
        }
        return operation;
    }
}
