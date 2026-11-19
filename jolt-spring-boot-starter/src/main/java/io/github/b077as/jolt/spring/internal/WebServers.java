package io.github.b077as.jolt.spring.internal;

import org.springframework.boot.web.server.WebServer;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ApplicationContext;
import org.springframework.util.ClassUtils;

/** The port of the application's web server, if it runs one. Spring Boot's web server classes are optional. */
public final class WebServers {

    private WebServers() {
    }

    /** The port, or -1 when the application runs no web server. */
    public static int port(ApplicationContext context) {
        if (!ClassUtils.isPresent("org.springframework.boot.web.server.context.WebServerApplicationContext", context.getClassLoader())) {
            return -1;
        }
        return Present.port(context);
    }

    private static final class Present {
        private Present() {
        }

        static int port(ApplicationContext context) {
            if (context instanceof WebServerApplicationContext web) {
                WebServer server = web.getWebServer();
                return server == null ? -1 : server.getPort();
            }
            return -1;
        }
    }
}
