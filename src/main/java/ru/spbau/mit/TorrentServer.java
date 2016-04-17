package ru.spbau.mit;

import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static ru.spbau.mit.TrackerProtocol.TrackerFileEntry;

public class TorrentServer implements Closeable {
    public static final String FILE_LIST_FILENAME = "file_list.cfg";

    private final Set<TrackerFileEntry> files = ConcurrentHashMap.newKeySet();
    private ServerSocket serverSocket = null;
    private final ExecutorService executorService = Executors.newCachedThreadPool();
    private final Map<Integer, Set<TrackerProtocol.ClientEntry>> fileSources = new ConcurrentHashMap<>();
    private final Set<TrackerProtocol.ClientEntry> clients = ConcurrentHashMap.newKeySet();
    private final Map<TrackerProtocol.ClientEntry, TimerTask> disconnectTasks = new ConcurrentHashMap<>();
    private final Timer disconnectTimer = new Timer();

    public TorrentServer() throws IOException {
        Path path = Paths.get(FILE_LIST_FILENAME);
        if (Files.notExists(path)) {
            Files.createFile(path);
        }

        try (DataInputStream inputStream = new DataInputStream(Files.newInputStream(path))) {
            while (true) {
                try {
                    files.add(new TrackerFileEntry(inputStream.readInt(), inputStream.readUTF(),
                            inputStream.readLong()));
                } catch (EOFException e) {
                    break;
                }
            }
        }
    }

    public void start() {
        executorService.submit(new ClientAcceptor());
    }

    @Override
    public void close() throws IOException {
        serverSocket.close();
        executorService.shutdownNow();
        Path path = Paths.get(FILE_LIST_FILENAME);
        disconnectTimer.cancel();

        try (DataOutputStream outputStream = new DataOutputStream(Files.newOutputStream(path))) {
            for (TrackerFileEntry entry : files) {
                outputStream.writeInt(entry.id);
                outputStream.writeUTF(entry.fileName);
                outputStream.writeLong(entry.size);
            }
        }

    }

    public void join() throws InterruptedException {
        executorService.awaitTermination(Long.MAX_VALUE, TimeUnit.NANOSECONDS);
    }

    private class ClientAcceptor implements Runnable {
        @Override
        public void run() {
            try (ServerSocket serverSocket = new ServerSocket(TrackerProtocol.SERVER_PORT)) {
                TorrentServer.this.serverSocket = serverSocket;
                while (!serverSocket.isClosed()) {
                    Socket client = serverSocket.accept();
                    executorService.submit(new ClientProcessor(client));
                }
            } catch (IOException e) {
                if (!e.getMessage().equals("socket closed")) {
                    e.printStackTrace();
                }
            }
        }
    }

    private class ClientProcessor implements Runnable {
        private final Socket client;
        private final TrackerProtocol.ClientEntry clientEntry;

        ClientProcessor(Socket client) {
            this.client = client;
            clientEntry = new TrackerProtocol.ClientEntry(client);
        }

        @Override
        public void run() {
            try (Socket dummy = client;
                 DataInputStream inputStream = new DataInputStream(client.getInputStream());
                 DataOutputStream outputStream = new DataOutputStream(client.getOutputStream())) {
                while (!client.isClosed()) {
                    int type = TrackerProtocol.getQueryType(inputStream);
                    switch (type) {
                        case TrackerProtocol.LIST_QUERY:
                            TrackerProtocol.listQueryResponse(outputStream, files);
                            break;

                        case TrackerProtocol.UPLOAD_QUERY:
                            TrackerFileEntry entry = TrackerProtocol.getUploadQueryData(inputStream);
                            synchronized (files) {
                                entry.id = files.size();
                                files.add(entry);
                            }
                            TrackerProtocol.uploadQueryResponse(outputStream, entry.id);
                            break;

                        case TrackerProtocol.SOURCES_QUERY:
                            int id = TrackerProtocol.getSourcesQueryFileId(inputStream);
                            fileSources.putIfAbsent(id, ConcurrentHashMap.newKeySet());
                            TrackerProtocol.sourcesQueryResponse(outputStream, fileSources.get(id));
                            break;

                        case TrackerProtocol.UPDATE_QUERY:
                            TrackerProtocol.UpdateQueryData data = TrackerProtocol.getUpdateQueryData(inputStream);
                            clientEntry.lastUpdateQueryTime = System.currentTimeMillis();
                            clientEntry.port = data.port;
                            TimerTask oldTask = disconnectTasks.get(clientEntry);
                            if (oldTask != null) {
                                oldTask.cancel();
                            }
                            disconnectTasks.remove(clientEntry);
                            TimerTask task = new TimerTask() {
                                @Override
                                public void run() {
                                    try {
                                        client.close();
                                        clients.remove(clientEntry);
                                    } catch (IOException e) {
                                        e.printStackTrace();
                                    }
                                }
                            };
                            disconnectTasks.put(clientEntry, task);
                            disconnectTimer.schedule(task, 2 * TrackerProtocol.TIME_BETWEEN_UPDATE_QUERIES);
                            clients.add(clientEntry);
                            for (int fileId : data.fileIds) {
                                fileSources.putIfAbsent(fileId, ConcurrentHashMap.newKeySet());
                                fileSources.get(fileId).add(clientEntry);
                            }
                            TrackerProtocol.updateQueryResponse(outputStream, true);
                            break;

                        default:
                            throw new IllegalStateException("Unknown query type: " + type);
                    }
                }
            } catch (EOFException ignored) {
            } catch (Exception e) { // to print all the exceptions
                e.printStackTrace();
            }
        }
    }
}
