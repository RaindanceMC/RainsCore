package de.raindancer.core.content.advancement;

/** The shape of the frame in the advancement screen and the toast; the economy pays by it. */
public enum AdvancementFrame {
    TASK("task"),
    GOAL("goal"),
    CHALLENGE("challenge");

    private final String json;

    AdvancementFrame(String json) {
        this.json = json;
    }

    String json() {
        return json;
    }
}
