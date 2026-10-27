package mx.gilortiz.transactions.provider.http;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.net.URI;
import java.time.Duration;

@ConfigurationProperties(prefix = "provider")
public record ProviderProperties(URI baseUrl, Duration connectTimeout, Duration readTimeout) {}
