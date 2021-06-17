package org.parchmentmc.compass.tasks;

import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;
import org.parchmentmc.compass.CompassPlugin;
import org.parchmentmc.compass.util.download.BlackstoneDownloader;
import org.parchmentmc.feather.mapping.MappingDataBuilder;
import org.parchmentmc.feather.mapping.MappingDataContainer;
import org.parchmentmc.feather.metadata.ClassMetadata;
import org.parchmentmc.feather.metadata.MethodMetadata;
import org.parchmentmc.feather.metadata.SourceMetadata;

import java.io.IOException;

public abstract class GenerateSanitizedExport extends GenerateExport {
    public GenerateSanitizedExport() {
        getParameterPrefix().convention("p");
        getSkipLambdaParameters().convention(Boolean.TRUE);
        getSkipAnonymousClassParameters().convention(Boolean.TRUE);
        getIntermediate().convention("official"); // Required for us to work.
        getUseBlackstone().convention(Boolean.FALSE);
    }

    @Override
    protected MappingDataContainer modifyData(MappingDataContainer container) throws IOException {
        final String paramPrefix = getParameterPrefix().get();
        final MappingDataBuilder builder = MappingDataBuilder.copyOf(container);

        final SourceMetadata metadata;
        if (getUseBlackstone().get()) {
            final BlackstoneDownloader blackstoneDownloader = getProject().getPlugins()
                    .getPlugin(CompassPlugin.class).getBlackstoneDownloader();
            metadata = blackstoneDownloader.retrieveMetadata();
        } else {
            metadata = null;
        }

        final boolean skipLambdas = getSkipLambdaParameters().get();
        final boolean skipAnonClasses = getSkipAnonymousClassParameters().get();

        builder.getClasses().forEach(clsData -> {
            final ClassMetadata clsMeta = metadata != null ? metadata.getClasses().stream()
                    .filter(s -> s.getName().getMojangName().orElse("").contentEquals(clsData.getName()))
                    .findFirst().orElse(null) : null;

            boolean anonClass = withinAnonymousClass(clsData.getName());

            clsData.getMethods().forEach(methodData -> {
                final MethodMetadata methodMeta = clsMeta != null ? clsMeta.getMethods().stream()
                        .filter(s -> s.getName().getMojangName().orElse("").contentEquals(methodData.getName())
                                && s.getDescriptor().getMojangName().orElse("").contentEquals(methodData.getDescriptor()))
                        .findFirst().orElse(null) : null;

                // Simple heuristic; if it starts with `lambda$`, it's a lambda.
                boolean lambda = (methodMeta != null && methodMeta.isLambda())
                        || (methodMeta == null && methodData.getName().startsWith("lambda$"));

                methodData.getParameters().forEach(paramData -> {
                    if (paramData.getName() != null) {
                        if ((skipAnonClasses && anonClass) || (skipLambdas && lambda)) {
                            paramData.setName(null);
                        } else {
                            paramData.setName(paramPrefix + capitalize(paramData.getName()));
                        }
                    }
                });
            });
        });

        return builder;
    }

    @Input
    public abstract Property<String> getParameterPrefix();

    @Input
    public abstract Property<Boolean> getSkipLambdaParameters();

    @Input
    public abstract Property<Boolean> getSkipAnonymousClassParameters();

    @Input
    public abstract Property<Boolean> getUseBlackstone();

    private static String capitalize(String input) {
        return Character.toTitleCase(input.charAt(0)) + input.substring(1);
    }

    private static boolean withinAnonymousClass(String className) {
        for (String name : className.split("\\$")) {
            /*
             * Anonymous classes have a simple heuristic for detection
             * According to JLS, class names must be defined by `[letter][letter or digit]*` identifiers
             * So, it stands to reason that if a class name starts with a digit, then it is an anonymous (or at least
             * synthetic) class.
             */
            int firstChar = name.codePointAt(0);
            // See Character#isJavaIdentifierPart
            if (Character.getType(firstChar) == Character.LETTER_NUMBER) {
                return true;
            }
        }
        return false;
    }
}
