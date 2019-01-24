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

import java.nio.CharBuffer;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;

import javax.annotation.Nullable;

import io.netty.buffer.ByteBuf;

public class CharArrayAggregater implements ByteBufAppendable {
    private final CharBuffer charBuffer;
    private final Charset charset;
    private final CharsetDecoder decoder;
    @Nullable
    private ByteBuf remainedBuffer;

    CharArrayAggregater(int capacity, Charset charset) {
        charBuffer = CharBuffer.allocate(capacity);
        this.charset = charset;
        decoder = charset.newDecoder();
        remainedBuffer = null;
    }

    public Object append(ByteBuf buf) {

    }
}
