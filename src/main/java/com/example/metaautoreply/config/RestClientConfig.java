package com.example.metaautoreply.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Duration;

/**
 * The {@link RestClient} used to call the Graph API.
 */
@Configuration
public class RestClientConfig {

	private static final Logger log = LoggerFactory.getLogger(RestClientConfig.class);

	private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
	private static final Duration READ_TIMEOUT = Duration.ofSeconds(10);

	@Bean
	public RestClient graphRestClient(MetaProperties props) {
		JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(
				HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build());
		requestFactory.setReadTimeout(READ_TIMEOUT);

		return RestClient.builder()
				.baseUrl(props.graphBaseUrl())
				.requestFactory(requestFactory)
				.requestInterceptor(loggingInterceptor())
				.build();
	}

	/**
	 * Logs method, URL and status.
	 *
	 * <p>Deliberately never logs headers or the request body. The bearer token lives in the
	 * Authorization header, and message bodies contain the text we are about to send to a real
	 * person — neither belongs in a log file.
	 */
	private ClientHttpRequestInterceptor loggingInterceptor() {
		return (request, body, execution) -> {
			long startedAt = System.nanoTime();
			try {
				ClientHttpResponse response = execution.execute(request, body);
				log.info("Graph API {} {} -> {} ({} ms)", request.getMethod(), request.getURI(),
						response.getStatusCode().value(), elapsedMillis(startedAt));
				return response;
			}
			catch (Exception e) {
				log.warn("Graph API {} {} failed after {} ms: {}", request.getMethod(), request.getURI(),
						elapsedMillis(startedAt), e.toString());
				throw e;
			}
		};
	}

	private static long elapsedMillis(long startedAtNanos) {
		return (System.nanoTime() - startedAtNanos) / 1_000_000;
	}
}
