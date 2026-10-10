package com.camellia.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final CamelliaProperties props;

    public WebConfig(CamelliaProperties props) {
        this.props = props;
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
                .allowedOrigins(props.getCorsOrigins().split("\\s*,\\s*"))
                .allowedMethods("GET", "POST", "PATCH", "OPTIONS")
                .allowedHeaders("*");
    }
}
