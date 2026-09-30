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
package org.apache.maven.plugins.shade.filter;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.zip.ZipException;

import org.apache.maven.api.plugin.Log;
import org.vafer.jdependency.Clazz;
import org.vafer.jdependency.Clazzpath;
import org.vafer.jdependency.ClazzpathUnit;

import static java.nio.charset.StandardCharsets.UTF_8;

/**
 * A filter that prevents the inclusion of classes not required in the final jar.
 */
public class MinijarFilter implements Filter {

    private Log log;

    private Set<Clazz> removable;

    private int classesKept;

    private int classesRemoved;

    // [MSHADE-209] This is introduced only for testing purposes which shows
    // there is something wrong with the design of this class. (SoC?)
    // unfortunately i don't have a better idea at the moment.
    MinijarFilter(int classesKept, int classesRemoved, Log log) {
        this.classesKept = classesKept;
        this.classesRemoved = classesRemoved;
        this.log = log;
    }

    /**
     * @param projectId name of the project, used in messages
     * @param projectArtifact the project's own artifact, may be {@code null} when it was not built
     * @param outputDirectory the project's output directory
     * @param dependencies the runtime dependencies, keyed by a label for messages, in classpath order
     * @param log {@link Log}
     * @throws IOException in case of error
     */
    public MinijarFilter(
            String projectId, Path projectArtifact, Path outputDirectory, Map<String, Path> dependencies, Log log)
            throws IOException {
        this(
                projectId,
                projectArtifact,
                outputDirectory,
                dependencies,
                log,
                Collections.<SimpleFilter>emptyList(),
                Collections.<String>emptySet());
    }

    /**
     * @param projectId name of the project, used in messages
     * @param projectArtifact the project's own artifact, may be {@code null} when it was not built
     * @param outputDirectory the project's output directory
     * @param dependencies the runtime dependencies, keyed by a label for messages, in classpath order
     * @param log {@link Log}
     * @param entryPoints
     * @throws IOException in case of error
     */
    public MinijarFilter(
            String projectId,
            Path projectArtifact,
            Path outputDirectory,
            Map<String, Path> dependencies,
            Log log,
            Set<String> entryPoints)
            throws IOException {
        this(
                projectId,
                projectArtifact,
                outputDirectory,
                dependencies,
                log,
                Collections.<SimpleFilter>emptyList(),
                entryPoints);
    }

    /**
     * @param projectId name of the project, used in messages
     * @param projectArtifact the project's own artifact, may be {@code null} when it was not built
     * @param outputDirectory the project's output directory
     * @param dependencies the runtime dependencies, keyed by a label for messages, in classpath order
     * @param log {@link Log}
     * @param simpleFilters {@link SimpleFilter}
     * @param entryPoints
     * @throws IOException in case of errors
     * @since 1.6
     */
    public MinijarFilter(
            String projectId,
            Path projectArtifact,
            Path outputDirectory,
            Map<String, Path> dependencies,
            Log log,
            List<SimpleFilter> simpleFilters,
            Set<String> entryPoints)
            throws IOException {
        this.log = log;

        Path artifactFile = projectArtifact;

        if (artifactFile != null) {
            Clazzpath cp = new Clazzpath();

            ClazzpathUnit artifactUnit = cp.addClazzpathUnit(Files.newInputStream(artifactFile), projectId);

            for (Map.Entry<String, Path> dependency : dependencies.entrySet()) {
                addDependencyToClasspath(cp, dependency.getKey(), dependency.getValue());
            }

            removable = cp.getClazzes();
            if (removable.remove(new Clazz("module-info"))) {
                log.warn("Removing module-info from " + artifactFile.getFileName());
            }
            removePackages(artifactUnit);
            if (entryPoints.isEmpty()) {
                removable.removeAll(artifactUnit.getClazzes());
                removable.removeAll(artifactUnit.getTransitiveDependencies());
            } else {
                Set<Clazz> artifactUnitClazzes = artifactUnit.getClazzes();
                Set<Clazz> entryPointsToKeep = new HashSet<>();
                for (String entryPoint : entryPoints) {
                    Clazz entryPointFound = null;
                    for (Clazz clazz : artifactUnitClazzes) {
                        if (clazz.getName().equals(entryPoint)) {
                            entryPointFound = clazz;
                            break;
                        }
                    }
                    if (entryPointFound != null) {
                        entryPointsToKeep.add(entryPointFound);
                    }
                }
                removable.removeAll(entryPointsToKeep);
                if (entryPointsToKeep.isEmpty()) {
                    removable.removeAll(artifactUnit.getTransitiveDependencies());
                } else {
                    for (Clazz entryPoint : entryPointsToKeep) {
                        removable.removeAll(entryPoint.getTransitiveDependencies());
                    }
                }
            }
            removeSpecificallyIncludedClasses(
                    dependencies, simpleFilters == null ? Collections.<SimpleFilter>emptyList() : simpleFilters);

            // the runtime classpath is the build output directory and all the dependencies' jars
            List<Path> runtimeClasspath = new ArrayList<>();
            if (outputDirectory != null) {
                runtimeClasspath.add(outputDirectory);
            }
            runtimeClasspath.addAll(dependencies.values());
            removeServices(runtimeClasspath, cp);
        }
    }

    private void removeServices(final List<Path> runtimeClasspath, final Clazzpath cp) {
        boolean repeatScan;
        do {
            repeatScan = false;
            final Set<Clazz> neededClasses = cp.getClazzes();
            neededClasses.removeAll(removable);
            // The runtime classpath is
            //  - the build output directory
            //  - all the paths to the dependencies' jars
            // We thereby need to ignore the build directory because we don't want
            // to remove anything from it, as it's the starting point of the
            // minification process.
            for (final Path element : runtimeClasspath) {
                final String fileName = element.toString();
                if (Files.isDirectory(element)) {
                    repeatScan |= removeServicesFromDir(cp, neededClasses, fileName);
                } else {
                    repeatScan |= removeServicesFromJar(cp, neededClasses, fileName);
                }
            }
        } while (repeatScan);
    }

    private boolean removeServicesFromDir(Clazzpath cp, Set<Clazz> neededClasses, String fileName) {
        final File servicesDir = new File(fileName, "META-INF/services/");
        if (!servicesDir.isDirectory()) {
            return false;
        }
        final File[] serviceProviderConfigFiles = servicesDir.listFiles();
        if (serviceProviderConfigFiles == null || serviceProviderConfigFiles.length == 0) {
            return false;
        }

        boolean repeatScan = false;
        for (File serviceProviderConfigFile : serviceProviderConfigFiles) {
            final String serviceClassName = serviceProviderConfigFile.getName();
            final boolean isNeededClass = neededClasses.contains(cp.getClazz(serviceClassName));
            if (!isNeededClass) {
                continue;
            }

            try (BufferedReader configFileReader = Files.newBufferedReader(serviceProviderConfigFile.toPath(), UTF_8)) {
                // check whether the found classes use services in turn
                repeatScan |= scanServiceProviderConfigFile(cp, configFileReader);
            } catch (final IOException e) {
                log.warn("Failed to scan service provider config file " + serviceProviderConfigFile, e);
            }
        }
        return repeatScan;
    }

    private boolean removeServicesFromJar(Clazzpath cp, Set<Clazz> neededClasses, String fileName) {
        boolean repeatScan = false;
        try (JarFile jar = new JarFile(fileName)) {
            for (final Enumeration<JarEntry> entries = jar.entries(); entries.hasMoreElements(); ) {
                final JarEntry jarEntry = entries.nextElement();
                if (jarEntry.isDirectory() || !jarEntry.getName().startsWith("META-INF/services/")) {
                    continue;
                }

                final String serviceClassName = jarEntry.getName().substring("META-INF/services/".length());
                final boolean isNeededClass = neededClasses.contains(cp.getClazz(serviceClassName));
                if (!isNeededClass) {
                    continue;
                }

                try (BufferedReader configFileReader =
                        new BufferedReader(new InputStreamReader(jar.getInputStream(jarEntry), UTF_8))) {
                    // check whether the found classes use services in turn
                    repeatScan = scanServiceProviderConfigFile(cp, configFileReader);
                } catch (final IOException e) {
                    log.warn("Failed to scan JAR entry " + jarEntry.getName() + " in jar " + jar.getName(), e);
                }
            }
        } catch (final IOException e) {
            log.warn("Not a JAR file candidate. Ignoring classpath element '" + fileName + "'", e);
        }
        return repeatScan;
    }

    private boolean scanServiceProviderConfigFile(Clazzpath cp, BufferedReader configFileReader) throws IOException {
        boolean serviceClassFound = false;
        for (String line = configFileReader.readLine(); line != null; line = configFileReader.readLine()) {
            final String className = line.split("#", 2)[0].trim();
            if (className.isEmpty()) {
                continue;
            }

            final Clazz clazz = cp.getClazz(className);
            if (clazz == null || !removable.contains(clazz)) {
                continue;
            }

            log.debug(className + " was not removed because it is a service");
            removeClass(clazz);
            serviceClassFound = true;
        }
        return serviceClassFound;
    }

    private void removeClass(final Clazz clazz) {
        removable.remove(clazz);
        removable.removeAll(clazz.getTransitiveDependencies());
    }

    private ClazzpathUnit addDependencyToClasspath(Clazzpath cp, String dependency, Path file) throws IOException {
        ClazzpathUnit clazzpathUnit = null;
        try (InputStream is = Files.newInputStream(file)) {
            clazzpathUnit = cp.addClazzpathUnit(is, dependency.toString());
        } catch (ZipException e) {
            log.warn(file + " could not be unpacked/read for minimization; dependency is probably malformed.");
            IOException ioe = new IOException(
                    "Dependency " + dependency + " in file " + file
                            + " could not be unpacked. File is probably corrupt",
                    e);
            throw ioe;
        } catch (ArrayIndexOutOfBoundsException | IllegalArgumentException e) {
            // trap ArrayIndexOutOfBoundsExceptions caused by malformed dependency classes (MSHADE-107)
            log.warn(dependency + " could not be analyzed for minimization; dependency is probably malformed.");
        }

        return clazzpathUnit;
    }

    private void removePackages(ClazzpathUnit artifactUnit) {
        Set<String> packageNames = new HashSet<>();
        removePackages(artifactUnit.getClazzes(), packageNames);
        removePackages(artifactUnit.getTransitiveDependencies(), packageNames);
    }

    private void removePackages(Set<Clazz> clazzes, Set<String> packageNames) {
        for (Clazz clazz : clazzes) {
            String name = clazz.getName();
            while (name.contains(".")) {
                name = name.substring(0, name.lastIndexOf('.'));
                if (packageNames.add(name)) {
                    removable.remove(new Clazz(name + ".package-info"));
                }
            }
        }
    }

    private void removeSpecificallyIncludedClasses(Map<String, Path> dependencies, List<SimpleFilter> simpleFilters)
            throws IOException {
        // remove classes specifically included in filters
        Clazzpath checkCp = new Clazzpath();
        for (Map.Entry<String, Path> dependency : dependencies.entrySet()) {
            File jar = dependency.getValue().toFile();

            for (SimpleFilter simpleFilter : simpleFilters) {
                if (simpleFilter.canFilter(jar)) {
                    ClazzpathUnit depClazzpathUnit =
                            addDependencyToClasspath(checkCp, dependency.getKey(), dependency.getValue());
                    if (depClazzpathUnit != null) {
                        Set<Clazz> clazzes = depClazzpathUnit.getClazzes();
                        for (final Clazz clazz : new HashSet<>(removable)) {
                            if (clazzes.contains(clazz) //
                                    && simpleFilter.isSpecificallyIncluded(
                                            clazz.getName().replace('.', '/'))) {
                                log.debug(clazz.getName() + " not removed because it was specifically included");
                                removeClass(clazz);
                            }
                        }
                    }
                }
            }
        }
    }

    @Override
    public boolean canFilter(File jar) {
        return true;
    }

    @Override
    public boolean isFiltered(String classFile) {
        String className = classFile.replace('/', '.').replaceFirst("\\.class$", "");
        Clazz clazz = new Clazz(className);

        if (removable != null && removable.contains(clazz)) {
            log.debug("Removing " + className);
            classesRemoved += 1;
            return true;
        }

        classesKept += 1;
        return false;
    }

    @Override
    public void finished() {
        int classesTotal = classesRemoved + classesKept;
        if (classesTotal != 0) {
            log.info("Minimized " + classesTotal + " -> " + classesKept + " (" + 100 * classesKept / classesTotal
                    + "%)");
        } else {
            log.info("Minimized " + classesTotal + " -> " + classesKept);
        }
    }
}
