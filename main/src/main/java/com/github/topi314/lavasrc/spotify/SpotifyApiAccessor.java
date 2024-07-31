package com.github.topi314.lavasrc.spotify;

import lombok.Getter;
import org.apache.hc.core5.http.ParseException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import se.michaelthelin.spotify.SpotifyApi;
import se.michaelthelin.spotify.exceptions.SpotifyWebApiException;
import se.michaelthelin.spotify.requests.authorization.client_credentials.ClientCredentialsRequest;

import java.io.IOException;
import java.time.Instant;

public class SpotifyApiAccessor {
	private static final Logger log = LoggerFactory.getLogger(SpotifyApiAccessor.class);

	@Getter
	private final SpotifyApi spotifyApi;

	private final ClientCredentialsRequest clientCredentialsRequest;

	private final String clientId;
	private final String clientSecret;

	private String accessToken;
	private Instant expires;

	public SpotifyApiAccessor(String clientId, String clientSecret) {
		this.clientId = clientId;
		this.clientSecret = clientSecret;

		if (!hasValidCredentials()) {
			throw new IllegalArgumentException("You must provide a valid client id and client secret");
		}

		spotifyApi = new SpotifyApi.Builder()
			.setClientId(clientId)
			.setClientSecret(clientSecret)
			.build();

		clientCredentialsRequest = spotifyApi.clientCredentials().build();
	}

	public String getAccessToken() {
		if (accessToken == null || expires == null || expires.isBefore(Instant.now())) {
			synchronized (this) {
				if (accessToken == null || expires == null || expires.isBefore(Instant.now())) {
					refreshAccessToken();
				}
			}
		}

		return accessToken;
	}

	private void refreshAccessToken() {
		if(!hasValidCredentials()) {
			throw new IllegalArgumentException("You must provide a valid client id and client secret");
		}

		try {
			var clientCredentials = clientCredentialsRequest.execute();
			accessToken  = clientCredentials.getAccessToken();
			spotifyApi.setAccessToken(accessToken);
			expires = Instant.now().plusSeconds(clientCredentials.getExpiresIn());
		} catch (IOException | SpotifyWebApiException | ParseException e) {
			throw new RuntimeException("Access token refreshing failed", e);
		}
	}

	private boolean hasValidCredentials() {
		return clientId != null && !clientId.isEmpty() && clientSecret != null && !clientSecret.isEmpty();
	}

}
