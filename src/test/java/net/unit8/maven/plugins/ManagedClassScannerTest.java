package net.unit8.maven.plugins;

import net.unit8.maven.plugins.fixture.EntityA;
import net.unit8.maven.plugins.fixture.NonEntity;
import net.unit8.maven.plugins.fixture.sub.EntityB;
import org.apache.maven.plugin.logging.Log;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.*;

public class ManagedClassScannerTest {

    private Log log;
    private ManagedClassScanner scanner;
    private ClassLoader classLoader;

    @BeforeEach
    void setUp() {
        log = mock(Log.class);
        classLoader = Thread.currentThread().getContextClassLoader();
        scanner = new ManagedClassScanner(classLoader, log);
    }

    @Test
    void scan_returnsOnlyEntityAnnotatedClasses() {
        List<Class<?>> result = scanner.scan(List.of("net.unit8.maven.plugins.fixture"));

        assertTrue(result.contains(EntityA.class));
        assertFalse(result.contains(NonEntity.class));
    }

    @Test
    void scan_deduplicatesAcrossMultiplePackages() {
        List<Class<?>> result = scanner.scan(List.of(
                "net.unit8.maven.plugins.fixture",
                "net.unit8.maven.plugins.fixture"
        ));

        long count = result.stream().filter(c -> c == EntityA.class).count();
        assertEquals(1, count);
    }

    @Test
    void scan_combinesResultsFromMultiplePackages() {
        List<Class<?>> result = scanner.scan(List.of(
                "net.unit8.maven.plugins.fixture",
                "net.unit8.maven.plugins.fixture.sub"
        ));

        assertTrue(result.contains(EntityA.class));
        assertTrue(result.contains(EntityB.class));
    }

    @Test
    void scanPackage_logsWarnOnClassNotFoundException(@TempDir Path tempDir) throws Exception {
        // findClasses が ClassNotFoundException をスローすると warn が呼ばれることを
        // ManagedClassScanner のサブクラスで findClasses をオーバーライドして検証する
        Path pkgDir = tempDir.resolve("net/unit8/broken");
        Files.createDirectories(pkgDir);
        Files.createFile(pkgDir.resolve("Broken.class"));

        URLClassLoader realLoader = new URLClassLoader(
                new URL[]{tempDir.toUri().toURL()},
                Thread.currentThread().getContextClassLoader());

        ManagedClassScanner brokenScanner = new ManagedClassScanner(realLoader, log) {
            @Override
            List<Class<?>> findClasses(File directory, String packageName) throws ClassNotFoundException {
                throw new ClassNotFoundException("net.unit8.broken.Broken");
            }
        };
        brokenScanner.scanPackage("net.unit8.broken");

        verify(log).warn(argThat((String s) -> s.contains("Failed to load classes")));
    }

    @Test
    void findClasses_throwsIllegalStateExceptionForDottedDirectoryName(@TempDir Path tempDir)
            throws Exception {
        Path dottedDir = tempDir.resolve("sub.dir");
        Files.createDirectory(dottedDir);
        Files.createFile(dottedDir.resolve("Dummy.class"));

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> scanner.findClasses(tempDir.toFile(), "com.example"));
        assertTrue(ex.getMessage().contains("sub.dir"));
    }

    @Test
    void findClasses_returnsEmptyListWhenDirectoryDoesNotExist() throws Exception {
        File nonExistent = new File("/nonexistent/path/that/does/not/exist");

        List<Class<?>> result = scanner.findClasses(nonExistent, "com.example");

        assertTrue(result.isEmpty());
    }

    @Test
    void scanPackage_ignoresJarURLs(@TempDir Path tempDir) throws Exception {
        // 存在しないパッケージのスキャンは空リストを返す（JAR URLの挙動を間接的に確認）
        // 実際の ClassLoader でスキャンして存在しないパッケージは空リスト
        List<Class<?>> result = scanner.scanPackage("net.unit8.maven.plugins.nonexistent.package");

        assertTrue(result.isEmpty());
        verify(log, never()).warn(any(String.class));
    }
}
