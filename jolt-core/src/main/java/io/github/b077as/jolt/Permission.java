package io.github.b077as.jolt;

/**
 * A capability a page can ask the web view for and that a {@link SecurityPolicy} can grant to your own content with
 * {@link SecurityPolicy#withGrantedPermissions}. Granting one is a decision to let the page use that device: the operating
 * system may still ask the user (Windows privacy settings, a Linux portal) or refuse.
 */
public enum Permission {
    /** {@code getUserMedia({audio: true})}, and device names in {@code enumerateDevices()}. */
    MICROPHONE,
    /** {@code getUserMedia({video: true})}, and device names in {@code enumerateDevices()}. */
    CAMERA
}
