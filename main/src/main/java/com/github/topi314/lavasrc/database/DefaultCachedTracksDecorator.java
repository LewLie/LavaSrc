package com.github.topi314.lavasrc.database;

import com.github.benmanes.caffeine.cache.AsyncCache;
import com.github.benmanes.caffeine.cache.Caffeine;
import se.michaelthelin.spotify.model_objects.specification.Track;

import java.util.concurrent.TimeUnit;
import java.util.function.Function;

public class DefaultCachedTracksDecorator implements Function<Void, AsyncCache<String, Track>> {

	@Override
	public AsyncCache<String, Track> apply(Void unused) {
		return Caffeine.newBuilder()
			.expireAfterAccess(10, TimeUnit.MINUTES)
			.expireAfterWrite(1, TimeUnit.HOURS)
			.buildAsync();
	}

}
