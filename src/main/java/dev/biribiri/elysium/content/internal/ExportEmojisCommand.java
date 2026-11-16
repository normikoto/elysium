package dev.biribiri.elysium.content.internal;

import org.springframework.beans.factory.annotation.Autowired;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;

import java.io.BufferedOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.Callable;

@Command(
        name = "export-emojis",
        mixinStandardHelpOptions = true,
        description = "Writes every custom emoji to a .zip that this server, Misskey, and Akkoma can import.")
class ExportEmojisCommand implements Callable<Integer> {

    private EmojiPacks packs;

    @Spec
    private CommandSpec spec;

    @Parameters(paramLabel = "FILE", description = "Where to write the .zip. It mustn't exist yet.")
    private Path file;

    @Autowired
    void wire(EmojiPacks packs) {
        this.packs = packs;
    }

    @Override
    public Integer call() throws Exception {
        if (Files.exists(file)) {
            spec.commandLine().getErr().println(file + " already exists");
            return 1;
        }
        try (var out = new BufferedOutputStream(Files.newOutputStream(file, StandardOpenOption.CREATE_NEW))) {
            packs.export(out);
        } catch (Exception e) {
            Files.deleteIfExists(file);
            throw e;
        }
        spec.commandLine().getOut().println("Wrote " + file);
        return 0;
    }
}
