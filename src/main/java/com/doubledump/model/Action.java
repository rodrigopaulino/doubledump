package com.doubledump.model;

import java.util.Locale;

public enum Action {
    PRINT,
    HARDLINK,
    SYMLINK,
    MOVE,
    NONE;

    public static Action fromUserInput(String raw) {
        switch (raw.toLowerCase(Locale.ROOT)) {
            case "print":
                return PRINT;
            case "hardlink":
                return HARDLINK;
            case "symlink":
                return SYMLINK;
            case "move":
                return MOVE;
            case "none":
                return NONE;
            default:
                throw new IllegalArgumentException("Unknown action: " + raw);
        }
    }
}
