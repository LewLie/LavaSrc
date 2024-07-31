package com.github.topi314.lavasrc.database;

import com.github.benmanes.caffeine.cache.AsyncCache;
import com.github.topi314.lavasrc.spotify.SpotifySourceManager;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import lombok.Getter;
import org.jetbrains.annotations.NotNull;
import se.michaelthelin.spotify.model_objects.specification.Track;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.Function;
import java.util.function.Supplier;

public class Database {

	@Getter
	private final HikariConfig hikariConfig = new HikariConfig();

	@Getter
	private final HikariDataSource hikariDataSource;

	private final AsyncCache<String, Track> spotifyCachedTracks;

	@Getter
	private final DatabaseConfiguration configuration;

	private final SpotifySourceManager spotifySourceManager;

	public Database(
		@NotNull
		DatabaseConfiguration configuration,
		SpotifySourceManager spotifySourceManager
	) {
		this(configuration, spotifySourceManager, new DefaultDatabaseDecorator(), new DefaultCachedTracksDecorator());
	}

	public Database(
		@NotNull
		DatabaseConfiguration configuration,
		SpotifySourceManager spotifySourceManager,
		@NotNull
		Function<HikariConfig, Void> configDecorator,
		@NotNull
		Function<Void, AsyncCache<String, Track>> spotifyCachedTracksDecorator
	) {
		this.configuration = configuration;
		this.spotifySourceManager = spotifySourceManager;

		hikariConfig.setJdbcUrl(configuration.getJdbcURL());
		hikariConfig.setUsername(configuration.getUsername());
		hikariConfig.setPassword(configuration.getPassword());

		configDecorator.apply(hikariConfig);
		hikariDataSource = new HikariDataSource(hikariConfig);

		spotifyCachedTracks = spotifyCachedTracksDecorator.apply(null);

		try {
			testConnection();
			createTables();
		} catch (SQLException e) {
			throw new RuntimeException("Failed to setup connection to database.", e);
		}
	}

	private void testConnection() throws SQLException {
		try(Statement stm = hikariDataSource.getConnection().createStatement()) {
			stm.executeQuery("SELECT 1");
		}
	}

	private void createTables() throws SQLException {
		try(Statement stm = hikariDataSource.getConnection().createStatement()) {
			stm.executeUpdate(
			"CREATE TABLE IF NOT EXISTS `" + configuration.getSpotifyTracksTableName() + "` (" + // spotify_track_metadata
				"`id_" + configuration.getSpotifyTracksTableName() + "` BIGINT NOT NULL AUTO_INCREMENT, " +
				"`album_id` VARCHAR(100) NOT NULL, " +
				"`artist1_id` VARCHAR(100) NOT NULL, " +
				"`artist2_id` VARCHAR(100), " +
				"`artist3_id` VARCHAR(100), " +
				"`artist4_id` VARCHAR(100),\n" +
				"`track_id` VARCHAR(100) NOT NULL, " +
				"`track_explicit` BOOLEAN(1) NOT NULL, " +
				"`track_popularity` INT(3) NOT NULL, " +
				"`metadata` TEXT(65535) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL, " +
				"UNIQUE KEY `" + configuration.getSpotifyTracksTableName() + "_track_id_idx` (`track_id`) USING BTREE, " +
				"KEY `" + configuration.getSpotifyTracksTableName() + "_album_id_idx` (`album_id`) USING BTREE, " +
				"KEY `" + configuration.getSpotifyTracksTableName() + "_artist1_id_idx` (`artist1_id`) USING BTREE, " +
				"KEY `" + configuration.getSpotifyTracksTableName() + "_artist2_id_idx` (`artist2_id`) USING BTREE, " +
				"KEY `" + configuration.getSpotifyTracksTableName() + "_artist3_id_idx` (`artist3_id`) USING BTREE, " +
				"KEY `" + configuration.getSpotifyTracksTableName() + "_artist4_id_idx` (`artist4_id`) USING BTREE, " +
				"PRIMARY KEY (`id_" + configuration.getSpotifyTracksTableName() + "`) " +
				");");
		}
	}

	synchronized public CompletableFuture<List<Track>> getSpotifyTrackInfo(String... trackIds) {
		if(trackIds.length > 50) {
			throw new IllegalArgumentException("You can only get 50 tracks at same time.");
		}

		Map<String, CompletableFuture<Track>> completableToTakeAccount = new HashMap<>();
		List<String> tracksToFetch = new ArrayList<>();

		for(String trackId : trackIds) {
			CompletableFuture<Track> future = spotifyCachedTracks.getIfPresent(trackId);

			if(future != null) {
				completableToTakeAccount.put(trackId, future);
			} else {
				// Crear nuevo completable
				CompletableFuture<Track> completableFuture = new CompletableFuture<>();
				spotifyCachedTracks.put(trackId, completableFuture);
				completableToTakeAccount.put(trackId, completableFuture);
				tracksToFetch.add(trackId);
			}
		}

		return CompletableFuture.supplyAsync((Supplier<List<Track>>) () -> {
			try(Statement stm = hikariDataSource.getConnection().createStatement()) {
				List<String> tracksToFetchFromSpotify = new ArrayList<>();

				for(String trackId : tracksToFetch) {
					// First try to retrieve metadata from database
					try(ResultSet res = stm.executeQuery("SELECT metadata FROM `" + configuration.getSpotifyTracksTableName() + "` WHERE `track_id` = '" + trackId + "' LIMIT 1")) {
						if(!res.next()) {
							tracksToFetchFromSpotify.add(trackId);
							continue;
						}

						// Get metadata from table and complete related future task
						String metadataRaw = res.getString("metadata");
						completableToTakeAccount.get(trackId).complete(new Track.JsonUtil().createModelObject(metadataRaw));
					}
				}

				if(!tracksToFetchFromSpotify.isEmpty()) {
					// We need to fetch tracks from spotify
				}

				throw new UnsupportedOperationException("Not implemented");
			} catch (SQLException ex) {
				throw new CompletionException("Exception retrieving spotify track metadata from database, trackID: " + 0, ex);
			}
		});
	}

	public void setSpotifyTrackInfo(String trackId, Track trackInfo) {
		spotifyCachedTracks.put(trackId, CompletableFuture.completedFuture(new Track.Builder().build()));
	}

	public void invalidateSpotifyTrackInfo(String trackId) {
		spotifyCachedTracks.synchronous().invalidate(trackId);
	}

	public void invalidateAllSpotifyTracksCache() {
		spotifyCachedTracks.synchronous().invalidateAll();
	}

	private void saveSpotifyTrackInfo(String trackId, JsonObject trackInfo) {

	}

}
