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

import static java.util.Objects.requireNonNull;

public class ChainedCorsPolicyBuilder extends AbstractCorsPolicyBuilder<ChainedCorsPolicyBuilder> {
    private final CorsServiceBuilder serviceBuilder;

    ChainedCorsPolicyBuilder(CorsServiceBuilder builder) {
        super();
        requireNonNull(builder, "builder");
        serviceBuilder = builder;
    }

    ChainedCorsPolicyBuilder(CorsServiceBuilder builder, final String... origins) {
        super(origins);
        requireNonNull(builder, "builder");
        serviceBuilder = builder;
    }

    /**
     * Returns the parent {@link CorsServiceBuilder}.
     */
    public CorsServiceBuilder and() {
        return serviceBuilder;
    }

    /**
     * Create a new instance of {@link ChainedCorsPolicyBuilder} added to the parent {@link CorsServiceBuilder}.
     * @return the created instance.
     */
    public ChainedCorsPolicyBuilder andForOrigins(final String... origins) {
        return and().andForOrigins(origins);
    }
}
