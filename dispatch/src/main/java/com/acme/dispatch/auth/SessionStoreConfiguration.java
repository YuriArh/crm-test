package com.acme.dispatch.auth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.convert.ConversionService;
import org.springframework.core.convert.converter.Converter;
import org.springframework.core.convert.support.GenericConversionService;
import org.springframework.core.serializer.support.DeserializingConverter;
import org.springframework.core.serializer.support.SerializingConverter;

/**
 * How the session attributes (the security context with {@link CurrentUser}) are turned into bytes in the
 * {@code spring_session_attributes} table and back (docs/specs/jdbc-sessions.md §2.1, §2.5).
 * <p>
 * Java serialization, as the framework does by default. The difference: bytes that cannot be read (after an update of
 * Spring Security or a change of the classes in the session) make the attribute <em>absent</em> instead of throwing,
 * so the user gets {@code 401} and signs in again — as after an expired session — and not a {@code 500} that nothing
 * but clearing the table would cure. Only unreadable bytes are swallowed here; a failure of the database never
 * reaches this converter.
 */
@Configuration(proxyBeanMethods = false)
class SessionStoreConfiguration {

    private static final Logger log = LoggerFactory.getLogger(SessionStoreConfiguration.class);

    /** Spring Session looks for exactly this name. */
    @Bean(name = "springSessionConversionService")
    ConversionService springSessionConversionService() {
        return conversionService(getClass().getClassLoader());
    }

    static GenericConversionService conversionService(ClassLoader classLoader) {
        GenericConversionService conversion = new GenericConversionService();
        conversion.addConverter(Object.class, byte[].class, new SerializingConverter());
        DeserializingConverter deserializer = new DeserializingConverter(classLoader);
        conversion.addConverter(byte[].class, Object.class, (Converter<byte[], Object>) bytes -> read(deserializer, bytes));
        return conversion;
    }

    private static Object read(DeserializingConverter deserializer, byte[] bytes) {
        try {
            return deserializer.convert(bytes);
        } catch (RuntimeException e) {
            // Neither the bytes nor the session id go to the log: only that, and why in a word.
            log.warn("A session attribute could not be read and is treated as absent ({}); the user signs in again",
                    e.getClass().getSimpleName());
            return null;
        }
    }
}
