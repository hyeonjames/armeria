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

import java.io.IOException;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;

import javax.annotation.Nullable;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.RemovalListener;
import com.google.common.base.Splitter;
import com.google.common.util.concurrent.MoreExecutors;

import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.metric.MeterIdPrefix;
import com.linecorp.armeria.common.util.Exceptions;
import com.linecorp.armeria.internal.metric.CaffeineMetricSupport;
import com.linecorp.armeria.server.AbstractHttpService;
import com.linecorp.armeria.server.HttpResponseException;
import com.linecorp.armeria.server.HttpService;
import com.linecorp.armeria.server.PathMapping;
import com.linecorp.armeria.server.Service;
import com.linecorp.armeria.server.ServiceConfig;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.server.encoding.HttpEncodingService;

import io.micrometer.core.instrument.MeterRegistry;
import io.netty.buffer.ByteBufHolder;

/**
 * An {@link HttpService} that serves static files from a file system.
 *
 * @see HttpFileServiceBuilder
 */
public final class HttpFileService extends AbstractHttpService {

    private static final Logger logger = LoggerFactory.getLogger(HttpFileService.class);

    private static final Splitter COMMA_SPLITTER = Splitter.on(',');

    /**
     * Creates a new {@link HttpFileService} for the specified {@code rootDir} in an O/S file system.
     */
    public static HttpFileService forFileSystem(String rootDir) {
        return HttpFileServiceBuilder.forFileSystem(rootDir).build();
    }

    /**
     * Creates a new {@link HttpFileService} for the specified {@code rootDir} in an O/S file system.
     */
    public static HttpFileService forFileSystem(Path rootDir) {
        return HttpFileServiceBuilder.forFileSystem(rootDir).build();
    }

    /**
     * Creates a new {@link HttpFileService} for the specified {@code rootDir} in the current class path.
     */
    public static HttpFileService forClassPath(String rootDir) {
        return HttpFileServiceBuilder.forClassPath(rootDir).build();
    }

    /**
     * Creates a new {@link HttpFileService} for the specified {@code rootDir} in the current class path.
     */
    public static HttpFileService forClassPath(ClassLoader classLoader, String rootDir) {
        return HttpFileServiceBuilder.forClassPath(classLoader, rootDir).build();
    }

    /**
     * Creates a new {@link HttpFileService} for the specified {@link HttpVfs}.
     */
    public static HttpFileService forVfs(HttpVfs vfs) {
        return HttpFileServiceBuilder.forVfs(vfs).build();
    }

    private final HttpFileServiceConfig config;

    @Nullable
    private final Cache<PathAndEncoding, AggregatedHttpFile> cache;

    HttpFileService(HttpFileServiceConfig config) {
        this.config = requireNonNull(config, "config");
        if (config.maxCacheEntries() != 0) {
            cache = newCache(config);
        } else {
            cache = null;
        }
    }

    private static Cache<PathAndEncoding, AggregatedHttpFile> newCache(HttpFileServiceConfig config) {
        final Caffeine<Object, Object> b = Caffeine.newBuilder();
        b.maximumSize(config.maxCacheEntries())
         .recordStats()
         .removalListener((RemovalListener<PathAndEncoding, AggregatedHttpFile>) (key, value, cause) -> {
             if (value != null) {
                 final HttpData content = value.content();
                 if (content instanceof ByteBufHolder) {
                     ((ByteBufHolder) content).release();
                 }
             }
         });
        return b.build();
    }

    @Override
    public void serviceAdded(ServiceConfig cfg) throws Exception {
        final MeterRegistry registry = cfg.server().meterRegistry();
        if (cache != null) {
            CaffeineMetricSupport.setup(
                    registry,
                    new MeterIdPrefix("armeria.server.file.vfsCache",
                                      "hostnamePattern", cfg.virtualHost().hostnamePattern(),
                                      "pathMapping", cfg.pathMapping().meterTag(),
                                      "vfs", config.vfs().meterTag()),
                    cache);
        }
    }

    @Override
    public boolean shouldCachePath(String path, @Nullable String query, PathMapping pathMapping) {
        // We assume that if a file cache is enabled, the number of paths is also finite.
        return cache != null;
    }

    /**
     * Returns the configuration.
     */
    public HttpFileServiceConfig config() {
        return config;
    }

    @Override
    protected HttpResponse doGet(ServiceRequestContext ctx, HttpRequest req) throws Exception {
        final HttpFile file = findFile(ctx, req);
        if (file == null) {
            return HttpResponse.of(HttpStatus.NOT_FOUND);
        }
        return file.asService().serve(ctx, req);
    }

    @Nullable
    private HttpFile findFile(ServiceRequestContext ctx, HttpRequest req) throws IOException {
        final String decodedMappedPath = ctx.decodedMappedPath();

        final EnumSet<FileServiceContentEncoding> supportedEncodings =
                EnumSet.noneOf(FileServiceContentEncoding.class);

        if (config.serveCompressedFiles()) {
            // We do a simple parse of the accept-encoding header, without worrying about star values
            // or priorities.
            final String acceptEncoding = req.headers().get(HttpHeaderNames.ACCEPT_ENCODING);
            if (acceptEncoding != null) {
                for (String encoding : COMMA_SPLITTER.split(acceptEncoding)) {
                    for (FileServiceContentEncoding possibleEncoding : FileServiceContentEncoding.values()) {
                        if (encoding.contains(possibleEncoding.headerValue)) {
                            supportedEncodings.add(possibleEncoding);
                        }
                    }
                }
            }
        }

        final HttpFile file = findFile(ctx, decodedMappedPath, supportedEncodings);
        if (file != null) {
            return file;
        }

        final boolean endsWithSlash = decodedMappedPath.charAt(decodedMappedPath.length() - 1) == '/';
        if (endsWithSlash) {
            // Try index.html if it was a directory access.
            final HttpFile indexFile = findFile(ctx, decodedMappedPath + "index.html", supportedEncodings);
            if (indexFile != null) {
                return indexFile;
            }

            // Auto-generate directory listing if enabled.
            if (config.autoIndex() && config.vfs().canList(decodedMappedPath)) {
                final List<String> listing = config.vfs().list(decodedMappedPath);
                final HttpData autoIndex =
                        AutoIndex.listingToHtml(ctx.decodedPath(), decodedMappedPath, listing);
                return HttpFileBuilder.of(autoIndex)
                                      .addHeader(HttpHeaderNames.CONTENT_TYPE, MediaType.HTML_UTF_8)
                                      .build();
            }
        } else {
            // Redirect to the slash appended path if 1) /index.html exists or 2) it has a directory listing.
            if (findFile(ctx, decodedMappedPath + "/index.html", supportedEncodings) != null ||
                config.autoIndex() && config.vfs().canList(decodedMappedPath)) {
                throw HttpResponseException.of(HttpResponse.of(
                        HttpHeaders.of(HttpStatus.TEMPORARY_REDIRECT)
                                   .set(HttpHeaderNames.LOCATION, ctx.path() + '/')));
            }
        }

        return null;
    }

    @Nullable
    private HttpFile findFile(ServiceRequestContext ctx, String path,
                              EnumSet<FileServiceContentEncoding> supportedEncodings) throws IOException {
        for (FileServiceContentEncoding encoding : supportedEncodings) {
            final String contentEncoding = encoding.headerValue;
            final HttpFile file = findFile(ctx, path + encoding.extension, contentEncoding);
            if (file != null) {
                return file;
            }
        }

        return findFile(ctx, path, (String) null);
    }

    @Nullable
    private HttpFile findFile(ServiceRequestContext ctx, String path,
                              @Nullable String contentEncoding) throws IOException {
        final HttpFile uncachedFile = config.vfs().get(path, config.clock(), contentEncoding);
        final HttpFileAttributes uncachedAttrs = uncachedFile.readAttributes();
        if (cache == null) {
            return uncachedAttrs != null ? uncachedFile : null;
        }

        final PathAndEncoding pathAndEncoding = new PathAndEncoding(path, contentEncoding);
        if (uncachedAttrs == null) {
            // Non-existent file. Invalidate the cache just in case it existed before.
            cache.invalidate(pathAndEncoding);
            return null;
        }

        if (uncachedAttrs.length() > config.maxCacheEntrySizeBytes()) {
            // Invalidate the cache just in case the file was small previously.
            cache.invalidate(pathAndEncoding);
            return uncachedFile;
        }

        final AggregatedHttpFile cachedFile = cache.getIfPresent(pathAndEncoding);
        if (cachedFile == null) {
            // Cache miss. Add a new entry to the cache.
            return cache(ctx, pathAndEncoding, uncachedFile);
        }

        final HttpFileAttributes cachedAttrs = cachedFile.readAttributes();
        assert cachedAttrs != null;
        if (cachedAttrs.equals(uncachedAttrs)) {
            // Cache hit, and the cached file is up-to-date.
            return cachedFile;
        }

        // Cache hit, but the cached file is out of date. Replace the old entry from the cache.
        cache.invalidate(pathAndEncoding);
        return cache(ctx, pathAndEncoding, uncachedFile);
    }

    private HttpFile cache(ServiceRequestContext ctx, PathAndEncoding pathAndEncoding, HttpFile file) {
        assert cache != null;

        // TODO(trustin): We assume here that the file being read is small enough that it will not block
        //                an event loop for a long time. Revisit if the assumption turns out to be false.
        final AggregatedHttpFile cachedFile = cache.get(pathAndEncoding, key -> {
            try {
                return file.aggregateWithPooledObjects(MoreExecutors.directExecutor(), ctx.alloc()).get();
            } catch (Exception e) {
                logger.warn("{} Failed to cache a file: {}", ctx, file, Exceptions.peel(e));
                return null;
            }
        });

        return cachedFile != null ? cachedFile : file;
    }

    /**
     * Creates a new {@link HttpService} that tries this {@link HttpFileService} first and then the specified
     * {@link HttpService} when this {@link HttpFileService} does not have a requested resource.
     *
     * @param nextService the {@link HttpService} to try secondly
     */
    public HttpService orElse(Service<HttpRequest, HttpResponse> nextService) {
        requireNonNull(nextService, "nextService");
        return new OrElseHttpService(this, nextService);
    }

    private static final class OrElseHttpService extends AbstractHttpService {

        private final HttpFileService first;
        private final Service<HttpRequest, HttpResponse> second;

        OrElseHttpService(HttpFileService first, Service<HttpRequest, HttpResponse> second) {
            this.first = first;
            this.second = second;
        }

        @Override
        public void serviceAdded(ServiceConfig cfg) throws Exception {
            first.serviceAdded(cfg);
            second.serviceAdded(cfg);
        }

        @Override
        public HttpResponse serve(ServiceRequestContext ctx, HttpRequest req) throws Exception {
            if (first.findFile(ctx, req) != null) {
                return first.serve(ctx, req);
            } else {
                return second.serve(ctx, req);
            }
        }

        @Override
        public boolean shouldCachePath(String path, @Nullable String query, PathMapping pathMapping) {
            // No good way of propagating the first vs second decision to the cache decision, so just make a
            // best effort, it should work for most cases.
            return first.shouldCachePath(path, query, pathMapping) &&
                   second.shouldCachePath(path, query, pathMapping);
        }
    }

    /**
     * Content encodings supported by {@link HttpFileService}. Will generally support more formats than
     * {@link HttpEncodingService} because new formats can be added as soon as browsers and build tools
     * support them, without having to implement on-the-fly compression.
     */
    private enum FileServiceContentEncoding {
        // Order matters, we use the enum ordinal as the priority to pick an encoding in. Encodings should
        // be ordered by priority.
        BROTLI(".br", "br"),
        GZIP(".gz", "gzip");

        private final String extension;
        private final String headerValue;

        FileServiceContentEncoding(String extension, String headerValue) {
            this.extension = extension;
            this.headerValue = headerValue;
        }
    }

    private static final class PathAndEncoding {
        private final String path;
        @Nullable
        private final String contentEncoding;

        PathAndEncoding(String path, @Nullable String contentEncoding) {
            this.path = path;
            this.contentEncoding = contentEncoding;
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) {
                return true;
            }
            if (!(obj instanceof PathAndEncoding)) {
                return false;
            }
            return path.equals(((PathAndEncoding) obj).path) &&
                   Objects.equals(contentEncoding, ((PathAndEncoding) obj).contentEncoding);
        }

        @Override
        public int hashCode() {
            return path.hashCode() * 31 + Objects.hashCode(contentEncoding);
        }
    }
}
