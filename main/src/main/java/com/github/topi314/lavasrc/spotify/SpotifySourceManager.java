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
import com.github.topi314.lavasrc.spotify.external.SearchItemRequestSpecial;
import com.github.topi314.lavasrc.spotify.external.SearchResultSpecial;
import com.github.topi314.lavasrc.spotify.external.TrackWrapper;
import com.neovisionaries.i18n.CountryCode;
import com.sedmelluq.discord.lavaplayer.player.AudioPlayerManager;
import com.sedmelluq.discord.lavaplayer.tools.io.HttpClientTools;
import com.sedmelluq.discord.lavaplayer.tools.io.HttpConfigurable;
import com.sedmelluq.discord.lavaplayer.tools.io.HttpInterfaceManager;
import com.sedmelluq.discord.lavaplayer.track.*;
import lombok.Setter;
import org.apache.hc.core5.http.ParseException;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpHead;
import org.apache.http.impl.client.HttpClientBuilder;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import se.michaelthelin.spotify.enums.ModelObjectType;
import se.michaelthelin.spotify.exceptions.SpotifyWebApiException;
import se.michaelthelin.spotify.model_objects.specification.*;

import java.io.DataInput;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@SuppressWarnings("unused")
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
	private final SpotifyApiAccessor spotifyApiAccessor;
	private final String spDc;
	private final String countryCode;

	@Setter
	private int playlistPageLimit = 6;
	@Setter
	private int albumPageLimit = 6;

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

		this.spotifyApiAccessor = new SpotifyApiAccessor(clientId, clientSecret);
		this.spDc = spDc;

		if (countryCode == null || countryCode.isEmpty()) {
			countryCode = "US";
		}
		this.countryCode = countryCode;
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
			} catch (IOException | ParseException e) {
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
		for (var line : json.get("lyrics").get("lines").values()) {
			lyrics.add(new BasicAudioLyrics.BasicLine(
				Duration.ofMillis(line.get("startTimeMs").asLong(0)),
				null,
				line.get("words").text()
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
		} catch (IOException | ParseException | SpotifyWebApiException e) {
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
		} catch (IOException | ParseException e) {
			throw new RuntimeException(e);
		}
		return null;
	}

	public void requestSpToken() throws IOException {
		var request = new HttpGet("https://open.spotify.com/get_access_token?reason=transport&productType=web_player");
		request.addHeader("App-Platform", "WebPlayer");
		request.addHeader("Cookie", "sp_dc=" + this.spDc);

		var json = LavaSrcTools.fetchResponseAsJson(this.httpInterfaceManager.getInterface(), request);
		this.spToken = json.get("accessToken").text();
		this.spTokenExpire = Instant.ofEpochMilli(json.get("accessTokenExpirationTimestampMs").asLong(0));
	}

	public String getSpToken() throws IOException {
		if (this.spToken == null || this.spTokenExpire == null || this.spTokenExpire.isBefore(Instant.now())) {
			this.requestSpToken();
		}
		return this.spToken;
	}

	private AudioSearchResult getAutocomplete(String query, Set<AudioSearchResult.Type> types) throws IOException, ParseException, SpotifyWebApiException {
		if (types.isEmpty()) {
			types = SEARCH_TYPES;
		}

		var api = spotifyApiAccessor.getSpotifyApi();

		// Custom handle for Albums
		var requestBuilder = new SearchItemRequestSpecial.Builder(api.getAccessToken())
			.setDefaults(api.getHttpManager(), api.getScheme(), api.getHost(), api.getPort())
			.q(URLEncoder.encode(query, StandardCharsets.UTF_8))
			.type(types.stream().map(AudioSearchResult.Type::getName).collect(Collectors.joining(",")))
			.build();

		SearchResultSpecial result;

		try {
			result = requestBuilder.execute();
		} catch (SpotifyWebApiException ex) {
			return AudioSearchResult.EMPTY;
		}

		var albums = new ArrayList<AudioPlaylist>();

		for (var album : result.getAlbums().getItems()) {
			albums.add(new SpotifyAudioPlaylist(
				album.getName(),
				Collections.emptyList(),
				ExtendedAudioPlaylist.Type.ALBUM,
				album.getExternalUrls().get("spotify"),
				album.getImages()[0].getUrl(),
				album.getArtists()[0].getName(),
				album.getTotalTracks()
			));
		}

		var artists = new ArrayList<AudioPlaylist>();

		for (var artist : result.getArtists().getItems()) {
			artists.add(new SpotifyAudioPlaylist(
				artist.getName() + "'s Top Tracks",
				Collections.emptyList(),
				ExtendedAudioPlaylist.Type.ARTIST,
				artist.getExternalUrls().get("spotify"),
				artist.getImages()[0].getUrl(),
				artist.getName(),
				null
			));
		}

		var playlists = new ArrayList<AudioPlaylist>();
		for (var playlist : result.getPlaylists().getItems()) {
			playlists.add(new SpotifyAudioPlaylist(
				playlist.getName(),
				Collections.emptyList(),
				ExtendedAudioPlaylist.Type.PLAYLIST,
				playlist.getExternalUrls().get("spotify"),
				playlist.getImages()[0].getUrl(),
				playlist.getOwner().getDisplayName(),
				playlist.getTracks().getTotal()
			));
		}

		var tracks = this.parseTrackItems(
			Arrays.stream(result.getTracks().getItems()).map(e -> new TrackWrapper(e, new ArrayList<>())).collect(Collectors.toList()),
			false
		);
		return new BasicAudioSearchResult(tracks, albums, artists, playlists, new ArrayList<>());
	}

	public AudioItem getSearch(String query, boolean preview) throws IOException, ParseException {
		var tracksRequest = spotifyApiAccessor
			.getSpotifyApi()
			.searchTracks(URLEncoder.encode(query, StandardCharsets.UTF_8))
			.build();

		Paging<Track> tracksResult = null;

		try {
			tracksResult = tracksRequest.execute();
		} catch (SpotifyWebApiException ignored) {
		}

		if (tracksResult == null || tracksResult.getItems().length == 0) {
			return AudioReference.NO_TRACK;
		}

		var artistIds = Arrays.stream(tracksResult.getItems()).map(track -> track.getArtists()[0].getId()).toArray(String[]::new);

		var artistRequest = spotifyApiAccessor
			.getSpotifyApi()
			.getSeveralArtists(artistIds)
			.build();

		Artist[] artistResult = null;

		try {
			artistResult = artistRequest.execute();
		} catch (SpotifyWebApiException ignored) {
		}

		var tracksWrappers = Arrays.stream(tracksResult.getItems()).map(e -> new TrackWrapper(e, new ArrayList<>())).collect(Collectors.toList());

		if (artistResult != null) {
			for (var artist : artistResult) {
				for (var trackWrapper : tracksWrappers) {
					if (trackWrapper.getTrack().getArtists()[0].getId().equals(artist.getId())) {
						trackWrapper.setArtistImages(Arrays.asList(artist.getImages()));
					}
				}
			}
		}

		return new BasicAudioPlaylist("Search results for: " + query, this.parseTrackItems(tracksWrappers, preview), null, true);
	}

	public AudioItem getRecommendations(String query, boolean preview) throws IOException, ParseException {
		var queryParts = query.split("&");

		var requestBuilder = spotifyApiAccessor.getSpotifyApi()
			.getRecommendations();

		for(String queryPart : queryParts) {
			var parts = queryPart.split("=");
			requestBuilder.setQueryParameter(parts[0], parts[1]);
		}

		Recommendations result = null;

		try {
			result = requestBuilder.build().execute();
		} catch (SpotifyWebApiException ignore) {}

		if (result == null || result.getTracks().length == 0) {
			return AudioReference.NO_TRACK;
		}

		return new SpotifyAudioPlaylist(
			"Spotify Recommendations:",
			this.parseTrackItems(Arrays.stream(result.getTracks()).map(e -> new TrackWrapper(e, new ArrayList<>())).collect(Collectors.toList()), preview),
			ExtendedAudioPlaylist.Type.RECOMMENDATIONS,
			null,
			null,
			null,
			null
		);
	}

	public AudioItem getAlbum(String id, boolean preview) throws IOException, ParseException {
		var albumRequest = spotifyApiAccessor.getSpotifyApi()
			.getAlbum(id)
			.build();

		Album albumResult = null;

		try {
			albumResult = albumRequest.execute();
		} catch (SpotifyWebApiException ignore) {}

		if (albumResult == null) {
			return AudioReference.NO_TRACK;
		}

		var artistRequest = spotifyApiAccessor.getSpotifyApi()
			.getArtist(albumResult.getArtists()[0].getId())
			.build();

		Artist artistResult = null;

		try {
			artistResult = artistRequest.execute();
		} catch (SpotifyWebApiException ignore) {}

		var tracks = new ArrayList<AudioTrack>();
		Paging<TrackSimplified> albumTracksResult = null;
		var offset = 0;
		var pages = 0;
		do {
			var albumsTracksRequest = spotifyApiAccessor.getSpotifyApi()
				.getAlbumsTracks(id)
				.limit(ALBUM_MAX_PAGE_ITEMS)
				.offset(offset)
				.build();

			try {
				albumTracksResult = albumsTracksRequest.execute();
			} catch (SpotifyWebApiException ignore) {}

			if(albumTracksResult == null) break;

			offset += ALBUM_MAX_PAGE_ITEMS;

			var tracksRequest = spotifyApiAccessor.getSpotifyApi()
				.getSeveralTracks(
					Arrays.stream(albumTracksResult.getItems()).map(TrackSimplified::getId).toArray(String[]::new)
				)
				.build();

			Track[] trackResult = null;
			List<TrackWrapper> trackWrappers = new ArrayList<>();

			try {
				trackResult = tracksRequest.execute();
			} catch (SpotifyWebApiException ignore) {}

			if (trackResult == null) break;

			for (var track : trackResult) {
				var trackAlbumBuilder = track
					.getAlbum()
					.builder()
					.setAlbumGroup(track.getAlbum().getAlbumGroup())
					.setAlbumType(track.getAlbum().getAlbumType())
					.setArtists(track.getAlbum().getArtists())
					.setAvailableMarkets(track.getAlbum().getAvailableMarkets())
					.setExternalUrls(track.getAlbum().getExternalUrls())
					.setHref(track.getAlbum().getHref())
					.setId(track.getAlbum().getId())
					.setImages(track.getAlbum().getImages())
					.setName(track.getAlbum().getName())
					.setReleaseDate(track.getAlbum().getReleaseDate())
					.setReleaseDatePrecision(track.getAlbum().getReleaseDatePrecision())
					.setRestrictions(track.getAlbum().getRestrictions())
					.setType(track.getAlbum().getType())
					.setUri(track.getAlbum().getUri());

				Map<String, String> externalUrls = new HashMap<>();
				externalUrls.put("spotify", albumResult.getExternalUrls().get("spotify"));

				trackAlbumBuilder.setExternalUrls(
					new ExternalUrl.Builder().setExternalUrls(externalUrls).build()
				);

				trackAlbumBuilder.setName(albumResult.getName());
				trackAlbumBuilder.setImages(albumResult.getImages());

				// Modify album object.
				try {
					Class<Track> trackClass = Track.class;
					Field albumField = trackClass.getDeclaredField("album");
					albumField.setAccessible(true);

					Field modifiersField = Field.class.getDeclaredField("modifiers");
					modifiersField.setAccessible(true);
					modifiersField.setInt(albumField, albumField.getModifiers() & ~Modifier.FINAL);

					albumField.set(track, trackAlbumBuilder.build());
				} catch (Exception ex) {
					throw new RuntimeException(ex);
				}

				if (artistResult != null) {
					trackWrappers.add(new TrackWrapper(track, Arrays.asList(artistResult.getImages())));
				}
			}

			tracks.addAll(this.parseTrackItems(trackWrappers, preview));
		}
		while (albumTracksResult.getNext() != null && ++pages < this.albumPageLimit);

		if (tracks.isEmpty()) {
			return AudioReference.NO_TRACK;
		}

		return new SpotifyAudioPlaylist(
			albumResult.getName(),
			tracks,
			ExtendedAudioPlaylist.Type.ALBUM,
			albumResult.getExternalUrls().get("spotify"),
			albumResult.getImages().length == 0 ? null : albumResult.getImages()[0].getUrl(),
			albumResult.getArtists()[0].getName(),
			albumResult.getTracks().getTotal()
		);
	}

	public AudioItem getPlaylist(String id, boolean preview) throws IOException, ParseException {
		var playListRequest = spotifyApiAccessor.getSpotifyApi()
			.getPlaylist(id)
			.build();

		Playlist playList = null;

		try {
			playList = playListRequest.execute();
		} catch (SpotifyWebApiException ignore) {}

		if (playList == null) {
			return AudioReference.NO_TRACK;
		}

		var tracks = new ArrayList<AudioTrack>();
		Paging<PlaylistTrack> page = null;
		var offset = 0;
		var pages = 0;
		do {
			var playListTracksRequest = spotifyApiAccessor.getSpotifyApi()
				.getPlaylistsItems(id)
				.limit(PLAYLIST_MAX_PAGE_ITEMS)
				.offset(offset)
				.build();

			try {
				page = playListTracksRequest.execute();
			} catch (SpotifyWebApiException ignore) {}

			if (page == null) break;

			offset += PLAYLIST_MAX_PAGE_ITEMS;

			for (var value : page.getItems()) {
				var track = value.getTrack();
				if (track == null || track.getType() == ModelObjectType.EPISODE /* || (!this.localFiles && track.getAsJsonObject().get("is_local").getAsBoolean()) */) {
					continue;
				}

				tracks.add(this.parseTrack(new TrackWrapper((Track) track, new ArrayList<>()), preview));
			}

		}
		while (page.getNext() != null && ++pages < this.playlistPageLimit);

		if (tracks.isEmpty()) {
			return AudioReference.NO_TRACK;
		}

		return new SpotifyAudioPlaylist(
			playList.getName(),
			tracks,
			ExtendedAudioPlaylist.Type.PLAYLIST,
			playList.getExternalUrls().get("spotify"),
			playList.getImages()[0].getUrl(),
			playList.getOwner().getDisplayName(),
			playList.getTracks().getTotal()
		);
	}

	public AudioItem getArtist(String id, boolean preview) throws IOException, ParseException {
		var artistRequest = spotifyApiAccessor.getSpotifyApi()
			.getArtist(id)
			.build();

		Artist artist = null;

		try {
			artist = artistRequest.execute();
		} catch (SpotifyWebApiException ignore) {}

		if (artist == null) {
			return AudioReference.NO_TRACK;
		}

		var topTracksRequest = spotifyApiAccessor.getSpotifyApi()
			.getArtistsTopTracks(id, CountryCode.valueOf(this.countryCode))
			.build();

		Track[] tracks = null;

		try {
			tracks = topTracksRequest.execute();
		} catch (SpotifyWebApiException ignore) {}

		if (tracks == null || tracks.length == 0) {
			return AudioReference.NO_TRACK;
		}

		List<TrackWrapper> trackWrappers = new ArrayList<>();

		for (var track : tracks) {
			trackWrappers.add(new TrackWrapper(track, Arrays.asList(artist.getImages())));
		}

		return new SpotifyAudioPlaylist(
			artist.getName() + "'s Top Tracks",
			this.parseTrackItems(trackWrappers, preview),
			ExtendedAudioPlaylist.Type.ARTIST,
			artist.getExternalUrls().get("spotify"),
			artist.getImages()[0].getUrl(),
			artist.getName(),
			tracks.length
		);
	}

	public AudioItem getTrack(String id, boolean preview) throws IOException, ParseException {
		var trackRequest = spotifyApiAccessor.getSpotifyApi()
			.getTrack(id)
			.build();

		Track track = null;

		try {
			track = trackRequest.execute();
		} catch (SpotifyWebApiException ignore) {}

		if (track == null) {
			return AudioReference.NO_TRACK;
		}

		var artistRequest = spotifyApiAccessor.getSpotifyApi()
			.getArtist(track.getArtists()[0].getId())
			.build();

		Artist artist = null;

		try {
			artist = artistRequest.execute();
		} catch (SpotifyWebApiException ignore) {}

		if (artist != null) {
			return this.parseTrack(new TrackWrapper(track, Arrays.asList(artist.getImages())), preview);
		} else {
			return this.parseTrack(new TrackWrapper(track, new ArrayList<>()), preview);
		}

	}

	private List<AudioTrack> parseTrackItems(List<TrackWrapper> tracks, boolean preview) {
		var tracksResult = new ArrayList<AudioTrack>();

		for (var track : tracks) {
			/*
			TODO: How handle this?
			if (valueJson.get("is_local").getAsBoolean()) {
				continue;
			}
			*/

			tracksResult.add(this.parseTrack(track, preview));
		}

		return tracksResult;
	}

	private AudioTrack parseTrack(TrackWrapper trackWrapper, boolean preview) {
		Track track = trackWrapper.getTrack();

		return new SpotifyAudioTrack(
			new AudioTrackInfo(
				track.getName(),
				track.getArtists()[0].getName().isEmpty() ? "Unknown" : track.getArtists()[0].getName(),
				preview ? PREVIEW_LENGTH : track.getDurationMs(),
				track.getId() != null ? track.getId() : "local",
				false,
				track.getExternalUrls().get("spotify"),
				track.getAlbum().getImages()[0].getUrl(),
				track.getExternalIds().getExternalIds().get("isrc")
			),
			track.getAlbum().getName(),
			track.getAlbum().getExternalUrls().get("spotify"),
			track.getArtists()[0].getExternalUrls().get("spotify"),
			trackWrapper.getArtistImages().isEmpty() ? null : trackWrapper.getArtistImages().get(0).getUrl(),
			track.getPreviewUrl(),
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
