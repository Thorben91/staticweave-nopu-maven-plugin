package net.unit8.maven.plugins;

import org.apache.maven.plugin.logging.Log;

import jakarta.persistence.Entity;
import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Scans filesystem directories for classes annotated with {@link jakarta.persistence.Entity}.
 *
 * <p>Resources are located via {@link ClassLoader#getResources(String)}. Only
 * {@code file:} URLs are descended into; entries inside JAR files are ignored
 * because this plugin is designed to weave the project's own compiled classes.</p>
 */
public class ManagedClassScanner {
    private final ClassLoader loader;
    private final Log logger;

    /**
     * Creates a new scanner.
     *
     * @param loader the class loader used to locate and load classes
     * @param logger the Maven log to write warnings to
     */
    public ManagedClassScanner(ClassLoader loader, Log logger) {
        this.loader = loader;
        this.logger = logger;
    }

    /**
     * Scans multiple packages and returns a deduplicated list of {@code @Entity} classes.
     *
     * @param packages package names to scan
     * @return distinct list of entity classes found across all packages
     */
    public List<Class<?>> scan(List<String> packages) {
        return packages.stream()
                .flatMap(p -> scanPackage(p).stream())
                .distinct()
                .collect(Collectors.toList());
    }

    /**
     * Scans a single package for {@code @Entity}-annotated classes.
     *
     * @param aPackage the package name to scan (e.g. {@code "com.example.entity"})
     * @return list of entity classes found in the package
     * @throws UncheckedIOException if an I/O error occurs while locating resources
     */
    public List<Class<?>> scanPackage(String aPackage) throws UncheckedIOException {
        List<Class<?>> managedClasses = new ArrayList<>();
        try {
            Enumeration<URL> resources = loader.getResources(aPackage.replace('.', '/'));
            List<File> dirs = new ArrayList<>();
            while (resources.hasMoreElements()) {
                URL resource = resources.nextElement();
                dirs.add(new File(resource.getFile()));
            }

            for (File directory : dirs) {
                try {
                    findClasses(directory, aPackage).stream()
                            .filter(c -> c.getAnnotation(Entity.class) != null)
                            .forEach(managedClasses::add);
                } catch (ClassNotFoundException e) {
                    logger.warn("Failed to load classes from directory: " + directory + " - " + e.getMessage());
                }
            }
            return managedClasses;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    List<Class<?>> findClasses(File directory, String packageName) throws ClassNotFoundException {
        List<Class<?>> classes = new ArrayList<>();
        if (!directory.exists()) {
            return classes;
        }
        File[] files = directory.listFiles();
        if (files == null) return classes;

        for (File file : files) {
            if (file.isDirectory()) {
                if (file.getName().contains(".")) {
                    throw new IllegalStateException("Directory name must not contain a dot: " + file.getName());
                }
                classes.addAll(findClasses(file, packageName + "." + file.getName()));
            } else if (file.getName().endsWith(".class")) {
                String className = packageName + '.' + file.getName().substring(0, file.getName().length() - 6);
                classes.add(Class.forName(className, true, loader));
            }
        }
        return classes;
    }

}
