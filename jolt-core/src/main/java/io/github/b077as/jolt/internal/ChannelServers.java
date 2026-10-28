package io.github.b077as.jolt.internal;

import io.github.b077as.jolt.JoltException;
import io.github.b077as.jolt.WebWindow;

import java.io.IOException;
import java.util.IdentityHashMap;
import java.util.Map;

/** Gives each window its {@link ChannelServer}, started the first time the window opens a channel and stopped when it closes. */
public final class ChannelServers {

    private static final Map<WebWindow, ChannelServer> SERVERS = new IdentityHashMap<>();

    private ChannelServers() {}

    public static ChannelServer of(WebWindow window) {
        synchronized (SERVERS) {
            ChannelServer existing = SERVERS.get(window);
            if (existing != null) {
                return existing;
            }
            ChannelServer server;
            try {
                server = new ChannelServer(window::security);
            } catch (IOException e) {
                throw new JoltException("cannot start the channel server", e);
            }
            SERVERS.put(window, server);
            window.onClosed(() -> {
                synchronized (SERVERS) {
                    SERVERS.remove(window);
                }
                server.close();
            });
            // later documents get the script; the one already showing (if any) gets it now
            String script = server.script();
            window.addInitScript(script);
            window.eval(script).exceptionally(t -> null);
            return server;
        }
    }
}
