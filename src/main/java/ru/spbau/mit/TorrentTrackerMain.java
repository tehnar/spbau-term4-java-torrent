package ru.spbau.mit;

import java.io.IOException;

/**
 * Created by Сева on 16.04.2016.
 */
public final class TorrentTrackerMain {
    private TorrentTrackerMain() {
    }

    public static void main(String[] args) throws IOException, InterruptedException {
        TorrentServer server = new TorrentServer();
        server.start();
        server.join();
    }
}
