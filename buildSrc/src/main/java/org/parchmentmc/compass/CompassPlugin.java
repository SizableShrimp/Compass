package org.parchmentmc.compass;

import net.minecraftforge.srgutils.IMappingFile;
import okio.BufferedSink;
import okio.Okio;
import org.gradle.api.DefaultTask;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.logging.Logger;
import org.gradle.api.provider.Provider;
import org.gradle.api.tasks.TaskContainer;
import org.gradle.api.tasks.TaskProvider;
import org.parchmentmc.compass.storage.MappingDataBuilder;
import org.parchmentmc.compass.storage.MappingDataContainer;
import org.parchmentmc.compass.storage.io.ExplodedDataIO;
import org.parchmentmc.compass.tasks.DisplayMinecraftVersions;
import org.parchmentmc.compass.util.JSONUtil;
import org.parchmentmc.compass.util.MappingUtil;
import org.parchmentmc.compass.util.download.ManifestsDownloader;
import org.parchmentmc.compass.util.download.ObfuscationMapsDownloader;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;

public class CompassPlugin implements Plugin<Project> {
    public static final String COMPASS_GROUP = "compass";

    @Override
    public void apply(Project project) {
        project.getPlugins().apply("de.undercouch.download");
        final CompassExtension extension = project.getExtensions().create("compass", CompassExtension.class, project);
        final TaskContainer tasks = project.getTasks();

        final ManifestsDownloader manifests = new ManifestsDownloader(project);
        manifests.getLauncherManifestURL().set(extension.getLauncherManifestURL());
        manifests.getVersion().set(extension.getVersion());

        ObfuscationMapsDownloader obfuscationMaps = new ObfuscationMapsDownloader(project);
        obfuscationMaps.getVersionManifest().set(manifests.getVersionManifest());

        final TaskProvider<DisplayMinecraftVersions> displayMinecraftVersions = tasks.register("displayMinecraftVersions", DisplayMinecraftVersions.class);
        displayMinecraftVersions.configure(t -> {
            t.setGroup(COMPASS_GROUP);
            t.setDescription("Displays all known Minecraft versions.");
            t.getManifest().set(manifests.getLauncherManifest());
        });

        TaskProvider<DefaultTask> generateVersionBase = tasks.register("generateVersionBase", DefaultTask.class);
        generateVersionBase.configure(t -> {
            t.setGroup(COMPASS_GROUP);
            t.setDescription("Generates the base data for the active version to the staging directory.");
            t.doLast(_t -> {
                IMappingFile obfMap = obfuscationMaps.getObfuscationMap().get();
                // reversed because normally, obf map is [Moj -> Obf] (because it's a ProGuard log of the obf)
                MappingDataBuilder data = MappingUtil.constructPackageData(MappingUtil.createBuilderFrom(obfMap, true));

                File stagingDataDir = extension.getStagingData().get().getAsFile();

                try {
                    ExplodedDataIO.INSTANCE.write(data, stagingDataDir);
                } catch (IOException e) {
                    throw new UncheckedIOException("Failed to write base data for active version to staging directory", e);
                }
            });
        });

        TaskProvider<DefaultTask> promoteStagingToProduction = tasks.register("promoteStagingToProduction", DefaultTask.class);
        promoteStagingToProduction.configure(t -> {
            t.setGroup(COMPASS_GROUP);
            t.setDescription("Promotes the staging data to production.");
            t.doLast(_t -> {
                File stagingDataDir = extension.getStagingData().get().getAsFile();
                if (stagingDataDir.exists()) {
                    String[] list = stagingDataDir.list();
                    if (list != null && list.length == 0) return;
                } else {
                    return;
                }

                MappingDataContainer staging;
                try {
                    staging = ExplodedDataIO.INSTANCE.read(stagingDataDir.toPath());
                } catch (IOException e) {
                    throw new UncheckedIOException("Failed to read staging data for promotion", e);
                }
                try {
                    // noinspection ResultOfMethodCallIgnored
                    Files.walk(stagingDataDir.toPath())
                            .sorted(Comparator.reverseOrder())
                            .map(Path::toFile)
                            .forEach(File::delete);
                } catch (IOException e) {
                    t.getLogger().warn("Unable to delete staging data directory; continuing", e);
                }

                File prodDataDir = extension.getProductionData().get().getAsFile();
                try {
                    ExplodedDataIO.INSTANCE.write(staging, prodDataDir.toPath());
                } catch (IOException e) {
                    throw new UncheckedIOException("Failed to write promoted staging->production data", e);
                }
            });
        });

        DefaultTask writeExploded = tasks.create("writeExploded", DefaultTask.class);
        writeExploded.setGroup(COMPASS_GROUP);
        writeExploded.setDescription("temporary task; Writes out the combined obfuscation maps into exploded directories.");
        writeExploded.doLast(t -> {
            Provider<IMappingFile> obfMapProvider = obfuscationMaps.getObfuscationMap();
            try {
                Path output = extension.getProductionData().get().getAsFile().toPath();
                IMappingFile mojToObf = obfMapProvider.get();

                // getMapped() == obfuscated, getOriginal() == mojmap
                MappingDataContainer obf = MappingUtil.constructPackageData(MappingUtil.createBuilderFrom(mojToObf, true));
                MappingDataContainer moj = MappingUtil.constructPackageData(MappingUtil.createBuilderFrom(mojToObf, false));

                Path obfPath = output.resolve("obf");
                Path mojPath = output.resolve("moj");

                ExplodedDataIO.INSTANCE.write(obf, obfPath);
                ExplodedDataIO.INSTANCE.write(moj, mojPath);

                MappingDataContainer readObf = ExplodedDataIO.INSTANCE.read(obfPath);
                MappingDataContainer readMoj = ExplodedDataIO.INSTANCE.read(mojPath);

                try (BufferedSink sink = Okio.buffer(Okio.sink(output.resolve("input_obf.json")))) {
                    JSONUtil.MOSHI.adapter(MappingDataContainer.class).indent("  ").toJson(sink, obf);
                }
                try (BufferedSink sink = Okio.buffer(Okio.sink(output.resolve("input_moj.json")))) {
                    JSONUtil.MOSHI.adapter(MappingDataContainer.class).indent("  ").toJson(sink, moj);
                }

                try (BufferedSink sink = Okio.buffer(Okio.sink(output.resolve("output_obf.json")))) {
                    JSONUtil.MOSHI.adapter(MappingDataContainer.class).indent("  ").toJson(sink, readObf);
                }
                try (BufferedSink sink = Okio.buffer(Okio.sink(output.resolve("output_moj.json")))) {
                    JSONUtil.MOSHI.adapter(MappingDataContainer.class).indent("  ").toJson(sink, readMoj);
                }

                Logger logger = t.getLogger();
                if (obf.equals(readObf)) {
                    logger.lifecycle("Obfuscation: Input mapping data matches read mapping data output");
                } else {
                    logger.warn("Obfuscation: Input mapping data DOES NOT match read mapping data output");
                }
                if (moj.equals(readMoj)) {
                    logger.lifecycle("Mojmaps: Input mapping data matches read mapping data output");
                } else {
                    logger.warn("Mojmaps: Input mapping data DOES NOT match read mapping data output");
                }
            } catch (IOException e) {
                throw new AssertionError(e);
            }
        });
    }
}
