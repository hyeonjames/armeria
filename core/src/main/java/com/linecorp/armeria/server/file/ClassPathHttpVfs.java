/*
 * Copyright 2016 LINE Corporation
 *
 * LINE Corporation licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package com.linecorp.armeria.server.file;

import static java.util.Objects.requireNonNull;

import java.time.Clock;

import javax.annotation.Nullable;

import com.linecorp.armeria.internal.PathMappingUtil;

final class ClassPathHttpVfs extends AbstractHttpVfs {

    private final ClassLoader classLoader;
    private final String rootDir;

    ClassPathHttpVfs(ClassLoader classLoader, String rootDir) {
        this.classLoader = requireNonNull(classLoader, "classLoader");
        this.rootDir = normalizeRootDir(rootDir);
    }

    private static String normalizeRootDir(String rootDir) {
        requireNonNull(rootDir, "rootDir");
        if (rootDir.startsWith("/")) {
            rootDir = rootDir.substring(1);
        }

        if (rootDir.endsWith("/")) {
            rootDir = rootDir.substring(0, rootDir.length() - 1);
        }

        return rootDir;
    }

    @Override
    public HttpFile get(String path, Clock clock,
                        @Nullable String contentEncoding) {
        PathMappingUtil.ensureAbsolutePath(path, "path");
        final String resourcePath = rootDir.isEmpty() ? path.substring(1) : rootDir + path;
        final HttpFileBuilder builder = HttpFileBuilder.ofResource(classLoader, resourcePath);
        return FileSystemHttpVfs.build(builder, clock, path, contentEncoding);
    }

    @Override
    public String meterTag() {
        return "classpath:" + rootDir;
    }
}
