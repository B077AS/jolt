package io.github.b077as.jolt.spring;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The {@code jolt.*} settings, from {@code application.properties} / {@code application.yml} like any other Spring setting.
 *
 * <pre>
 * jolt.app-id=com.example.notes          # where the web view keeps its data; default spring.application.name
 * jolt.mode=auto                         # auto | rpc | web
 * jolt.ui=classpath:ui                   # rpc mode: the page's files (classpath:... or file:...)
 * jolt.window.title=Notes
 * jolt.window.width=1200
 * jolt.window.height=800
 * jolt.window.show=on-load               # on-load | manual (the page calls jolt.call("jolt.window.show"))
 * jolt.web.start-path=/                  # web mode: the first page
 * jolt.rpc.expose-error-messages=false
 * </pre>
 *
 * <p>The window settings are read before the rest of the application starts (the window is created while Spring is still
 * starting), so they must come from the environment (properties, YAML, environment variables, command line), not from beans.
 */
@ConfigurationProperties("jolt")
public class JoltProperties {

    /** How the page reaches the application. */
    public enum Mode {
        /** {@code web} when the application runs a servlet web server, otherwise {@code rpc}. */
        AUTO,
        /** The page's files come from {@link #getUi()}; it calls {@code @JoltRpc} methods with {@code jolt.call}. */
        RPC,
        /** The window shows the application's own web server (Spring MVC), on a loopback port only the window can use. */
        WEB
    }

    /** When the window appears. */
    public enum Show {
        /** When the page has loaded (no blank window while it starts), or after {@code show-after} at the latest. */
        ON_LOAD,
        /** When the page calls {@code jolt.call("jolt.window.show")}, or after {@code show-after} at the latest. */
        MANUAL
    }

    private String appId;
    private Mode mode = Mode.AUTO;
    private String ui = "classpath:ui";
    private final Window window = new Window();
    private final Web web = new Web();
    private final Rpc rpc = new Rpc();

    /** Identifies the application to the system and names its data folder. Default: {@code spring.application.name}. */
    public String getAppId() {
        return appId;
    }

    public void setAppId(String appId) {
        this.appId = appId;
    }

    public Mode getMode() {
        return mode;
    }

    public void setMode(Mode mode) {
        this.mode = mode;
    }

    /** rpc mode: where the page's files are, {@code classpath:<folder>} or {@code file:<folder>}. Must hold an {@code index.html}. */
    public String getUi() {
        return ui;
    }

    public void setUi(String ui) {
        this.ui = ui;
    }

    public Window getWindow() {
        return window;
    }

    public Web getWeb() {
        return web;
    }

    public Rpc getRpc() {
        return rpc;
    }

    /** {@code jolt.window.*}: the main window. */
    public static class Window {
        private String title = "";
        private int width = 1200;
        private int height = 800;
        private int minWidth;
        private int minHeight;
        private boolean maximized;
        private boolean frameless;
        private boolean resizable = true;
        private boolean devTools;
        private Show show = Show.ON_LOAD;
        private java.time.Duration showAfter = java.time.Duration.ofSeconds(10);

        public String getTitle() {
            return title;
        }

        public void setTitle(String title) {
            this.title = title;
        }

        public int getWidth() {
            return width;
        }

        public void setWidth(int width) {
            this.width = width;
        }

        public int getHeight() {
            return height;
        }

        public void setHeight(int height) {
            this.height = height;
        }

        public int getMinWidth() {
            return minWidth;
        }

        public void setMinWidth(int minWidth) {
            this.minWidth = minWidth;
        }

        public int getMinHeight() {
            return minHeight;
        }

        public void setMinHeight(int minHeight) {
            this.minHeight = minHeight;
        }

        public boolean isMaximized() {
            return maximized;
        }

        public void setMaximized(boolean maximized) {
            this.maximized = maximized;
        }

        public boolean isFrameless() {
            return frameless;
        }

        public void setFrameless(boolean frameless) {
            this.frameless = frameless;
        }

        public boolean isResizable() {
            return resizable;
        }

        public void setResizable(boolean resizable) {
            this.resizable = resizable;
        }

        public boolean isDevTools() {
            return devTools;
        }

        public void setDevTools(boolean devTools) {
            this.devTools = devTools;
        }

        public Show getShow() {
            return show;
        }

        public void setShow(Show show) {
            this.show = show;
        }

        /** The window appears after this long even if the page never says it is ready, so a broken page cannot leave it hidden. */
        public java.time.Duration getShowAfter() {
            return showAfter;
        }

        public void setShowAfter(java.time.Duration showAfter) {
            this.showAfter = showAfter;
        }
    }

    /** {@code jolt.web.*}: web mode. */
    public static class Web {
        private String startPath = "/";

        /** The page the window opens first, relative to the server's root. */
        public String getStartPath() {
            return startPath;
        }

        public void setStartPath(String startPath) {
            this.startPath = startPath;
        }
    }

    /** {@code jolt.rpc.*}: see {@link io.github.b077as.jolt.RpcOptions}. */
    public static class Rpc {
        private boolean exposeErrorMessages;
        private int maxConcurrentCalls = 64;
        private int maxMessageChars = 8 * 1024 * 1024;

        /** Send the class and message of unexpected exceptions to the page (off: the page only learns that the call failed). */
        public boolean isExposeErrorMessages() {
            return exposeErrorMessages;
        }

        public void setExposeErrorMessages(boolean exposeErrorMessages) {
            this.exposeErrorMessages = exposeErrorMessages;
        }

        public int getMaxConcurrentCalls() {
            return maxConcurrentCalls;
        }

        public void setMaxConcurrentCalls(int maxConcurrentCalls) {
            this.maxConcurrentCalls = maxConcurrentCalls;
        }

        public int getMaxMessageChars() {
            return maxMessageChars;
        }

        public void setMaxMessageChars(int maxMessageChars) {
            this.maxMessageChars = maxMessageChars;
        }
    }
}
