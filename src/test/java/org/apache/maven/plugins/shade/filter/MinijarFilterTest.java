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

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import org.apache.maven.api.plugin.Log;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

public class MinijarFilterTest {

    @TempDir
    File tempFolder;

    private File outputDirectory;
    private File emptyFile;
    private Log log;
    private ArgumentCaptor<CharSequence> logCaptor;

    @BeforeEach
    public void init() throws IOException {
        this.outputDirectory = newFolder(tempFolder, "junit");
        this.emptyFile = File.createTempFile("junit", null, tempFolder);
        this.log = mock(Log.class);
        logCaptor = ArgumentCaptor.forClass(CharSequence.class);
    }

    @Test
    public void testWithMockProject() throws IOException {
        Map<String, Path> dependencies = new LinkedHashMap<>();
        dependencies.put("dep.com:dep.aid:jar:classifier2:1.0:compile", emptyFile.toPath());

        MinijarFilter mf =
                new MinijarFilter("com:aid:jar:1.9", emptyFile.toPath(), outputDirectory.toPath(), dependencies, log);

        mf.finished();

        verify(log).info(logCaptor.capture());

        assertEquals("Minimized 0 -> 0", logCaptor.getValue());
    }

    @Test
    public void testWithPomProject() throws IOException {
        // project with pom packaging and no artifact.
        @SuppressWarnings("unchecked")
        Map<String, Path> dependencies = mock(Map.class);

        MinijarFilter mf = new MinijarFilter("com:aid:pom:1.9", null, outputDirectory.toPath(), dependencies, log);

        mf.finished();

        verify(log).info(logCaptor.capture());

        // verify no access to project's dependencies
        verifyNoInteractions(dependencies);

        assertEquals("Minimized 0 -> 0", logCaptor.getValue());
    }

    @Test
    public void finsishedShouldProduceMessageForClassesTotalNonZero() {
        MinijarFilter m = new MinijarFilter(1, 50, log);

        m.finished();

        verify(log).info(logCaptor.capture());

        assertEquals("Minimized 51 -> 1 (1%)", logCaptor.getValue());
    }

    @Test
    public void finishedShouldProduceMessageForClassesTotalZero() {
        MinijarFilter m = new MinijarFilter(0, 0, log);

        m.finished();

        verify(log).info(logCaptor.capture());

        assertEquals("Minimized 0 -> 0", logCaptor.getValue());
    }

    private static File newFolder(File root, String... subDirs) throws IOException {
        String subFolder = String.join("/", subDirs);
        File result = new File(root, subFolder);
        if (!result.mkdirs()) {
            throw new IOException("Couldn't create folders " + root);
        }
        return result;
    }
}
