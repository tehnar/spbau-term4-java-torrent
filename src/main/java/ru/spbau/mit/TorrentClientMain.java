package ru.spbau.mit;

import java.io.IOException;
import java.nio.file.Paths;

/**
 * Created by Сева on 16.04.2016.
 */
public final class TorrentClientMain {
    private static final int PRINT_DELAY = 1000;

    private TorrentClientMain() {
    }

    private static final String USAGE_STRING = "Usage: list <tracker ip> | get <tracker ip> <file id> | "
            + "newfile <tracker ip> <file> | run <tracker ip>";
    private static final int DOWNLOAD_LINE_LEN = 20;

    public static void main(String[] args) throws IOException, InterruptedException {
        if (args.length < 2) {
            System.err.println(USAGE_STRING);
            return;
        }
        try (TorrentClient client = new TorrentClient(Paths.get("./files"), args[1])) {
            if (args[0].equals("list")) {
                System.out.printf("+---------+-----------+-------------------+\n");
                System.out.printf("| File id | File size |     File name     |\n");
                System.out.printf("+---------+-----------+-------------------+\n");
                for (TrackerProtocol.TrackerFileEntry entry : client.filesOnServer()) {
                    System.out.printf("| %-7d | %-9d | %-17s |\n", entry.id, entry.size, entry.fileName);
                }
                System.out.printf("+---------+-----------+-------------------+\n");
                return;
            }
            if (args[0].equals("newfile")) {
                int id = client.addFile(args[2]);
                System.out.println("File added, id=" + id);
                return;
            }
            if (args[0].equals("get")) {
                client.getFile(Integer.parseInt(args[2]));
                System.out.println("Downloading scheduled");
                return;
            }
            if (args[0].equals("run")) {
                client.startPeering((short) 0);
                while (!Thread.interrupted()) {
                    Thread.sleep(PRINT_DELAY);
                    for (TorrentClient.FileInfo info: client.getFilesInfo()) {
                        System.out.printf("%-15s ", info.fileName);
                        for (int i = 0; i < DOWNLOAD_LINE_LEN; i++) {
                            System.out.print(i < info.downloadedPercentage * DOWNLOAD_LINE_LEN ? "=" : '-');
                        }
                        System.out.println();
                    }
                }
            }
        }
    }
}
