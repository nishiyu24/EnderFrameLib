package com.nishiyu.enderframelib.api;

import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

/**
 * Read-only view of the document currently loaded into a {@link WebSessionHandle}.
 *
 * <p>This interface deliberately exposes no DOM node type. The internal node
 * representation ({@code core.dom.HtmlNode}) is an implementation detail and is
 * free to change between versions; everything downstream code legitimately needs
 * about a loaded document is available here or through the {@code patch*} methods
 * of {@link WebSessionHandle}.</p>
 */
@ApiStatus.AvailableSince("1.0")
public interface WebDocument {

    /** The author CSS collected for this document (inline {@code <style>}, {@code <link>} and external sheets). */
    String authorCss();

    /** Whether an element with the given {@code id} attribute exists in the document. */
    boolean hasElement(String elementId);

    /** The tag name of the element with the given id, or {@code null} when no such element exists. */
    @Nullable
    String tagOf(String elementId);

    /**
     * The value of an attribute of the element with the given id,
     * or {@code null} when the element or the attribute does not exist.
     */
    @Nullable
    String attributeOf(String elementId, String attributeName);
}
