package de.raindancer.core.world.model;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/** The model engine's pack as a folder Core contributes to the server's one pack. File work: off the server threads. */
final class PackFolder {

    private PackFolder() {
    }

    /** Replaces what is in {@code folder} by {@code files} (pack-relative paths). @return how many were written */
    static int write(Map<String, byte[]> files, Path folder) throws IOException {
        Path root = folder.toAbsolutePath().normalize();
        for (String name : files.keySet()) {
            if (!root.resolve(name).normalize().startsWith(root)) {
                throw new IOException(name + " would land outside " + root);
            }
        }
        if (Files.isDirectory(root)) {
            try (Stream<Path> old = Files.walk(root)) {
                List<Path> gone = old.sorted(Comparator.reverseOrder()).filter(path -> !path.equals(root)).toList();
                for (Path path : gone) {
                    Files.delete(path);
                }
            }
        }
        Files.createDirectories(root);
        for (Map.Entry<String, byte[]> file : files.entrySet()) {
            Path target = root.resolve(file.getKey()).normalize();
            Files.createDirectories(target.getParent());
            Files.write(target, file.getValue());
        }
        return files.size();
    }

    /** Every file of a pack zip, by its path inside. */
    static Map<String, byte[]> readZip(Path zip) throws IOException {
        Map<String, byte[]> files = new LinkedHashMap<>();
        try (InputStream in = Files.newInputStream(zip); ZipInputStream entries = new ZipInputStream(in)) {
            for (ZipEntry entry = entries.getNextEntry(); entry != null; entry = entries.getNextEntry()) {
                if (!entry.isDirectory()) {
                    files.put(entry.getName(), entries.readAllBytes());
                }
            }
        }
        return files;
    }
}
