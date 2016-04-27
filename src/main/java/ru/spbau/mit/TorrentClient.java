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
import java.util.stream.Collectors;


/**
 * Created by Сева on 26.03.2016.
 */
public class TorrentClient implements Closeable {
    private static final int SERVER_QUERY_DELAY = 1000;
    private static final String SEEDING_FILES_FILENAME = "seeding_files.cfg";

    private final Path seedingFolder;
    private final String serverIp;
    private final Map<Integer, ClientFileEntry> seedingFiles = new ConcurrentHashMap<>();
    private final Map<Integer, ClientFileEntry> downloadingFiles = new ConcurrentHashMap<>();
    private final Path seedingFilesPath;
    private ServerSocket serverSocket = null;
    private final ExecutorService executorService = Executors.newCachedThreadPool();
    private final Timer updateTimer = new Timer();
    private final Map<String, Path> filePathByName = new ConcurrentHashMap<>();

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
                    String path = stream.readUTF();
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
                    ClientFileEntry entry = new ClientFileEntry(id, Paths.get(path), size, isPartPresent);
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

    @Override
    public void close() throws IOException {
        updateTimer.cancel();
        executorService.shutdownNow();
        if (serverSocket != null) {
            serverSocket.close();
            serverSocket = null;
        }

        try (DataOutputStream stream = new DataOutputStream(Files.newOutputStream(seedingFilesPath))) {
            seedingFiles.forEach(((integer, clientFileEntry) -> {
                try {
                    stream.writeInt(clientFileEntry.id);
                    stream.writeUTF(clientFileEntry.path.toString());
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

    private class ClientFileEntry {
        // CHECKSTYLE.OFF: VisibilityModifier
        int id;
        Path path;
        long size;
        boolean[] isPartPresent;
        // CHECKSTYLE.ON: VisibilityModifier

        ClientFileEntry(int id, Path path, long size, boolean isFileExists) {
            this.id = id;
            this.path = path;
            this.size = size;
            int partCount = getPartCount(size);
            isPartPresent = new boolean[partCount];
            for (int i = 0; i < partCount; i++) {
                isPartPresent[i] = isFileExists;
            }
        }

        ClientFileEntry(int id, Path path, long size, boolean[] isPartPresent) {
            this.id = id;
            this.path = path;
            this.size = size;
            this.isPartPresent = isPartPresent;
        }

        public int getPartSize(int partId) {
            long partSize = size - (long) partId * ClientProtocol.PART_SIZE;
            if (partSize > ClientProtocol.PART_SIZE) {
                partSize = ClientProtocol.PART_SIZE;
            }
            return (int) partSize;
        }
    }

    private int getPartCount(long size) {
        return (int) Math.ceil(1.0 * size / ClientProtocol.PART_SIZE);
    }

    public List<FileInfo> getFilesInfo() {
        return seedingFiles.entrySet()
                .stream()
                .map(entry -> new FileInfo(entry.getValue()))
                .collect(Collectors.toList());
    }

    private class Connection implements Closeable{
        private final Socket socket;
        private final DataInputStream inputStream;
        private final DataOutputStream outputStream;

        Connection(String serverIp, int port) throws IOException {
            this.socket = new Socket(serverIp, port);
            this.inputStream = new DataInputStream(socket.getInputStream());
            this.outputStream = new DataOutputStream(socket.getOutputStream());
        }

        @Override
        public void close() throws IOException {
            outputStream.close();
            inputStream.close();
            socket.close();
        }
    }

    public List<TrackerProtocol.TrackerFileEntry> filesOnServer() throws IOException {
        try (Connection connection = new Connection(serverIp, TrackerProtocol.SERVER_PORT)) {
            return TrackerProtocol.makeListQuery(connection.inputStream, connection.outputStream);
        }
    }

    public void getFile(int id) throws IOException {
        List<TrackerProtocol.TrackerFileEntry> files = filesOnServer();
        for (TrackerProtocol.TrackerFileEntry entry : files) {
            if (entry.id == id) {
                synchronized (downloadingFiles) {
                    Path filePath = Paths.get(seedingFolder.toString(), entry.fileName);
                    ClientFileEntry newEntry = new ClientFileEntry(id, filePath, entry.size, false);
                    downloadingFiles.put(id, newEntry);
                    seedingFiles.put(id, newEntry);
                    downloadingFiles.notify();
                }
                break;
            }
        }
    }

    public int addFile(Path filePath) throws IOException {
        try (Connection connection = new Connection(serverIp, TrackerProtocol.SERVER_PORT)) {
            long size = Files.size(filePath);
            int id = TrackerProtocol.makeUploadQuery(connection.inputStream, connection.outputStream,
                    filePath.getFileName().toString(), size);
            ClientFileEntry entry = new ClientFileEntry(id, filePath, size, true);
            seedingFiles.put(id, entry);
            return id;
        }
    }

    public List<TrackerProtocol.ClientEntry> fileSeeders(int fileId) throws IOException {
        try (Connection connection = new Connection(serverIp, TrackerProtocol.SERVER_PORT)) {
            return TrackerProtocol.makeSourcesQuery(connection.inputStream, connection.outputStream, fileId);
        }
    }

    public boolean update() throws IOException {
        try (Connection connection = new Connection(serverIp, TrackerProtocol.SERVER_PORT)) {
            return TrackerProtocol.makeUpdateQuery(connection.inputStream, connection.outputStream,
                    serverSocket.getLocalPort(),
                    seedingFiles.entrySet().stream().mapToInt(Map.Entry::getKey).toArray());
        }
    }

    private byte[] getPart(int fileId, int partId, int partSize,
                           TrackerProtocol.ClientEntry seeder) throws IOException {
        try (Connection connection =
                     new Connection(InetAddress.getByAddress(seeder.ip).getHostAddress(), seeder.port)) {
            return ClientProtocol.makeGetQuery(connection.inputStream, connection.outputStream,
                    fileId, partId, partSize);
        }
    }

    public void startPeering(int seedingPort) throws IOException {
        serverSocket = new ServerSocket(seedingPort);
        executorService.submit(this::startServer);
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
        executorService.submit(this::downloadFiles);
    }


    private void startServer() {
        try (ServerSocket dummy = serverSocket) {
            while (!serverSocket.isClosed()) {
                Socket socket = serverSocket.accept();
                executorService.submit(() -> handleClient(socket));
            }
        } catch (IOException e) {
            if (!e.getMessage().equals("socket closed")) {
                e.printStackTrace();
            }
        }
    }

    private void handleClient(Socket socket) {
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
                        try (RandomAccessFile file = new RandomAccessFile(entry.path.toString(), "r")) {
                            file.seek((long) queryData.part * ClientProtocol.PART_SIZE);
                            int partSize = entry.getPartSize(queryData.part);
                            byte[] buffer = new byte[partSize];
                            file.readFully(buffer, 0, partSize);
                            ClientProtocol.getGetQueryResponse(outputStream, buffer);
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

    private void downloadFiles() {
        while (!Thread.interrupted()) {
            Set<Integer> fileDownloaders = new HashSet<>();
            try {
                synchronized (downloadingFiles) {
                    for (Map.Entry<Integer, ClientFileEntry> entry : downloadingFiles.entrySet()) {
                        if (!fileDownloaders.contains(entry.getKey())) {
                            executorService.submit(() -> downloadFile(entry.getValue()));
                            fileDownloaders.add(entry.getKey());
                        }
                    }
                    downloadingFiles.wait(); // wait until new file to be downloaded
                }
            } catch (InterruptedException e) {
                return;
            }
        }
    }

    private void downloadFile(ClientFileEntry entry) {
        Path filePath = Paths.get(seedingFolder.toString(), entry.path.getFileName().toString());
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
                    try (Connection connection = new Connection(
                            InetAddress.getByAddress(seeder.ip).getHostAddress(), seeder.port)) {
                        for (int partId : ClientProtocol.makeStatQuery(connection.inputStream,
                                connection.outputStream, entry.id)) {
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

                        byte[] partData = getPart(entry.id, partId, entry.getPartSize(partId), partOwner[partId]);
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

    public class FileInfo {
        // CHECKSTYLE.OFF: VisibilityModifier
        final boolean isFinished;
        final String fileName;
        final double downloadedPercentage;
        // CHECKSTYLE.ON: VisibilityModifier

        FileInfo(ClientFileEntry entry) {
            fileName = entry.path.getFileName().toString();
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
