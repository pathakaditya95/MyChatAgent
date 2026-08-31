package com.example.metaautoreply;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class MetaAutoReplyApplication {

	public static void main(String[] args) {
		SpringApplication.run(MetaAutoReplyApplication.class, args);
	}
}
