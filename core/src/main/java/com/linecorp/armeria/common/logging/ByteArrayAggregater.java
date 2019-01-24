/*
 * Copyright 2019 LINE Corporation
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
package com.linecorp.armeria.common.logging;

import java.util.function.Function;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

public class ByteArrayAggregater implements ByteBufAppendable {

    private final int capacity;
    private final ByteBuf buf;
    private final Function<ByteBuf, String> reproducer;

    /**
     * .
     */
    public ByteArrayAggregater(int capacity, Function<ByteBuf, String> reproducer) {
        this.capacity = capacity;
        this.reproducer = reproducer;
        buf = Unpooled.buffer(capacity);
    }

    @Override
    public Object append(ByteBuf buf) {
        try {
            this.buf.writeBytes(buf, Math.min(buf.readableBytes(),this.buf.writableBytes()));
            if (this.buf.writerIndex() >= capacity) {
                return produce();
            }
        } catch (Exception ex) {
            throw new IllegalStateException("append() raised an exception", ex);
        }

        return this;
    }

    private String produce() {
        if (buf.writerIndex() == 0) {
            return "";
        }
        return reproducer.apply(buf.asReadOnly());
    }

    @Override
    public String toString() {
        return produce();
    }
}
