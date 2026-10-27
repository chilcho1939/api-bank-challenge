package mx.gilortiz.transactions.provider.http;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Duration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({ProviderProperties.class})
public class ProviderClientConfig {

    @Bean
    RestClient providerRestClient(ProviderProperties properties) {
        // HTTP/1.1 explícito: evita el intento de upgrade a HTTP/2 en texto plano (h2c).
        HttpClient httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(properties.connectTimeout())
                .build();

        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(properties.readTimeout());

        return RestClient.builder()
                .baseUrl(properties.baseUrl().toString())
                .requestFactory(requestFactory)
                .build();
    }

    @Bean
    CircuitBreaker providerCircuitBreaker() {
        // Abre si al menos la mitad de las últimas 10 llamadas fallan (mínimo 5 medidas)
        // y vuelve a probar a los 10 s. Solo las excepciones cuentan como falla:
        // un rechazo de negocio (fondos insuficientes) es una respuesta sana del proveedor.
        CircuitBreakerConfig config = CircuitBreakerConfig.custom()
                .slidingWindowSize(10)
                .minimumNumberOfCalls(5)
                .failureRateThreshold(50)
                .waitDurationInOpenState(Duration.ofSeconds(10))
                .permittedNumberOfCallsInHalfOpenState(2)
                .build();
        return CircuitBreaker.of("provider", config);
    }

    @Bean
    Retry providerRetry() {
        // Solo se reintenta si la conexión nunca se estableció. Un timeout o un 5xx no:
        // el contrato no tiene llave de idempotencia y un DEBIT podría cobrarse dos veces.
        RetryConfig config = RetryConfig.custom()
                .maxAttempts(3)
                .waitDuration(Duration.ofMillis(200))
                .retryOnException(HttpTransactionProvider::isConnectionFailure)
                .build();
        return Retry.of("provider", config);
    }
}
