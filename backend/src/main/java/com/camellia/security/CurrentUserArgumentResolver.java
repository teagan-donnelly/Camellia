package com.camellia.security;

import com.camellia.web.ApiException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

/** Injects the {@link DemoUser} resolved from the signed demo-session cookie into controller methods. */
@Component
public class CurrentUserArgumentResolver implements HandlerMethodArgumentResolver {

    private final DemoSessionService sessions;

    public CurrentUserArgumentResolver(DemoSessionService sessions) {
        this.sessions = sessions;
    }

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return parameter.getParameterType().equals(DemoUser.class);
    }

    @Override
    public Object resolveArgument(MethodParameter parameter, ModelAndViewContainer mav, NativeWebRequest webRequest,
                                  WebDataBinderFactory binderFactory) {
        HttpServletRequest request = webRequest.getNativeRequest(HttpServletRequest.class);
        String token = null;
        if (request != null && request.getCookies() != null) {
            for (Cookie c : request.getCookies()) {
                if (DemoSessionService.COOKIE.equals(c.getName())) token = c.getValue();
            }
        }
        return sessions.resolve(token).orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "DEMO_SESSION_REQUIRED",
                "Select a demo user to continue (simulated identity, not a real login)."));
    }
}
