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
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
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
import org.apache.maven.api.plugin.Log;
import org.apache.maven.api.services.ProjectManager;
import org.apache.maven.plugins.shade.DefaultShader;
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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
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
