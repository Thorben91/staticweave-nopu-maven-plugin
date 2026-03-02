package net.unit8.maven.plugins;

import jakarta.persistence.spi.ClassTransformer;
import jakarta.persistence.spi.TransformerException;
import org.apache.maven.artifact.Artifact;
import org.apache.maven.plugin.logging.Log;
import org.apache.maven.project.MavenProject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.URL;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

public class WeaveMojoTest {

    private WeaveMojo mojo;
    private Log log;

    @BeforeEach
    void setUp() {
        mojo = new WeaveMojo();
        log = mock(Log.class);
        mojo.setLog(log);
    }

    // ---- setLogLevel ----

    @ParameterizedTest
    @ValueSource(strings = {"OFF", "SEVERE", "WARNING", "INFO", "CONFIG", "FINE", "FINER", "FINEST", "ALL"})
    void setLogLevel_acceptsValidLevel(String level) {
        mojo.setLogLevel(level);
        verify(log, never()).error(any(CharSequence.class));
    }

    @ParameterizedTest
    @ValueSource(strings = {"off", "severe", "warning", "info", "config", "fine", "finer", "finest", "all", "Warning", "wArNiNg"})
    void setLogLevel_isCaseInsensitive(String level) {
        mojo.setLogLevel(level);
        verify(log, never()).error(any(CharSequence.class));
    }

    @Test
    void setLogLevel_logsErrorForUnknownLevel() {
        mojo.setLogLevel("UNKNOWN_LEVEL");
        verify(log).error(argThat((CharSequence cs) -> cs.toString().contains("Unknown log level")));
    }

    @Test
    void setLogLevel_doesNotChangeLogLevelForUnknownLevel() throws Exception {
        mojo.setLogLevel("ALL");
        mojo.setLogLevel("UNKNOWN_LEVEL");

        Field logLevelField = WeaveMojo.class.getDeclaredField("logLevel");
        logLevelField.setAccessible(true);
        assertEquals("ALL", logLevelField.get(mojo));
    }

    // ---- transform ----

    @Test
    void transform_returnsNullWhenNoTransformerModifiesBytes() throws Exception {
        ClassTransformer ct = mock(ClassTransformer.class);
        when(ct.transform(any(), any(), any(), any(), any())).thenReturn(null);
        setField("classLoader", Thread.currentThread().getContextClassLoader());

        byte[] result = mojo.transform(List.of(ct), "com/example/Foo", Object.class, new byte[0]);

        assertNull(result);
    }

    @Test
    void transform_returnsFirstNonNullResult() throws Exception {
        byte[] expected = new byte[]{1, 2, 3};
        ClassTransformer ct1 = mock(ClassTransformer.class);
        ClassTransformer ct2 = mock(ClassTransformer.class);
        when(ct1.transform(any(), any(), any(), any(), any())).thenReturn(expected);
        when(ct2.transform(any(), any(), any(), any(), any())).thenReturn(new byte[]{4, 5, 6});
        setField("classLoader", Thread.currentThread().getContextClassLoader());

        byte[] result = mojo.transform(List.of(ct1, ct2), "com/example/Foo", Object.class, new byte[0]);

        assertArrayEquals(expected, result);
        verifyNoInteractions(ct2);
    }

    @Test
    void transform_skipsRemainingTransformersAfterFirstSuccess() throws Exception {
        byte[] transformed = new byte[]{9};
        ClassTransformer ct1 = mock(ClassTransformer.class);
        ClassTransformer ct2 = mock(ClassTransformer.class);
        when(ct1.transform(any(), any(), any(), any(), any())).thenReturn(null);
        when(ct2.transform(any(), any(), any(), any(), any())).thenReturn(transformed);
        ClassTransformer ct3 = mock(ClassTransformer.class);
        setField("classLoader", Thread.currentThread().getContextClassLoader());

        mojo.transform(List.of(ct1, ct2, ct3), "com/example/Foo", Object.class, new byte[0]);

        verifyNoInteractions(ct3);
    }

    @Test
    void transform_propagatesTransformerException() throws Exception {
        ClassTransformer ct = mock(ClassTransformer.class);
        when(ct.transform(any(), any(), any(), any(), any()))
                .thenThrow(new TransformerException("test error", null));
        setField("classLoader", Thread.currentThread().getContextClassLoader());

        assertThrows(TransformerException.class,
                () -> mojo.transform(List.of(ct), "com/example/Foo", Object.class, new byte[0]));
    }

    @Test
    void transform_passesClassLoaderToTransformer() throws Exception {
        ClassLoader cl = Thread.currentThread().getContextClassLoader();
        setField("classLoader", cl);
        ClassTransformer ct = mock(ClassTransformer.class);
        when(ct.transform(any(), any(), any(), any(), any())).thenReturn(null);

        mojo.transform(List.of(ct), "com/example/Foo", Object.class, new byte[0]);

        verify(ct).transform(eq(cl), any(), any(), any(), any());
    }

    // ---- buildClassPath ----

    @Test
    void buildClassPath_returnsEmptyListAndLogsErrorWhenProjectIsNull() throws Exception {
        setField("project", null);

        List<URL> result = invokeBuildClassPath();

        assertTrue(result.isEmpty());
        verify(log).error(argThat((CharSequence cs) -> cs.toString().contains("MavenProject is empty")));
    }

    @Test
    void buildClassPath_returnsArtifactURLsWhenProjectHasArtifacts() throws Exception {
        MavenProject project = mock(MavenProject.class);
        Artifact artifact = mock(Artifact.class);
        File artifactFile = new File(getClass().getResource("/").toURI());
        when(artifact.getFile()).thenReturn(artifactFile);
        when(project.getArtifacts()).thenReturn(Set.of(artifact));
        setField("project", project);

        List<URL> result = invokeBuildClassPath();

        assertEquals(1, result.size());
        assertEquals(artifactFile.toURI().toURL(), result.get(0));
    }

    @Test
    void buildClassPath_returnsEmptyListWhenProjectHasNoArtifacts() throws Exception {
        MavenProject project = mock(MavenProject.class);
        when(project.getArtifacts()).thenReturn(Set.of());
        setField("project", project);

        List<URL> result = invokeBuildClassPath();

        assertTrue(result.isEmpty());
    }

    // ---- createPersistenceUnitInfo ----

    @Test
    void createPersistenceUnitInfo_setsPersistenceUnitName() throws Exception {
        setField("classLoader", Thread.currentThread().getContextClassLoader());
        setField("packages", List.of("net.unit8.maven.plugins.fixture"));

        var pu = mojo.createPersistenceUnitInfo();

        assertEquals("for-weaving", pu.getPersistenceUnitName());
    }

    @Test
    void createPersistenceUnitInfo_setsManagedClassNamesFromScanner() throws Exception {
        setField("classLoader", Thread.currentThread().getContextClassLoader());
        setField("packages", List.of("net.unit8.maven.plugins.fixture"));

        var pu = mojo.createPersistenceUnitInfo();

        assertTrue(pu.getManagedClassNames().contains("net.unit8.maven.plugins.fixture.EntityA"));
        assertFalse(pu.getManagedClassNames().contains("net.unit8.maven.plugins.fixture.NonEntity"));
    }

    @Test
    void createPersistenceUnitInfo_setsExcludeUnlistedClassesToFalse() throws Exception {
        setField("classLoader", Thread.currentThread().getContextClassLoader());
        setField("packages", List.of("net.unit8.maven.plugins.fixture"));

        var pu = mojo.createPersistenceUnitInfo();

        assertFalse(pu.excludeUnlistedClasses());
    }

    // ---- helpers ----

    private void setField(String fieldName, Object value) throws Exception {
        Field f = WeaveMojo.class.getDeclaredField(fieldName);
        f.setAccessible(true);
        f.set(mojo, value);
    }

    @SuppressWarnings("unchecked")
    private List<URL> invokeBuildClassPath() throws Exception {
        Method m = WeaveMojo.class.getDeclaredMethod("buildClassPath");
        m.setAccessible(true);
        return (List<URL>) m.invoke(mojo);
    }
}
