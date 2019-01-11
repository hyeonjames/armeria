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

package com.linecorp.armeria.server.cors;

import java.time.Instant;
import java.util.Collections;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import javax.annotation.Nullable;

import com.google.common.base.Ascii;
import com.google.common.base.MoreObjects;
import com.google.common.collect.ImmutableSet;

/**
 * <a href="https://en.wikipedia.org/wiki/Cross-origin_resource_sharing">Cross-Origin Resource Sharing
 * (CORS)</a> configuration.
 *
 * @see CorsServiceBuilder
 * @see CorsService#config()
 */
public final class CorsConfig {

    /**
     * {@link CorsConfig} with CORS disabled.
     */
    public static final CorsConfig DISABLED = new CorsConfig();

    private final boolean enabled;
    private final boolean anyOriginSupported;
    private final Set<CorsPolicy> policies;

    CorsConfig() {
        enabled = false;
        anyOriginSupported = false;
        policies = Collections.emptySet();
    }

    CorsConfig(final CorsServiceBuilder builder) {
        enabled = true;
        anyOriginSupported = builder.anyOriginSupported;
        policies = new ImmutableSet.Builder<CorsPolicy>()
                                   .add(builder.defaultPolicyBuilder.build())
                                   .addAll(builder.policies)
                                   .addAll(builder.policyBuilders.stream().map(AbstractCorsPolicyBuilder::build)
                                                                 .collect(Collectors.toSet()))
                                   .build();
    }

    /**
     * Determines if support for CORS is enabled.
     *
     * @return {@code true} if support for CORS is enabled, false otherwise.
     */
    public boolean isEnabled() {
        return enabled;
    }

    /**
     * Determines whether a wildcard origin, '*', is supported.
     *
     * @return {@code true} if any origin is allowed.
     *
     * @throws IllegalStateException if CORS support is not enabled
     */
    public boolean isAnyOriginSupported() {
        ensureEnabled();
        return anyOriginSupported;
    }

    /**
     * Returns the policies.
     *
     * @throws IllegalStateException if CORS support is not enabled.
     */
    public Set<CorsPolicy> policies() {
        ensureEnabled();
        return policies;
    }

    /**
     * Returns the policy for the specific {@code origin}.
     *
     * @return {@link CorsPolicy} which allows the {@code origin},
     *         {@code null} if the {@code origin} is not allowed in any policy.
     */
    @Nullable
    CorsPolicy getPolicy(String origin) {
        if (isAnyOriginSupported() && CorsService.ANY_ORIGIN.equals(origin)) {
            return policies.iterator().next();
        }
        final String lowerCaseOrigin = Ascii.toLowerCase(origin);
        final boolean isNullOrigin = CorsService.NULL_ORIGIN.equals(lowerCaseOrigin);
        for (CorsPolicy policy : policies) {
            if (isNullOrigin && policy.isNullOriginAllowed()) {
                return policy;
            } else if (policy.origins().contains(lowerCaseOrigin)) {
                return policy;
            }
        }
        return null;
    }

    private void ensureEnabled() {
        if (!isEnabled()) {
            throw new IllegalStateException("CORS support not enabled");
        }
    }

    private static <T> T getValue(final Supplier<T> callable) {
        try {
            return callable.get();
        } catch (final Exception e) {
            throw new IllegalStateException("could not generate value for supplier: " + callable, e);
        }
    }

    @Override
    public String toString() {
        return toString(this, enabled, anyOriginSupported, policies);
    }

    static String toString(Object obj, boolean enabled, boolean anyOriginSupported,
                           Set<CorsPolicy> policies) {
        if (enabled) {
            return MoreObjects.toStringHelper(obj)
                              .add("policies", policies)
                              .add("anyOriginSupported", anyOriginSupported).toString();
        } else {
            return obj.getClass().getSimpleName() + "{disabled}";
        }
    }

    /**
     * This class is used for preflight HTTP response values that do not need to be
     * generated, but instead the value is "static" in that the same value will be returned
     * for each call.
     */
    static final class ConstantValueSupplier implements Supplier<Object> {

        static final ConstantValueSupplier ZERO = new ConstantValueSupplier("0");

        private final Object value;

        ConstantValueSupplier(final Object value) {
            this.value = value;
        }

        @Override
        public Object get() {
            return value;
        }

        @Override
        public String toString() {
            return String.valueOf(value);
        }
    }

    /**
     * This {@link Supplier} is used for the DATE preflight HTTP response HTTP header.
     * It's value must be generated when the response is generated, hence will be
     * different for every call.
     */
    static final class InstantValueSupplier implements Supplier<Instant> {

        static final InstantValueSupplier INSTANCE = new InstantValueSupplier();

        @Override
        public Instant get() {
            return Instant.now();
        }

        @Override
        public String toString() {
            return "<now>";
        }
    }
}
