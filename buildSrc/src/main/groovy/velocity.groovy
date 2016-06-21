import org.apache.velocity.VelocityContext;
import org.apache.velocity.app.VelocityEngine;
import org.apache.velocity.runtime.log.SystemLogChute;
import org.codehaus.groovy.runtime.DefaultGroovyMethods;
import org.gradle.api.GradleException;
import org.gradle.api.file.EmptyFileVisitor;
import org.gradle.api.file.FileCollection;
import org.gradle.api.file.FileTree;
import org.gradle.api.file.FileVisitDetails
import org.gradle.api.file.RelativePath;
import org.gradle.api.file.SourceDirectorySet;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputFiles;
import org.gradle.api.tasks.Optional;
import org.gradle.api.tasks.OutputDirectory;
import org.gradle.api.tasks.SourceTask;
import org.gradle.api.tasks.TaskAction;

/**
 * A bare velocity task.
 *
 * You may use this to do arbitrary velocity processing without
 * necessarily applying the plugin.
 *
 * @author shevek
 */
public class VelocityTask extends SourceTask {

    private static interface Collector {

        public void accept(File dir);
    }

    private static class IncludePathCollector implements Collector {

        private final StringBuilder out = new StringBuilder();

        @Override
        public void accept(File dir) {
            if (out.length() > 0)
                out.append(", ");
            out.append(dir.getAbsolutePath());
        }
    }

    private class IncludeFileCollector implements Collector {

        private FileCollection out = getProject().files();

        @Override
        public void accept(File dir) {
            out = out.plus(getProject().fileTree(dir));
        }
    }

    private File outputDir;

    private List<File> includeDirs;
    private Map<String, Object> contextValues;

    @OutputDirectory
       // Not @Optional
    public File getOutputDir() {
        return outputDir;
    }

    public void setOutputDir(File outputDir) {
        this.outputDir = outputDir;
    }

    @Input
    @Optional
    public List<File> getIncludeDirs() {
        return includeDirs;
    }

    public void setIncludeDirs(List<File> includeDirs) {
        this.includeDirs = includeDirs;
    }

    @Input
    @Optional
    public Map<String, Object> getContextValues() {
        return contextValues;
    }

    public void setContextValues(Map<String, Object> contextValues) {
        this.contextValues = contextValues;
    }

    private void setProperty(VelocityEngine engine, String name, Object value) {
        getLogger().info("VelocityEngine property: " + name + " = " + value);
        engine.setProperty(name, value);
    }

    private void collectDir(Collector collector, File dir) {
        getLogger().info("Collecting dir " + dir);
        collector.accept(dir);
    }

    private void collectDirs(Collector collector, Iterable<File> dirs) {
        if (dirs != null)
            for (File dir : dirs)
                collectDir(collector, dir);
    }

    private void collectUnknown(Collector collector, Iterable<Object> sources) {
        for (Object source : sources) {
            getLogger().info("Attepmting to collect " + source.getClass() + ":" + source);
            if (source instanceof File)
                collectDir(collector, (File) source);
            else if (source instanceof SourceDirectorySet)
                collectDirs(collector, ((SourceDirectorySet) source).getSrcDirs());
            // I wish we could introspect CompositeFileTree.
        }
    }

    @InputFiles
       // Not @Optional
    private FileCollection getIncludeFiles() {
        IncludeFileCollector collector = new IncludeFileCollector();
        collectUnknown(collector, source);
        collectDirs(collector, getIncludeDirs());
        for (File file : collector.out)
            getLogger().info("Including " + file);
        return collector.out;
    }

    @TaskAction
    public void runVelocity() throws Exception {
        final FileTree inputFiles = getSource();
        final File outputDir = getOutputDir();

        DefaultGroovyMethods.deleteDir(outputDir);
        outputDir.mkdirs();

        final VelocityEngine engine = new VelocityEngine();
        setProperty(engine, VelocityEngine.RUNTIME_LOG_LOGSYSTEM_CLASS, SystemLogChute.class.getName());
        setProperty(engine, VelocityEngine.RESOURCE_LOADER, "file");
        setProperty(engine, VelocityEngine.FILE_RESOURCE_LOADER_CACHE, "true");
        // FILE_RESOURCE_LOADER_PATH actually takes a comma separated list.
        IncludePathCollector collector = new IncludePathCollector();
        collectUnknown(collector, source);
        collectDirs(collector, getIncludeDirs());
        setProperty(engine, VelocityEngine.FILE_RESOURCE_LOADER_PATH, collector.out.toString());

        inputFiles.visit(new EmptyFileVisitor() {
            @Override
            public void visitFile(FileVisitDetails fvd) {
                try {
                    def path = fvd.getRelativePath()
                    path = path.replaceLastName(path.getLastName().replaceFirst("\\.vm\$", ""))
                    File outputFile = path.getFile(outputDir);
                    if (getLogger().isDebugEnabled())
                        getLogger().debug("Preprocessing " + fvd.getFile() + " -> " + outputFile);
                    VelocityContext context = new VelocityContext();
                    Map<String, Object> contextValues = getContextValues();
                    if (contextValues != null)
                        for (Map.Entry<String, Object> e : contextValues.entrySet())
                            context.put(e.getKey(), e.getValue());
                    context.put("project", getProject());
                    context.put("package", DefaultGroovyMethods.join(fvd.getRelativePath().getParent().getSegments(), "."));
                    context.put("class", fvd.getRelativePath().getLastName().replaceFirst("\\.java\$", ""));

                    FileReader reader = new FileReader(fvd.getFile());
                    try {
                        outputFile.getParentFile().mkdirs();
                        System.out.println("Writing to ${outputFile.path}")
                        FileWriter writer = new FileWriter(outputFile);
                        try {
                            engine.evaluate(context, writer, fvd.getRelativePath().toString(), reader);
                        } finally {
                            writer.close();
                        }
                    } finally {
                        reader.close();
                    }
                } catch (IOException e) {
                    throw new GradleException("Failed to process " + fvd, e);
                }
            }
        });
    }
}