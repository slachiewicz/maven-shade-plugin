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

import java.nio.file.Path;
import java.util.Objects;

/**
 * An artifact the mojo works with: the project's main artifact, a resolved dependency, or one of their
 * classified companions (sources, tests). Carries the coordinates the artifact selector matches on, the
 * version in both forms, the dependency scope and the file.
 */
final class ResolvedArtifact {
    private final String groupId;
    private final String artifactId;
    private final String version;
    private final String baseVersion;
    private final String type;
    private final String extension;
    private final String classifier;
    private final String scope;
    private final boolean optional;
    private final Path file;

    @SuppressWarnings("checkstyle:parameternumber")
    ResolvedArtifact(
            String groupId,
            String artifactId,
            String version,
            String baseVersion,
            String type,
            String extension,
            String classifier,
            String scope,
            boolean optional,
            Path file) {
        this.groupId = groupId;
        this.artifactId = artifactId;
        this.version = version;
        this.baseVersion = baseVersion;
        this.type = type;
        this.extension = extension;
        this.classifier = classifier == null ? "" : classifier;
        this.scope = scope;
        this.optional = optional;
        this.file = file;
    }

    String getGroupId() {
        return groupId;
    }

    String getArtifactId() {
        return artifactId;
    }

    String getVersion() {
        return version;
    }

    String getBaseVersion() {
        return baseVersion;
    }

    String getType() {
        return type;
    }

    String getExtension() {
        return extension;
    }

    String getClassifier() {
        return classifier;
    }

    boolean hasClassifier() {
        return !classifier.isEmpty();
    }

    String getScope() {
        return scope;
    }

    boolean isOptional() {
        return optional;
    }

    Path getFile() {
        return file;
    }

    ArtifactId toId() {
        return new ArtifactId(groupId, artifactId, type, classifier);
    }

    /**
     * Same artifact with another classifier and file.
     */
    ResolvedArtifact withClassifier(String newClassifier, Path newFile) {
        return new ResolvedArtifact(
                groupId, artifactId, version, baseVersion, type, extension, newClassifier, scope, optional, newFile);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof ResolvedArtifact)) {
            return false;
        }
        ResolvedArtifact that = (ResolvedArtifact) o;
        return groupId.equals(that.groupId)
                && artifactId.equals(that.artifactId)
                && Objects.equals(version, that.version)
                && Objects.equals(type, that.type)
                && classifier.equals(that.classifier);
    }

    @Override
    public int hashCode() {
        return Objects.hash(groupId, artifactId, version, type, classifier);
    }

    /**
     * Identifies the artifact as {@code groupId:artifactId:type[:classifier]:version}.
     */
    String getId() {
        StringBuilder sb = new StringBuilder();
        sb.append(groupId).append(':').append(artifactId).append(':').append(type);
        if (hasClassifier()) {
            sb.append(':').append(classifier);
        }
        sb.append(':').append(version);
        return sb.toString();
    }

    @Override
    public String toString() {
        return scope == null ? getId() : getId() + ':' + scope;
    }
}
