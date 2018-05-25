/*
 * Copyright 2018 GoDataDriven B.V.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.divolte.server.recordmapping;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;

import javax.annotation.ParametersAreNonnullByDefault;
import java.util.Optional;

@ParametersAreNonnullByDefault
public class JacksonSupport {
    private JacksonSupport() {
        // Prevent external instantiation.
    }

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final ObjectReader OBJECT_READER = OBJECT_MAPPER.reader()
                                                                   .with(DeserializationFeature.ACCEPT_SINGLE_VALUE_AS_ARRAY,
                                                                         DeserializationFeature.UNWRAP_SINGLE_VALUE_ARRAYS)
                                                                   .without(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    static AvroGenericRecordMapper createAvroMapper() {
        return new AvroGenericRecordMapper(OBJECT_READER);
    }

    static Optional<String> toString(final Object jsonNode) {
        try {
            return Optional.of(OBJECT_MAPPER.convertValue(jsonNode, String.class));
        } catch (final IllegalArgumentException e) {
            return Optional.empty();
        }
    }
}
