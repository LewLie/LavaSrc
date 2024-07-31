package com.github.topi314.lavasrc.spotify.external;

import lombok.Getter;
import lombok.Setter;
import se.michaelthelin.spotify.model_objects.specification.Image;
import se.michaelthelin.spotify.model_objects.specification.Track;

import java.util.List;

@Getter
public class TrackWrapper {

	private final Track track;

	@Setter
	private List<Image> artistImages;

	public TrackWrapper(
		Track track,
		List<Image> artistImages
	) {
		this.track = track;
		this.artistImages = artistImages;
	}

}
