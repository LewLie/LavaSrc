package com.github.topi314.lavasrc.spotify;

import com.github.topi314.lavalyrics.AudioLyricsManager;
import com.github.topi314.lavalyrics.lyrics.AudioLyrics;
import com.github.topi314.lavalyrics.lyrics.BasicAudioLyrics;
import com.github.topi314.lavasearch.AudioSearchManager;
import com.github.topi314.lavasearch.result.AudioSearchResult;
import com.github.topi314.lavasearch.result.BasicAudioSearchResult;
import com.github.topi314.lavasrc.ExtendedAudioPlaylist;
import com.github.topi314.lavasrc.LavaSrcTools;
import com.github.topi314.lavasrc.mirror.DefaultMirroringAudioTrackResolver;
import com.github.topi314.lavasrc.mirror.MirroringAudioSourceManager;
import com.github.topi314.lavasrc.mirror.MirroringAudioTrackResolver;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.sedmelluq.discord.lavaplayer.player.AudioPlayerManager;
import com.sedmelluq.discord.lavaplayer.tools.JsonBrowser;
import com.sedmelluq.discord.lavaplayer.tools.io.HttpClientTools;
import com.sedmelluq.discord.lavaplayer.tools.io.HttpConfigurable;
import com.sedmelluq.discord.lavaplayer.tools.io.HttpInterfaceManager;
import com.sedmelluq.discord.lavaplayer.track.*;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpHead;
import org.apache.http.impl.client.HttpClientBuilder;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.DataInput;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public class SpotifySourceManager extends MirroringAudioSourceManager implements HttpConfigurable, AudioSearchManager, AudioLyricsManager {

	public static final Pattern URL_PATTERN = Pattern.compile("(https?://)(www\\.)?open\\.spotify\\.com/((?<region>[a-zA-Z-]+)/)?(user/(?<user>[a-zA-Z0-9-_]+)/)?(?<type>track|album|playlist|artist)/(?<identifier>[a-zA-Z0-9-_]+)");
	public static final String SEARCH_PREFIX = "spsearch:";
	public static final String RECOMMENDATIONS_PREFIX = "sprec:";
	public static final String PREVIEW_PREFIX = "spprev:";
	public static final long PREVIEW_LENGTH = 30000;
	public static final String SHARE_URL = "https://spotify.link/";
	public static final int PLAYLIST_MAX_PAGE_ITEMS = 100;
	public static final int ALBUM_MAX_PAGE_ITEMS = 50;
	public static final String API_BASE = "https://api.spotify.com/v1/";
	public static final String CLIENT_API_BASE = "https://spclient.wg.spotify.com/";
	public static final Set<AudioSearchResult.Type> SEARCH_TYPES = Set.of(AudioSearchResult.Type.ALBUM, AudioSearchResult.Type.ARTIST, AudioSearchResult.Type.PLAYLIST, AudioSearchResult.Type.TRACK);
	private static final Logger log = LoggerFactory.getLogger(SpotifySourceManager.class);

	private final HttpInterfaceManager httpInterfaceManager = HttpClientTools.createDefaultThreadLocalManager();
	private final SpotifyTokenTracker tokenTracker;
	private final String spDc;
	private final String countryCode;
	private int playlistPageLimit = 6;
	private int albumPageLimit = 6;
	private boolean localFiles;

	private String spToken;
	private Instant spTokenExpire;

	public SpotifySourceManager(String[] providers, String clientId, String clientSecret, String countryCode, AudioPlayerManager audioPlayerManager) {
		this(clientId, clientSecret, null, countryCode, unused -> audioPlayerManager, new DefaultMirroringAudioTrackResolver(providers));
	}

	public SpotifySourceManager(String[] providers, String clientId, String clientSecret, String countryCode, Function<Void, AudioPlayerManager> audioPlayerManager) {
		this(clientId, clientSecret, null, countryCode, audioPlayerManager, new DefaultMirroringAudioTrackResolver(providers));
	}

	public SpotifySourceManager(String clientId, String clientSecret, String countryCode, AudioPlayerManager audioPlayerManager, MirroringAudioTrackResolver mirroringAudioTrackResolver) {
		this(clientId, clientSecret, null, countryCode, unused -> audioPlayerManager, mirroringAudioTrackResolver);
	}

	public SpotifySourceManager(String clientId, String clientSecret, String countryCode, Function<Void, AudioPlayerManager> audioPlayerManager, MirroringAudioTrackResolver mirroringAudioTrackResolver) {
		this(clientId, clientSecret, null, countryCode, audioPlayerManager, mirroringAudioTrackResolver);
	}

	public SpotifySourceManager(String clientId, String clientSecret, String spDc, String countryCode, Function<Void, AudioPlayerManager> audioPlayerManager, MirroringAudioTrackResolver mirroringAudioTrackResolver) {
		super(audioPlayerManager, mirroringAudioTrackResolver);

		this.tokenTracker = new SpotifyTokenTracker(this, clientId, clientSecret);
		this.spDc = spDc;

		if (countryCode == null || countryCode.isEmpty()) {
			countryCode = "US";
		}
		this.countryCode = countryCode;
	}

	public void setPlaylistPageLimit(int playlistPageLimit) {
		this.playlistPageLimit = playlistPageLimit;
	}

	public void setAlbumPageLimit(int albumPageLimit) {
		this.albumPageLimit = albumPageLimit;
	}

	public void setLocalFiles(boolean localFiles) {
		this.localFiles = localFiles;
	}

	@NotNull
	@Override
	public String getSourceName() {
		return "spotify";
	}

	@Override
	@Nullable
	public AudioLyrics loadLyrics(@NotNull AudioTrack audioTrack) {
		var spotifyTackId = "";
		if (audioTrack instanceof SpotifyAudioTrack) {
			spotifyTackId = audioTrack.getIdentifier();
		}

		if (spotifyTackId.isEmpty()) {
			AudioItem item = AudioReference.NO_TRACK;
			try {
				if (audioTrack.getInfo().isrc != null && !audioTrack.getInfo().isrc.isEmpty()) {
					item = this.getSearch("isrc:" + audioTrack.getInfo().isrc, false);
				}
				if (item == AudioReference.NO_TRACK) {
					item = this.getSearch(String.format("%s %s", audioTrack.getInfo().title, audioTrack.getInfo().author), false);
				}
			} catch (IOException e) {
				throw new RuntimeException(e);
			}

			if (item == AudioReference.NO_TRACK) {
				return null;
			}
			if (item instanceof AudioTrack) {
				spotifyTackId = ((AudioTrack) item).getIdentifier();
			} else if (item instanceof AudioPlaylist) {
				var playlist = (AudioPlaylist) item;
				if (!playlist.getTracks().isEmpty()) {
					spotifyTackId = playlist.getTracks().get(0).getIdentifier();
				}
			}
		}

		try {
			return this.getLyrics(spotifyTackId);
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
	}

	public AudioLyrics getLyrics(String id) throws IOException {
		if (this.spDc == null || this.spDc.isEmpty()) {
			throw new IllegalArgumentException("Spotify spDc must be set");
		}

		var request = new HttpGet(CLIENT_API_BASE + "color-lyrics/v2/track/" + id + "?format=json&vocalRemoval=false");
		request.addHeader("App-Platform", "WebPlayer");
		request.addHeader("Authorization", "Bearer " + this.getSpToken());
		var json = LavaSrcTools.fetchResponseAsJson(this.httpInterfaceManager.getInterface(), request);

		if (json == null) {
			return null;
		}

		var lyrics = new ArrayList<AudioLyrics.Line>();
		for (var line : json.get("lyrics").getAsJsonObject().get("lines").getAsJsonArray()) {
			var lineJson = line.getAsJsonObject();

			lyrics.add(new BasicAudioLyrics.BasicLine(
				Duration.ofMillis(lineJson.get("startTimeMs").getAsLong()),
				null,
				lineJson.get("words").getAsString()
			));
		}

		return new BasicAudioLyrics("spotify", "MusixMatch", null, lyrics);
	}

	@Override
	public AudioTrack decodeTrack(AudioTrackInfo trackInfo, DataInput input) throws IOException {
		var extendedAudioTrackInfo = super.decodeTrack(input);
		return new SpotifyAudioTrack(trackInfo,
			extendedAudioTrackInfo.albumName,
			extendedAudioTrackInfo.albumUrl,
			extendedAudioTrackInfo.artistUrl,
			extendedAudioTrackInfo.artistArtworkUrl,
			extendedAudioTrackInfo.previewUrl,
			extendedAudioTrackInfo.isPreview,
			this
		);
	}

	@Override
	@Nullable
	public AudioSearchResult loadSearch(@NotNull String query, @NotNull Set<AudioSearchResult.Type> types) {
		try {
			if (query.startsWith(SEARCH_PREFIX)) {
				return this.getAutocomplete(query.substring(SEARCH_PREFIX.length()), types);
			}
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
		return null;
	}

	@Override
	public AudioItem loadItem(AudioPlayerManager manager, AudioReference reference) {
		var identifier = reference.identifier;
		var preview = reference.identifier.startsWith(PREVIEW_PREFIX);
		return this.loadItem(preview ? identifier.substring(PREVIEW_PREFIX.length()) : identifier, preview);
	}

	public AudioItem loadItem(String identifier, boolean preview) {
		try {
			if (identifier.startsWith(SEARCH_PREFIX)) {
				return this.getSearch(identifier.substring(SEARCH_PREFIX.length()).trim(), preview);
			}

			if (identifier.startsWith(RECOMMENDATIONS_PREFIX)) {
				return this.getRecommendations(identifier.substring(RECOMMENDATIONS_PREFIX.length()).trim(), preview);
			}

			// If the identifier is a share URL, we need to follow the redirect to find out the real url behind it
			if (identifier.startsWith(SHARE_URL)) {
				var request = new HttpHead(identifier);
				request.setConfig(RequestConfig.custom().setRedirectsEnabled(false).build());
				try (var response = this.httpInterfaceManager.getInterface().execute(request)) {
					if (response.getStatusLine().getStatusCode() == 307) {
						var location = response.getFirstHeader("Location").getValue();
						if (location.startsWith("https://open.spotify.com/")) {
							return this.loadItem(location, preview);
						}
					}
					return null;
				}
			}

			var matcher = URL_PATTERN.matcher(identifier);
			if (!matcher.find()) {
				return null;
			}

			var id = matcher.group("identifier");
			switch (matcher.group("type")) {
				case "album":
					return this.getAlbum(id, preview);

				case "track":
					return this.getTrack(id, preview);

				case "playlist":
					return this.getPlaylist(id, preview);

				case "artist":
					return this.getArtist(id, preview);
			}
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
		return null;
	}

	public void requestSpToken() throws IOException {
		var request = new HttpGet("https://open.spotify.com/get_access_token?reason=transport&productType=web_player");
		request.addHeader("App-Platform", "WebPlayer");
		request.addHeader("Cookie", "sp_dc=" + this.spDc);

		var json = LavaSrcTools.fetchResponseAsJson(this.httpInterfaceManager.getInterface(), request);

		if(json == null) return;

		this.spToken = json.get("accessToken").getAsString();
		this.spTokenExpire = Instant.ofEpochMilli(json.get("accessTokenExpirationTimestampMs").getAsLong());
	}

	public String getSpToken() throws IOException {
		if (this.spToken == null || this.spTokenExpire == null || this.spTokenExpire.isBefore(Instant.now())) {
			this.requestSpToken();
		}
		return this.spToken;
	}

	@Nullable
	public JsonObject getJson(String uri) throws IOException {
		var request = new HttpGet(uri);
		request.addHeader("Authorization", "Bearer " + this.tokenTracker.getAccessToken());
		return LavaSrcTools.fetchResponseAsJson(this.httpInterfaceManager.getInterface(), request);
	}

	private AudioSearchResult getAutocomplete(String query, Set<AudioSearchResult.Type> types) throws IOException {
		if (types.isEmpty()) {
			types = SEARCH_TYPES;
		}
		var url = API_BASE + "search?q=" + URLEncoder.encode(query, StandardCharsets.UTF_8) + "&type=" + types.stream().map(AudioSearchResult.Type::getName).collect(Collectors.joining(","));
		var json = this.getJson(url);

		if (json == null) {
			return AudioSearchResult.EMPTY;
		}

		var albums = new ArrayList<AudioPlaylist>();
		for (var album : json.get("albums").getAsJsonObject().get("items").getAsJsonArray()) {
			var albumJson = album.getAsJsonObject();

			albums.add(new SpotifyAudioPlaylist(
				albumJson.get("name").getAsString(),
				Collections.emptyList(),
				ExtendedAudioPlaylist.Type.ALBUM,
				albumJson.get("external_urls").getAsJsonObject().get("spotify").getAsString(),
				albumJson.get("images").getAsJsonArray().get(0).getAsJsonObject().get("url").getAsString(),
				albumJson.get("artists").getAsJsonArray().get(0).getAsJsonObject().get("name").getAsString(),
				albumJson.get("total_tracks").getAsInt()
			));
		}

		var artists = new ArrayList<AudioPlaylist>();
		for (var artist : json.get("artists").getAsJsonObject().get("items").getAsJsonArray()) {
			var artistJson = artist.getAsJsonObject();

			artists.add(new SpotifyAudioPlaylist(
				artistJson.get("name").getAsString() + "'s Top Tracks",
				Collections.emptyList(),
				ExtendedAudioPlaylist.Type.ARTIST,
				artistJson.get("external_urls").getAsJsonObject().get("spotify").getAsString(),
				artistJson.get("images").getAsJsonArray().get(0).getAsJsonObject().get("url").getAsString(),
				artistJson.get("name").getAsString(),
				null
			));
		}

		var playlists = new ArrayList<AudioPlaylist>();
		for (var playlist : json.get("playlists").getAsJsonObject().get("items").getAsJsonArray()) {
			var playlistJson = playlist.getAsJsonObject();

			playlists.add(new SpotifyAudioPlaylist(
				playlistJson.get("name").getAsString(),
				Collections.emptyList(),
				ExtendedAudioPlaylist.Type.PLAYLIST,
				playlistJson.get("external_urls").getAsJsonObject().get("spotify").getAsString(),
				playlistJson.get("images").getAsJsonArray().get(0).getAsJsonObject().get("url").getAsString(),
				playlistJson.get("owner").getAsJsonObject().get("display_name").getAsString(),
				(int) playlistJson.get("tracks").getAsJsonObject().get("total").getAsLong()
			));
		}

		var tracks = this.parseTrackItems(json.get("tracks").getAsJsonObject(), false);

		return new BasicAudioSearchResult(tracks, albums, artists, playlists, new ArrayList<>());
	}

	public AudioItem getSearch(String query, boolean preview) throws IOException {
		var json = this.getJson(API_BASE + "search?q=" + URLEncoder.encode(query, StandardCharsets.UTF_8) + "&type=track");

		if (json == null || json.getAsJsonObject().get("tracks").getAsJsonObject().get("items").getAsJsonArray().isEmpty()) {
			return AudioReference.NO_TRACK;
		}

		var artistIds = json.get("tracks").getAsJsonObject().get("items").getAsJsonArray().asList().stream().map(track -> track.getAsJsonObject().get("artists").getAsJsonArray().get(0).getAsJsonObject().get("id").getAsString()).collect(Collectors.joining(","));
		var artistJson = this.getJson(API_BASE + "artists?ids=" + artistIds);

		if (artistJson != null) {
			for (var artist : artistJson.get("artists").getAsJsonArray()) {
				var artistJsonObject = artist.getAsJsonObject();

				for (var track : json.get("tracks").getAsJsonObject().get("items").getAsJsonArray()) {
					var trackJsonObject = track.getAsJsonObject();

					if (trackJsonObject.get("artists").getAsJsonArray().get(0).getAsJsonObject().get("id").getAsString().equals(artistJsonObject.get("id").getAsString())) {
						trackJsonObject.get("artists").getAsJsonArray().get(0).getAsJsonObject().add("images", artistJsonObject.get("images"));
					}
				}
			}
		}

		return new BasicAudioPlaylist("Search results for: " + query, this.parseTrackItems(json.get("tracks").getAsJsonObject(), preview), null, true);
	}

	public AudioItem getRecommendations(String query, boolean preview) throws IOException {
		var json = this.getJson(API_BASE + "recommendations?" + query);
		if (json == null || json.get("tracks").getAsJsonArray().isEmpty()) {
			return AudioReference.NO_TRACK;
		}

		return new SpotifyAudioPlaylist("Spotify Recommendations:", this.parseTracks(json, preview), ExtendedAudioPlaylist.Type.RECOMMENDATIONS, null, null, null, null);
	}

	public AudioItem getAlbum(String id, boolean preview) throws IOException {
		var json = this.getJson(API_BASE + "albums/" + id);
		if (json == null) {
			return AudioReference.NO_TRACK;
		}

		var artistJson = this.getJson(API_BASE + "artists/" + json.get("artists").getAsJsonArray().get(0).getAsJsonObject().get("id").getAsString());
		if (artistJson == null) {
			artistJson = new JsonObject();
		}

		var tracks = new ArrayList<AudioTrack>();
		JsonObject page;
		var offset = 0;
		var pages = 0;
		do {
			page = this.getJson(API_BASE + "albums/" + id + "/tracks?limit=" + ALBUM_MAX_PAGE_ITEMS + "&offset=" + offset);
			offset += ALBUM_MAX_PAGE_ITEMS;

			var tracksPage = this.getJson(API_BASE + "tracks/?ids=" + Objects.requireNonNull(page).get("items").getAsJsonArray().asList().stream().map(track -> track.getAsJsonObject().get("id").getAsString()).collect(Collectors.joining(",")));

			for (var track : Objects.requireNonNull(tracksPage).get("tracks").getAsJsonArray()) {
				var trackJsonObject = track.getAsJsonObject();
				var albumJson = new JsonObject();
				albumJson.add("external_urls", json.get("external_urls"));
				albumJson.add("name", json.get("name"));
				albumJson.add("images", json.get("images"));
				trackJsonObject.add("album", albumJson);

				trackJsonObject.get("artists").getAsJsonArray().get(0).getAsJsonObject().add("images", artistJson.get("images"));
			}

			tracks.addAll(this.parseTracks(tracksPage, preview));
		}
		while (page.get("next").getAsString() != null && ++pages < this.albumPageLimit);

		if (tracks.isEmpty()) {
			return AudioReference.NO_TRACK;
		}

		return new SpotifyAudioPlaylist(json.get("name").getAsString(), tracks, ExtendedAudioPlaylist.Type.ALBUM, json.get("external_urls").getAsJsonObject().get("spotify").getAsString(), json.get("images").getAsJsonArray().get(0).getAsJsonObject().get("url").getAsString(), json.get("artists").getAsJsonArray().get(0).getAsJsonObject().get("name").getAsString(), json.get("total_tracks").getAsInt());

	}

	public AudioItem getPlaylist(String id, boolean preview) throws IOException {
		var json = this.getJson(API_BASE + "playlists/" + id);
		if (json == null) {
			return AudioReference.NO_TRACK;
		}

		var tracks = new ArrayList<AudioTrack>();
		JsonObject page;
		var offset = 0;
		var pages = 0;
		do {
			page = this.getJson(API_BASE + "playlists/" + id + "/tracks?limit=" + PLAYLIST_MAX_PAGE_ITEMS + "&offset=" + offset);
			offset += PLAYLIST_MAX_PAGE_ITEMS;

			for (var value : Objects.requireNonNull(page).get("items").getAsJsonArray()) {
				var valueJsonObject = value.getAsJsonObject();
				var track = valueJsonObject.get("track");
				if (track.isJsonNull() || track.getAsJsonObject().get("type").getAsString().equals("episode") || (!this.localFiles && track.getAsJsonObject().get("is_local").getAsBoolean())) {
					continue;
				}

				tracks.add(this.parseTrack(track.getAsJsonObject(), preview));
			}

		}
		while (page.get("next").getAsString() != null && ++pages < this.playlistPageLimit);

		if (tracks.isEmpty()) {
			return AudioReference.NO_TRACK;
		}

		return new SpotifyAudioPlaylist(json.get("name").getAsString(), tracks, ExtendedAudioPlaylist.Type.PLAYLIST, json.get("external_urls").getAsJsonObject().get("spotify").getAsString(), json.get("images").getAsJsonArray().get(0).getAsJsonObject().get("url").getAsString(), json.get("owner").getAsJsonObject().get("display_name").getAsString(), json.get("tracks").getAsJsonObject().get("total").getAsInt());
	}

	public AudioItem getArtist(String id, boolean preview) throws IOException {
		var json = this.getJson(API_BASE + "artists/" + id);
		if (json == null) {
			return AudioReference.NO_TRACK;
		}

		var tracksJson = this.getJson(API_BASE + "artists/" + id + "/top-tracks?market=" + this.countryCode);
		if (tracksJson == null || tracksJson.get("tracks").getAsJsonArray().isEmpty()) {
			return AudioReference.NO_TRACK;
		}

		for (var track : tracksJson.get("tracks").getAsJsonArray()) {
			track.getAsJsonObject().get("artists").getAsJsonArray().get(0).getAsJsonObject().add("images", json.get("images"));
		}

		return new SpotifyAudioPlaylist(json.get("name").getAsString() + "'s Top Tracks", this.parseTracks(tracksJson, preview), ExtendedAudioPlaylist.Type.ARTIST, json.get("external_urls").getAsJsonObject().get("spotify").getAsString(), json.get("images").getAsJsonArray().get(0).getAsJsonObject().get("url").getAsString(), json.get("name").getAsString(), tracksJson.get("tracks").getAsJsonObject().get("total").getAsInt());
	}

	public AudioItem getTrack(String id, boolean preview) throws IOException {
		var json = this.getJson(API_BASE + "tracks/" + id);

		if (json == null) {
			return AudioReference.NO_TRACK;
		}

		var artistJson = this.getJson(API_BASE + "artists/" + json.get("artists").getAsJsonArray().get(0).getAsJsonObject().get("id").getAsString());

		if (artistJson != null) {
			json.get("artists").getAsJsonArray().get(0).getAsJsonObject().add("images", artistJson.getAsJsonObject().get("images"));
		}

		return this.parseTrack(json, preview);
	}

	private List<AudioTrack> parseTracks(JsonObject json, boolean preview) {
		var tracks = new ArrayList<AudioTrack>();

		for (var value : json.get("tracks").getAsJsonArray()) {
			var valueJson = value.getAsJsonObject();
			tracks.add(this.parseTrack(valueJson, preview));
		}

		return tracks;
	}

	private List<AudioTrack> parseTrackItems(JsonObject json, boolean preview) {
		var tracks = new ArrayList<AudioTrack>();
		for (var value : json.get("items").getAsJsonArray()) {
			var valueJson = value.getAsJsonObject();

			if (valueJson.get("is_local").getAsBoolean()) {
				continue;
			}

			tracks.add(this.parseTrack(valueJson, preview));
		}
		return tracks;
	}

	private AudioTrack parseTrack(JsonObject json, boolean preview) {
		return new SpotifyAudioTrack(
			new AudioTrackInfo(
				json.get("name").getAsString(),
				json.get("artists").getAsJsonArray().get(0).getAsJsonObject().get("name").getAsString().isEmpty() ? "Unknown" : json.get("artists").getAsJsonArray().get(0).getAsJsonObject().get("name").getAsString(),
				preview ? PREVIEW_LENGTH : json.get("duration_ms").getAsInt(),
				json.get("id").getAsString() != null ? json.get("id").getAsString() : "local",
				false,
				json.get("external_urls").getAsJsonObject().get("spotify").getAsString(),
				json.get("album").getAsJsonObject().get("images").getAsJsonArray().get(0).getAsJsonObject().get("url").getAsString(),
				json.get("external_ids").getAsJsonObject().get("isrc").getAsString()
			),
			json.get("album").getAsJsonObject().get("name").getAsString(),
			json.get("album").getAsJsonObject().get("external_urls").getAsJsonObject().get("spotify").getAsString(),
			json.get("artists").getAsJsonArray().get(0).getAsJsonObject().get("external_urls").getAsJsonObject().get("spotify").getAsString(),
			json.get("artists").getAsJsonArray().get(0).getAsJsonObject().get("images").getAsJsonArray().get(0).getAsJsonObject().get("url").getAsString(),
			json.get("preview_url").getAsString(),
			preview,
			this
		);
	}

	@Override
	public void shutdown() {
		try {
			this.httpInterfaceManager.close();
		} catch (IOException e) {
			log.error("Failed to close HTTP interface manager", e);
		}
	}

	@Override
	public void configureRequests(Function<RequestConfig, RequestConfig> configurator) {
		this.httpInterfaceManager.configureRequests(configurator);
	}

	@Override
	public void configureBuilder(Consumer<HttpClientBuilder> configurator) {
		this.httpInterfaceManager.configureBuilder(configurator);
	}

}
