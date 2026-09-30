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
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.maven.api.ArtifactCoordinates;
import org.apache.maven.api.Dependency;
import org.apache.maven.api.DependencyCoordinates;
import org.apache.maven.api.DownloadedArtifact;
import org.apache.maven.api.Node;
import org.apache.maven.api.PathScope;
import org.apache.maven.api.ProducedArtifact;
import org.apache.maven.api.Project;
import org.apache.maven.api.RemoteRepository;
import org.apache.maven.api.Session;
import org.apache.maven.api.di.Inject;
import org.apache.maven.api.model.Exclusion;
import org.apache.maven.api.model.Model;
import org.apache.maven.api.model.Parent;
import org.apache.maven.api.plugin.Log;
import org.apache.maven.api.plugin.MojoException;
import org.apache.maven.api.plugin.annotations.Mojo;
import org.apache.maven.api.plugin.annotations.Parameter;
import org.apache.maven.api.services.ArtifactManager;
import org.apache.maven.api.services.DependencyCoordinatesFactory;
import org.apache.maven.api.services.DependencyCoordinatesFactoryRequest;
import org.apache.maven.api.services.DependencyResolver;
import org.apache.maven.api.services.DependencyResolverRequest;
import org.apache.maven.api.services.DependencyResolverResult;
import org.apache.maven.api.services.ProjectManager;
import org.apache.maven.api.services.xml.ModelXmlFactory;
import org.apache.maven.api.services.xml.XmlReaderRequest;
import org.apache.maven.api.services.xml.XmlWriterRequest;
import org.apache.maven.plugins.shade.ShadeRequest;
import org.apache.maven.plugins.shade.Shader;
import org.apache.maven.plugins.shade.filter.Filter;
import org.apache.maven.plugins.shade.filter.MinijarFilter;
import org.apache.maven.plugins.shade.filter.SimpleFilter;
import org.apache.maven.plugins.shade.relocation.Relocator;
import org.apache.maven.plugins.shade.relocation.SerializedLambdaRelocator;
import org.apache.maven.plugins.shade.relocation.SimpleRelocator;
import org.apache.maven.plugins.shade.resource.ManifestResourceTransformer;
import org.apache.maven.plugins.shade.resource.ResourceTransformer;
import org.codehaus.plexus.util.IOUtil;

import static org.apache.maven.plugins.shade.resource.UseDependencyReducedPom.createPomReplaceTransformers;

/**
 * Creates a shaded JAR artifact (i.e. embeds additional artifacts and optionally relocates their packages).
 * The generated shaded JAR becomes either the primary artifact or a secondary artifact of the current project.
 * The actual shading process is delegated to a {@link Shader} component.
 * It supports relocating references in binary classes and optionally also in Java source files, as well as resource transformations.
 * @see org.apache.maven.plugins.shade.DefaultShader DefaultShader (the default implementation of the Shader component).
 * @see <a href="https://maven.apache.org/plugins/maven-shade-plugin/examples/resource-transformers.html">Resource Transformers</a>
 *
 * @author Jason van Zyl
 * @author Mauro Talevi
 * @author David Blevins
 * @author Hiram Chirino
 */
// CHECKSTYLE_OFF: LineLength
@Mojo(name = "shade", defaultPhase = "package")
// CHECKSTYLE_ON: LineLength
public class ShadeMojo implements org.apache.maven.api.plugin.Mojo {
    private static final String DEFAULT_PARENT_RELATIVE_PATH = "../pom.xml";

    private static final String DEFAULT_SHADER_HINT = "default";

    @Inject
    private Log log;

    /**
     * The current Maven session.
     */
    @Inject
    private Session session;

    /**
     * The current Maven project.
     */
    @Inject
    private Project project;

    /**
     * Artifacts to include/exclude from the final artifact. Artifacts are denoted by composite identifiers of the
     * general form <code>groupId:artifactId:type:classifier</code>. Since version 1.3, the wildcard characters '*' and
     * '?' can be used within the sub parts of those composite identifiers to do pattern matching. For convenience, the
     * syntax <code>groupId</code> is equivalent to <code>groupId:*:*:*</code>, <code>groupId:artifactId</code> is
     * equivalent to <code>groupId:artifactId:*:*</code> and <code>groupId:artifactId:classifier</code> is equivalent to
     * <code>groupId:artifactId:*:classifier</code>. For example:
     *
     * <pre>
     * &lt;artifactSet&gt;
     *   &lt;includes&gt;
     *     &lt;include&gt;org.apache.maven:*&lt;/include&gt;
     *   &lt;/includes&gt;
     *   &lt;excludes&gt;
     *     &lt;exclude&gt;*:maven-core&lt;/exclude&gt;
     *   &lt;/excludes&gt;
     * &lt;/artifactSet&gt;
     * </pre>
     */
    @Parameter
    private ArtifactSet artifactSet;

    /**
     * Packages to be relocated. For example:
     *
     * <pre>
     * &lt;relocations&gt;
     *   &lt;relocation&gt;
     *     &lt;pattern&gt;org.apache&lt;/pattern&gt;
     *     &lt;shadedPattern&gt;hidden.org.apache&lt;/shadedPattern&gt;
     *     &lt;includes&gt;
     *       &lt;include&gt;org.apache.maven.*&lt;/include&gt;
     *     &lt;/includes&gt;
     *     &lt;excludes&gt;
     *       &lt;exclude&gt;org.apache.maven.Public*&lt;/exclude&gt;
     *     &lt;/excludes&gt;
     *   &lt;/relocation&gt;
     *   &lt;relocation&gt;
     *     &lt;pattern&gt;Lorg/aspectj/&lt;/pattern&gt;
     *     &lt;shadedPattern&gt;Lorg/example/shaded/aspectj/&lt;/shadedPattern&gt;
     *     &lt;rawString&gt;true&lt;/rawString&gt;
     *   &lt;/relocation&gt;
     * &lt;/relocations&gt;
     * </pre>
     *
     * Each {@code relocation} item must contain a non-empty {@code pattern} element. Its value may use either {@code /} or {@code .} as separator. It will be normalized accordingly depending on the relocation context (filesystem or fully qualified class/package name).
     * The {@code shadedPattern} element is optional, if not given or empty the shaded pattern will be the same as the pattern prefixed by {@code hidden.}. Its value may use either {@code /} or {@code .} as separator. It will be normalized accordingly depending on the relocation context (filesystem or fully qualified class/package name).
     * When {@code rawString} is set to {@code true} the given pattern is treated as regular expression {@link Pattern} otherwise is treated as plain String (not supporting any wildcards).
     * The {@code includes} and {@code excludes} elements are optional and can be used to further fine-tune the set of classes to be relocated.
     * Both are <a href="https://ant.apache.org/manual/dirtasks.html#patterns">Ant-based patterns</a>, i.e. support wildcards {@code *}, {@code **} and {@code ?}.
     * If both {@code includes} and {@code excludes} are given both conditions need to be fulfilled for a relocation to happen (i.e. both included and not excluded).
     * @see <a href="https://maven.apache.org/plugins/maven-shade-plugin/examples/class-relocation.html">Class Relocation</a>
     */
    @SuppressWarnings("MismatchedReadAndWriteOfArray")
    @Parameter
    private PackageRelocation[] relocations;

    /**
     * Resource transformers to be used.
     * @see <a href="https://maven.apache.org/plugins/maven-shade-plugin/examples/resource-transformers.html">Resource Transformers</a>
     */
    @Parameter
    private ResourceTransformer[] transformers;

    /**
     * Archive Filters to be used. Allows you to specify an artifact in the form of a composite identifier as used by
     * {@link #artifactSet} and a set of include/exclude file patterns for filtering which contents of the archive are
     * added to the shaded jar. From a logical perspective, includes are processed before excludes, thus it's possible
     * to use an include to collect a set of files from the archive then use excludes to further reduce the set. By
     * default, all files are included and no files are excluded. If multiple filters apply to an artifact, the
     * intersection of the matched files will be included in the final JAR. For example:
     *
     * <pre>
     * &lt;filters&gt;
     *   &lt;filter&gt;
     *     &lt;artifact&gt;junit:junit&lt;/artifact&gt;
     *     &lt;includes&gt;
     *       &lt;include&gt;org/junit/**&lt;/include&gt;
     *     &lt;/includes&gt;
     *     &lt;excludes&gt;
     *       &lt;exclude&gt;org/junit/experimental/**&lt;/exclude&gt;
     *     &lt;/excludes&gt;
     *   &lt;/filter&gt;
     * &lt;/filters&gt;
     * </pre>
     */
    @SuppressWarnings("MismatchedReadAndWriteOfArray")
    @Parameter
    private ArchiveFilter[] filters;

    /**
     * The destination directory for the shaded artifact.
     */
    @Parameter(defaultValue = "${project.build.directory}")
    private File outputDirectory;

    /**
     * The name of the shaded artifactId.
     * <p/>
     * If you like to change the name of the native artifact, you may use the &lt;build>&lt;finalName> setting. If this
     * is set to something different than &lt;build>&lt;finalName>, no file replacement will be performed, even if
     * shadedArtifactAttached is being used.
     */
    @Parameter
    private String finalName;

    /**
     * The name of the shaded artifactId. So you may want to use a different artifactId and keep the standard version.
     * If the original artifactId was "foo" then the final artifact would be something like foo-1.0.jar. So if you
     * change the artifactId you might have something like foo-special-1.0.jar.
     */
    @Parameter(defaultValue = "${project.artifactId}")
    private String shadedArtifactId;

    /**
     * If specified, this will include only artifacts which have groupIds which start with this.
     */
    @Parameter
    private String shadedGroupFilter;

    /**
     * Defines whether the shaded artifact should be attached as classifier to the original artifact. If false, the
     * shaded jar will be the main artifact of the project.
     */
    @Parameter
    private boolean shadedArtifactAttached;

    /**
     * Flag whether to generate a simplified POM for the shaded artifact. If set to <code>true</code>, dependencies that
     * have been included into the uber JAR will be removed from the <code>&lt;dependencies&gt;</code> section of the
     * generated POM. The reduced POM will be named <code>dependency-reduced-pom.xml</code> and is stored into the same
     * directory as the shaded artifact. Unless you also specify dependencyReducedPomLocation, the plugin will create a
     * temporary file named <code>dependency-reduced-pom.xml</code> in the project basedir.
     */
    @Parameter(defaultValue = "true")
    private boolean createDependencyReducedPom;

    /**
     * Where to put the dependency reduced pom. Note: setting a value for this parameter with a directory other than
     * ${basedir} will change the value of ${basedir} for all executions that come after the shade execution. This is
     * often not what you want. This is considered an open issue with this plugin.
     *
     * @since 1.7
     */
    @Parameter(defaultValue = "${project.basedir}/dependency-reduced-pom.xml")
    private File dependencyReducedPomLocation;

    /**
     * Create a dependency-reduced POM in ${basedir}/drp-UNIQUE.pom. This avoids build collisions of parallel builds
     * without moving the dependency-reduced POM to a different directory. The property
     * maven.shade.dependency-reduced-pom is set to the generated filename.
     *
     * @since 1.7.2
     */
    @Parameter(defaultValue = "false")
    private boolean generateUniqueDependencyReducedPom;

    /**
     * Add dependency reduced POM to the JAR instead of the original one provided by the project.
     * If {@code createDependencyReducedPom} is {@code false} this parameter will be ignored.
     *
     * @since 3.3.0
     */
    @Parameter(defaultValue = "false")
    private boolean useDependencyReducedPomInJar;

    /**
     * When true, dependencies are kept in the pom but with scope 'provided'; when false, the dependency is removed.
     */
    @Parameter
    private boolean keepDependenciesWithProvidedScope;

    /**
     * When true, transitive deps of removed dependencies are promoted to direct dependencies. This should allow the
     * drop in replacement of the removed deps with the new shaded jar and everything should still work.
     */
    @Parameter
    private boolean promoteTransitiveDependencies;

    /**
     * The name of the classifier used in case the shaded artifact is attached.
     */
    @Parameter(defaultValue = "shaded")
    private String shadedClassifierName;

    /**
     * When true, it will attempt to create a sources jar as well.
     */
    @Parameter
    private boolean createSourcesJar;

    /**
     * When true, it will attempt to create a test sources jar.
     */
    @Parameter
    private boolean createTestSourcesJar;

    /**
     * When true, it will attempt to shade the contents of Java source files when creating the sources JAR. When false,
     * it will just relocate the Java source files to the shaded paths, but will not modify the actual source file
     * contents.
     * <p>
     * <b>Please note:</b> This feature uses a heuristic search & replace approach which covers many, but definitely not
     * all possible cases of source code shading and its excludes. There is no full Java parser behind this
     * functionality, which would be the only way to get this right for Java language elements. As for matching within
     * Java string constants, this is next to impossible to get 100% right, trying to guess if they are used in
     * reflection or not.
     * <p>
     * Please understand that the source shading feature is not meant as a source code generator anyway, merely as a
     * tool creating reasonably plausible source code when navigating to a relocated library class from an IDE,
     * hopefully displaying source code which makes 95% sense - no more, no less.
     */
    @Parameter(property = "shadeSourcesContent", defaultValue = "false")
    private boolean shadeSourcesContent;

    /**
     * When true, dependencies will be stripped down on the class level to only the transitive hull required for the
     * artifact. See also {@link #entryPoints}, if you wish to further optimize JAR minimization.
     * <p>
     * <em>Note:</em> This feature uses
     * <a href="https://github.com/tcurdt/jdependency">jdependency</a>. Its accuracy therefore depends on
     * jdependency's limitations.
     *
     * @since 1.4
     */
    @Parameter
    private boolean minimizeJar;

    /**
     * Use this option in order to fine-tune {@link #minimizeJar}: By default, all of the target module's classes are
     * kept and used as entry points for JAR minimization. By explicitly limiting the set of entry points, you can
     * further minimize the set of classes kept in the shaded JAR. This affects both classes in the module itself and
     * dependency classes. If {@link #minimizeJar} is inactive, this option has no effect either.
     * <p>
     * <em>Note:</em> This feature requires Java 1.8 or higher due to its use of
     * <a href="https://github.com/tcurdt/jdependency">jdependency</a>. Its accuracy therefore also depends on
     * jdependency's limitations.
     * <p>
     * Configuration example:
     * <pre>{@code
     * <minimizeJar>true</minimizeJar>
     * <entryPoints>
     *   <entryPoint>org.acme.Application</entryPoint>
     *   <entryPoint>org.acme.OtherEntryPoint</entryPoint>
     * </entryPoints>
     * }</pre>
     *
     * @since 3.5.0
     */
    @Parameter
    private Set<String> entryPoints;

    /**
     * The path to the output file for the shaded artifact. When this parameter is set, the created archive will neither
     * replace the project's main artifact nor will it be attached. Hence, this parameter causes the parameters
     * {@link #finalName}, {@link #shadedArtifactAttached}, {@link #shadedClassifierName} and
     * {@link #createDependencyReducedPom} to be ignored when used.
     *
     * @since 1.3
     */
    @Parameter
    private File outputFile;

    /**
     * You can pass here the roleHint about your own Shader implementation plexus component.
     *
     * @since 1.6
     */
    @Parameter
    private String shaderHint;

    /**
     * When true, the version of each dependency of the reduced pom will be based on the baseVersion of the original
     * dependency instead of its resolved version. For example, if the original pom (transitively) depends on
     * a:a:2.7-SNAPSHOT, if useBaseVersion is set to false, the reduced pom will depend on a:a:2.7-20130312.222222-12
     * whereas if useBaseVersion is set to true, the reduced pom will depend on a:a:2.7-SNAPSHOT.
     *
     * @since 3.0
     */
    @Parameter(defaultValue = "false")
    private boolean useBaseVersion;

    /**
     * When true, creates a shaded test-jar artifact as well.
     */
    @Parameter(defaultValue = "false")
    private boolean shadeTestJar;

    /**
     * When true, skips the execution of this MOJO.
     *
     * @since 3.3.0
     */
    @Parameter(defaultValue = "false")
    private boolean skip;

    /**
     * Extra JAR files to infuse into shaded result. Accepts list of files that must exists. If any of specified
     * files does not exist (or is not a file), Mojo will fail.
     * <p>
     * Extra JARs will be processed in same way as main JAR (if any) is: applied relocation, resource transformers
     * but <em>not filtering</em>.
     * <p>
     * Note: this feature should be used lightly, is not meant as ability to replace dependency hull! It is more
     * just a feature to be able to slightly "differentiate" shaded JAR from main only.
     *
     * @since 3.6.0
     */
    @Parameter
    private List<File> extraJars;

    /**
     * Extra Artifacts to infuse into shaded result. Accepts list of GAVs in form of
     * {@code <groupId>:<artifactId>[:<extension>[:<classifier>]]:<version>} that will be resolved. If any of them
     * cannot be resolved, Mojo will fail.
     * <p>
     * The artifacts will be resolved (not transitively), and will be processed in same way as dependency JARs
     * are (if any): applied relocation, resource transformers and filtering.
     * <p>
     * Note: this feature should be used lightly, is not meant as ability to replace dependency hull! It is more
     * just a feature to be able to slightly "differentiate" shaded JAR from main only.
     *
     * @since 3.6.0
     */
    @Parameter
    private List<String> extraArtifacts;

    /**
     * The Shader in use, selected by {@link #shaderHint}; see {@link #setupHintedShader()}.
     */
    private Shader shader;

    /**
     * All the present Shaders. Injecting a single {@code Shader} instead would pick one of them arbitrarily as
     * soon as a build extends the plugin with another implementation.
     */
    @Inject
    private Map<String, Shader> shaders;

    /**
     * The project's own artifact; resolved lazily, see {@link #projectArtifact()}.
     */
    private ResolvedArtifact projectArtifact;

    /**
     * The project's runtime dependencies in classpath order, resolved lazily,
     * see {@link #dependencyArtifacts()}.
     */
    private List<ResolvedArtifact> dependencyArtifacts;

    private ProjectManager projectManager() {
        return session.getService(ProjectManager.class);
    }

    /**
     * @throws MojoException in case of an error
     */
    @SuppressWarnings("checkstyle:methodlength")
    @Override
    public void execute() throws MojoException {
        if (skip) {
            log.info("Shading has been skipped.");
            return;
        }

        setupHintedShader();

        Set<File> artifacts = new LinkedHashSet<>();
        Set<String> artifactIds = new LinkedHashSet<>();
        Set<File> sourceArtifacts = new LinkedHashSet<>();
        Set<File> testArtifacts = new LinkedHashSet<>();
        Set<File> testSourceArtifacts = new LinkedHashSet<>();

        ResolvedArtifact projectArtifact = projectArtifact();
        ArtifactSelector artifactSelector = new ArtifactSelector(projectArtifact, artifactSet, shadedGroupFilter);

        if (artifactSelector.isSelected(projectArtifact) && !"pom".equals(projectArtifact.getType())) {
            if (invalidMainArtifact()) {
                createErrorOutput();
                throw new MojoException("Failed to create shaded artifact, " + "project main artifact does not exist.");
            }

            artifacts.add(projectArtifact.getFile().toFile());

            if (extraJars != null && !extraJars.isEmpty()) {
                for (File extraJar : extraJars) {
                    if (!Files.isRegularFile(extraJar.toPath())) {
                        throw new MojoException("Failed to create shaded artifact: parameter extraJars contains path "
                                + extraJar + " that is not a file (does not exist or is not a file)");
                    }
                    artifacts.add(extraJar);
                }
            }

            if (createSourcesJar) {
                File file = shadedSourcesArtifactFile();
                if (file.isFile()) {
                    sourceArtifacts.add(file);
                }
            }

            if (shadeTestJar) {
                File file = shadedTestArtifactFile();
                if (file.isFile()) {
                    testArtifacts.add(file);
                }
            }

            if (createTestSourcesJar) {
                File file = shadedTestSourcesArtifactFile();
                if (file.isFile()) {
                    testSourceArtifacts.add(file);
                }
            }
        }

        List<ResolvedArtifact> processedArtifacts = processArtifactSelectors(
                artifacts, artifactIds, sourceArtifacts, testArtifacts, testSourceArtifacts, artifactSelector);

        File outputJar = (outputFile != null) ? outputFile : shadedArtifactFileWithClassifier();
        File sourcesJar = shadedSourceArtifactFileWithClassifier();
        File testJar = shadedTestArtifactFileWithClassifier();
        File testSourcesJar = shadedTestSourceArtifactFileWithClassifier();

        // Now add our extra resources
        try {
            List<Filter> filters = getFilters(processedArtifacts, artifactSelector);

            List<Relocator> relocators = getRelocators();

            List<ResourceTransformer> resourceTransformers = getResourceTransformers();

            if (createDependencyReducedPom) {
                createDependencyReducedPom(artifactIds);

                if (useDependencyReducedPomInJar) {
                    // In some cases the used implementation of the resourceTransformers is immutable.
                    resourceTransformers = new ArrayList<>(resourceTransformers);
                    resourceTransformers.addAll(createPomReplaceTransformers(project, dependencyReducedPomLocation));
                }
            }

            ShadeRequest shadeRequest =
                    shadeRequest("jar", artifacts, outputJar, filters, relocators, resourceTransformers);

            shader.shade(shadeRequest);

            if (createSourcesJar) {
                ShadeRequest shadeSourcesRequest = createShadeSourcesRequest(
                        "sources-jar", sourceArtifacts, sourcesJar, filters, relocators, resourceTransformers);

                shader.shade(shadeSourcesRequest);
            }

            if (shadeTestJar) {
                ShadeRequest shadeTestRequest =
                        shadeRequest("test-jar", testArtifacts, testJar, filters, relocators, resourceTransformers);

                shader.shade(shadeTestRequest);
            }

            if (createTestSourcesJar) {
                ShadeRequest shadeTestSourcesRequest = createShadeSourcesRequest(
                        "test-sources-jar",
                        testSourceArtifacts,
                        testSourcesJar,
                        filters,
                        relocators,
                        resourceTransformers);

                shader.shade(shadeTestSourcesRequest);
            }

            if (outputFile == null) {
                boolean renamed = false;

                // rename the output file if a specific finalName is set
                // but don't rename if the finalName is the <build><finalName>
                // because this will be handled implicitly later
                if (finalName != null
                        && finalName.length() > 0 //
                        && !finalName.equals(project.getBuild().getFinalName())) {
                    String finalFileName = finalName + "." + projectArtifact.getExtension();
                    File finalFile = new File(outputDirectory, finalFileName);
                    replaceFile(finalFile, outputJar);
                    outputJar = finalFile;

                    // Also support the sources JAR
                    if (createSourcesJar) {
                        finalFileName = finalName + "-sources.jar";
                        finalFile = new File(outputDirectory, finalFileName);
                        replaceFile(finalFile, sourcesJar);
                        sourcesJar = finalFile;
                    }

                    // Also support the test JAR
                    if (shadeTestJar) {
                        finalFileName = finalName + "-tests.jar";
                        finalFile = new File(outputDirectory, finalFileName);
                        replaceFile(finalFile, testJar);
                        testJar = finalFile;
                    }

                    if (createTestSourcesJar) {
                        finalFileName = finalName + "-test-sources.jar";
                        finalFile = new File(outputDirectory, finalFileName);
                        replaceFile(finalFile, testSourcesJar);
                        testSourcesJar = finalFile;
                    }

                    renamed = true;
                }

                if (shadedArtifactAttached) {
                    log.info("Attaching shaded artifact.");
                    attachArtifact(projectArtifact.getExtension(), shadedClassifierName, outputJar);
                    if (createSourcesJar) {
                        attachArtifact("jar", shadedClassifierName + "-sources", sourcesJar);
                    }

                    if (shadeTestJar) {
                        attachArtifact("jar", shadedClassifierName + "-tests", testJar);
                    }

                    if (createTestSourcesJar) {
                        attachArtifact("jar", shadedClassifierName + "-test-sources", testSourcesJar);
                    }
                } else if (!renamed) {
                    log.info("Replacing original artifact with shaded artifact.");
                    File originalArtifact = projectArtifact.getFile() == null
                            ? null
                            : projectArtifact.getFile().toFile();
                    if (originalArtifact != null) {
                        replaceFile(originalArtifact, outputJar);

                        if (createSourcesJar) {
                            log.info("Replacing original source artifact with shaded source artifact.");
                            File shadedSources = shadedSourcesArtifactFile();

                            replaceFile(shadedSources, sourcesJar);

                            attachArtifact("jar", "sources", shadedSources);
                        }

                        if (shadeTestJar) {
                            log.info("Replacing original test artifact with shaded test artifact.");
                            File shadedTests = shadedTestArtifactFile();

                            replaceFile(shadedTests, testJar);

                            attachArtifact("jar", "tests", shadedTests);
                        }

                        if (createTestSourcesJar) {
                            log.info("Replacing original test source artifact " + "with shaded test source artifact.");
                            File shadedTestSources = shadedTestSourcesArtifactFile();

                            replaceFile(shadedTestSources, testSourcesJar);

                            attachArtifact("jar", "test-sources", shadedTestSources);
                        }
                    }
                }
            }
        } catch (Exception e) {
            throw new MojoException("Error creating shaded jar: " + e.getMessage(), e);
        }
    }

    /**
     * Attaches a file to the project as a secondary artifact.
     */
    private void attachArtifact(String extension, String classifier, File file) {
        ProducedArtifact artifact = session.createProducedArtifact(
                project.getGroupId(), project.getArtifactId(), project.getVersion(), classifier, extension, null);
        projectManager().attachArtifact(project, artifact, file.toPath());
    }

    /**
     * The project's own artifact, as produced by the packaging. Its file is {@code null} when it has not been built.
     */
    private ResolvedArtifact projectArtifact() {
        if (projectArtifact == null) {
            Path file = project.getMainArtifact()
                    .flatMap(a -> session.getService(ArtifactManager.class).getPath(a))
                    .orElse(null);
            org.apache.maven.api.Type type = project.getPackaging().type();
            projectArtifact = new ResolvedArtifact(
                    project.getGroupId(),
                    project.getArtifactId(),
                    project.getVersion(),
                    project.getVersion(),
                    type.id(),
                    type.getExtension(),
                    "",
                    "compile",
                    false,
                    file);
        }
        return projectArtifact;
    }

    /**
     * The runtime dependencies of the project, including the transitive ones, in classpath order.
     */
    private List<ResolvedArtifact> dependencyArtifacts() {
        if (dependencyArtifacts == null) {
            DependencyResolverResult result =
                    session.getService(DependencyResolver.class).resolve(session, project, PathScope.MAIN_RUNTIME);
            List<ResolvedArtifact> artifacts = new ArrayList<>();
            for (Map.Entry<Dependency, Path> entry : result.getDependencies().entrySet()) {
                Dependency dependency = entry.getKey();
                artifacts.add(new ResolvedArtifact(
                        dependency.getGroupId(),
                        dependency.getArtifactId(),
                        dependency.getVersion().toString(),
                        dependency.getBaseVersion().toString(),
                        dependency.getType().id(),
                        dependency.getExtension(),
                        dependency.getClassifier(),
                        dependency.getScope().id(),
                        dependency.isOptional(),
                        entry.getValue()));
            }
            dependencyArtifacts = artifacts;
        }
        return dependencyArtifacts;
    }

    private void createErrorOutput() {
        log.error("The project main artifact does not exist. This could have the following");
        log.error("reasons:");
        log.error("- You have invoked the goal directly from the command line. This is not");
        log.error("  supported. Please add the goal to the default lifecycle via an");
        log.error("  <execution> element in your POM and use \"mvn package\" to have it run.");
        log.error("- You have bound the goal to a lifecycle phase before \"package\". Please");
        log.error("  remove this binding from your POM such that the goal will be run in");
        log.error("  the proper phase.");
        log.error("- You removed the configuration of the maven-jar-plugin that produces the main artifact.");
    }

    private ShadeRequest shadeRequest(
            String shade,
            Set<File> artifacts,
            File outputJar,
            List<Filter> filters,
            List<Relocator> relocators,
            List<ResourceTransformer> resourceTransformers) {
        ShadeRequest shadeRequest = new ShadeRequest();
        shadeRequest.setJars(artifacts);
        shadeRequest.setUberJar(outputJar);
        shadeRequest.setFilters(filters);
        shadeRequest.setRelocators(relocators);
        shadeRequest.setResourceTransformers(toResourceTransformers(shade, resourceTransformers));
        return shadeRequest;
    }

    private ShadeRequest createShadeSourcesRequest(
            String shade,
            Set<File> testArtifacts,
            File testJar,
            List<Filter> filters,
            List<Relocator> relocators,
            List<ResourceTransformer> resourceTransformers) {
        ShadeRequest shadeSourcesRequest =
                shadeRequest(shade, testArtifacts, testJar, filters, relocators, resourceTransformers);
        shadeSourcesRequest.setShadeSourcesContent(shadeSourcesContent);
        return shadeSourcesRequest;
    }

    private void setupHintedShader() throws MojoException {
        String hint = shaderHint != null ? shaderHint : DEFAULT_SHADER_HINT;
        shader = shaders.get(hint);

        if (shader == null) {
            throw new MojoException("unable to lookup own Shader implementation with hint: '" + hint + "'");
        }
    }

    private List<ResolvedArtifact> processArtifactSelectors(
            Set<File> artifacts,
            Set<String> artifactIds,
            Set<File> sourceArtifacts,
            Set<File> testArtifacts,
            Set<File> testSourceArtifacts,
            ArtifactSelector artifactSelector)
            throws MojoException {

        List<ResolvedArtifact> excludedArtifacts = new ArrayList<>();
        List<ResolvedArtifact> pomArtifacts = new ArrayList<>();
        List<ResolvedArtifact> emptySourceArtifacts = new ArrayList<>();
        List<ResolvedArtifact> emptyTestArtifacts = new ArrayList<>();
        List<ResolvedArtifact> emptyTestSourceArtifacts = new ArrayList<>();

        ArrayList<ResolvedArtifact> processedArtifacts = new ArrayList<>();
        if (extraArtifacts != null && !extraArtifacts.isEmpty()) {
            for (String extraArtifact : extraArtifacts) {
                try {
                    ArtifactCoordinates coordinates = session.createArtifactCoordinates(extraArtifact);
                    DownloadedArtifact resolved = session.resolveArtifact(coordinates, remoteRepositories());
                    processedArtifacts.add(new ResolvedArtifact(
                            resolved.getGroupId(),
                            resolved.getArtifactId(),
                            resolved.getVersion().toString(),
                            resolved.getBaseVersion().toString(),
                            resolved.getExtension(),
                            resolved.getExtension(),
                            resolved.getClassifier(),
                            null,
                            false,
                            resolved.getPath()));
                } catch (RuntimeException e) {
                    throw new MojoException(
                            "Failed to create shaded artifact: parameter extraArtifacts contains artifact "
                                    + extraArtifact + " that is not resolvable",
                            e);
                }
            }
        }
        processedArtifacts.addAll(dependencyArtifacts());

        // for loop over COPY; as we add to the list in this loop
        for (ResolvedArtifact artifact : new ArrayList<>(processedArtifacts)) {
            if (!artifactSelector.isSelected(artifact)) {
                excludedArtifacts.add(artifact);
                continue;
            }

            if ("pom".equals(artifact.getType())) {
                pomArtifacts.add(artifact);
                continue;
            }

            log.debug("Including " + artifact.getId() + " in the shaded jar.");

            artifacts.add(artifact.getFile().toFile());
            artifactIds.add(getId(artifact));

            if (createSourcesJar) {
                ArtifactId sourcesArtifactId =
                        new ArtifactId(artifact.getGroupId(), artifact.getArtifactId(), artifact.getType(), "sources");
                if (artifactSelector.isSelected(sourcesArtifactId)) {
                    ResolvedArtifact sources = resolveArtifactForClassifier(artifact, "sources");
                    if (sources != null) {
                        if (sources.getFile().toFile().length() > 0) {
                            sourceArtifacts.add(sources.getFile().toFile());
                            processedArtifacts.add(sources);
                        } else {
                            emptySourceArtifacts.add(artifact);
                        }
                    }
                }
            }

            if (shadeTestJar) {
                ArtifactId testArtifactId =
                        new ArtifactId(artifact.getGroupId(), artifact.getArtifactId(), artifact.getType(), "tests");
                if (artifactSelector.isSelected(testArtifactId)) {
                    ResolvedArtifact tests = resolveArtifactForClassifier(artifact, "tests");
                    if (tests != null) {
                        if (tests.getFile().toFile().length() > 0) {
                            testArtifacts.add(tests.getFile().toFile());
                            processedArtifacts.add(tests);
                        } else {
                            emptyTestArtifacts.add(artifact);
                        }
                    }
                }
            }

            if (createTestSourcesJar) {
                ArtifactId testSourcesArtifactId = new ArtifactId(
                        artifact.getGroupId(), artifact.getArtifactId(), artifact.getType(), "test-sources");
                if (artifactSelector.isSelected(testSourcesArtifactId)) {
                    ResolvedArtifact testSources = resolveArtifactForClassifier(artifact, "test-sources");
                    if (testSources != null) {
                        testSourceArtifacts.add(testSources.getFile().toFile());
                        processedArtifacts.add(testSources);
                    } else {
                        emptyTestSourceArtifacts.add(artifact);
                    }
                }
            }
        }

        processedArtifacts.removeAll(excludedArtifacts);
        processedArtifacts.removeAll(pomArtifacts);
        processedArtifacts.removeAll(emptySourceArtifacts);
        processedArtifacts.removeAll(emptyTestArtifacts);
        processedArtifacts.removeAll(emptyTestSourceArtifacts);

        for (ResolvedArtifact artifact : excludedArtifacts) {
            log.debug("Excluding " + artifact.getId() + " from the shaded jar.");
        }
        for (ResolvedArtifact artifact : pomArtifacts) {
            log.debug("Skipping pom dependency " + artifact.getId() + " in the shaded jar.");
        }
        for (ResolvedArtifact artifact : emptySourceArtifacts) {
            log.warn("Skipping empty source jar " + artifact.getId() + ".");
        }
        for (ResolvedArtifact artifact : emptyTestArtifacts) {
            log.warn("Skipping empty test jar " + artifact.getId() + ".");
        }
        for (ResolvedArtifact artifact : emptyTestSourceArtifacts) {
            log.warn("Skipping empty test source jar " + artifact.getId() + ".");
        }
        return processedArtifacts;
    }

    private boolean invalidMainArtifact() {
        Path file = projectArtifact().getFile();
        return file == null || !Files.isRegularFile(file);
    }

    private void replaceFile(File oldFile, File newFile) throws MojoException {
        log.debug("Replacing " + oldFile + " with " + newFile);

        File origFile = new File(outputDirectory, "original-" + oldFile.getName());
        if (oldFile.exists() && !oldFile.renameTo(origFile)) {
            // try a gc to see if an unclosed stream needs garbage collecting
            System.gc();
            System.gc();

            if (!oldFile.renameTo(origFile)) {
                // Still didn't work. We'll do a copy
                try {
                    copyFiles(oldFile, origFile);
                } catch (IOException ex) {
                    // kind of ignorable here. We're just trying to save the original
                    log.warn(ex);
                }
            }
        }
        if (!newFile.renameTo(oldFile)) {
            // try a gc to see if an unclosed stream needs garbage collecting
            System.gc();
            System.gc();

            if (!newFile.renameTo(oldFile)) {
                // Still didn't work. We'll do a copy
                try {
                    copyFiles(newFile, oldFile);
                } catch (IOException ex) {
                    throw new MojoException("Could not replace original artifact with shaded artifact!", ex);
                }
            }
        }
    }

    private void copyFiles(File source, File target) throws IOException {
        try (InputStream in = Files.newInputStream(source.toPath());
                OutputStream out = Files.newOutputStream(target.toPath())) {
            IOUtil.copy(in, out);
        }
    }

    private ResolvedArtifact resolveArtifactForClassifier(ResolvedArtifact artifact, String classifier) {
        ArtifactCoordinates coordinates = session.createArtifactCoordinates(
                artifact.getGroupId(),
                artifact.getArtifactId(),
                artifact.getVersion(),
                classifier,
                artifact.getExtension(),
                artifact.getType());
        try {
            DownloadedArtifact resolved = session.resolveArtifact(coordinates, remoteRepositories());
            if (resolved.getPath() != null) {
                return artifact.withClassifier(classifier, resolved.getPath());
            }
            return null;
        } catch (RuntimeException e) {
            log.warn("Could not get " + classifier + " for " + artifact);
            return null;
        }
    }

    private List<RemoteRepository> remoteRepositories() {
        return projectManager().getRemoteProjectRepositories(project);
    }

    private List<Relocator> getRelocators() {
        List<Relocator> relocators = new ArrayList<>();

        if (relocations == null) {
            return relocators;
        }

        for (PackageRelocation r : relocations) {
            relocators.add(new SimpleRelocator(
                    r.getPattern(), r.getShadedPattern(), r.getIncludes(), r.getExcludes(), r.isRawString()));
            if (r.isShadeSerializedLambda()) {
                relocators.add(new SerializedLambdaRelocator(
                        r.getPattern(), r.getShadedPattern(), r.getIncludes(), r.getExcludes(), r.isRawString()));
            }
        }

        return relocators;
    }

    private List<ResourceTransformer> getResourceTransformers() throws MojoException {
        if (transformers == null) {
            return Collections.emptyList();
        }
        for (ResourceTransformer transformer : transformers) {
            if (transformer == null) {
                throw new MojoException(
                        "Failed to create shaded artifact: parameter transformers contains null (double-check XML attribute)");
            }
        }
        return Arrays.asList(transformers);
    }

    private List<Filter> getFilters(List<ResolvedArtifact> artifactCollection, ArtifactSelector artifactSelector)
            throws MojoException {
        List<Filter> filters = new ArrayList<>();
        List<SimpleFilter> simpleFilters = new ArrayList<>();

        if (this.filters != null && this.filters.length > 0) {
            Map<ResolvedArtifact, ArtifactId> artifacts = new HashMap<>();

            // artifactCollection does not contain project; that must also be subjected to filtering
            artifacts.put(projectArtifact(), projectArtifact().toId());

            for (ResolvedArtifact artifact : artifactCollection) {
                if (artifactSelector.isSelected(artifact)) {
                    artifacts.put(artifact, artifact.toId());
                }
            }

            for (ArchiveFilter filter : this.filters) {
                ArtifactId pattern = new ArtifactId(filter.getArtifact());

                Set<File> jars = new HashSet<>();

                for (Map.Entry<ResolvedArtifact, ArtifactId> entry : artifacts.entrySet()) {
                    if (entry.getValue().matches(pattern)) {
                        ResolvedArtifact artifact = entry.getKey();

                        if (artifact.getFile() != null) {
                            jars.add(artifact.getFile().toFile());
                        }

                        if (createSourcesJar) {
                            ArtifactId sourcesArtifactId = new ArtifactId(
                                    artifact.getGroupId(), artifact.getArtifactId(), artifact.getType(), "sources");
                            if (artifactSelector.isSelected(sourcesArtifactId)) {
                                ResolvedArtifact sources = resolveArtifactForClassifier(artifact, "sources");
                                if (sources != null) {
                                    jars.add(sources.getFile().toFile());
                                }
                            }
                        }

                        if (shadeTestJar) {
                            ArtifactId testsArtifactId = new ArtifactId(
                                    artifact.getGroupId(), artifact.getArtifactId(), artifact.getType(), "tests");
                            if (artifactSelector.isSelected(testsArtifactId)) {
                                ResolvedArtifact tests = resolveArtifactForClassifier(artifact, "tests");
                                if (tests != null) {
                                    jars.add(tests.getFile().toFile());
                                }
                            }
                        }
                    }
                }

                if (jars.isEmpty()) {
                    log.debug("No artifact matching filter " + filter.getArtifact());

                    continue;
                }

                simpleFilters.add(new SimpleFilter(jars, filter));
            }
        }

        filters.addAll(simpleFilters);

        if (minimizeJar) {
            if (entryPoints == null) {
                entryPoints = new HashSet<>();
            }
            log.info("Minimizing jar " + projectArtifact());

            try {
                Map<String, Path> dependencies = new LinkedHashMap<>();
                for (ResolvedArtifact dependency : dependencyArtifacts()) {
                    dependencies.put(dependency.toString(), dependency.getFile());
                }
                filters.add(new MinijarFilter(
                        project.getId(),
                        projectArtifact().getFile(),
                        project.getOutputDirectory(org.apache.maven.api.ProjectScope.MAIN),
                        dependencies,
                        log,
                        simpleFilters,
                        entryPoints));
            } catch (IOException e) {
                throw new MojoException("Failed to analyze class dependencies", e);
            }
        }

        return filters;
    }

    private File shadedArtifactFileWithClassifier() {
        ResolvedArtifact artifact = projectArtifact();
        final String shadedName = shadedArtifactId + "-" + artifact.getVersion() + "-" + shadedClassifierName + "."
                + artifact.getExtension();
        return new File(outputDirectory, shadedName);
    }

    private File shadedSourceArtifactFileWithClassifier() {
        return shadedArtifactFileWithClassifier("sources");
    }

    private File shadedTestSourceArtifactFileWithClassifier() {
        return shadedArtifactFileWithClassifier("test-sources");
    }

    private File shadedArtifactFileWithClassifier(String classifier) {
        ResolvedArtifact artifact = projectArtifact();
        final String shadedName = shadedArtifactId + "-" + artifact.getVersion() + "-" + shadedClassifierName + "-"
                + classifier + "." + artifact.getExtension();
        return new File(outputDirectory, shadedName);
    }

    private File shadedTestArtifactFileWithClassifier() {
        return shadedArtifactFileWithClassifier("tests");
    }

    private File shadedSourcesArtifactFile() {
        return shadedArtifactFile("sources");
    }

    private File shadedTestSourcesArtifactFile() {
        return shadedArtifactFile("test-sources");
    }

    private File shadedArtifactFile(String classifier) {
        ResolvedArtifact artifact = projectArtifact();

        String shadedName;

        if (project.getBuild().getFinalName() != null) {
            shadedName = project.getBuild().getFinalName() + "-" + classifier + "." + artifact.getExtension();
        } else {
            shadedName =
                    shadedArtifactId + "-" + artifact.getVersion() + "-" + classifier + "." + artifact.getExtension();
        }

        return new File(outputDirectory, shadedName);
    }

    private File shadedTestArtifactFile() {
        return shadedArtifactFile("tests");
    }

    // We need to find the direct dependencies that have been included in the uber JAR so that we can modify the
    // POM accordingly.
    private void createDependencyReducedPom(Set<String> artifactsToRemove) throws IOException {
        List<org.apache.maven.api.model.Dependency> transitiveDeps = new ArrayList<>();

        // NOTE: The resolved dependencies are the completely evaluated artifacts
        // including the system scoped artifacts with expanded values of properties used.
        for (ResolvedArtifact artifact : dependencyArtifacts()) {
            if ("pom".equals(artifact.getType())) {
                // don't include pom type dependencies in dependency reduced pom
                continue;
            }

            // promote
            transitiveDeps.add(createDependency(artifact));
        }

        // The model as written in the POM file: not interpolated and without inherited values.
        Model model = readOriginalModel();

        // The model objects are immutable, the lists are ours to change.
        List<org.apache.maven.api.model.Dependency> origDeps = new ArrayList<>(
                promoteTransitiveDependencies
                        ? transitiveDeps
                        : project.getModel().getDependencies());

        // MSHADE-185: We will remove all system scoped dependencies which usually
        // have some kind of property usage. At this time the properties within
        // such things are already evaluated.
        List<org.apache.maven.api.model.Dependency> originalDependencies = model.getDependencies();
        removeSystemScopedDependencies(artifactsToRemove, originalDependencies);

        List<org.apache.maven.api.model.Dependency> dependencies = new ArrayList<>();
        boolean modified = false;
        for (org.apache.maven.api.model.Dependency d : origDeps) {
            if (artifactsToRemove.contains(getId(d))) {
                if (keepDependenciesWithProvidedScope) {
                    if (!"provided".equals(d.getScope())) {
                        modified = true;
                        d = org.apache.maven.api.model.Dependency.newBuilder(d)
                                .scope("provided")
                                .build();
                    }
                } else {
                    modified = true;
                    continue;
                }
            }

            dependencies.add(d);
        }

        // MSHADE-155
        model = Model.newBuilder(model).artifactId(shadedArtifactId).build();

        // MSHADE-185: We will add those system scoped dependencies
        // from the non interpolated original pom file. So we keep
        // things like this: <systemPath>${tools.jar}</systemPath> intact.
        addSystemScopedDependencyFromNonInterpolatedPom(dependencies, originalDependencies);

        // Check to see if we have a reduction and if so rewrite the POM.
        rewriteDependencyReducedPomIfWeHaveReduction(dependencies, modified, transitiveDeps, model);
    }

    private Model readOriginalModel() {
        return session.getService(ModelXmlFactory.class)
                .read(XmlReaderRequest.builder()
                        .path(project.getPomPath())
                        .strict(false)
                        .build());
    }

    private void rewriteDependencyReducedPomIfWeHaveReduction(
            List<org.apache.maven.api.model.Dependency> dependencies,
            boolean modified,
            List<org.apache.maven.api.model.Dependency> transitiveDeps,
            Model model)
            throws IOException {
        if (modified) {
            for (int loopCounter = 0; modified; loopCounter++) {

                model = Model.newBuilder(model).dependencies(dependencies).build();

                if (generateUniqueDependencyReducedPom) {
                    dependencyReducedPomLocation = Files.createTempFile(
                                    project.getBasedir(), "dependency-reduced-pom-", ".xml")
                            .toFile();
                    projectManager()
                            .setProperty(
                                    project,
                                    "maven.shade.dependency-reduced-pom",
                                    dependencyReducedPomLocation.getAbsolutePath());
                } else {
                    if (dependencyReducedPomLocation == null) {
                        // MSHADE-123: We can't default to 'target' because it messes up uses of ${project.basedir}
                        dependencyReducedPomLocation = project.getBasedir()
                                .resolve("dependency-reduced-pom.xml")
                                .toFile();
                    }
                }

                File f = dependencyReducedPomLocation;
                // MSHADE-225
                // Works for now, maybe there's a better algorithm where no for-loop is required
                if (loopCounter == 0) {
                    log.info("Dependency-reduced POM written at: " + f.getAbsolutePath());
                }

                if (f.exists()) {
                    // noinspection ResultOfMethodCallIgnored
                    f.delete();
                }

                Model toWrite = model;
                Parent parent = model.getParent();
                // An unset relativePath means the default "../pom.xml", an empty one means "no relative parent":
                // resolving the latter against the base directory would point it at the project's own pom.xml.
                if (parent != null
                        && (parent.getRelativePath() == null
                                || !parent.getRelativePath().isEmpty())) {
                    String configuredPath =
                            parent.getRelativePath() == null ? DEFAULT_PARENT_RELATIVE_PATH : parent.getRelativePath();
                    File parentFile = new File(project.getBasedir().toFile(), configuredPath).getCanonicalFile();
                    if (!parentFile.isFile()) {
                        parentFile = new File(parentFile, "pom.xml");
                    }

                    parentFile = parentFile.getCanonicalFile();

                    String relPath = RelativizePath.convertToRelativePath(parentFile, f);
                    // The model writer writes every relativePath that is set, including the default. Leave the
                    // default unset: Maven 4 rejects an explicit "../pom.xml" that resolves to another project,
                    // but tolerates the implicit one (#813).
                    if (parent.getRelativePath() != null || !DEFAULT_PARENT_RELATIVE_PATH.equals(relPath)) {
                        toWrite = Model.newBuilder(model)
                                .parent(Parent.newBuilder(parent)
                                        .relativePath(relPath)
                                        .build())
                                .build();
                    }
                }

                session.getService(ModelXmlFactory.class)
                        .write(XmlWriterRequest.<Model>builder()
                                .content(toWrite)
                                .path(f.toPath())
                                .build());

                log.debug("updateExcludesInDeps()");
                modified = updateExcludesInDeps(dependencies, transitiveDeps);
            }

            // GAP: v3 called MavenProject#setFile here so that install and deploy publish the dependency-reduced
            // POM. Project has no POM file setter in v4, and ArtifactManager#setPath on the POM artifact has no
            // effect on what install publishes, so the original POM is published.
        }
    }

    private void removeSystemScopedDependencies(
            Set<String> artifactsToRemove, List<org.apache.maven.api.model.Dependency> originalDependencies) {
        for (org.apache.maven.api.model.Dependency dependency : originalDependencies) {
            if (dependency.getScope() != null && dependency.getScope().equalsIgnoreCase("system")) {
                artifactsToRemove.add(getId(dependency));
            }
        }
    }

    private void addSystemScopedDependencyFromNonInterpolatedPom(
            List<org.apache.maven.api.model.Dependency> dependencies,
            List<org.apache.maven.api.model.Dependency> originalDependencies) {
        for (org.apache.maven.api.model.Dependency dependency : originalDependencies) {
            if (dependency.getScope() != null && dependency.getScope().equalsIgnoreCase("system")) {
                dependencies.add(dependency);
            }
        }
    }

    private org.apache.maven.api.model.Dependency createDependency(ResolvedArtifact artifact) {
        org.apache.maven.api.model.Dependency.Builder dep = org.apache.maven.api.model.Dependency.newBuilder()
                .artifactId(artifact.getArtifactId())
                .groupId(artifact.getGroupId())
                .optional(String.valueOf(artifact.isOptional()))
                .scope(artifact.getScope())
                .type(artifact.getType())
                .version(useBaseVersion ? artifact.getBaseVersion() : artifact.getVersion());
        if (artifact.hasClassifier()) {
            dep.classifier(artifact.getClassifier());
        }
        return dep.build();
    }

    private String getId(ResolvedArtifact artifact) {
        return getId(artifact.getGroupId(), artifact.getArtifactId(), artifact.getType(), artifact.getClassifier());
    }

    private String getId(org.apache.maven.api.model.Dependency dependency) {
        return getId(
                dependency.getGroupId(), dependency.getArtifactId(), dependency.getType(), dependency.getClassifier());
    }

    private String getId(String groupId, String artifactId, String type, String classifier) {
        return groupId + ":" + artifactId + ":" + type + ":" + ((classifier != null) ? classifier : "");
    }

    private String getId(Dependency dependency) {
        return getId(
                dependency.getGroupId(),
                dependency.getArtifactId(),
                dependency.getType().id(),
                dependency.getClassifier());
    }

    public boolean updateExcludesInDeps(
            List<org.apache.maven.api.model.Dependency> dependencies,
            List<org.apache.maven.api.model.Dependency> transitiveDeps) {
        // The v4 ProjectBuilder creates a bare request (no root directory, properties or profiles of the session), so
        // the dependency-reduced POM cannot be built as a project to read its dependencies back. Collect the
        // dependencies that were just written instead.
        List<DependencyCoordinates> coordinates = new ArrayList<>();
        for (org.apache.maven.api.model.Dependency dependency : dependencies) {
            coordinates.add(createCoordinates(dependency));
        }

        // #819: collect with verbose conflict resolution so that dependencies omitted as conflict
        // losers (e.g. a transitive dependency shared by two classifier-distinct variants of the same
        // artifact) are retained in the graph as markers. Without this, conflict resolution under Maven 4
        // prunes the duplicate node from all but one variant, and the exclusion is only applied to that
        // single variant in the dependency-reduced-pom.
        DependencyResolverResult result = session.getService(DependencyResolver.class)
                .collect(DependencyResolverRequest.builder()
                        .session(session)
                        .requestType(DependencyResolverRequest.RequestType.COLLECT)
                        .rootArtifact(project.getPomArtifact())
                        .dependencies(coordinates)
                        .managedDependencies(project.getManagedDependencies())
                        .repositories(remoteRepositories())
                        .pathScope(PathScope.TEST_RUNTIME)
                        .verbose(true)
                        .build());
        boolean modified = false;
        if (result.getRoot() != null) {
            for (Node n2 : result.getRoot().getChildren()) {
                String artifactId2 = getId(n2.getDependency());
                log.debug("DBG n2 " + n2.getDependency() + " scope="
                        + n2.getDependency().getScope() + " id="
                        + n2.getDependency().getScope().id());

                for (Node n3 : n2.getChildren()) {
                    Dependency artifact3 = n3.getDependency();
                    // The scope a dependency has through its parent: everything below a provided dependency is
                    // provided as well. The collected nodes carry the declared scope of the dependency.
                    String scope3 =
                            "provided".equals(n2.getDependency().getScope().id())
                                    ? "provided"
                                    : artifact3.getScope().id();
                    String artifactId3 = getId(artifact3);

                    // check if it really isn't in the list of original dependencies. Maven
                    // prior to 2.0.8 may grab versions from transients instead of
                    // from the direct deps in which case they would be marked included
                    // instead of OMITTED_FOR_DUPLICATE

                    // also, if not promoting the transitives, level 2's would be included
                    boolean found = false;
                    for (org.apache.maven.api.model.Dependency dep : transitiveDeps) {
                        if (getId(dep).equals(artifactId3)) {
                            found = true;
                            break;
                        }
                    }

                    // MSHADE-311: do not add exclusion for provided transitive dep
                    //       note: MSHADE-31 introduced the exclusion logic for promoteTransitiveDependencies=true,
                    //             but as of 3.2.1 promoteTransitiveDependencies has no effect for provided deps,
                    //             which makes this fix even possible (see also MSHADE-181)
                    if (!found && !"provided".equals(scope3)) {
                        log.debug(String.format(
                                "dependency %s (scope %s) not found in transitive dependencies", artifactId3, scope3));
                        for (int i = 0; i < dependencies.size(); i++) {
                            org.apache.maven.api.model.Dependency dep = dependencies.get(i);
                            if (getId(dep).equals(artifactId2)) {
                                // MSHADE-413: First check whether the exclusion has already been added,
                                // because it's meaningless to add it more than once. Certain cases
                                // can end up adding the exclusion "forever" and cause an endless loop
                                // rewriting the whole dependency-reduced-pom.xml file.
                                if (!dependencyHasExclusion(dep, artifact3)) {
                                    log.debug(String.format(
                                            "Adding exclusion for dependency %s (scope %s) " + "to %s (scope %s)",
                                            artifactId3, scope3, getId(dep), dep.getScope()));
                                    Exclusion exclusion = Exclusion.newBuilder()
                                            .artifactId(artifact3.getArtifactId())
                                            .groupId(artifact3.getGroupId())
                                            .build();
                                    List<Exclusion> exclusions = new ArrayList<>(dep.getExclusions());
                                    exclusions.add(exclusion);
                                    dependencies.set(
                                            i,
                                            org.apache.maven.api.model.Dependency.newBuilder(dep)
                                                    .exclusions(exclusions)
                                                    .build());
                                    modified = true;
                                    break;
                                }
                            }
                        }
                    }
                }
            }
        }
        return modified;
    }

    /**
     * DependencyCoordinatesFactory#create(Session, org.apache.maven.api.model.Dependency) drops the scope, the
     * optional flag and the exclusions of the dependency, so the request is filled in here.
     */
    private DependencyCoordinates createCoordinates(org.apache.maven.api.model.Dependency dependency) {
        DependencyCoordinatesFactoryRequest.DependencyCoordinatesFactoryRequestBuilder request =
                DependencyCoordinatesFactoryRequest.builder()
                        .session(session)
                        .groupId(dependency.getGroupId())
                        .artifactId(dependency.getArtifactId())
                        .version(dependency.getVersion())
                        .classifier(dependency.getClassifier())
                        .type(dependency.getType())
                        .scope(
                                dependency.getScope() == null
                                                || dependency.getScope().isEmpty()
                                        ? "compile"
                                        : dependency.getScope())
                        .optional(Boolean.parseBoolean(dependency.getOptional()));
        for (Exclusion exclusion : dependency.getExclusions()) {
            request.exclusion(new org.apache.maven.api.Exclusion() {
                @Override
                public String getGroupId() {
                    return exclusion.getGroupId();
                }

                @Override
                public String getArtifactId() {
                    return exclusion.getArtifactId();
                }
            });
        }
        return session.getService(DependencyCoordinatesFactory.class).create(request.build());
    }

    private boolean dependencyHasExclusion(org.apache.maven.api.model.Dependency dep, Dependency exclusionToCheck) {
        boolean containsExclusion = false;
        for (Exclusion existingExclusion : dep.getExclusions()) {
            if (existingExclusion.getGroupId().equals(exclusionToCheck.getGroupId())
                    && existingExclusion.getArtifactId().equals(exclusionToCheck.getArtifactId())) {
                containsExclusion = true;
                break;
            }
        }
        return containsExclusion;
    }

    private List<ResourceTransformer> toResourceTransformers(
            String shade, List<ResourceTransformer> resourceTransformers) {
        List<ResourceTransformer> forShade = new ArrayList<>();
        ManifestResourceTransformer lastMt = null;
        for (ResourceTransformer transformer : resourceTransformers) {
            if (!(transformer instanceof ManifestResourceTransformer)) {
                forShade.add(transformer);
            } else if (((ManifestResourceTransformer) transformer).isForShade(shade)) {
                final ManifestResourceTransformer mt = (ManifestResourceTransformer) transformer;
                if (mt.isUsedForDefaultShading() && lastMt != null && !lastMt.isUsedForDefaultShading()) {
                    continue; // skip, we already have a specific transformer
                }
                if (!mt.isUsedForDefaultShading() && lastMt != null && lastMt.isUsedForDefaultShading()) {
                    forShade.remove(lastMt);
                } else if (!mt.isUsedForDefaultShading() && lastMt != null) {
                    log.warn("Ambiguous manifest transformer definition for '" + shade + "': " + mt + " / " + lastMt);
                }
                if (lastMt == null || !mt.isUsedForDefaultShading()) {
                    lastMt = mt;
                }
                forShade.add(transformer);
            }
        }
        return forShade;
    }
}
