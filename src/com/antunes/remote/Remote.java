package com.antunes.remote;

/** What the phone can ask any TV or box to do, whatever the way of talking to it. */
interface Remote {
    void run();
    void stop();
    boolean connected();
    /** direction: 1 = press, 2 = release, 3 = short press */
    void key(int code, int direction);
    default void key(int code) { key(code, 3); }
    void type(String text);
    /** Opens an app (by package) or a link. */
    void open(String pkgOrLink);
}
