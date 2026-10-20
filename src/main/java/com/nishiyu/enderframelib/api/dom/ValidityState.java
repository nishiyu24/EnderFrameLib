package com.nishiyu.enderframelib.api.dom;

import org.jetbrains.annotations.ApiStatus;

/**
 * The validity state of a form control element.
 *
 * <p>Conforms to WHATWG HTML Standard §4.10.21.3 Interface ValidityState.</p>
 */
@ApiStatus.AvailableSince("1.0")
public interface ValidityState {

    /**
     * Returns {@code true} if the user has provided input that the browser is unable to convert.
     */
    boolean isBadInput();

    /**
     * Returns {@code true} if the element's custom validity message has been set to a non-empty string.
     */
    boolean isCustomError();

    /**
     * Returns {@code true} if the value does not match the specified pattern attribute.
     */
    boolean isPatternMismatch();

    /**
     * Returns {@code true} if the value is greater than the maximum specified by the max attribute.
     */
    boolean isRangeOverflow();

    /**
     * Returns {@code true} if the value is less than the minimum specified by the min attribute.
     */
    boolean isRangeUnderflow();

    /**
     * Returns {@code true} if the value does not fit the rules determined by the step attribute.
     */
    boolean isStepMismatch();

    /**
     * Returns {@code true} if the value exceeds the specified maxlength.
     */
    boolean isTooLong();

    /**
     * Returns {@code true} if the value does not meet the specified minlength.
     */
    boolean isTooShort();

    /**
     * Returns {@code true} if the value is not in the required syntax (for type=email, type=url, etc.).
     */
    boolean isTypeMismatch();

    /**
     * Returns {@code true} if the element has the required attribute, but has no value.
     */
    boolean isValueMissing();

    /**
     * Returns {@code true} if the element meets all its validation constraints.
     */
    boolean isValid();
}