package ru.spbau.mit;

import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.*;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Collectors;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.apache.commons.io.FileUtils;

/**
 * Created by Сева on 26.03.2016.
 */

public class TorrentServerTest {
    private static final int CLIENT_CNT = 5;
    private static final int DEFAULT_PORT = 12345;
    private static final int PARTS_IN_FILE = 30;
    private static final int PRINT_DELAY = 1000;

    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    @After
    public void cleanupServerFiles() {
        Paths.get(TorrentServer.FILE_LIST_FILENAME).toFile().delete();
    }

    @Test
    public void testDifferentFileIds() throws IOException, InterruptedException {
        List<TorrentClient> clients;
        try (TorrentServer tracker = new TorrentServer()) {
            tracker.start();
            clients = getClients();
            ExecutorService executorService = Executors.newCachedThreadPool();
            List<Future<Integer>> ids = new ArrayList<>();
            for (int i = 0; i < clients.size(); i++) {
                final int index = i;
                final TorrentClient client = clients.get(index);
                ids.add(executorService.submit(() -> client.addFile(index + ".txt")));
            }
            assertEquals(clients.size(), ids.stream()
                    .map((integerFuture) -> {
                        try {
                            //System.err.println(integerFuture.get());
                            return integerFuture.get();
                        } catch (InterruptedException | ExecutionException e) {
                            e.printStackTrace();
                            return 0;
                        }
                    })
                    .distinct()
                    .count());
        }
    }

    @Test
    public void testServerQueries() throws IOException, InterruptedException {
        List<TorrentClient> clients = new ArrayList<>();
        try (TorrentServer tracker = new TorrentServer()) {
            tracker.start();
            clients = getClients();
            for (int i = 0; i < clients.size(); i++) {
                clients.get(i).startPeering(DEFAULT_PORT + i);
                assertEquals(i, clients.get(i).addFile(i + ".txt"));
                assertTrue(clients.get(i).update());
            }
            List<Thread> threads = new ArrayList<>();
            for (int i = 0; i < clients.size(); i++) {
                final int index = i;
                final TorrentClient client = clients.get(index);
                threads.add(new Thread(() -> {
                    try {
                        List<TrackerProtocol.ClientEntry> sources = client.fileSeeders(index);
                        assertEquals(Arrays.asList(index), sources.stream()
                                .map(clientEntry -> clientEntry.port - DEFAULT_PORT)
                                .collect(Collectors.toList()));
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                }));
            }
            threads.forEach(Thread::start);
            for (Thread thread : threads) {
                thread.join();
            }
            threads.clear();
        }
    }

    @Test
    public void testSavingInformation() throws IOException {
        try (TorrentServer server = new TorrentServer()) {
            server.start();
            List<TorrentClient> clients = getClients();
            for (int i = 0; i < clients.size(); i++) {
                clients.get(i).addFile(i + ".txt");
                clients.get(i).close();
            }
        }
        try (TorrentServer server = new TorrentServer();
             TorrentClient client = new TorrentClient(Paths.get("./tmp1"), "localhost")) {
            server.start();
            List<TrackerProtocol.TrackerFileEntry> files = new ArrayList<>();
            for (int i = 0; i < CLIENT_CNT; i++) {
                files.add(new TrackerProtocol.TrackerFileEntry(i, i + ".txt",
                        (long) ClientProtocol.PART_SIZE * PARTS_IN_FILE));
            }
            List<TrackerProtocol.TrackerFileEntry> serverFiles = client.filesOnServer();
            serverFiles.sort((entry1, entry2) -> entry1.fileName.compareTo(entry2.fileName));
            assertEquals(files, serverFiles);
        }
    }

    @Test
    public void testTracker() throws InterruptedException, IOException {
        try (TorrentServer server = new TorrentServer()) {
            server.start();
            List<TorrentClient> clients = getClients();
            for (int i = 0; i < clients.size(); i++) {
                clients.get(i).addFile(i + ".txt");
                clients.get(i).startPeering(0);
                for (int j = 0; j < i; j++) {
                    clients.get(i).getFile(j);
                }

            }

            download(clients);
            String tempPath = temporaryFolder.getRoot().getAbsolutePath();
            for (int i = 0; i < clients.size(); i++) {
                for (int j = 0; j < i; j++) {
                    Path first = Paths.get(tempPath, Integer.toString(j), j + ".txt");
                    Path second = Paths.get(tempPath, Integer.toString(i), j + ".txt");
                    assertTrue(FileUtils.contentEquals(first.toFile(), second.toFile()));
                }
            }
            File clientFile = temporaryFolder.newFile(Paths.get("0", "newFile").toString());
            // CHECKSTYLE.OFF: MagicNumber
            fillFile(clientFile, (byte) 239);
            // CHECKSTYLE.ON: MagicNumber
            int id = clients.get(0).addFile("newFile");
            for (int i = 1; i < clients.size(); i++) {
                clients.get(i).getFile(id);
            }
            download(clients);
            for (int i = 1; i < clients.size(); i++) {
                Path first = Paths.get(tempPath, "0", "newFile");
                Path second = Paths.get(tempPath, Integer.toString(i), "newFile");
                assertTrue(FileUtils.contentEquals(first.toFile(), second.toFile()));
            }
        }
    }

    private void download(List<TorrentClient> clients) throws InterruptedException {
        while (!Thread.interrupted()) {
            Thread.sleep(PRINT_DELAY);
            boolean everythingDownloaded = true;
            // CHECKSTYLE.OFF: MagicNumber
            for (int i = 0; i < 50; i++) {
                System.out.println();
            }
            // CHECKSTYLE.ON: MagicNumber
            for (TorrentClient client : clients) {
                for (TorrentClient.FileInfo info : client.getFilesInfo()) {
                    if (!info.isFinished) {
                        everythingDownloaded = false;
                    }
                    System.out.printf("%-15s ", info.fileName);
                    // CHECKSTYLE.OFF: MagicNumber
                    for (int i = 0; i < 30; i++) {
                        System.out.print(i < info.downloadedPercentage * 30 ? "=" : '-');
                    }
                    // CHECKSTYLE.ON: MagicNumber
                    System.out.println();
                }
                System.out.println("-------------");
            }
            if (everythingDownloaded) {
                break;
            }
        }
    }

    private void fillFile(File file, byte fileByte) throws IOException {
        DataOutputStream stream = new DataOutputStream(new FileOutputStream(file));
        byte[] block = new byte[ClientProtocol.PART_SIZE];
        for (int j = 0; j < ClientProtocol.PART_SIZE; j++) {
            block[j] = fileByte;
        }
        for (int j = 0; j < PARTS_IN_FILE; j++) {
            stream.write(block);
        }
    }

    private List<TorrentClient> getClients() throws IOException {
        List<TorrentClient> clients = new ArrayList<>(CLIENT_CNT);
        for (int i = 0; i < CLIENT_CNT; i++) {
            File folder = temporaryFolder.newFolder(Integer.toString(i));
            TorrentClient seeder = new TorrentClient(folder.toPath(), "localhost");
            clients.add(seeder);
            File clientFile = temporaryFolder.newFile(Paths.get(Integer.toString(i),
                    i + ".txt").toString());
            fillFile(clientFile, (byte) i);
        }
        return clients;
    }
}
