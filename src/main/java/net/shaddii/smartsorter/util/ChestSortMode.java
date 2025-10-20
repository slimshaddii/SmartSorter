package net.shaddii.smartsorter.util;

public enum ChestSortMode {
    PRIORITY("Priority"),
    NAME("Name"),
    FULLNESS("Fullness"),
    COORDINATES("Position");

    private final String displayName;

    ChestSortMode(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }

    public ChestSortMode next() {
        ChestSortMode[] values = values();
        return values[(this.ordinal() + 1) % values.length];
    }

    public static ChestSortMode fromString(String str) {
        for (ChestSortMode mode : values()) {
            if (mode.name().equalsIgnoreCase(str)) {
                return mode;
            }
        }
        return PRIORITY;
    }

    public String asString() {
        return name();
    }
}