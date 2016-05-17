package ru.spbau.mit;

import java.io.IOException;

/**
 * Created by Сева on 16.04.2016.
 */
public final class TorrentClientMain {
    private static final int PRINT_DELAY = 1000;
    private static final int DOWNLOAD_LINE_LEN = 50;

    private static final String USAGE_STRING = "Usage: list <tracker ip> | get <tracker ip> <file id> | "
            + "newfile <tracker ip> <file> | run <tracker ip>";

    private TorrentClientMain() {
    }

    public static void main(String[] args) throws IOException, InterruptedException {
        TorrentClientFrame frame = new TorrentClientFrame();
        frame.pack();
        frame.setVisible(true);
    }
}
