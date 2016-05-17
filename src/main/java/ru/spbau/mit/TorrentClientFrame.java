package ru.spbau.mit;

import javax.swing.*;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.TableCellRenderer;
import java.awt.*;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.IOException;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * Created by Сева on 15.05.2016.
 */

// CHECKSTYLE.OFF: MagicNumber
public class TorrentClientFrame extends JFrame {
    private final JMenuItem getFilesItem;
    private final JMenuItem addFileItem;
    private final TorrentClient client;

    private class TorrentTableModel extends AbstractTableModel {
        private final String[] columnNames = {"Name", "Size", "Progress"};
        private final Timer timer = new Timer(100, (event) -> {
            fireTableDataChanged();
        });

        TorrentTableModel() {
            super();
            timer.start();
        }

        @Override
        public String getColumnName(int column) {
            return columnNames[column];
        }

        @Override
        public int getRowCount() {
            return client.getFilesInfo().size();
        }

        @Override
        public int getColumnCount() {
            return columnNames.length;
        }

        @Override
        public Object getValueAt(int rowIndex, int columnIndex) {
            if (rowIndex >= client.getFilesInfo().size()) {
                return null;
            }
            switch (columnIndex) {
                case 0:
                    return client.getFilesInfo().get(rowIndex).fileName;
                case 1:
                    return client.getFilesInfo().get(rowIndex).fileSize;
                case 2:
                    return client.getFilesInfo().get(rowIndex).downloadedPercentage;
                default:
                    return null;
            }
        }
    }

    private class SizeRenderer extends JLabel implements TableCellRenderer {
        private static final long KILOBYTE = 1024;
        private static final long MEGABYTE = 1024 * 1024;
        private static final long GIGABYTE = 1024 * 1024 * 1024;

        @Override
        public Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected,
                                                       boolean hasFocus, int row, int column) {
            long size = (Long) value;
            if (size < KILOBYTE * 2) {
                setText(Long.toString(size) + "B");
            } else if (size < MEGABYTE * 2) {
                setText(Long.toString(size / KILOBYTE) + "KB");
            } else if (size < GIGABYTE * 2) {
                setText(Long.toString(size / MEGABYTE) + "MB");
            } else {
                setText(Long.toString(size / GIGABYTE) + "GB");
            }
            return this;
        }
    }

    private class ProgressRenderer extends JProgressBar implements TableCellRenderer {
        static final int MAX_VALUE = 1000;

        ProgressRenderer() {
            super(0, MAX_VALUE);
        }

        @Override
        public Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected,
                                                       boolean hasFocus, int row, int column) {
            setValue((int) (MAX_VALUE * (Double) value));
            setStringPainted(true);
            setString(String.format("%.1f%%", 100 * (Double) value));
            return this;
        }
    }

    TorrentClientFrame() throws IOException {
        super("TorrentClient");

        client = new TorrentClient(Paths.get(".", "downloads"), "");
        this.addWindowListener(new WindowAdapter(){
            public void windowClosing(WindowEvent e) {
                try {
                    client.close();
                } catch (IOException e1) {
                    e1.printStackTrace();
                }
            }
        });
        JTable table = new JTable(new TorrentTableModel());
        table.getColumn("Progress").setCellRenderer(new ProgressRenderer());
        table.getColumn("Size").setCellRenderer(new SizeRenderer());
        table.getColumnModel().getColumn(1).setMaxWidth(150);
        table.setFillsViewportHeight(true);

        JScrollPane pane = new JScrollPane(table);
        add(pane);
        setDefaultCloseOperation(WindowConstants.EXIT_ON_CLOSE);
        setSize(1200, 600);

        JMenu menu = new JMenu("Menu");

        JMenuItem connectToServerItem = new JMenuItem("Connect to server");
        connectToServerItem.addActionListener((event) -> connectToServer());
        menu.add(connectToServerItem);

        getFilesItem = new JMenuItem("Get files");
        getFilesItem.addActionListener((event) -> getFiles());
        menu.add(getFilesItem);
        getFilesItem.setEnabled(false);

        addFileItem = new JMenuItem("Add file");
        addFileItem.addActionListener((event) -> addFile());
        menu.add(addFileItem);
        addFileItem.setEnabled(false);

        JMenuItem startDownloadItem = new JMenuItem("Start download");
        startDownloadItem.addActionListener((event) -> startDownload());
        menu.add(startDownloadItem);

        JMenuBar menuBar = new JMenuBar();
        menuBar.add(menu);
        setJMenuBar(menuBar);
    }

    private void startDownload() {
        try {
            client.startPeering(0);
        } catch (IOException e) {
            System.err.println("An error occurred while peering:");
            e.printStackTrace();
        }
    }

    private void addFile() {
        JFileChooser chooser = new JFileChooser();
        if (chooser.showDialog(null, "Add file") == JFileChooser.APPROVE_OPTION) {
            try {
                client.addFile(chooser.getSelectedFile().toPath());
            } catch (IOException e) {
                System.err.println("Failed to add file");
                e.printStackTrace();
            }
        }
        repaint();
    }

    private void connectToServer() {
        String ip = (String) JOptionPane.showInputDialog(null, null, "Enter server IP", JOptionPane.PLAIN_MESSAGE,
                null, null, null);
        client.setServerAddress(ip);
        getFilesItem.setEnabled(true);
        addFileItem.setEnabled(true);
    }

    private void getFiles() {
        List<TrackerProtocol.TrackerFileEntry> files;
        try {
            files = client.filesOnServer();
        } catch (IOException e) {
            System.err.println("Unable to get files list from server");
            e.printStackTrace();
            return;
        }

        GetFilesTableModel model = new GetFilesTableModel(files);
        JTable table = new JTable(model);
        table.getColumn("Size").setCellRenderer(new SizeRenderer());
        table.getColumnModel().getColumn(1).setMaxWidth(150);
        table.getColumnModel().getColumn(2).setMaxWidth(100);
        JScrollPane pane = new JScrollPane(table);
        JOptionPane.showOptionDialog(this, pane, "Select files", JOptionPane.DEFAULT_OPTION,
                JOptionPane.PLAIN_MESSAGE, null, null, null);
        for (TrackerProtocol.TrackerFileEntry selected: model.getSelectedFiles()) {
            try {
                client.getFile(selected.id);
            } catch (IOException e) {
                System.err.println("Error getting file");
                e.printStackTrace();
            }
        }
        repaint();
    }

    private class GetFilesTableModel extends AbstractTableModel {
        private final List<TrackerProtocol.TrackerFileEntry> files;
        private final String[] columnNames = {"Filename", "Size", "Download"};
        private final boolean[] selected;

        GetFilesTableModel(List<TrackerProtocol.TrackerFileEntry> files) {
            this.files = files;
            selected = new boolean[files.size()];
        }

        @Override
        public int getRowCount() {
            return files.size();
        }

        @Override
        public int getColumnCount() {
            return columnNames.length;
        }

        @Override
        public Object getValueAt(int rowIndex, int columnIndex) {
            if (rowIndex >= files.size()) {
                return null;
            }
            switch (columnIndex) {
                case 0:
                    return files.get(rowIndex).fileName;
                case 1:
                    return files.get(rowIndex).size;
                case 2:
                    return selected[rowIndex];
                default:
                    return null;
            }
        }

        @Override
        public boolean isCellEditable(int rowIndex, int columnIndex) {
            return columnIndex == 2;
        }

        @Override
        public void setValueAt(Object aValue, int rowIndex, int columnIndex) {
            if (columnIndex == 2) {
                selected[rowIndex] = (Boolean) aValue;
            }
        }

        @Override
        public String getColumnName(int column) {
            return columnNames[column];
        }

        @Override
        public Class<?> getColumnClass(int columnIndex) {
            if (columnIndex == 2) {
                return Boolean.class;
            }
            return super.getColumnClass(columnIndex);
        }

        public List<TrackerProtocol.TrackerFileEntry> getSelectedFiles() {
            List<TrackerProtocol.TrackerFileEntry> result = new ArrayList<>();
            for (int i = 0; i < files.size(); i++) {
                if (selected[i]) {
                    result.add(files.get(i));
                }
            }
            return result;
        }
    }
}
// CHECKSTYLE.ON: MagicNumber
