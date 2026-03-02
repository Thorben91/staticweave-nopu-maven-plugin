package net.unit8.maven.plugins;

import org.apache.maven.artifact.Artifact;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.*;
import org.apache.maven.project.MavenProject;
import org.eclipse.persistence.config.PersistenceUnitProperties;
import org.eclipse.persistence.internal.jpa.EntityManagerSetupImpl;
import org.eclipse.persistence.internal.jpa.StaticWeaveInfo;
import org.eclipse.persistence.internal.jpa.deployment.ArchiveFactoryImpl;
import org.eclipse.persistence.internal.jpa.deployment.PersistenceUnitProcessor;
import org.eclipse.persistence.internal.jpa.deployment.SEPersistenceUnitInfo;
import org.eclipse.persistence.internal.jpa.weaving.StaticWeaveDirectoryOutputHandler;
import org.eclipse.persistence.jpa.Archive;
import org.eclipse.persistence.logging.AbstractSessionLog;
import org.eclipse.persistence.logging.SessionLog;

import jakarta.persistence.spi.ClassTransformer;
import jakarta.persistence.spi.TransformerException;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.*;
import java.util.jar.JarEntry;
import java.util.stream.Collectors;

import static org.eclipse.persistence.tools.weaving.jpa.StaticWeaveProcessor.getDirectoryFromEntryName;

/**
 * Maven Mojo that applies EclipseLink static weaving to {@code @Entity} classes
 * at the {@code process-classes} phase without requiring a {@code persistence.xml}.
 *
 * <p>Specify the packages to scan via the {@code packages} parameter. All classes
 * annotated with {@link jakarta.persistence.Entity} that are found on the filesystem
 * under {@code target/classes} will have their bytecode woven in-place.</p>
 */
@Mojo(name = "weave",
        defaultPhase = LifecyclePhase.PROCESS_CLASSES,
        requiresDependencyResolution = ResolutionScope.COMPILE)
public class WeaveMojo extends AbstractMojo {
    /**
     * Directory containing compiled classes to weave (source).
     * Defaults to {@code ${project.build.outputDirectory}}.
     */
    @Parameter(defaultValue = "${project.build.outputDirectory}", readonly = true, required = true)
    protected String source;

    /**
     * Directory to write the woven class files (target).
     * Defaults to {@code ${project.build.outputDirectory}}, so weaving is in-place.
     */
    @Parameter(defaultValue = "${project.build.outputDirectory}", readonly = true, required = true)
    protected String target;

    /**
     * Package names to scan for {@code @Entity} classes.
     * At least one package must be specified.
     */
    @Parameter(required = true)
    protected List<String> packages;

    /**
     * EclipseLink log level for the weaving process.
     * Valid values: {@code OFF}, {@code SEVERE}, {@code WARNING}, {@code INFO},
     * {@code CONFIG}, {@code FINE}, {@code FINER}, {@code FINEST}, {@code ALL}.
     * Can also be set via {@code -Dweave.logLevel=FINE} on the command line.
     */
    @Parameter(property = "weave.logLevel", defaultValue = "ALL")
    private String logLevel;

    /** Creates a new {@code WeaveMojo} instance. */
    public WeaveMojo() {
    }

    private static final int NUMBER_OF_BYTES = 1024;
    private ClassLoader classLoader;

    @Component(role = MavenProject.class)
    private MavenProject project;

    public void execute() throws MojoExecutionException, MojoFailureException {
        try {
            List<URL> classpath = buildClassPath();
            classpath.add(0, new File(source).toURI().toURL());
            try (URLClassLoader cl = new URLClassLoader(
                    classpath.toArray(new URL[]{}),
                    Thread.currentThread().getContextClassLoader())) {
                classLoader = cl;
                weave();
            }
        } catch (IOException | URISyntaxException e) {
            throw new MojoExecutionException("The error occurs at weaving", e);
        }
    }

    /**
     * Performs the actual static weaving.
     * Iterates over all entries in the source archive and applies the EclipseLink
     * {@link jakarta.persistence.spi.ClassTransformer} to each {@code .class} file.
     *
     * @throws IOException        if an I/O error occurs reading or writing class files
     * @throws URISyntaxException if the source or target path cannot be converted to a URI
     */
    protected void weave() throws IOException, URISyntaxException {
        URL sourceUrl = new File(source).toURI().toURL();
        URL targetUrl = new File(target).toURI().toURL();

        StaticWeaveInfo info = new StaticWeaveInfo(new LogWriter(getLog()), getLogLevel());
        SEPersistenceUnitInfo unitInfo = createPersistenceUnitInfo();
        Map<String, Object> emptyMap = new HashMap<>(0);
        // build class transformer.
        String puName = unitInfo.getPersistenceUnitName();
        String sessionName = (String) unitInfo.getProperties().get(PersistenceUnitProperties.SESSION_NAME);
        if (sessionName == null) {
            sessionName = puName;
        }
        EntityManagerSetupImpl emSetupImpl = new EntityManagerSetupImpl(puName, sessionName);
        // indicates that predeploy is used for static weaving, also passes logging parameters
        emSetupImpl.setStaticWeaveInfo(info);
        ClassTransformer transformer = emSetupImpl.predeploy(unitInfo, emptyMap);
        List<ClassTransformer> classTransformers = new ArrayList<>();
        classTransformers.add(transformer);

        StaticWeaveDirectoryOutputHandler swoh = new StaticWeaveDirectoryOutputHandler(sourceUrl, targetUrl);
        Archive sourceArchive = (new ArchiveFactoryImpl()).createArchive(sourceUrl, null, null);
        if (sourceArchive != null) {
            try {
                Iterator<String> entries = sourceArchive.getEntries();
                while (entries.hasNext()) {
                    String entryName = entries.next();
                    InputStream entryInputStream = sourceArchive.getEntry(entryName);

                    // Add a directory entry
                    swoh.addDirEntry(getDirectoryFromEntryName(entryName));

                    // Add a regular entry
                    JarEntry newEntry = new JarEntry(entryName);

                    // Ignore non-class files.
                    if (!entryName.endsWith(".class") || "module-info.class".equals(entryName)) {
                        swoh.addEntry(entryInputStream, newEntry);
                        continue;
                    }

                    String className = PersistenceUnitProcessor.buildClassNameFromEntryString(entryName);
                    byte[] originalClassBytes;
                    byte[] transferredClassBytes;
                    try {
                        Class<?> thisClass = classLoader.loadClass(className);
                        // If the class is not in the classpath, we simply copy the entry
                        // to the target(no weaving).
                        if (thisClass == null) {
                            swoh.addEntry(entryInputStream, newEntry);
                            continue;
                        }

                        // Try to read the loaded class bytes, the class bytes is required for
                        // classtransformer to perform transfer. Simply copy entry to the target(no weaving)
                        // if the class bytes can't be read.
                        InputStream is = classLoader.getResourceAsStream(entryName);
                        if (is != null) {
                            try (ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
                                byte[] bytes = new byte[NUMBER_OF_BYTES];
                                int bytesRead = is.read(bytes, 0, NUMBER_OF_BYTES);
                                while (bytesRead >= 0) {
                                    baos.write(bytes, 0, bytesRead);
                                    bytesRead = is.read(bytes, 0, NUMBER_OF_BYTES);
                                }
                                originalClassBytes = baos.toByteArray();
                            } finally {
                                is.close();
                            }
                        } else {
                            swoh.addEntry(entryInputStream, newEntry);
                            continue;
                        }

                        // If everything is OK so far, we perform the weaving. we need three parameters in order to
                        // class to perform weaving for that class, the class name,the class object and class bytes.
                        transferredClassBytes = transform(classTransformers, className.replace('.', '/'), thisClass, originalClassBytes);

                        // If transferredClassBytes is null means the class does not get woven.
                        if (transferredClassBytes != null) {
                            swoh.addEntry(newEntry, transferredClassBytes);
                        } else {
                            swoh.addEntry(entryInputStream, newEntry);
                        }
                    } catch (TransformerException | ClassNotFoundException e) {
                        AbstractSessionLog.getLog().logThrowable(AbstractSessionLog.WARNING, AbstractSessionLog.WEAVER, e);
                        // Anything went wrong, we need log a warning message, copy the entry to the target and
                        // process next entry.
                        swoh.addEntry(entryInputStream, newEntry);
                    } finally {
                        // Need close the inputstream for current entry before processing next one.
                        entryInputStream.close();
                    }
                }
            } finally {
                sourceArchive.close();
                swoh.closeOutputStream();
            }
        }
    }

    byte[] transform(List<ClassTransformer> classTransformers, String originalClassName, Class<?> originalClass, byte[] originalClassBytes) throws TransformerException {
        byte[] newClassBytes = null;
        for (ClassTransformer ct : classTransformers) {
            newClassBytes = ct.transform(classLoader, originalClassName, originalClass, null, originalClassBytes);
            if (newClassBytes != null) {
                break;
            }
        }
        return newClassBytes;
    }

    SEPersistenceUnitInfo createPersistenceUnitInfo() {
        SEPersistenceUnitInfo pu = new SEPersistenceUnitInfo();
        pu.setPersistenceUnitName("for-weaving");
        pu.setClassLoader(classLoader);
        pu.setPersistenceUnitRootUrl(getClass().getResource("/"));
        ManagedClassScanner managedClassScanner = new ManagedClassScanner(classLoader, getLog());
        final List<Class<?>> managedClasses = managedClassScanner.scan(packages);
        List<URL> jarFiles = managedClasses.stream()
                .map(cls -> cls.getResource("/"))
                .distinct()
                .collect(Collectors.toList());
        pu.setJarFileUrls(jarFiles);

        List<String> managedClassNames = managedClasses.stream()
                .map(Class::getName)
                .collect(Collectors.toList());
        getLog().info("Entity:" + managedClasses);
        pu.setManagedClassNames(managedClassNames);
        pu.setExcludeUnlistedClasses(false);
        return pu;
    }

    private List<URL> buildClassPath() throws MalformedURLException {
        List<URL> urls = new ArrayList<>();

        if (project == null) {

            getLog().error(
                    "MavenProject is empty, unable to build ClassPath. No Models can be woven.");

        } else {
            Set<Artifact> artifacts = project.getArtifacts();
            for (Artifact a : artifacts) {
                urls.add(a.getFile().toURI().toURL());
            }

        }

        return urls;
    }
    /**
     * Sets the EclipseLink log level by name (case-insensitive).
     * If an unknown level is supplied, the current level is left unchanged and
     * an error is logged.
     *
     * @param logLevel the level name, e.g. {@code "FINE"} or {@code "WARNING"}
     */
    public void setLogLevel(String logLevel) {
        if (SessionLog.OFF_LABEL.equalsIgnoreCase(logLevel)
                || SessionLog.SEVERE_LABEL.equalsIgnoreCase(logLevel)
                || SessionLog.WARNING_LABEL.equalsIgnoreCase(logLevel)
                || SessionLog.INFO_LABEL.equalsIgnoreCase(logLevel)
                || SessionLog.CONFIG_LABEL.equalsIgnoreCase(logLevel)
                || SessionLog.FINE_LABEL.equalsIgnoreCase(logLevel)
                || SessionLog.FINER_LABEL.equalsIgnoreCase(logLevel)
                || SessionLog.FINEST_LABEL.equalsIgnoreCase(logLevel)
                || SessionLog.ALL_LABEL.equalsIgnoreCase(logLevel)) {
            this.logLevel = logLevel.toUpperCase();
        } else {
            getLog().error(
                    "Unknown log level: " + logLevel
                            + " default LogLevel is used.");
        }
    }

    private int getLogLevel() {
        return AbstractSessionLog.translateStringToLoggingLevel(logLevel);
    }
}
