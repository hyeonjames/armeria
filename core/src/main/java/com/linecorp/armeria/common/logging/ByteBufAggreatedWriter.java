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

import static com.google.common.base.Preconditions.checkState;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiFunction;

import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpHeaders;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufHolder;
import io.netty.buffer.Unpooled;
import io.netty.util.ReferenceCountUtil;

public class ByteBufAggreatedWriter implements ContentPreviewWriter {

    public static final ByteBuf[] BYTE_BUFS = new ByteBuf[0];
    private final int capacity;
    private final List<ByteBuf> bufferList;
    private final BiFunction<HttpHeaders, ByteBuf, String> reproducer;
    private boolean appendable;
    private HttpHeaders headers;
    private String produced;
    private int aggregatedLength;

    /**
     * TODO: Javadocs.
     */
    public ByteBufAggreatedWriter(int capacity, BiFunction<HttpHeaders, ByteBuf, String> reproducer) {
        this.capacity = capacity;
        bufferList = new ArrayList<>();
        appendable = false;
        this.reproducer = reproducer;
    }

    @Override
    public void write(HttpHeaders headers, HttpData data) {
        this.headers = headers;
        if (data.isEmpty() || !appendable) {
            return;
        }
        ByteBuf newBuffer;
        if (data instanceof ByteBufHolder) {
            newBuffer = ((ByteBufHolder) data).content();
        } else {
            newBuffer = Unpooled.wrappedBuffer(data.array(), data.offset(), data.length());
        }
        aggregatedLength += data.length();
        bufferList.add(newBuffer.retainedDuplicate());
        if (aggregatedLength >= capacity) {
            produce();
        }
    }

    @Override
    public String produce() {
        if (produced != null) {
            return produced;
        }
        checkState(appendable, "");
        appendable = false;
        try {
            return produced = reproducer.apply(headers, Unpooled.wrappedBuffer(bufferList.toArray(
                    BYTE_BUFS)));
        } finally {
            bufferList.forEach(ReferenceCountUtil::safeRelease);
            bufferList.clear();
        }
    }
}
