package com.nishiyu.enderframelib.api.dom;

import org.jetbrains.annotations.ApiStatus;

/**
 * Represents an abnormal event (such as an illegal operation) that occurs during DOM operations.
 *
 * <p>Conforms to the WHATWG DOM Standard §2.4 DOMException specification.</p>
 */
@ApiStatus.AvailableSince("1.0")
public class DOMException extends RuntimeException {

    public static final short INDEX_SIZE_ERR = 1;
    public static final short HIERARCHY_REQUEST_ERR = 3;
    public static final short WRONG_DOCUMENT_ERR = 4;
    public static final short INVALID_CHARACTER_ERR = 5;
    public static final short NO_MODIFICATION_ALLOWED_ERR = 7;
    public static final short NOT_FOUND_ERR = 8;
    public static final short NOT_SUPPORTED_ERR = 9;
    public static final short INUSE_ATTRIBUTE_ERR = 10;
    public static final short INVALID_STATE_ERR = 11;
    public static final short SYNTAX_ERR = 12;
    public static final short INVALID_MODIFICATION_ERR = 13;
    public static final short NAMESPACE_ERR = 14;
    public static final short INVALID_ACCESS_ERR = 15;
    public static final short ABORT_ERR = 20;

    private final String name;
    private final short code;

    public DOMException(String message, String name, short code) {
        super(message);
        this.name = name;
        this.code = code;
    }

    public DOMException(String message, String name) {
        this(message, name, codeFromName(name));
    }

    public DOMException(String message) {
        this(message, "Error", (short) 0);
    }

    public String getName() {
        return name;
    }

    public short getCode() {
        return code;
    }

    private static short codeFromName(String name) {
        if (name == null) return 0;
        return switch (name) {
            case "IndexSizeError" -> INDEX_SIZE_ERR;
            case "HierarchyRequestError" -> HIERARCHY_REQUEST_ERR;
            case "WrongDocumentError" -> WRONG_DOCUMENT_ERR;
            case "InvalidCharacterError" -> INVALID_CHARACTER_ERR;
            case "NoModificationAllowedError" -> NO_MODIFICATION_ALLOWED_ERR;
            case "NotFoundError" -> NOT_FOUND_ERR;
            case "NotSupportedError" -> NOT_SUPPORTED_ERR;
            case "InUseAttributeError" -> INUSE_ATTRIBUTE_ERR;
            case "InvalidStateError" -> INVALID_STATE_ERR;
            case "SyntaxError" -> SYNTAX_ERR;
            case "InvalidModificationError" -> INVALID_MODIFICATION_ERR;
            case "NamespaceError" -> NAMESPACE_ERR;
            case "InvalidAccessError" -> INVALID_ACCESS_ERR;
            case "AbortError" -> ABORT_ERR;
            default -> 0;
        };
    }
}
