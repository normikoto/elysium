package dev.biribiri.elysium.content.internal;

import dev.biribiri.elysium.content.InvalidCustomEmojiException;
import org.springframework.beans.factory.annotation.Autowired;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Callable;

@Command(
        name = "import-emojis",
        mixinStandardHelpOptions = true,
        description = "Imports a pack of custom emoji: a Misskey export, a Pleroma or Akkoma pack, or a .zip or .tar.gz "
                + "of images named after their files. Unlike the admin page, it has no limit on the pack's size, but "
                + "each image still has to be within elysium.custom-emojis.max-image-size.")
class ImportEmojisCommand implements Callable<Integer> {

    private EmojiPacks packs;

    @Spec
    private CommandSpec spec;

    @Parameters(paramLabel = "FILE", description = "The pack's .zip, .tar.gz, or .tar file.")
    private Path file;

    @Option(names = "--category", paramLabel = "NAME",
            description = "Puts every emoji in this category. Defaults to Misskey's categories, or the pack's name.")
    private String category;

    @Option(names = "--replace",
            description = "Replaces emoji that already exist, rather than skipping them. Posts and reactions that use "
                    + "them keep them.")
    private boolean replace;

    @Autowired
    void wire(EmojiPacks packs) {
        this.packs = packs;
    }

    @Override
    public Integer call() throws Exception {
        var out = spec.commandLine().getOut();
        var err = spec.commandLine().getErr();
        if (!Files.isRegularFile(file)) {
            err.println("There's no file at " + file);
            return 1;
        }
        EmojiPacks.ImportResult result;
        try {
            result = packs.importPack(file, file.getFileName().toString(), category, replace, false);
        } catch (InvalidCustomEmojiException e) {
            err.println(e.getMessage());
            return 1;
        }
        out.printf("Added %d, replaced %d, skipped %d that already existed, and couldn't import %d%n",
                result.added().size(), result.replaced().size(), result.skipped().size(), result.failed().size());
        if (!result.skipped().isEmpty()) {
            out.println("Skipped: " + String.join(", ", result.skipped()));
        }
        result.failed().forEach(problem -> out.println(problem.what() + ": " + problem.reason()));
        return 0;
    }
}
