package com.ubot.chat.config;

import com.maxmind.geoip2.DatabaseReader;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.io.InputStream;

@Configuration
public class GeoIpConfig {
	@Bean
	public DatabaseReader geoIpDatabaseReader() throws IOException {
		InputStream database = new ClassPathResource(
				"geoip/GeoLite2-City.mmdb"
		).getInputStream();

		return new DatabaseReader.Builder(database).build();
	}
}
