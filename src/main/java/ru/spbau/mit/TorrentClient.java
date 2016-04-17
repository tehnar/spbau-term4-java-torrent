package ru.spbau.mit;

import java.io.*;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Collectors;


/**
 * Created by Сева on 26.03.2016.
 */
public class TorrentClient implements Closeable {
    private final Path seedingFolder;
    private final String serverIp;
    private final Map<Integer, ClientFileEntry> seedingFiles = new ConcurrentHashMap<>();
    private final Map<Integer, ClientFileEntry> downloadingFiles = new ConcurrentHashMap<>();
    private final Path seedingFilesPath;
    private ServerSocket serverSocket = null;
    private final ExecutorService executorService = Executors.newCachedThreadPool();
    private static final String SEEDING_FILES_FILENAME = "seeding_files.cfg";
    private final Timer updateTimer = new Timer();


    public List<FileInfo> getFilesInfo() {
        return seedingFiles.entrySet()
                .stream()
                .map(entry -> new FileInfo(entry.getValue()))
                .collect(Collectors.toList());
    }

    @Override
    public void close() throws IOException {
        if (serverSocket != null) {
            serverSocket.close();
            updateTimer.cancel();
            executorService.shutdownNow();
            serverSocket = null;
        }

        try (DataOutputStream stream = new DataOutputStream(Files.newOutputStream(seedingFilesPath))) {
            seedingFiles.forEach(((integer, clientFileEntry) -> {
                try {
                    stream.writeInt(clientFileEntry.id);
                    stream.writeUTF(clientFileEntry.name);
                    stream.writeLong(clientFileEntry.size);

                    for (boolean b : clientFileEntry.isPartPresent) {
                        stream.writeBoolean(b);
                    }
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }));
        }
    }

    public class ClientFileEntry {
        // CHECKSTYLE.OFF: VisibilityModifier
        int id;
        String name;
        long size;
        boolean[] isPartPresent;
        // CHECKSTYLE.ON: VisibilityModifier

        ClientFileEntry(int id, String name, long size, boolean isFileExists) {
            this.id = id;
            this.name = name;
            this.size = size;
            int partCount = getPartCount(size);
            isPartPresent = new boolean[partCount];
            for (int i = 0; i < partCount; i++) {
                isPartPresent[i] = isFileExists;
            }
        }

        ClientFileEntry(int id, String name, long size, boolean[] isPartPresent) {
            this.id = id;
            this.name = name;
            this.size = size;
            this.isPartPresent = isPartPresent;
        }
    }

    private int getPartCount(long size) {
        return (int) Math.ceil(1.0 * size / ClientProtocol.PART_SIZE);
    }

    public TorrentClient(Path seedingFolder, String serverIp) throws IOException {
        this.serverIp = serverIp;
        this.seedingFolder = seedingFolder;
        if (Files.notExists(seedingFolder)) {
            Files.createDirectory(seedingFolder);
        }

        this.seedingFilesPath = Paths.get(seedingFolder.toString(), SEEDING_FILES_FILENAME);
        if (Files.notExists(seedingFilesPath)) {
            Files.createFile(seedingFilesPath);
        }
        try (DataInputStream stream = new DataInputStream(Files.newInputStream(seedingFilesPath))) {
            while (true) {
                try {
                    int id = stream.readInt();
                    String name = stream.readUTF();
                    long size = stream.readLong();
                    int partCount = getPartCount(size);
                    boolean[] isPartPresent = new boolean[partCount];
                    boolean downloaded = true;
                    for (int i = 0; i < partCount; i++) {
                        isPartPresent[i] = stream.readBoolean();
                        if (!isPartPresent[i]) {
                            downloaded = false;
                        }
                    }
                    ClientFileEntry entry = new ClientFileEntry(id, name, size, isPartPresent);
                    seedingFiles.put(id, entry);
                    if (!downloaded) {
                        downloadingFiles.put(id, entry);
                    }
                } catch (EOFException ignored) {
                    break;
                }
            }
        }
    }

    public List<TrackerProtocol.TrackerFileEntry> filesOnServer() throws IOException {
        try (Socket socket = new Socket(serverIp, TrackerProtocol.SERVER_PORT);
             DataInputStream inputStream = new DataInputStream(socket.getInputStream());
             DataOutputStream outputStream = new DataOutputStream(socket.getOutputStream())) {

            return TrackerProtocol.makeListQuery(inputStream, outputStream);
        }
    }

    public void getFile(int id) throws IOException {
        List<TrackerProtocol.TrackerFileEntry> files = filesOnServer();
        for (TrackerProtocol.TrackerFileEntry entry : files) {
            if (entry.id == id) {
                synchronized (downloadingFiles) {
                    ClientFileEntry newEntry = new ClientFileEntry(id, entry.fileName, entry.size, false);
                    downloadingFiles.put(id, newEntry);
                    seedingFiles.put(id, newEntry);
                    downloadingFiles.notify();
                }
                break;
            }
        }
    }

    public int addFile(String fileName) throws IOException {
        try (Socket socket = new Socket(serverIp, TrackerProtocol.SERVER_PORT);
             DataInputStream inputStream = new DataInputStream(socket.getInputStream());
             DataOutputStream outputStream = new DataOutputStream(socket.getOutputStream())) {

            long size = Files.size(Paths.get(seedingFolder.toString(), fileName));
            int id = TrackerProtocol.makeUploadQuery(inputStream, outputStream, fileName, size);
            ClientFileEntry entry = new ClientFileEntry(id, fileName, size, true);
            seedingFiles.put(id, entry);
            return id;
        }
    }

    public List<TrackerProtocol.ClientEntry> fileSeeders(int fileId) throws IOException {
        try (Socket socket = new Socket(serverIp, TrackerProtocol.SERVER_PORT);
             DataInputStream inputStream = new DataInputStream(socket.getInputStream());
             DataOutputStream outputStream = new DataOutputStream(socket.getOutputStream())) {

            return TrackerProtocol.makeSourcesQuery(inputStream, outputStream, fileId);
        }
    }

    public boolean update() throws IOException {
        try (Socket socket = new Socket(serverIp, TrackerProtocol.SERVER_PORT);
             DataInputStream inputStream = new DataInputStream(socket.getInputStream());
             DataOutputStream outputStream = new DataOutputStream(socket.getOutputStream())) {

            return TrackerProtocol.makeUpdateQuery(inputStream, outputStream, serverSocket.getLocalPort(),
                    seedingFiles.entrySet().stream().mapToInt(Map.Entry::getKey).toArray());
        }
    }

    private byte[] getPart(int fileId, int partId, int partSize,
                           TrackerProtocol.ClientEntry seeder) throws IOException {
        try (Socket socket = new Socket(InetAddress.getByAddress(seeder.ip), seeder.port);
             DataInputStream inputStream = new DataInputStream(socket.getInputStream());
             DataOutputStream outputStream = new DataOutputStream(socket.getOutputStream())) {

            return ClientProtocol.makeGetQuery(inputStream, outputStream, fileId, partId, partSize);
        }
    }

    public void startPeering(int seedingPort) throws IOException {
        serverSocket = new ServerSocket(seedingPort);
        executorService.submit(new ClientAcceptor());
        updateTimer.scheduleAtFixedRate(new TimerTask() {
            @Override
            public void run() {
                try {
                    update();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }, 0, TrackerProtocol.TIME_BETWEEN_UPDATE_QUERIES);
        executorService.submit(new FileDownloader());
    }

    private class ClientAcceptor implements Runnable {

        @Override
        public void run() {
            try (ServerSocket dummy = serverSocket) {
                while (!serverSocket.isClosed()) {
                    Socket socket = serverSocket.accept();
                    executorService.submit(new ClientProcessor(socket));
                }
            } catch (IOException e) {
                if (!e.getMessage().equals("socket closed")) {
                    e.printStackTrace();
                }
            }
        }
    }

    private class ClientProcessor implements Runnable {
        private final Socket socket;

        ClientProcessor(Socket socket) {
            this.socket = socket;
        }

        @Override
        public void run() {
            try (DataInputStream inputStream = new DataInputStream(socket.getInputStream());
                 DataOutputStream outputStream = new DataOutputStream(socket.getOutputStream())) {
                while (!socket.isClosed()) {
                    int type = ClientProtocol.getQueryType(inputStream);
                    switch (type) {
                        case ClientProtocol.STAT_QUERY:
                            int id = ClientProtocol.getStatQueryId(inputStream);
                            List<Integer> availableParts = new ArrayList<>();
                            ClientFileEntry entry = seedingFiles.get(id);
                            for (int i = 0; i < getPartCount(entry.size); i++) {
                                if (entry.isPartPresent[i]) {
                                    availableParts.add(i);
                                }
                            }
                            ClientProtocol.statQueryResponse(outputStream, availableParts);
                            break;

                        case ClientProtocol.GET_QUERY:
                            ClientProtocol.GetQueryData queryData = ClientProtocol.getGetQueryData(inputStream);
                            entry = seedingFiles.get(queryData.id);
                            try (RandomAccessFile file = new RandomAccessFile(Paths.get(seedingFolder.toString(),
                                    entry.name).toString(), "r")) {
                                file.seek((long) queryData.part * ClientProtocol.PART_SIZE);
                                ClientProtocol.getQueryResponse(outputStream, file);
                            }
                            break;

                        default:
                            throw new IllegalStateException("Unknown query type: " + type);
                    }
                }
            } catch (EOFException ignored) {

            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    private class FileDownloader implements Runnable {
        @Override
        public void run() {
            while (!Thread.interrupted()) {
                Map<Integer, Future> fileDownloaders = new HashMap<>();
                try {
                    synchronized (downloadingFiles) {
                        for (Map.Entry<Integer, ClientFileEntry> entry : downloadingFiles.entrySet()) {
                            if (!fileDownloaders.containsKey(entry.getKey())) {
                                Future future = executorService.submit(new Downloader(entry.getValue()));
                                fileDownloaders.put(entry.getKey(), future);
                            }
                        }
                        downloadingFiles.wait(); // wait until new file to be downloaded
                    }
                } catch (InterruptedException e) {
                    return;
                }
            }
        }

        private class Downloader implements Runnable {
            private static final int SERVER_QUERY_DELAY = 1000;
            private final ClientFileEntry entry;

            Downloader(ClientFileEntry entry) {
                this.entry = entry;
            }

            @Override
            public void run() {
                Path filePath = Paths.get(seedingFolder.toString(), entry.name);
                final RandomAccessFile file;
                try {
                    if (Files.notExists(filePath)) {
                        Files.createFile(filePath);
                    }
                    file = new RandomAccessFile(filePath.toFile(), "rw");
                    file.setLength(entry.size);
                } catch (IOException e) {
                    e.printStackTrace();
                    return;
                }
                boolean downloadCompleted = false;
                while (!downloadCompleted) {
                    try {
                        List<TrackerProtocol.ClientEntry> seeders = fileSeeders(entry.id);
                        Collections.shuffle(seeders); // to distribute load,
                        // otherwise all the clients will be downloading from the same seeder
                        TrackerProtocol.ClientEntry[] partOwner =
                                new TrackerProtocol.ClientEntry[entry.isPartPresent.length];
                        for (TrackerProtocol.ClientEntry seeder : seeders) {
                            if (seeder.port == serverSocket.getLocalPort()) {
                                continue;
                            }
                            try (Socket socket = new Socket(InetAddress.getByAddress(seeder.ip), seeder.port);
                                 DataInputStream inputStream = new DataInputStream(socket.getInputStream());
                                 DataOutputStream outputStream = new DataOutputStream(socket.getOutputStream())) {

                                for (int partId : ClientProtocol.makeStatQuery(inputStream,
                                        outputStream, entry.id)) {
                                    partOwner[partId] = seeder;
                                }
                            }
                        }
                        downloadCompleted = true;
                        for (int partId = 0; partId < entry.isPartPresent.length; partId++) {
                            if (!entry.isPartPresent[partId]) {
                                downloadCompleted = false;
                                if (partOwner[partId] == null) {
                                    continue;
                                }
                                int partSize = ClientProtocol.PART_SIZE;
                                if (partId == entry.isPartPresent.length - 1) {
                                    partSize = (int) (entry.size % ClientProtocol.PART_SIZE);
                                    if (partSize == 0) {
                                        partSize = ClientProtocol.PART_SIZE;
                                    }
                                }
                                byte[] partData = getPart(entry.id, partId, partSize, partOwner[partId]);
                                if (partData.length < partSize) {
                                    continue;
                                }
                                file.seek((long) partId * ClientProtocol.PART_SIZE);
                                file.write(partData);
                                entry.isPartPresent[partId] = true;
                            }
                        }
                        Thread.sleep(SERVER_QUERY_DELAY); //to not to spam with queries
                    } catch (IOException e) {
                        e.printStackTrace();
                    } catch (InterruptedException e) {
                        return;
                    }
                }
                downloadingFiles.remove(entry.id);
            }
        }
    }

    public class FileInfo {
        // CHECKSTYLE.OFF: VisibilityModifier
        final boolean isFinished;
        final String fileName;
        final double downloadedPercentage;
        // CHECKSTYLE.ON: VisibilityModifier

        FileInfo(ClientFileEntry entry) {
            fileName = entry.name;
            int downloadedPartCnt = 0;
            for (boolean b : entry.isPartPresent) {
                if (b) {
                    downloadedPartCnt++;
                }
            }
            isFinished = downloadedPartCnt == entry.isPartPresent.length;
            downloadedPercentage = (double) downloadedPartCnt / entry.isPartPresent.length;
        }
    }
}
