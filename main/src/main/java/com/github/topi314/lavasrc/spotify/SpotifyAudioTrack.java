package com.github.topi314.lavasrc.spotify;

import com.github.topi314.lavasrc.mirror.MirroringAudioSourceManager;
import com.github.topi314.lavasrc.mirror.MirroringAudioTrack;
import com.sedmelluq.discord.lavaplayer.container.mp3.Mp3AudioTrack;
import com.sedmelluq.discord.lavaplayer.tools.io.SeekableInputStream;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import com.sedmelluq.discord.lavaplayer.track.AudioTrackInfo;
import com.sedmelluq.discord.lavaplayer.track.InternalAudioTrack;
import se.michaelthelin.spotify.model_objects.specification.Track;

public class SpotifyAudioTrack extends MirroringAudioTrack {

	private Track metadata;

	public SpotifyAudioTrack(AudioTrackInfo trackInfo, Track metadata, SpotifySourceManager sourceManager) {
		this(trackInfo, metadata, null, null, null, null, null, false, sourceManager);
	}

	public SpotifyAudioTrack(AudioTrackInfo trackInfo, Track metadata, String albumName, String albumUrl, String artistUrl, String artistArtworkUrl, String previewUrl, boolean isPreview, MirroringAudioSourceManager sourceManager) {
		super(trackInfo, albumName, albumUrl, artistUrl, artistArtworkUrl, previewUrl, isPreview, sourceManager);
		this.metadata = metadata;
	}

	@Override
	protected InternalAudioTrack createAudioTrack(AudioTrackInfo trackInfo, SeekableInputStream stream) {
		return new Mp3AudioTrack(trackInfo, stream);
	}

	@Override
	protected AudioTrack makeShallowClone() {
		return new SpotifyAudioTrack(this.trackInfo, this.metadata, (SpotifySourceManager) this.sourceManager);
	}

	public boolean isLocal() {
        return this.trackInfo.identifier.equals("local");
    }

	public Track getMetadata() {
		return metadata;
	}
}
