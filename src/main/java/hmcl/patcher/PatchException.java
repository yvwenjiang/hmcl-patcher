package hmcl.patcher;

/// Thrown when a class file cannot be understood or does not contain the expected method.
public final class PatchException extends Exception {

    public PatchException(String message) {
        super(message);
    }

    public PatchException(String message, Throwable cause) {
        super(message, cause);
    }
}
