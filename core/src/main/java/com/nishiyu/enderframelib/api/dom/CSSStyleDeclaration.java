package com.nishiyu.enderframelib.api.dom;

import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

/**
 * An interface representing a CSS declaration block, conforming to the CSSOM specification.
 */
@ApiStatus.AvailableSince("1.0")
public interface CSSStyleDeclaration {

    /**
     * The textual representation of the declaration block.
     */
    String getCssText();

    /**
     * Sets the textual representation of the declaration block.
     */
    void setCssText(String cssText);

    /**
     * Returns the number of properties in this declaration block.
     */
    int getLength();

    /**
     * Returns the property name at the specified index.
     */
    @Nullable
    String item(int index);

    /**
     * Returns the value of the specified property.
     */
    String getPropertyValue(String property);

    /**
     * Returns the priority of the specified property ("important" or empty string).
     */
    String getPropertyPriority(String property);

    /**
     * Sets a CSS property with optional priority.
     */
    void setProperty(String property, @Nullable String value, @Nullable String priority);

    /**
     * Sets a CSS property without priority.
     */
    default void setProperty(String property, @Nullable String value) {
        setProperty(property, value, "");
    }

    /**
     * Removes a CSS property from this declaration block.
     *
     * @return the value of the property before removal.
     */
    String removeProperty(String property);
}
