package com.ubot.chat.util;

import com.maxmind.geoip2.DatabaseReader;
import com.ubot.ranking.enums.RegionSido;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.net.InetAddress;
import java.util.Optional;

@Component
@RequiredArgsConstructor
public class IpRegionResolver {
	private final DatabaseReader databaseReader;

	public Optional<RegionSido> resolve(String ip) {
		if(!StringUtils.hasText(ip))
			return Optional.empty();

		try {
			InetAddress address = InetAddress.getByName(ip);

			return databaseReader.tryCity(address)
					.flatMap(response -> {
						if(!"KR".equals(response.country().isoCode()))
							return Optional.empty();

						String subdivisionCode = response.mostSpecificSubdivision().isoCode();

						return RegionSido.fromCode(subdivisionCode);
					});
		} catch (Exception e) {
			return Optional.empty();
		}
	}
}
