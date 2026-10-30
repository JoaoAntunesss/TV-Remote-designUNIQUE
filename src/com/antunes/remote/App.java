package com.antunes.remote;

import android.app.Application;

import java.io.File;

public class App extends Application {
    private static App self;

    @Override public void onCreate() { super.onCreate(); self = this; }

    static File files() { return self.getFilesDir(); }
}
