package de.raindancer.core.data.store;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class DocumentsCommandPathTest {

    @Test
    @DisplayName("an exported file is named from the plugins folder, though the data folder is relative and the file absolute")
    void relativeDataFolderAbsoluteFile() {
        Path dataFolder = Path.of("plugins/RainsCore");
        Path written = dataFolder.toAbsolutePath().resolve("edit/network/RainsCore/changelog.yml");
        assertThat(DocumentsCommand.shown(dataFolder, written).toString())
                .isEqualTo(Path.of("RainsCore/edit/network/RainsCore/changelog.yml").toString());
    }

    @Test
    @DisplayName("the same when both are relative, as they were when it was written")
    void bothRelative() {
        Path dataFolder = Path.of("plugins/RainsCore");
        assertThat(DocumentsCommand.shown(dataFolder, dataFolder.resolve("edit/x.yml")).toString())
                .isEqualTo(Path.of("RainsCore/edit/x.yml").toString());
    }
}
