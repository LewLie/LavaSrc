package com.github.topi314.lavasrc.database;

import com.zaxxer.hikari.HikariConfig;

import java.util.function.Function;

public class DefaultDatabaseDecorator implements Function<HikariConfig, Void> {

	@Override
	public Void apply(HikariConfig config) {
		config.addDataSourceProperty( "cachePrepStmts" , "true" );
		config.addDataSourceProperty( "prepStmtCacheSize" , "250" );
		config.addDataSourceProperty( "prepStmtCacheSqlLimit" , "2048" );
		return null;
	}

}
