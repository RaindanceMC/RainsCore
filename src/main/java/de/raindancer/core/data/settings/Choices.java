package de.raindancer.core.data.settings;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.List;
import java.util.function.Supplier;

/**
 * Every entry a {@code List<String>} setting may hold, so {@code /settings} shows a page with one on/off button per
 * entry instead of asking for the list typed in chat.
 *
 * <p>The supplier needs a public no-argument constructor; it is asked once, when the schema is built.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.RECORD_COMPONENT, ElementType.PARAMETER, ElementType.METHOD, ElementType.FIELD})
public @interface Choices {

    Class<? extends Supplier<List<String>>> value();

    /** For a list of what is switched off: a listed entry shows as off, an unlisted one as on. */
    boolean listedMeansOff() default false;
}
