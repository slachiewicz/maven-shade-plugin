/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.maven.plugins.shade.mojo;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.apache.maven.api.DownloadedArtifact;
import org.apache.maven.api.Project;
import org.apache.maven.api.Session;
import org.apache.maven.api.Toolchain;
import org.apache.maven.api.Type;
import org.apache.maven.api.plugin.Log;
import org.apache.maven.api.services.ProjectManager;
import org.apache.maven.api.services.ToolchainManager;
import org.apache.maven.plugins.shade.DefaultShader;
import org.apache.maven.plugins.shade.ModuleInfoConfiguration;
import org.apache.maven.plugins.shade.ModuleInfoMode;
import org.apache.maven.plugins.shade.ShadeRequest;
import org.apache.maven.plugins.shade.Shader;
import org.apache.maven.plugins.shade.filter.Filter;
import org.apache.maven.plugins.shade.relocation.Relocator;
import org.apache.maven.plugins.shade.relocation.SimpleRelocator;
import org.apache.maven.plugins.shade.resource.ComponentsXmlResourceTransformer;
import org.apache.maven.plugins.shade.resource.ManifestResourceTransformer;
import org.apache.maven.plugins.shade.resource.ResourceTransformer;
import org.junit.jupiter.api.Test;

import static java.util.Arrays.asList;
import static java.util.Collections.singletonList;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * @author Jason van Zyl
 * @author Mauro Talevi
 */
public class ShadeMojoTest {
    private static String getBasedir() {
        return System.getProperty("basedir", new File("").getAbsolutePath());
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    @Test
    public void testManifestTransformerSelection() throws Exception {
        final ShadeMojo mojo = new ShadeMojo();
        setField(mojo, "log", mock(Log.class));
        final Method m = ShadeMojo.class.getDeclaredMethod("toResourceTransformers", String.class, List.class);
        m.setAccessible(true);

        final ManifestResourceTransformer defaultTfr = new ManifestResourceTransformer() {
            @Override
            public String toString() // when test fails junit does a toString so easier to read errors this way
                    {
                return "default";
            }
        };
        final ManifestResourceTransformer testsTfr1 = new ManifestResourceTransformer() {
            @Override
            public String toString() {
                return "t1";
            }
        };
        testsTfr1.setForShade("tests");
        final ManifestResourceTransformer testsTfr2 = new ManifestResourceTransformer() {
            @Override
            public String toString() {
                return "t2";
            }
        };
        testsTfr2.setForShade("tests");

        assertEquals(singletonList(defaultTfr), m.invoke(mojo, "jar", asList(defaultTfr, testsTfr1, testsTfr2)));
        assertEquals(asList(testsTfr1, testsTfr2), m.invoke(mojo, "tests", asList(defaultTfr, testsTfr1, testsTfr2)));
        assertEquals(asList(testsTfr1, testsTfr2), m.invoke(mojo, "tests", asList(testsTfr1, defaultTfr, testsTfr2)));
        assertEquals(asList(testsTfr1, testsTfr2), m.invoke(mojo, "tests", asList(testsTfr1, testsTfr2, defaultTfr)));
    }

    @Test
    public void testShaderWithDefaultShadedPattern() throws Exception {
        shaderWithPattern(null, new File("target/foo-default.jar"));
    }

    @Test
    public void testShaderWithCustomShadedPattern() throws Exception {
        shaderWithPattern("org/shaded/plexus/util", new File("target/foo-custom.jar"));
    }

    @Test
    public void testShaderWithExclusions() throws Exception {
        File jarFile = new File(getBasedir(), "target/unit/foo-bar.jar");

        Shader s = new DefaultShader();

        Set<File> set = new LinkedHashSet<>();
        set.add(new File(getBasedir(), "src/test/jars/test-artifact-1.0-SNAPSHOT.jar"));

        List<Relocator> relocators = new ArrayList<>();
        relocators.add(new SimpleRelocator(
                "org.codehaus.plexus.util",
                "hidden",
                null,
                Arrays.asList("org.codehaus.plexus.util.xml.Xpp3Dom", "org.codehaus.plexus.util.xml.pull.*")));

        List<ResourceTransformer> resourceTransformers = new ArrayList<>();

        List<Filter> filters = new ArrayList<>();

        ShadeRequest shadeRequest = new ShadeRequest();
        shadeRequest.setJars(set);
        shadeRequest.setUberJar(jarFile);
        shadeRequest.setFilters(filters);
        shadeRequest.setRelocators(relocators);
        shadeRequest.setResourceTransformers(resourceTransformers);

        s.shade(shadeRequest);

        try (URLClassLoader cl = new URLClassLoader(new URL[] {jarFile.toURI().toURL()})) {
            Class<?> c = cl.loadClass("org.apache.maven.plugins.shade.Lib");

            Field field = c.getDeclaredField("CLASS_REALM_PACKAGE_IMPORT");
            assertEquals("org.codehaus.plexus.util.xml.pull", field.get(null));

            Method method = c.getDeclaredMethod("getClassRealmPackageImport");
            assertEquals("org.codehaus.plexus.util.xml.pull", method.invoke(null));
        }
    }

    /**
     * Tests if a Filter is installed correctly, also if createSourcesJar is set to true.
     *
     * @throws Exception
     */
    @Test
    public void testShadeWithFilter() throws Exception {
        Path binaryJar = Paths.get("myfaces-impl-2.0.1-SNAPSHOT.jar");
        Path sourcesJar = Paths.get("myfaces-impl-2.0.1-SNAPSHOT-sources.jar");

        ShadeMojo mojo = new ShadeMojo();

        // the project's own artifact
        setField(
                mojo,
                "projectArtifact",
                new ResolvedArtifact(
                        "org.apache.myfaces.core",
                        "myfaces-impl",
                        "2.0.1-SNAPSHOT",
                        "2.0.1-SNAPSHOT",
                        "jar",
                        "jar",
                        "",
                        "compile",
                        false,
                        binaryJar));

        // the session resolves the sources of the project's artifact
        Session session = mock(Session.class);
        ProjectManager projectManager = mock(ProjectManager.class);
        when(session.getService(ProjectManager.class)).thenReturn(projectManager);
        when(projectManager.getRemoteProjectRepositories(any())).thenReturn(Collections.emptyList());
        DownloadedArtifact sources = mock(DownloadedArtifact.class);
        when(sources.getPath()).thenReturn(sourcesJar);
        when(session.createArtifactCoordinates(
                        eq("org.apache.myfaces.core"),
                        eq("myfaces-impl"),
                        eq("2.0.1-SNAPSHOT"),
                        eq("sources"),
                        eq("jar"),
                        eq("jar")))
                .thenReturn(mock(org.apache.maven.api.ArtifactCoordinates.class));
        when(session.resolveArtifact(any(org.apache.maven.api.ArtifactCoordinates.class), anyList()))
                .thenReturn(sources);
        setField(mojo, "session", session);
        setField(mojo, "project", mock(Project.class));

        // set createSourcesJar = true
        setField(mojo, "createSourcesJar", true);

        // create and configure the ArchiveFilter
        ArchiveFilter archiveFilter = new ArchiveFilter();
        Field archiveFilterArtifact = ArchiveFilter.class.getDeclaredField("artifact");
        archiveFilterArtifact.setAccessible(true);
        archiveFilterArtifact.set(archiveFilter, "org.apache.myfaces.core:myfaces-impl");

        // add ArchiveFilter to mojo
        setField(mojo, "filters", new ArchiveFilter[] {archiveFilter});

        // invoke getFilters()
        Method getFilters = ShadeMojo.class.getDeclaredMethod("getFilters", List.class, ArtifactSelector.class);
        getFilters.setAccessible(true);
        ArtifactSelector selector = new ArtifactSelector(Collections.emptyList(), Collections.emptyList(), null);
        @SuppressWarnings("unchecked")
        List<Filter> filters = (List<Filter>) getFilters.invoke(mojo, Collections.emptyList(), selector);

        // assertions - there must be one filter
        assertEquals(1, filters.size());

        // the filter must be able to filter the binary and the sources jar
        Filter filter = filters.get(0);
        assertTrue(filter.canFilter(binaryJar.toFile())); // binary jar
        assertTrue(filter.canFilter(sourcesJar.toFile())); // sources jar
    }

    @Test
    public void testSourceRequestsDiscardModuleInfo() throws Exception {
        ShadeMojo mojo = new ShadeMojo();
        setField(mojo, "moduleInfoMode", "mErGe");
        Method method = ShadeMojo.class.getDeclaredMethod(
                "createShadeSourcesRequest", String.class, Set.class, File.class, List.class, List.class, List.class);
        method.setAccessible(true);

        ShadeRequest request = (ShadeRequest) method.invoke(
                mojo,
                "sources-jar",
                Collections.emptySet(),
                new File("target/sources.jar"),
                Collections.emptyList(),
                Collections.emptyList(),
                Collections.emptyList());

        assertEquals(ModuleInfoMode.DISCARD, request.getModuleInfoMode());
        assertTrue(request.getDependencyAnalysisArtifacts().isEmpty());
    }

    @Test
    public void testSelectsConfiguredModuleInfoAnalysisJdkToolchain() throws Exception {
        ShadeMojo mojo = new ShadeMojo();
        ModuleInfoConfiguration configuration = new ModuleInfoConfiguration();
        configuration.setAnalysisJdkToolchain(Collections.singletonMap("version", "21"));
        Session session = mock(Session.class);
        ToolchainManager manager = mock(ToolchainManager.class);
        Toolchain toolchain = mock(Toolchain.class);
        File javaHome = new File(System.getProperty("java.home")).getCanonicalFile();
        when(session.getService(ToolchainManager.class)).thenReturn(manager);
        when(manager.getToolchains(session, "jdk", configuration.getAnalysisJdkToolchain()))
                .thenReturn(Collections.singletonList(toolchain));
        when(toolchain.findTool("javac")).thenReturn(new File(new File(javaHome, "bin"), "javac").getPath());
        setField(mojo, "moduleInfo", configuration);
        setField(mojo, "session", session);

        Method method = ShadeMojo.class.getDeclaredMethod("resolveModuleInfoAnalysisJdkHome");
        method.setAccessible(true);
        assertEquals(javaHome, method.invoke(mojo));
        verify(manager).getToolchains(session, "jdk", configuration.getAnalysisJdkToolchain());
    }

    @Test
    public void testRejectsMissingModuleInfoAnalysisJdkToolchain() throws Exception {
        ShadeMojo mojo = new ShadeMojo();
        ModuleInfoConfiguration configuration = new ModuleInfoConfiguration();
        configuration.setAnalysisJdkToolchain(Collections.singletonMap("version", "99"));
        Session session = mock(Session.class);
        ToolchainManager manager = mock(ToolchainManager.class);
        when(session.getService(ToolchainManager.class)).thenReturn(manager);
        when(manager.getToolchains(session, "jdk", configuration.getAnalysisJdkToolchain()))
                .thenReturn(Collections.emptyList());
        setField(mojo, "moduleInfo", configuration);
        setField(mojo, "session", session);

        Method method = ShadeMojo.class.getDeclaredMethod("resolveModuleInfoAnalysisJdkHome");
        method.setAccessible(true);
        InvocationTargetException e = assertThrows(InvocationTargetException.class, () -> method.invoke(mojo));
        assertTrue(e.getCause().getMessage().contains("No JDK toolchain matches"));
        assertTrue(e.getCause().getMessage().contains("version=99"));
    }

    /**
     * MSHADE-456: a POM-declared {@code <dependency>} of type {@code test-jar} has no classifier set explicitly, but
     * resolves to classifier {@code tests}. The id computed from the declared dependency must match the id computed
     * from the resolved artifact, otherwise a shaded test-jar dependency is never removed from the dependency
     * reduced POM.
     */
    @Test
    public void testGetIdForTestJarDependencyMatchesResolvedArtifact() throws Exception {
        ShadeMojo mojo = new ShadeMojo();
        Session session = mock(Session.class);
        Type testJar = mock(Type.class);
        when(testJar.getClassifier()).thenReturn("tests");
        when(session.requireType("test-jar")).thenReturn(testJar);
        setField(mojo, "session", session);

        org.apache.maven.api.model.Dependency dependency = org.apache.maven.api.model.Dependency.newBuilder()
                .groupId("org.apache.maven.its")
                .artifactId("foo")
                .version("1.0")
                .type("test-jar")
                .build();

        Method getIdForDependency =
                ShadeMojo.class.getDeclaredMethod("getId", org.apache.maven.api.model.Dependency.class);
        getIdForDependency.setAccessible(true);
        String dependencyId = (String) getIdForDependency.invoke(mojo, dependency);

        assertEquals("org.apache.maven.its:foo:test-jar:tests", dependencyId);

        Method getIdForArtifact = ShadeMojo.class.getDeclaredMethod("getId", ResolvedArtifact.class);
        getIdForArtifact.setAccessible(true);
        ResolvedArtifact artifact = new ResolvedArtifact(
                "org.apache.maven.its", "foo", "1.0", "1.0", "test-jar", "jar", "tests", "compile", false, null);
        assertEquals(dependencyId, getIdForArtifact.invoke(mojo, artifact));
    }

    /**
     * A dependency with an ordinary type keeps relying on its explicit (possibly absent) classifier.
     */
    @Test
    public void testGetIdForRegularDependencyKeepsExplicitClassifier() throws Exception {
        ShadeMojo mojo = new ShadeMojo();
        Session session = mock(Session.class);
        Type jar = mock(Type.class);
        when(session.requireType("jar")).thenReturn(jar);
        setField(mojo, "session", session);

        Method getIdForDependency =
                ShadeMojo.class.getDeclaredMethod("getId", org.apache.maven.api.model.Dependency.class);
        getIdForDependency.setAccessible(true);

        org.apache.maven.api.model.Dependency.Builder dependency = org.apache.maven.api.model.Dependency.newBuilder()
                .groupId("org.apache.maven.its")
                .artifactId("bar")
                .version("1.0")
                .type("jar");
        assertEquals("org.apache.maven.its:bar:jar:", getIdForDependency.invoke(mojo, dependency.build()));

        assertEquals(
                "org.apache.maven.its:bar:jar:linux",
                getIdForDependency.invoke(mojo, dependency.classifier("linux").build()));
    }

    /**
     * The shaded main artifact must land in {@code outputDirectory}, exactly like the shaded sources, test and
     * test-sources artifacts, even when the pre-shade artifact file lives elsewhere.
     */
    @Test
    public void testShadedMainArtifactFileUsesOutputDirectory() throws Exception {
        ShadeMojo mojo = new ShadeMojo();

        File outputDirectory = new File(getBasedir(), "target/testShadedMainArtifactFileUsesOutputDirectory/custom");
        setField(mojo, "outputDirectory", outputDirectory);
        setField(
                mojo,
                "projectArtifact",
                new ResolvedArtifact(
                        "org.apache.maven.plugins.shade.test",
                        "test-project",
                        "1.0",
                        "1.0",
                        "jar",
                        "jar",
                        "",
                        "compile",
                        false,
                        Paths.get(
                                getBasedir(),
                                "target/testShadedMainArtifactFileUsesOutputDirectory/build/test-project-1.0.jar")));

        Method shadedMainArtifactFile = ShadeMojo.class.getDeclaredMethod("shadedMainArtifactFile");
        shadedMainArtifactFile.setAccessible(true);

        assertEquals(new File(outputDirectory, "test-project-1.0.jar"), shadedMainArtifactFile.invoke(mojo));
    }

    /**
     * Reproduces MSHADE-354: when the pre-shade artifact does not already live where the shaded artifact belongs
     * (e.g. {@code outputDirectory} differs from the default build directory), {@code replaceFile} must still move the
     * shaded content to the configured destination and back up the pre-shade content there.
     */
    @Test
    public void testReplaceFileMovesShadedJarAcrossDirectories() throws Exception {
        ShadeMojo mojo = new ShadeMojo();
        setField(mojo, "log", mock(Log.class));

        File baseDir = new File(getBasedir(), "target/testReplaceFileMovesShadedJarAcrossDirectories");
        File buildDirectory = new File(baseDir, "build");
        File outputDirectory = new File(baseDir, "custom");
        buildDirectory.mkdirs();
        outputDirectory.mkdirs();
        setField(mojo, "outputDirectory", outputDirectory);

        File preShadeArtifact = new File(buildDirectory, "app-1.0.jar");
        Files.write(preShadeArtifact.toPath(), "original".getBytes(StandardCharsets.UTF_8));

        File shadedJar = new File(outputDirectory, "app-1.0-shaded.jar");
        Files.write(shadedJar.toPath(), "shaded".getBytes(StandardCharsets.UTF_8));

        File destFile = new File(outputDirectory, "app-1.0.jar");

        Method replaceFile = ShadeMojo.class.getDeclaredMethod("replaceFile", File.class, File.class, File.class);
        replaceFile.setAccessible(true);
        replaceFile.invoke(mojo, destFile, preShadeArtifact, shadedJar);

        assertFalse(
                preShadeArtifact.exists(), "the pre-shade artifact must be moved out of the default build directory");
        assertTrue(destFile.exists(), "the shaded artifact must land in the configured outputDirectory");
        assertEquals("shaded", new String(Files.readAllBytes(destFile.toPath()), StandardCharsets.UTF_8));

        File backup = new File(outputDirectory, "original-app-1.0.jar");
        assertTrue(backup.exists(), "the pre-shade artifact must be preserved in outputDirectory");
        assertEquals("original", new String(Files.readAllBytes(backup.toPath()), StandardCharsets.UTF_8));
    }

    /**
     * MSHADE-382: a skipped execution returns before it touches the project, the shader or any file.
     */
    @Test
    public void testSkipDoesNothing() throws Exception {
        ShadeMojo mojo = new ShadeMojo();
        Log log = mock(Log.class);
        Shader skippedShader = mock(Shader.class);
        setField(mojo, "log", log);
        // no project: the mojo fails with a NullPointerException as soon as it goes on after the skip
        setField(mojo, "skip", true);
        setField(mojo, "shader", skippedShader);

        mojo.execute();

        verify(log).info("Shading has been skipped.");
        verify(skippedShader, never()).shade(any(ShadeRequest.class));
    }

    public void shaderWithPattern(String shadedPattern, File jar) throws Exception {
        Shader s = new DefaultShader();

        Set<File> set = new LinkedHashSet<>();

        set.add(new File(getBasedir(), "src/test/jars/test-project-1.0-SNAPSHOT.jar"));

        set.add(new File(getBasedir(), "src/test/jars/plexus-utils-1.4.1.jar"));

        List<Relocator> relocators = new ArrayList<>();

        relocators.add(new SimpleRelocator(
                "org/codehaus/plexus/util",
                shadedPattern,
                null,
                Arrays.asList("org/codehaus/plexus/util/xml/Xpp3Dom", "org/codehaus/plexus/util/xml/pull.*")));

        List<ResourceTransformer> resourceTransformers = new ArrayList<>();

        resourceTransformers.add(new ComponentsXmlResourceTransformer());

        List<Filter> filters = new ArrayList<>();

        ShadeRequest shadeRequest = new ShadeRequest();
        shadeRequest.setJars(set);
        shadeRequest.setUberJar(jar);
        shadeRequest.setFilters(filters);
        shadeRequest.setRelocators(relocators);
        shadeRequest.setResourceTransformers(resourceTransformers);

        s.shade(shadeRequest);
    }
}
