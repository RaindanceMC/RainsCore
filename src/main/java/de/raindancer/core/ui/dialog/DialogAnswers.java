package de.raindancer.core.ui.dialog;

import io.papermc.paper.dialog.DialogResponseView;

/**
 * What was in a dialog's fields when a button was pressed. Never null: a field the page did not have reads as
 * empty, a switch as off — so a handler checks what the answer says rather than whether there is one.
 */
public final class DialogAnswers {

    private static final DialogAnswers NONE = new DialogAnswers(null);

    private final DialogResponseView view;

    private DialogAnswers(DialogResponseView view) {
        this.view = view;
    }

    public static DialogAnswers of(DialogResponseView view) {
        return view == null ? NONE : new DialogAnswers(view);
    }

    /** For a press with no fields at all, and for tests. */
    public static DialogAnswers none() {
        return NONE;
    }

    /** A text field, stripped; empty when there was none or nothing in it. */
    public String text(String key) {
        String typed = view == null ? null : view.getText(key);
        return typed == null ? "" : typed.strip();
    }

    /** Which option of a choice was picked — its id; empty when none was. */
    public String choice(String key) {
        return text(key);
    }

    /** A switch. */
    public boolean flag(String key) {
        Boolean on = view == null ? null : view.getBoolean(key);
        return on != null && on;
    }
}
