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

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.util.Arrays;

import javax.annotation.Nullable;

import com.google.common.primitives.Chars;

import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpHeaders;

import io.netty.buffer.ByteBufHolder;

public class StringAggregatedWriter implements ContentPreviewWriter {

    private final CharBuffer buffer;
    private final Charset defaultCharset;
    @Nullable
    private CharsetDecoder decoder;

    @Nullable
    private String produced;

    private byte remainedByte;
    private boolean remained;
    /**
     * TODO:Javadocs.
     */
    public StringAggregatedWriter(int length, Charset defaultCharset) {
        buffer = CharBuffer.allocate(length);
        this.defaultCharset = defaultCharset;
    }

    @Override
    public void write(HttpHeaders headers, HttpData data) {
        if (produced != null) {
            return;
        }
        if (decoder == null) {
            decoder = headers.contentType().charset().orElse(defaultCharset).newDecoder();
        }
        if (data instanceof ByteBufHolder) {
            Arrays.stream(((ByteBufHolder) data).content().nioBuffers()).forEach(this::append);
        } else {
            append(ByteBuffer.wrap(data.array(), 0, data.length()));
        }
    }

    @Override
    public String produce() {
        if (produced != null) {
            return produced;
        }
        return produced = new String(buffer.array(), 0, buffer.position());
    }

    private void append(ByteBuffer buf) {
        if (produced != null || !buffer.hasRemaining() || !buf.hasRemaining()) {
            return;
        }
        if (remained) {
            buffer.put(Chars.fromBytes(remainedByte, buf.get()));
            remained = false;
        }
        if (!buffer.hasRemaining()) {
            produce();
        } else {
            if (decoder.decode(buf, buffer, true).isUnderflow()) {
                remained = true;
                remainedByte = buf.get();
            }
        }
    }
}
