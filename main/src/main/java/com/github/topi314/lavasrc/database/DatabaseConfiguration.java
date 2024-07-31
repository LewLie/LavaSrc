package com.github.topi314.lavasrc.database;

import lombok.AllArgsConstructor;
import lombok.Getter;
import org.jetbrains.annotations.NotNull;

@Getter
@AllArgsConstructor
public class DatabaseConfiguration {

	@NotNull
	private final String jdbcURL;

	@NotNull
	private final String username;

	@NotNull
	private final String password;

	@NotNull
	private final String spotifyTracksTableName;

}
