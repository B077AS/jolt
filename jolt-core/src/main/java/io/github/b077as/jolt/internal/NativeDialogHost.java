package io.github.b077as.jolt.internal;

import io.github.b077as.jolt.Dialogs;

/** Implemented by a window that can show the platform's own dialogs. */
public interface NativeDialogHost {

    Dialogs dialogs();
}
