package com.riguz.forks.config;

import javax.inject.Singleton;

import com.riguz.forks.http.NetworkServer;
import com.riguz.forks.http.RequestDelegator;
import com.riguz.forks.http.undertow.UndertowServer;
import com.riguz.forks.ioc.Provides;
import com.riguz.forks.mvc.Dispatcher;
import com.riguz.forks.router.Router;
import com.riguz.forks.router.TrieRouter;

public class DefaultConfig {

	@Provides
	@Singleton
	public Router router(TrieRouter router) {
		return router;
	}

	@Provides
	@Singleton
	public RequestDelegator delegator(Dispatcher dispatcher) {
		return dispatcher;
	}

	@Provides
	@Singleton
	public NetworkServer server(RequestDelegator handler) {
		return new UndertowServer(8080, handler);
	}
}
