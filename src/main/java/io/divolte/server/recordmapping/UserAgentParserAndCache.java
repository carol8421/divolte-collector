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

import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import io.divolte.server.config.UserAgentParserConfiguration;
import io.divolte.server.config.ValidatedConfiguration;
import net.sf.uadetector.ReadableUserAgent;
import net.sf.uadetector.UserAgentStringParser;
import net.sf.uadetector.service.UADetectorServiceFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.ParametersAreNonnullByDefault;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.function.Function;

@ParametersAreNonnullByDefault
public final class UserAgentParserAndCache {
    private final static Logger logger = LoggerFactory.getLogger(UserAgentParserAndCache.class);

    private final LoadingCache<String,ReadableUserAgent> cache;

    public UserAgentParserAndCache(final ValidatedConfiguration vc) {
        final UserAgentStringParser parser = parserBasedOnTypeConfig(vc.configuration().global.mapper.userAgentParser.type);
        this.cache = sizeBoundCacheFromLoadingFunction(parser::parse, vc.configuration().global.mapper.userAgentParser.cacheSize);
        logger.info("User agent parser data version: {}", parser.getDataVersion());
    }

    public Optional<ReadableUserAgent> tryParse(final String userAgentString) {
        try {
            return Optional.of(cache.get(userAgentString));
        } catch (final ExecutionException e) {
            logger.debug("Failed to parse user agent string for: " + userAgentString);
            return Optional.empty();
        }
    }

    private static UserAgentStringParser parserBasedOnTypeConfig(UserAgentParserConfiguration.ParserType type) {
        switch (type) {
        case CACHING_AND_UPDATING:
            logger.info("Using caching and updating user agent parser.");
            return UADetectorServiceFactory.getCachingAndUpdatingParser();
        case ONLINE_UPDATING:
            logger.info("Using online updating user agent parser.");
            return UADetectorServiceFactory.getOnlineUpdatingParser();
        case NON_UPDATING:
            logger.info("Using non-updating (resource module based) user agent parser.");
            return UADetectorServiceFactory.getResourceModuleParser();
        default:
            throw new IllegalArgumentException("Invalid user agent parser type. Valid values are: caching_and_updating, online_updating, non_updating.");
        }
    }

    private static <K,V> LoadingCache<K, V> sizeBoundCacheFromLoadingFunction(Function<K, V> loader, int size) {
        return CacheBuilder
                .newBuilder()
                .maximumSize(size)
                .initialCapacity(size)
                .build(new CacheLoader<K, V>() {
                    @Override
                    public V load(K key) throws Exception {
                        return loader.apply(key);
                    }
                });
    }
}
